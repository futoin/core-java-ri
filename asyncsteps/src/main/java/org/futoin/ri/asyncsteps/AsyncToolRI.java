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

import java.util.ArrayDeque;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.futoin.api.AsyncTool;

/**
 * FutoIn AsyncTool Reference Implementation
 */
public final class AsyncToolRI implements AsyncTool, AutoCloseable {
    /**
     * Immediate handle implementation
     * @hidden
     */
    private class HandleRI implements Handle {
        /** callback or null, if canceled */
        public volatile Callback callback_;

        /**
         * C-tor
         * @param cb callback
         */
        public HandleRI(Callback cb) {
            callback_ = cb;
        }

        /**
         * {@inheritDoc}
         */
        public void cancel() {
            AsyncToolRI.this.cancel(this);
        }

        /**
         * {@inheritDoc}
         */
        public boolean is_valid() {
            return AsyncToolRI.this.is_valid(this);
        }

        /**
         * Get outer this for matching.
         * @return outer this
         * @hidden
         */
        public AsyncToolRI outerThis() {
            return AsyncToolRI.this;
        }
    }

    /**
     * Delayed handle implementation
     * @hidden
     */
    private class DelayedHandleRI extends HandleRI implements Comparable<DelayedHandleRI> {
        /** fire time in nanoseconds */
        private final long fireTime_;

        /**
         * Default c-tor
         * @param cb callback
         * @param ft time reference in nanoseconds
         */
        public DelayedHandleRI(Callback cb, long ft) {
            super(cb);
            fireTime_ = ft;
        }

        /**
         * Comparison for priority queue
         * {@inheritDoc}
         */
        @Override
        public int compareTo(DelayedHandleRI o) {
            return Long.compare(fireTime_, o.fireTime_);
        }

        /**
         * Make lint happy
         * {@inheritDoc}
         */
        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        /**
         * Make lint happy
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    /**
     * Immediate callbacks.
     * @hidden
     */
    private final ArrayDeque<HandleRI> immediates_ = new ArrayDeque<>();

    /**
     * Callbacks with time delay.
     * @hidden
     */
    private final PriorityQueue<DelayedHandleRI> deferred_calls_ = new PriorityQueue<>();

    /**
     * Queue for out-of event loop actions.
     * @hidden
     */
    private final ConcurrentLinkedQueue<Callback> foreign_actions_ = new ConcurrentLinkedQueue<>();

    /**
     * Associated event loop thread, internal or external
     * @hidden
     */
    private final Thread thread_;

    /**
     * Callback to resume sleeping loop on external actions.
     * @hidden
     */
    private final Callback poke_cb_;

    /**
     * Indicator for shutdown.
     * @hidden
     */
    private volatile boolean shutdown_ = false;

    /**
     * Wait object for inner loop cooldown.
     * @hidden
     */
    private final Object poke_lock_;

    /**
     * Default c-tor with internal thread
     */
    public AsyncToolRI() {
        thread_ = new Thread(this::innerLoop, "FTNAsyncToolRI");
        poke_lock_ = new Object();
        poke_cb_ =
                () -> {
                    synchronized (poke_lock_) {
                        poke_lock_.notify();
                    }
                };

        thread_.start();
    }

    /**
     * A special c-tor for integration with external event loop.
     *
     * @param poke_cb A callback to
     */
    public AsyncToolRI(Callback poke_cb) {
        thread_ = Thread.currentThread();
        poke_lock_ = null; // Make lint happy
        poke_cb_ = poke_cb;
    }

    /**
     * Check if external event loop is configured.
     * @return true, if external loop
     * @hidden
     */
    private boolean isExternalLoop() {
        return (poke_lock_ == null);
    }

    /**
     * Time reference
     * @return monotonic time ref in nanoseconds
     * @hidden
     */
    private long now() {
        return System.nanoTime();
    }

    /**
     * Inner loop
     * @hidden
     */
    private void innerLoop() {
        for (; ; ) {
            innerIterate();

            if (immediates_.isEmpty()) {
                var delay = 0L;

                if (!deferred_calls_.isEmpty()) {
                    delay = deferred_calls_.peek().fireTime_ - now();
                } else if (foreign_actions_.isEmpty() && shutdown_) {
                    break;
                }

                if (delay > 0) {
                    synchronized (poke_lock_) {
                        // Do not cast to int too early!
                        var ns = delay % 1_000_000L;
                        var ms = delay / 1_000_000L;

                        try {
                            poke_lock_.wait(ms, (int) ns);
                        } catch (InterruptedException ex) {
                            // pass
                        }
                    }
                }
            }
        }
    }

