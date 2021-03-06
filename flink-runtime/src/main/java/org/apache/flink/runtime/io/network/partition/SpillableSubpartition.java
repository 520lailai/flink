/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.io.network.partition;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.runtime.io.disk.iomanager.BufferFileWriter;
import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.io.network.api.EndOfPartitionEvent;
import org.apache.flink.runtime.io.network.api.serialization.EventSerializer;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.BufferPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.util.ArrayDeque;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * A spillable sub partition starts out in-memory and spills to disk if asked
 * to do so.
 *
 * <p>Buffers for the partition come from a {@link BufferPool}. The buffer pool
 * is also responsible to trigger the release of the buffers if it needs them
 * back. At this point, the spillable sub partition will write all in-memory
 * buffers to disk. All added buffers after that point directly go to disk.
 *
 * <p>This partition type is used for {@link ResultPartitionType#BLOCKING}
 * results that are fully produced before they can be consumed. At the point
 * when they are consumed, the buffers are (i) all in-memory, (ii) currently
 * being spilled to disk, or (iii) completely spilled to disk. Depending on
 * this state, different reader variants are returned (see
 * {@link SpillableSubpartitionView} and {@link SpilledSubpartitionView}).
 *
 * <p>Since the network buffer pool size for outgoing partitions is usually
 * quite small, e.g. via the {@link TaskManagerOptions#NETWORK_BUFFERS_PER_CHANNEL}
 * and {@link TaskManagerOptions#NETWORK_EXTRA_BUFFERS_PER_GATE} parameters
 * for bounded channels or from the default values of
 * {@link TaskManagerOptions#NETWORK_BUFFERS_MEMORY_FRACTION},
 * {@link TaskManagerOptions#NETWORK_BUFFERS_MEMORY_MIN}, and
 * {@link TaskManagerOptions#NETWORK_BUFFERS_MEMORY_MAX}, most spillable partitions
 * will be spilled for real-world data sets.
 *
 * <p>Note on thread safety. Synchronizing on {@code buffers} is used to synchronize
 * writes and reads. Synchronizing on {@code this} is used against concurrent
 * {@link #add(Buffer)} and clean ups {@link #release()} / {@link #finish()} which
 * also are touching {@code spillWriter}. Since we do not want to block reads during
 * spilling, we need those two synchronization. Probably this model could be simplified.
 */
class SpillableSubpartition extends ResultSubpartition {

	private static final Logger LOG = LoggerFactory.getLogger(SpillableSubpartition.class);

	/** Buffers are kept in this queue as long as we weren't ask to release any. */
	private final ArrayDeque<Buffer> buffers = new ArrayDeque<>();

	/** The I/O manager used for spilling buffers to disk. */
	private final IOManager ioManager;

	/** The writer used for spilling. As long as this is null, we are in-memory. */
	private BufferFileWriter spillWriter;

	/** Flag indicating whether the subpartition has been finished. */
	private boolean isFinished;

	/** Flag indicating whether the subpartition has been released. */
	private volatile boolean isReleased;

	/** The number of non-event buffers currently in this subpartition */
	@GuardedBy("buffers")
	private int buffersInBacklog;

	/** The read view to consume this subpartition. */
	private ResultSubpartitionView readView;

	SpillableSubpartition(int index, ResultPartition parent, IOManager ioManager) {
		super(index, parent);

		this.ioManager = checkNotNull(ioManager);
	}

	@Override
	public synchronized boolean add(Buffer buffer) throws IOException {
		checkNotNull(buffer);

		synchronized (buffers) {
			if (isFinished || isReleased) {
				buffer.recycle();
				return false;
			}

			if (spillWriter == null) {
				buffers.add(buffer);
				// The number of buffers are needed later when creating
				// the read views. If you ever remove this line here,
				// make sure to still count the number of buffers.
				updateStatistics(buffer);
				increaseBuffersInBacklog(buffer);

				return true;
			}
		}

		// Didn't return early => go to disk
		try {
			// retain buffer for updateStatistics() below
			spillWriter.writeBlock(buffer.retain());
			synchronized (buffers) {
				// See the note above, but only do this if the buffer was correctly added!
				updateStatistics(buffer);
				increaseBuffersInBacklog(buffer);
			}
		} finally {
			buffer.recycle();
		}

		return true;
	}

	@Override
	public synchronized void finish() throws IOException {
		synchronized (buffers) {
			if (add(EventSerializer.toBuffer(EndOfPartitionEvent.INSTANCE))) {
				isFinished = true;
			}
		}

		// If we are spilling/have spilled, wait for the writer to finish
		if (spillWriter != null) {
			spillWriter.close();
		}
	}

	@Override
	public synchronized void release() throws IOException {
		// view reference accessible outside the lock, but assigned inside the locked scope
		final ResultSubpartitionView view;

		synchronized (buffers) {
			if (isReleased) {
				return;
			}

			// Release all available buffers
			for (Buffer buffer : buffers) {
				buffer.recycle();
			}
			buffers.clear();

			view = readView;

			// No consumer yet, we are responsible to clean everything up. If
			// one is available, the view is responsible is to clean up (see
			// below).
			if (view == null) {

				// TODO This can block until all buffers are written out to
				// disk if a spill is in-progress before deleting the file.
				// It is possibly called from the Netty event loop threads,
				// which can bring down the network.
				if (spillWriter != null) {
					spillWriter.closeAndDelete();
				}
			}

			isReleased = true;
		}

		if (view != null) {
			view.releaseAllResources();
		}
	}

	@Override
	public ResultSubpartitionView createReadView(BufferAvailabilityListener availabilityListener) throws IOException {
		synchronized (buffers) {
			if (!isFinished) {
				throw new IllegalStateException("Subpartition has not been finished yet, " +
					"but blocking subpartitions can only be consumed after they have " +
					"been finished.");
			}

			if (readView != null) {
				throw new IllegalStateException("Subpartition is being or already has been " +
					"consumed, but we currently allow subpartitions to only be consumed once.");
			}

			if (spillWriter != null) {
				readView = new SpilledSubpartitionView(
					this,
					parent.getBufferProvider().getMemorySegmentSize(),
					spillWriter,
					getTotalNumberOfBuffers(),
					availabilityListener);
			} else {
				readView = new SpillableSubpartitionView(
					this,
					buffers,
					ioManager,
					parent.getBufferProvider().getMemorySegmentSize(),
					availabilityListener);
			}

			return readView;
		}
	}

	@Override
	public int releaseMemory() throws IOException {
		synchronized (buffers) {
			ResultSubpartitionView view = readView;

			if (view != null && view.getClass() == SpillableSubpartitionView.class) {
				// If there is a spillable view, it's the responsibility of the
				// view to release memory.
				SpillableSubpartitionView spillableView = (SpillableSubpartitionView) view;
				return spillableView.releaseMemory();
			} else if (spillWriter == null) {
				// No view and in-memory => spill to disk
				spillWriter = ioManager.createBufferFileWriter(ioManager.createChannel());

				int numberOfBuffers = buffers.size();
				long spilledBytes = 0;

				// Spill all buffers
				for (int i = 0; i < numberOfBuffers; i++) {
					Buffer buffer = buffers.remove();
					spilledBytes += buffer.getSize();
					spillWriter.writeBlock(buffer);
				}

				LOG.debug("Spilling {} bytes for sub partition {} of {}.", spilledBytes, index, parent.getPartitionId());

				return numberOfBuffers;
			}
		}

		// Else: We have already spilled and don't hold any buffers
		return 0;
	}

	@Override
	public boolean isReleased() {
		return isReleased;
	}

	@Override
	@VisibleForTesting
	public int getBuffersInBacklog() {
		return buffersInBacklog;
	}

	/**
	 * Decreases the number of non-event buffers by one after fetching a non-event
	 * buffer from this subpartition (for access by the subpartition views).
	 *
	 * @return backlog after the operation
	 */
	public int decreaseBuffersInBacklog(Buffer buffer) {
		synchronized (buffers) {
			if (buffer != null && buffer.isBuffer()) {
				buffersInBacklog--;
			}
			return buffersInBacklog;
		}
	}

	/**
	 * Increases the number of non-event buffers by one after adding a non-event
	 * buffer into this subpartition.
	 */
	private void increaseBuffersInBacklog(Buffer buffer) {
		assert Thread.holdsLock(buffers);

		if (buffer != null && buffer.isBuffer()) {
			buffersInBacklog++;
		}
	}

	@Override
	public int unsynchronizedGetNumberOfQueuedBuffers() {
		// since we do not synchronize, the size may actually be lower than 0!
		return Math.max(buffers.size(), 0);
	}

	@Override
	public String toString() {
		return String.format("SpillableSubpartition [%d number of buffers (%d bytes)," +
				"%d number of buffers in backlog, finished? %s, read view? %s, spilled? %s]",
			getTotalNumberOfBuffers(), getTotalNumberOfBytes(),
			buffersInBacklog, isFinished, readView != null, spillWriter != null);
	}

}
