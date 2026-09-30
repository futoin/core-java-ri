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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.futoin.api.AsyncSteps;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

@Threads(value = 1)
@Fork(value = 1)
@Timeout(time = 90, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
public class FutoInAsyncStepsBench extends BenchBase {
    @State(Scope.Thread)
    public static class FutoInState {
        public final AsyncToolRI async_tool = new AsyncToolRI(() -> {});

        void completeWork() {
            var at = async_tool;
            while (at.iterate().haveWork()) {}
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void simple(FutoInState state) {
        var $as = new AsyncStepsRI(state.async_tool);
        $as.add((asi) -> {});
        $as.execute();
        state.completeWork();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OperationsPerInvocation(Parallel_COUNT)
    public void parallel(FutoInState state, Blackhole blackhole) {
        var stack = new ArrayDeque<AsyncSteps>();
        var remaining = new AtomicInteger(Parallel_COUNT);

        for (int i = Parallel_COUNT / Parallel_LIMIT; i > 0; --i) {
            for (int j = Parallel_LIMIT; j > 0; --j) {
                var $as = new AsyncStepsRI(state.async_tool);
                $as.add(
                        (asi) -> {
                            stack.add(asi);
                            asi.waitExternal();
                        });
                $as.<Integer>add(
                        (asi, res) -> {
                            remaining.getAndAdd(-res);
                        });
                $as.execute();
            }

            state.completeWork();

            for (var oasi = stack.poll(); oasi != null; oasi = stack.poll()) {
                oasi.success(1);
            }

            state.completeWork();
        }

        blackhole.consume(remaining);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OperationsPerInvocation(ParallelWaitLoop_COUNT)
    public void parallelWaitLoop(FutoInState state, Blackhole blackhole) {
        var stack = new ArrayDeque<AsyncSteps>();
        var remaining = new AtomicInteger(ParallelWaitLoop_COUNT);

        for (int i = Parallel_LIMIT; i > 0; --i) {
            var $as = new AsyncStepsRI(state.async_tool);
            $as.loop(
                    (asi) -> {
                        if (remaining.get() <= 0) {
                            asi.breakLoop();
                        }
                        asi.add(
                                (asi2) -> {
                                    stack.add(asi2);
                                    asi2.waitExternal();
                                });
                        asi.<Integer>add(
                                (asi2, res) -> {
                                    remaining.getAndAdd(-res);
                                });
                    });
            $as.execute();
        }

        {
            var $as = new AsyncStepsRI(state.async_tool);

            $as.loop(
                    (asi) -> {
                        if (remaining.get() <= 0) {
                            asi.breakLoop();
                        }

                        for (var oasi = stack.poll(); oasi != null; oasi = stack.poll()) {
                            oasi.success(1);
                        }

                        asi.relinquish();
                    });

            $as.execute();
        }

        state.completeWork();

        for (var oasi = stack.poll(); oasi != null; oasi = stack.poll()) {
            oasi.success(0);
        }

        state.completeWork();

        blackhole.consume(remaining);
    }
}
