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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

@Threads(value = 1)
@Fork(
        value = 1,
        jvmArgsAppend = {"-Djdk.virtualThreadScheduler.parallelism=1"})
@Timeout(time = 90, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
public class VirtualThreadsBench extends BenchBase {
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void simple() throws Throwable {
        Thread.ofVirtual().start(() -> {}).join();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OperationsPerInvocation(Parallel_COUNT)
    public void parallel(Blackhole blackhole) throws Throwable {
        var stack = new ArrayDeque<CompletableFuture<Integer>>();
        var remaining = new AtomicInteger(Parallel_COUNT);
        var done = new CompletableFuture<Void>();

        for (int i = Parallel_COUNT / Parallel_LIMIT; i >= 0; --i) {
            var threads = new ArrayDeque<Thread>();
            for (int j = Parallel_LIMIT; j > 0; --j) {
                var t =
                        Thread.ofVirtual()
                                .start(
                                        () -> {
                                            var f = new CompletableFuture<Integer>();
                                            /*synchronized (stack)*/ {
                                                stack.add(f);
                                            }
                                            try {
                                                if (remaining.addAndGet(-f.get()) == 0) {
                                                    done.complete(null);
                                                }
                                            } catch (Exception e) {
                                                throw new RuntimeException(e);
                                            }
                                        });
                threads.add(t);
            }

            Thread.ofVirtual()
                    .start(
                            () -> {
                                for (int j = Parallel_LIMIT; j > 0; --j) {
                                    for (; ; ) {
                                        CompletableFuture<Integer> f;
                                        /*synchronized (stack)*/ {
                                            f = stack.poll();
                                        }
                                        if (f != null) {
                                            f.complete(1);
                                            break;
                                        } else {
                                            Thread.yield();
                                        }
                                    }
                                }
                            })
                    .join();

            // Resources get exhausted otherwise
            threads.forEach(
                    (t) -> {
                        try {
                            t.join();
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        }

        done.get();

        blackhole.consume(remaining);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OperationsPerInvocation(ParallelWaitLoop_COUNT)
    public void parallelWaitLoop(Blackhole blackhole) throws Throwable {
        var stack = new ArrayDeque<CompletableFuture<Integer>>();
        var remaining = new AtomicInteger(ParallelWaitLoop_COUNT);
        var done = new CompletableFuture<Void>();
        var threads = new ArrayDeque<Thread>();

        for (int i = Parallel_LIMIT; i > 0; --i) {
            var t =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        for (; ; ) {
                                            if (remaining.get() <= 0) {
                                                break;
                                            }
                                            var f = new CompletableFuture<Integer>();
                                            /*synchronized (stack)*/ {
                                                stack.add(f);
                                            }
                                            try {
                                                if (remaining.addAndGet(-f.get()) == 0) {
                                                    done.complete(null);
                                                }
                                            } catch (Exception e) {
                                                throw new RuntimeException(e);
                                            }
                                        }
                                    });
            threads.add(t);
        }

        Thread.ofVirtual()
                .start(
                        () -> {
                            for (int j = ParallelWaitLoop_COUNT; j > 0; --j) {
                                for (; ; ) {
                                    CompletableFuture<Integer> f;
                                    /*synchronized (stack)*/ {
                                        f = stack.poll();
                                    }
                                    if (f != null) {
                                        f.complete(1);
                                        break;
                                    } else {
                                        Thread.yield();
                                    }
                                }
                            }
                        })
                .join();

        done.get();

        /*synchronized (stack)*/ {
            stack.forEach((f) -> f.complete(0));
        }

        // Resources get exhausted otherwise
        threads.forEach(
                (t) -> {
                    try {
                        t.join();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });

        blackhole.consume(remaining);
    }
}
