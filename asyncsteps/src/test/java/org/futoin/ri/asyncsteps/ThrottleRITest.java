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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.futoin.api.AsyncSteps;
import org.futoin.api.Error;
import org.futoin.api.Throttle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ThrottleRITest {
    static class EntryCounter {
        final AtomicInteger counter = new AtomicInteger(0);
        final AtomicInteger concurrent = new AtomicInteger(0);
        volatile int max = 0;

        void enter() {
            counter.incrementAndGet();
            max = Math.max(max, concurrent.incrementAndGet());
        }

        void leave() {
            concurrent.decrementAndGet();
        }
    }

    // --------------------------------------------------------------------
    @Test
    void invalidArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    new ThrottleRI(-1);
                });
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    new ThrottleRI(AsyncToolRI.shared(), -1);
                });
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    new ThrottleRI(AsyncToolRI.shared(), 100, -1);
                });
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    new ThrottleRI(AsyncToolRI.shared(), 100, Duration.ofMillis(-1));
                });
        new ThrottleRI(AsyncToolRI.shared(), 100);
        new ThrottleRI(AsyncToolRI.shared(), 100, 1);
        new ThrottleRI(AsyncToolRI.shared(), 100, Duration.ofMillis(1));
        new ThrottleRI(AsyncToolRI.shared(), 100, Duration.ofMillis(1), 100);
    }

    // --------------------------------------------------------------------
    @ParameterizedTest
    @ValueSource(ints = {3, 7, 10})
    void singleThread(int max) throws Throwable {
        AsyncSteps $as = new AsyncStepsRI();
        Throttle thtl = new ThrottleRI(max);

        var counter = new EntryCounter();
        var done = new CompletableFuture<Void>();

        $as.state()
                .set_catch_trace(
                        (ex) -> {
                            ex.printStackTrace(System.err);
                        });

        $as.add(
                (asi) -> {
                    var p = asi.parallel();
                    for (int i = 10; i > 0; --i) {
                        p.sync(
                                thtl,
                                (asi2) -> {
                                    counter.enter();
                                    asi2.relinquish();
                                    asi2.add((asi3) -> counter.leave());
                                });
                    }
                });

        $as.add(
                (asi) -> {
                    done.complete(null);
                });

        $as.execute();

        if (max < 10) {
            assertThrows(
                    TimeoutException.class,
                    () -> {
                        done.get(1, TimeUnit.SECONDS);
                    });
        }

        done.get(10 / max, TimeUnit.SECONDS);

        assertEquals(max, counter.max);
        assertEquals(10, counter.counter.get());
    }

    // --------------------------------------------------------------------
    @Test
    void queueLimit() throws Throwable {
        AsyncSteps $as = new AsyncStepsRI();
        Throttle thtl = new ThrottleRI($as.tool(), 1, 10, 3);

        var counter = new EntryCounter();
        var done = new CompletableFuture<Void>();

        $as.state()
                .set_catch_trace(
                        (ex) -> {
                            ex.printStackTrace(System.err);
                        });

        $as.add(
                (asi) -> {
                    var p = asi.parallel();
                    for (int i = 5; i > 0; --i) {
                        p.add(
                                (asi2) -> {
                                    asi2.sync(
                                            thtl,
                                            (asi3) -> {
                                                counter.enter();
                                                asi3.relinquish();
                                                asi3.add((asi4) -> counter.leave());
                                            });
                                },
                                (asi2, err) -> {
                                    if (err.equals(Error.DefenseRejected)) {
                                        asi2.success();
                                    }
                                });
                    }
                });

        $as.add(
                (asi) -> {
                    done.complete(null);
                });

        $as.execute();

        done.get(1, TimeUnit.SECONDS);

        assertEquals(1, counter.max);
        assertEquals(4, counter.counter.get());
    }

    // --------------------------------------------------------------------
    @Test
    void noQueue() throws Throwable {
        AsyncSteps $as = new AsyncStepsRI();
        Throttle thtl = new ThrottleRI($as.tool(), 1, 10, 0);

        var counter = new EntryCounter();
        var done = new CompletableFuture<Void>();

        $as.state()
                .set_catch_trace(
                        (ex) -> {
                            ex.printStackTrace(System.err);
                        });

        $as.add(
                (asi) -> {
                    var p = asi.parallel();
                    for (int i = 5; i > 0; --i) {
                        p.add(
                                (asi2) -> {
                                    asi2.sync(
                                            thtl,
                                            (asi3) -> {
                                                counter.enter();
                                                asi3.relinquish();
                                                asi3.add((asi4) -> counter.leave());
                                            });
                                },
                                (asi2, err) -> {
                                    if (err.equals(Error.DefenseRejected)) {
                                        asi2.success();
                                    }
                                });
                    }
                });

        $as.add(
                (asi) -> {
                    done.complete(null);
                });

        $as.execute();

        done.get(1, TimeUnit.SECONDS);

        assertEquals(1, counter.max);
        assertEquals(1, counter.counter.get());
    }

    // --------------------------------------------------------------------
    @Test
    void delayedTimer() throws Throwable {
        AsyncSteps $as = new AsyncStepsRI();
        Throttle thtl = new ThrottleRI($as.tool(), 1, 10, 3);

        var counter = new EntryCounter();
        var done = new CompletableFuture<Void>();

        $as.state()
                .set_catch_trace(
                        (ex) -> {
                            ex.printStackTrace(System.err);
                        });

        $as.add(
                (asi) -> {
                    var p = asi.parallel();
                    for (int i = 5; i > 0; --i) {
                        final var idx = i;
                        p.add(
                                (asi2) -> {
                                    asi2.sync(
                                            thtl,
                                            (asi3) -> {
                                                counter.enter();
                                                if (idx >= 4) {
                                                    try {
                                                        Thread.sleep(15);
                                                    } catch (Exception e) {
                                                        // pass
                                                    }
                                                }
                                                asi3.relinquish();
                                                asi3.add(
                                                        (asi4) -> {
                                                            counter.leave();
                                                        });
                                            });
                                },
                                (asi2, err) -> {
                                    if (err.equals(Error.DefenseRejected)) {
                                        asi2.success();
                                    }
                                });
                    }
                });

        $as.add(
                (asi) -> {
                    done.complete(null);
                });

        var startTs = System.nanoTime();
        $as.execute();

        done.get(1, TimeUnit.SECONDS);
        var diffTime = System.nanoTime() - startTs;

        assertTrue(diffTime < 45_000_000, "diffTime = " + diffTime);

        assertEquals(2, counter.max);
        assertEquals(4, counter.counter.get());
    }

    // --------------------------------------------------------------------
    @Test
    void throttleStress() throws Throwable {
        try (var at_a = new AsyncToolRI();
                var at_b = new AsyncToolRI();
                var at_c = new AsyncToolRI()) {
            Throttle thtl = new ThrottleRI(at_a, 2, 1);

            var counter = new EntryCounter();

            var done_a = new CompletableFuture<Void>();
            var done_b = new CompletableFuture<Void>();
            var done_c = new CompletableFuture<Void>();

            AsyncSteps.State.CatchTrace catch_trace =
                    (asi, ex) -> {
                        ex.printStackTrace(System.err);
                    };

            AsyncSteps $asi_a = new AsyncStepsRI(at_a);
            AsyncSteps $asi_b = new AsyncStepsRI(at_b);
            AsyncSteps $asi_c = new AsyncStepsRI(at_c);

            $asi_a.state().set_catch_trace(catch_trace);
            $asi_b.state().set_catch_trace(catch_trace);
            $asi_c.state().set_catch_trace(catch_trace);

            $asi_a.repeat(
                    1000,
                    (asi, i) -> {
                        asi.add(
                                (asi2) -> {
                                    asi2.sync(
                                            thtl,
                                            (asi3) -> {
                                                counter.enter();
                                                asi3.setCancel((asi4) -> counter.leave());
                                                asi3.setTimeout(Math.max(i % 10, 1));
                                            });
                                },
                                (asi2, err) -> {
                                    if (err.equals(Error.Timeout)) {
                                        asi2.success();
                                    }
                                });
                    });
            $asi_b.repeat(
                    1000,
                    (asi, i) -> {
                        asi.sync(
                                thtl,
                                (asi2) -> {
                                    counter.enter();
                                    asi2.add(
                                            (asi3) -> {
                                                asi3.setTimeout(Math.max(i % 5, 1));
                                            },
                                            (asi3, err) -> {
                                                counter.leave();
                                                if (err.equals(Error.Timeout)) {
                                                    asi3.success();
                                                }
                                            });
                                });
                    });
            $asi_c.repeat(
                    1000,
                    (asi, i) -> {
                        asi.sync(
                                thtl,
                                (asi2) -> {
                                    counter.enter();
                                    asi2.relinquish();
                                    asi2.add((asi3) -> counter.leave());
                                });
                    });

            $asi_a.add((asi) -> done_a.complete(null));
            $asi_b.add((asi) -> done_b.complete(null));
            $asi_c.add((asi) -> done_c.complete(null));

            $asi_a.execute();
            $asi_b.execute();
            $asi_c.execute();

            try {
                done_a.get(10, TimeUnit.SECONDS);
                done_b.get(10, TimeUnit.SECONDS);
                done_c.get(10, TimeUnit.SECONDS);
            } finally {
                assertEquals(3000, counter.counter.get());
            }
        }
    }
}
