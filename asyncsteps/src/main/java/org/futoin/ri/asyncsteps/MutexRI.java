/*
 * Copyright 2026 FutoIn Project (https://futoin.org)
 * Copyright 2026 Andrey Galkin <andrey@futoin.org>
 *
 * Licensed under the FutoIn Public License 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://specs.futoin.org/LICENSE.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.futoin.ri.asyncsteps;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.futoin.api.AsyncSteps;
import org.futoin.api.Error;
import org.futoin.api.Mutex;

/**
 * Reference Implementation of AsyncSteps Mutex
 */
public final class MutexRI extends Mutex {
    /** ignore */
    private final AtomicInteger stepCount_ = new AtomicInteger(0);

    /** ignore */
    private final ConcurrentHashMap<Object, Integer> stepMap_ = new ConcurrentHashMap<>();

    /** ignore */
    private final ConcurrentLinkedQueue<AsyncSteps> queue_;

    /** ignore */
    private final int max_;

    /** ignore */
    private final int maxTotal_;

    /**
     * C-tor
     * @param max maximum number of concurrent critical section entries.
     * @param max_queue maximum number of queued requests, &lt;0 = unlimited.
     */
    public MutexRI(int max, int max_queue) {
        super(max, max_queue);

        if (max <= 0) {
            throw new IllegalArgumentException("MutextRI max parameter must be positive");
        }

        max_ = max;

        if (max_queue < 0 || max_queue == Integer.MAX_VALUE) {
            max_queue = Integer.MAX_VALUE - max;
        }

        maxTotal_ = max + max_queue;

        if (max_queue > 0) {
            queue_ = new ConcurrentLinkedQueue<>();
        } else {
            queue_ = null;
        }
    }

    /**
     * C-tor
     * @param max maximum number of concurrent critical section entries.
     */
    public MutexRI(int max) {
        this(max, MAX_QUEUE_DEFAULT);
    }

    /**
     * C-tor
     */
    public MutexRI() {
        this(MAX_DEFAULT, MAX_QUEUE_DEFAULT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void lock(AsyncSteps asi) {
        var syncRoot = asi.syncRoot();
        int entryCount = stepMap_.getOrDefault(syncRoot, 0);

        if (entryCount == 0) {
            int stepCount = stepCount_.incrementAndGet();

            if (stepCount <= max_) {
                // acquired
                stepMap_.put(syncRoot, 1);
            } else if (stepCount <= maxTotal_) {
                // queue
                queue_.add(asi);
                asi.setCancel(
                        (asi2) -> {
                            stepCount_.getAndDecrement();
                            // We get here only if Mutex is locked, so unlock
                            // will eventually cleanup the queue below.
                        });
            } else {
                // release failed
                stepCount_.decrementAndGet();
                asi.errorNoThrow(Error.DefenseRejected, "Mutex queue limit");
            }
        } else {
            // re-entry
            stepMap_.put(syncRoot, entryCount + 1);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unlock(AsyncSteps asi) {
        var syncRoot = asi.syncRoot();
        int entryCount = stepMap_.getOrDefault(syncRoot, 0);

        if (entryCount == 0) {
            // Early cancellation in queue and DefenseRejection cases
        } else if (entryCount == 1) {
            // normal release
            var removed = stepMap_.remove(syncRoot);
            stepCount_.getAndDecrement();

            // unlock must always occur in flow's thread
            assert (removed != null);

            processQueue();
        } else {
            // re-entered
            stepMap_.put(syncRoot, entryCount - 1);
        }
    }

    /** ignore */
    private void processQueue() {
        if (queue_ == null) {
            return;
        }

        for (; ; ) {
            var nextAsi = queue_.poll();

            if (nextAsi != null) {
                var nextSyncRoot = nextAsi.syncRoot();

                if (nextAsi.state() != null) {
                    // There is a race on cancellation
                    //
                    // Flows A and B in different AsyncTool threads.
                    //
                    // A: unlocks mutex and goes to the queue continuation below.
                    // A: picks flow B from the queue.
                    // B: cancellation enters and unable to remove self from the queue.
                    // A: marks B as active with entry count 1 and schedules success().
                    // B: exits.
                    // B: delayed success() fires with noop and mutex stalls.
                    // ^ to avoid this, a special extra-check inside B thread is required.
                    //
                    // There is still some risk of premature shutdown of the AsyncTool to
                    // be mitigated by its convention.
                    //
                    var nextAsyncTool = nextAsi.tool();

                    if (nextAsyncTool.is_same_thread()) {
                        stepMap_.put(nextSyncRoot, 1);
                        nextAsi.success();
                    } else {
                        nextAsyncTool.immediate(
                                () -> {
                                    if (nextAsi.state() != null) {
                                        stepMap_.put(nextSyncRoot, 1);
                                        nextAsi.success();
                                    } else {
                                        // trigger the queue
                                        processQueue();
                                    }
                                });
                    }
                    break;
                }
            } else {
                break;
            }
        }
    }
}
