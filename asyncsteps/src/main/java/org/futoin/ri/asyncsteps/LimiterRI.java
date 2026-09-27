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

import org.futoin.api.AsyncSteps;
import org.futoin.api.AsyncTool;
import org.futoin.api.Limiter;

/**
 * Reference Implementation of AsyncSteps Limiter
 */
public final class LimiterRI extends Limiter {
    /** ignore */
    private final MutexRI mutex_;

    /** ignore */
    private final ThrottleRI throttle_;

    /**
     * C-tor with custom options
     *
     * @param at AsyncTool ref
     * @param options Configuration options.
     */
    public LimiterRI(AsyncTool at, Options options) {
        super(options);
        mutex_ = new MutexRI(options.concurrent, options.max_queue);
        throttle_ = new ThrottleRI(at, options.rate, options.period_ms, options.burst);
    }

    /**
     * C-tor with custom options and the default shared AsyncTool
     *
     * @param options Configuration options.
     */
    public LimiterRI(Options options) {
        this(AsyncToolRI.shared(), options);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void lock(AsyncSteps asi) {
        mutex_.lock(asi);
        asi.add((asi2) -> throttle_.lock(asi2));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unlock(AsyncSteps asi) {
        throttle_.unlock(asi);
        mutex_.unlock(asi);
    }
}