    /**
     * Common machinery for callback invocation
     * @param handle Handle impl
     * @hidden
     */
    private void handleCallback(HandleRI handle) {
        var cb = handle.callback_;
        handle.callback_ = null;

        if (cb != null) {
            try {
                cb.call();
            } catch (Throwable ex) {
                System.err.println("AsyncTool: unhandled exception");
                ex.printStackTrace(System.err);
            }
        }
    }

    /**
     * General iteration
     * @hidden
     */
    private void innerIterate() {
        var immed = immediates_;

        // NOTE: only process as much as available at the start!
        for (var i = immed.size(); i > 0; --i) {
            handleCallback(immed.poll());
        }

        var barrier = now();
        var defer = deferred_calls_;

        for (var h = defer.peek(); h != null && h.fireTime_ <= barrier; h = defer.peek()) {
            defer.remove();
            handleCallback(h);
        }

        var foreign = foreign_actions_;

        // NOTE: only process as much as available at the start!
        for (var i = foreign_actions_.size(); i > 0; --i) {
            foreign_actions_.poll().call();
        }
    }

    /**
     * Shutdown inner loop.
     *
     * In case of external event loop, if must be called from that loop
     * to prevent inner race conditions.
     *
     * The loop is safe to shutdown only once it has not pending actions.
     */
    public void shutdown() {
        var orig_shutdown = shutdown_;
        shutdown_ = true;

        if (isExternalLoop()) {
            if (!is_same_thread()) {
                throw new RuntimeException("shutdown() no from c-tor thread");
            }
        } else if (!orig_shutdown) {
            poke_cb_.call();
            for (; ; ) {
                try {
                    thread_.join();
                    break;
                } catch (InterruptedException ex) {
                    // pass
                }
            }
        }

        immediates_.clear();
        deferred_calls_.clear();
        foreign_actions_.clear();
    }

    /**
     * AutoCloseable support
     */
    @Override
    public void close() {
        shutdown();
    }

    /**
     * Iterate results for external event loop integration.
     * @param haveWork a new iteration must be scheduled.
     * @param delayNs the delay for the new iteration, if any.
     */
    public record CycleResult(boolean haveWork, long delayNs) {}

    /**
     * Perform one iteration of the inner event loop for integration into
     * an external event loop.
     *
     * @return Details for the next iteration scheduling
     */
    public CycleResult iterate() {
        if (!is_same_thread()) {
            throw new RuntimeException("iterate() no from c-tor thread");
        }

        innerIterate();
        var haveWork = true;
        var delay = 0L;

        if (immediates_.isEmpty()) {
            if (deferred_calls_.isEmpty()) {
                haveWork = false;
            } else {
                delay = deferred_calls_.peek().fireTime_ - now();
            }
        }

        return new CycleResult(haveWork, delay);
    }

    /**
     * Helper for calls out of the event loop thread.
     * @param cb callback
     * @hidden
     */
    private void foreignCall(Callback cb) {
        foreign_actions_.add(() -> cb.call());
        poke_cb_.call();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Handle immediate(Callback func) {
        var handle = new HandleRI(func);

        if (is_same_thread()) {
            immediates_.add(handle);
        } else {
            foreignCall(() -> immediates_.add(handle));
        }

        return handle;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Handle deferred(long delay_ms, Callback func) {
        var fireTime = now() + (delay_ms * 1_000_000L);
        var handle = new DelayedHandleRI(func, fireTime);

        if (is_same_thread()) {
            deferred_calls_.add(handle);
        } else {
            foreignCall(() -> deferred_calls_.add(handle));
        }

        return handle;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean is_same_thread() {
        return thread_.equals(Thread.currentThread());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void cancel(Handle handle) {
        if (handle instanceof HandleRI h && h.outerThis() == this) {
            if (h.callback_ == null) {
                return;
            }

            h.callback_ = null;

            if (h instanceof DelayedHandleRI dh) {
                if (is_same_thread()) {
                    deferred_calls_.remove(dh);
                } else {
                    foreignCall(() -> deferred_calls_.remove(dh));
                }
            }
        } else {
            throw new IllegalArgumentException("Foreign Handle");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean is_valid(Handle handle) {
        if (handle instanceof HandleRI h && h.outerThis() == this) {
            return h.callback_ != null;
        }

        throw new IllegalArgumentException("Foreign Handle");
    }

    /**
     * On-demand signleton loader
     * @hidden
     */
    private static final class Singleton {
        /**
         * Make stupid doclint happy
         * @hidden
         */
        private Singleton() {}

        /**
         * Default shared instance
         */
        public static AsyncToolRI instance = new AsyncToolRI();
    }

    /**
     * Initialize and get the shared instance of AsyncTool.
     * @return AsyncToolRI singletone
     */
    public static AsyncTool shared() {
        return Singleton.instance;
    }
}
