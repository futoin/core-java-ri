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

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.futoin.api.AsyncSteps;
import org.futoin.api.AsyncTool;
import org.futoin.api.Error;
import org.futoin.api.Throttle;

/**
 * Reference Implementation of AsyncSteps Throttle
 */
public final class ThrottleRI extends Throttle {
    /** ignore */
    private final AtomicInteger stepCount_ = new AtomicInteger(0);

    /** ignore */
    private final ConcurrentLinkedQueue<AsyncSteps> queue_;

    /** ignore */
    private final int rate_;

    /** ignore */
    private final long periodNano_;

    /** ignore */
    private final int maxTotal_;

    /** ignore */
    private final AsyncTool asyncTool_;

    /** ignore */
    private volatile AsyncTool.Handle timer_;

    /** ignore */
    private long nextFireTime_;

    /**
     * C-tor
     * @param at AsyncTool ref
     * @param rate maximum number of concurrent critical section entries per period.
     * @param period_ms period duration in milliseconds
     * @param max_queue maximum number of queued requests, &lt;0 = unlimited.
     */
    public ThrottleRI(AsyncTool at, int rate, long period_ms, int max_queue) {
        super(rate, period_ms, max_queue);

        if (rate <= 0) {
            throw new IllegalArgumentException("ThrottleRI rate must be positive");
        }

        if (period_ms <= 0) {
            throw new IllegalArgumentException("ThrottleRI period must be positive");
        }

        asyncTool_ = at;
        rate_ = rate;
        periodNano_ = period_ms * 1_000_000L;

        if (max_queue < 0 || max_queue == Integer.MAX_VALUE) {
            max_queue = Integer.MAX_VALUE - rate;
        }

        maxTotal_ = rate + max_queue;

        if (max_queue > 0) {
            queue_ = new ConcurrentLinkedQueue<>();
        } else {
            queue_ = null;
        }
    }

    /**
     * C-tor
     * @param at AsyncTool ref
     * @param rate maximum number of concurrent critical section entries per period.
     * @param period period duration
     * @param max_queue maximum number of queued requests, &lt;0 = unlimited.
     */
    public ThrottleRI(AsyncTool at, int rate, Duration period, int max_queue) {
        this(at, rate, period.toMillis(), max_queue);
    }

    /**
     * C-tor
     * @param at AsyncTool ref
     * @param rate maximum number of concurrent critical section entries per period.
     * @param period_ms period duration.
     */
    public ThrottleRI(AsyncTool at, int rate, long period_ms) {
        this(at, rate, period_ms, MAX_QUEUE_DEFAULT);
    }

    /**
     * C-tor
     * @param at AsyncTool ref
     * @param rate maximum number of concurrent critical section entries per period.
     * @param period period duration.
     */
    public ThrottleRI(AsyncTool at, int rate, Duration period) {
        this(at, rate, period.toMillis());
    }

    /**
     * C-tor
     * @param at AsyncTool ref
     * @param rate maximum number of concurrent critical section entries per period.
     */
    public ThrottleRI(AsyncTool at, int rate) {
        this(at, rate, PERIOD_MS_DEFAULT, MAX_QUEUE_DEFAULT);
    }

    /**
     * C-tor
     * @param rate maximum number of concurrent critical section entries per period.
     */
    public ThrottleRI(int rate) {
        this(AsyncToolRI.shared(), rate, PERIOD_MS_DEFAULT, MAX_QUEUE_DEFAULT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void lock(AsyncSteps asi) {
        int stepCount = stepCount_.incrementAndGet();

        if (stepCount <= rate_) {
            // pass
        } else if (stepCount <= maxTotal_) {
            // queue
            asi.add(
                    (asi2) -> {
                        queue_.add(asi2);
                        asi2.setCancel(
                                (asi3) -> {
                                    stepCount_.decrementAndGet();
                                });
                    });
        } else {
            // revert
            stepCount_.decrementAndGet();
            asi.errorNoThrow(Error.DefenseRejected, "Throttle queue limit");
        }

        if (timer_ == null) {
            synchronized (this) {
                if (timer_ == null) {
                    nextFireTime_ = System.nanoTime() + periodNano_;
                    timer_ = asyncTool_.deferred(periodNano_ / 1_000_000, this::handle_timer);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unlock(AsyncSteps asi) {
        // NOOP, see setCancel() for queue
    }

    /** ignore */
    private void handle_timer() {
        var stepLeft = stepCount_.get();

        if (stepLeft == 0) {
            synchronized (this) {
                if (stepCount_.get() == 0) {
                    timer_ = null;
                    return;
                }
            }
        }

        stepLeft = Math.min(stepLeft, rate_);

        if (queue_ != null) {
            for (var i = stepLeft; i > 0; --i) {
                var asi = queue_.poll();

                if (asi != null) {
                    if (asi.state() != null) {
                        asi.success();
                    } else {
                        ++i; // retry
                    }
                } else {
                    break;
                }
            }
        }

        stepCount_.addAndGet(-stepLeft);

        var nextTime = nextFireTime_ + periodNano_;
        nextFireTime_ = nextTime;
        nextTime = (nextTime - System.nanoTime()) / 1_000_000L;

        if (nextTime <= 0) {
            timer_ = asyncTool_.immediate(this::handle_timer);
        } else {
            timer_ = asyncTool_.deferred(nextTime, this::handle_timer);
        }
    }
}
