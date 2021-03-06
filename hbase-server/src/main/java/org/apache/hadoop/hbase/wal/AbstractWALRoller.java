/**
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
package org.apache.hadoop.hbase.wal;

import java.io.Closeable;
import java.io.IOException;
import java.net.ConnectException;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Abortable;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.regionserver.wal.AbstractFSWAL;
import org.apache.hadoop.hbase.regionserver.wal.FailedLogCloseException;
import org.apache.hadoop.hbase.regionserver.wal.WALActionsListener;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs periodically to determine if the WAL should be rolled.
 * <p/>
 * NOTE: This class extends Thread rather than Chore because the sleep time can be interrupted when
 * there is something to do, rather than the Chore sleep time which is invariant.
 * <p/>
 * The {@link #scheduleFlush(String)} is abstract here, as sometimes we hold a region without a
 * region server but we still want to roll its WAL.
 * <p/>
 * TODO: change to a pool of threads
 */
@InterfaceAudience.Private
public abstract class AbstractWALRoller<T extends Abortable> extends Thread
  implements Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractWALRoller.class);

  protected static final String WAL_ROLL_PERIOD_KEY = "hbase.regionserver.logroll.period";

  protected final ConcurrentMap<WAL, RollController> wals = new ConcurrentHashMap<>();
  protected final T abortable;
  // Period to roll log.
  private final long rollPeriod;
  private final int threadWakeFrequency;
  // The interval to check low replication on hlog's pipeline
  private final long checkLowReplicationInterval;

  private volatile boolean running = true;

  public void addWAL(WAL wal) {
    // check without lock first
    if (wals.containsKey(wal)) {
      return;
    }
    // this is to avoid race between addWAL and requestRollAll.
    synchronized (this) {
      if (wals.putIfAbsent(wal, new RollController(wal)) == null) {
        wal.registerWALActionsListener(new WALActionsListener() {
          @Override
          public void logRollRequested(WALActionsListener.RollRequestReason reason) {
            // TODO logs will contend with each other here, replace with e.g. DelayedQueue
            synchronized (AbstractWALRoller.this) {
              RollController controller = wals.computeIfAbsent(wal, rc -> new RollController(wal));
              controller.requestRoll();
              AbstractWALRoller.this.notifyAll();
            }
          }
        });
      }
    }
  }

  public void requestRollAll() {
    synchronized (this) {
      for (RollController controller : wals.values()) {
        controller.requestRoll();
      }
      notifyAll();
    }
  }

  protected AbstractWALRoller(String name, Configuration conf, T abortable) {
    super(name);
    this.abortable = abortable;
    this.rollPeriod = conf.getLong(WAL_ROLL_PERIOD_KEY, 3600000);
    this.threadWakeFrequency = conf.getInt(HConstants.THREAD_WAKE_FREQUENCY, 10 * 1000);
    this.checkLowReplicationInterval =
      conf.getLong("hbase.regionserver.hlog.check.lowreplication.interval", 30 * 1000);
  }

  /**
   * we need to check low replication in period, see HBASE-18132
   */
  private void checkLowReplication(long now) {
    try {
      for (Entry<WAL, RollController> entry : wals.entrySet()) {
        WAL wal = entry.getKey();
        boolean needRollAlready = entry.getValue().needsRoll(now);
        if (needRollAlready || !(wal instanceof AbstractFSWAL)) {
          continue;
        }
        ((AbstractFSWAL<?>) wal).checkLogLowReplication(checkLowReplicationInterval);
      }
    } catch (Throwable e) {
      LOG.warn("Failed checking low replication", e);
    }
  }

  private void abort(String reason, Throwable cause) {
    // close all WALs before calling abort on RS.
    // This is because AsyncFSWAL replies on us for rolling a new writer to make progress, and if we
    // failed, AsyncFSWAL may be stuck, so we need to close it to let the upper layer know that it
    // is already broken.
    for (WAL wal : wals.keySet()) {
      // shutdown rather than close here since we are going to abort the RS and the wals need to be
      // split when recovery
      try {
        wal.shutdown();
      } catch (IOException e) {
        LOG.warn("Failed to shutdown wal", e);
      }
    }
    abortable.abort(reason, cause);
  }

  @Override
  public void run() {
    while (running) {
      long now = System.currentTimeMillis();
      checkLowReplication(now);
      synchronized (this) {
        if (wals.values().stream().noneMatch(rc -> rc.needsRoll(now))) {
          try {
            wait(this.threadWakeFrequency);
          } catch (InterruptedException e) {
            // restore the interrupt state
            Thread.currentThread().interrupt();
          }
          // goto the beginning to check whether again whether we should fall through to roll
          // several WALs, and also check whether we should quit.
          continue;
        }
      }
      try {
        for (Iterator<Entry<WAL, RollController>> iter = wals.entrySet().iterator();
             iter.hasNext();) {
          Entry<WAL, RollController> entry = iter.next();
          WAL wal = entry.getKey();
          RollController controller = entry.getValue();
          if (controller.isRollRequested()) {
            // WAL roll requested, fall through
            LOG.debug("WAL {} roll requested", wal);
          } else if (controller.needsPeriodicRoll(now)){
            // Time for periodic roll, fall through
            LOG.debug("WAL {} roll period {} ms elapsed", wal, this.rollPeriod);
          } else {
            continue;
          }
          // Force the roll if the logroll.period is elapsed or if a roll was requested.
          // The returned value is an array of actual region names.
          byte[][] regionsToFlush = controller.rollWal(now);
          if (regionsToFlush != null) {
            for (byte[] r : regionsToFlush) {
              scheduleFlush(Bytes.toString(r));
            }
          }
          afterRoll(wal);
        }
      } catch (FailedLogCloseException | ConnectException e) {
        abort("Failed log close in log roller", e);
      } catch (IOException ex) {
        // Abort if we get here. We probably won't recover an IOE. HBASE-1132
        abort("IOE in log roller",
          ex instanceof RemoteException ? ((RemoteException) ex).unwrapRemoteException() : ex);
      } catch (Exception ex) {
        LOG.error("Log rolling failed", ex);
        abort("Log rolling failed", ex);
      }
    }
    LOG.info("LogRoller exiting.");
  }

  /**
   * Called after we finish rolling the give {@code wal}.
   */
  protected void afterRoll(WAL wal) {
  }

  /**
   * @param encodedRegionName Encoded name of region to flush.
   */
  protected abstract void scheduleFlush(String encodedRegionName);

  private boolean isWaiting() {
    Thread.State state = getState();
    return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
  }

  /**
   * @return true if all WAL roll finished
   */
  public boolean walRollFinished() {
    return wals.values().stream().noneMatch(rc -> rc.needsRoll(System.currentTimeMillis()))
      && isWaiting();
  }

  /**
   * Wait until all wals have been rolled after calling {@link #requestRollAll()}.
   */
  public void waitUntilWalRollFinished() throws InterruptedException {
    while (!walRollFinished()) {
      Thread.sleep(100);
    }
  }

  @Override
  public void close() {
    running = false;
    interrupt();
  }

  /**
   * Independently control the roll of each wal. When use multiwal,
   * can avoid all wal roll together. see HBASE-24665 for detail
   */
  protected class RollController {
    private final WAL wal;
    private final AtomicBoolean rollRequest;
    private long lastRollTime;

    RollController(WAL wal) {
      this.wal = wal;
      this.rollRequest = new AtomicBoolean(false);
      this.lastRollTime = System.currentTimeMillis();
    }

    public void requestRoll() {
      this.rollRequest.set(true);
    }

    public byte[][] rollWal(long now) throws IOException {
      this.lastRollTime = now;
      // reset the flag in front to avoid missing roll request before we return from rollWriter.
      this.rollRequest.set(false);
      return wal.rollWriter(true);
    }

    public boolean isRollRequested() {
      return rollRequest.get();
    }

    public boolean needsPeriodicRoll(long now) {
      return (now - this.lastRollTime) > rollPeriod;
    }

    public boolean needsRoll(long now) {
      return isRollRequested() || needsPeriodicRoll(now);
    }
  }
}
