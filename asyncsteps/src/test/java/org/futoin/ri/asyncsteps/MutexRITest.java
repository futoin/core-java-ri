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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.futoin.api.AsyncSteps;
import org.futoin.api.Error;
import org.futoin.api.Mutex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MutexRITest {
    static class EntryCounter {
        volatile AtomicInteger counter = new AtomicInteger(0);
        volatile AtomicInteger concurrent = new AtomicInteger(0);
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
                    new MutexRI(-1);
                });
    }

    // --------------------------------------------------------------------
    @ParameterizedTest
    @ValueSource(ints = {1, 3, 10})
    void singleThread(int max) throws Throwable {
        AsyncSteps $as = new AsyncStepsRI();
        Mutex mtx = (max == 1) ? new MutexRI() : new MutexRI(max);

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
                                mtx,
                                (asi2) -> {
                                    counter.enter();
                                    asi2.relinquish();
                                    asi2.sync(mtx, (asi3) -> asi3.relinquish());
                                    asi2.add((asi3) -> counter.leave());
                                });
                    }
                });

        $as.add(
                (asi) -> {
                    done.complete(null);
                });

        $as.execute();

        done.get(1, TimeUnit.SECONDS);

        assertEquals(max, counter.max);
        assertEquals(10, counter.counter.get());
    }

    // --------------------------------------------------------------------
    @Test
    void queueLimit() throws Throwable {
        AsyncSteps $as = new AsyncStepsRI();
        Mutex mtx = new MutexRI(1, 3);

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
                                            mtx,
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
        Mutex mtx = new MutexRI(1, 0);

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
                                            mtx,
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
    void cancelEdgeCases() throws Throwable {
        AsyncSteps $as = new AsyncStepsRI();
        Mutex mtx = new MutexRI(1, Integer.MAX_VALUE);

        var counter = new EntryCounter();
        var done = new CompletableFuture<Void>();

        $as.state()
                .set_catch_trace(
                        (ex) -> {
                            ex.printStackTrace(System.err);
                        });

        $as.add(
                (asi) -> {
                    var $asi_a = asi.newInstance();
                    var $asi_b = asi.newInstance();
                    var $asi_c = asi.newInstance();
                    var $asi_d = asi.newInstance();

                    $asi_a.sync(
                                    mtx,
                                    (asi2) -> {
                                        counter.enter();
                                        asi2.relinquish();
                                        asi2.add(
                                                (asi3) -> {
                                                    // Normal cancel in queue
                                                    $asi_b.cancel();
                                                    // Cancel upon schedule from queue
                                                    asi3.tool()
                                                            .immediate(
                                                                    () -> {
                                                                        $asi_d.cancel();
                                                                    });
                                                });
                                    })
                            .execute();

                    $asi_b.sync(
                                    mtx,
                                    (asi2) -> {
                                        counter.enter();
                                    })
                            .execute();
                    $asi_d.sync(
                                    mtx,
                                    (asi2) -> {
                                        counter.enter();
                                    })
                            .execute();
                    $asi_c.sync(
                                    mtx,
                                    (asi2) -> {
                                        counter.enter();
                                        asi2.tool()
                                                .immediate(
                                                        () -> {
                                                            $asi_c.cancel();
                                                        });
                                        asi2.relinquish();
                                        asi2.setCancel(
                                                (asi3) -> {
                                                    asi3.newInstance()
                                                            .sync(
                                                                    mtx,
                                                                    (asi4) -> {
                                                                        done.complete(null);
                                                                    })
                                                            .execute();
                                                });
                                    })
                            .execute();
                });

        $as.execute();

        done.get(1, TimeUnit.SECONDS);

        assertEquals(2, counter.counter.get());
    }

    // --------------------------------------------------------------------
    @Test
    void cancelEdgeCasesMT() throws Throwable {
        try (var at_a = new AsyncToolRI();
                var at_b = new AsyncToolRI();
                var at_c = new AsyncToolRI();
                var at_d = new AsyncToolRI()) {
            AsyncSteps $as = new AsyncStepsRI();
            Mutex mtx = new MutexRI(1, Integer.MAX_VALUE);

            var counter = new EntryCounter();
            var done = new CompletableFuture<Void>();

            var $asi_a = new AsyncStepsRI(at_a);
            var $asi_b = new AsyncStepsRI(at_b);
            var $asi_c = new AsyncStepsRI(at_c);
            var $asi_d = new AsyncStepsRI(at_d);

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        $asi_a.sync(
                                        mtx,
                                        (asi2) -> {
                                            counter.enter();
                                            asi2.relinquish();
                                            asi2.add(
                                                    (asi3) -> {
                                                        // Normal cancel in queue
                                                        $asi_b.cancel();
                                                        // Cancel upon schedule from queue
                                                        asi3.tool()
                                                                .immediate(
                                                                        () -> {
                                                                            $asi_d.cancel();
                                                                        });
                                                    });
                                        })
                                .execute();

                        $asi_b.sync(
                                        mtx,
                                        (asi2) -> {
                                            counter.enter();
                                        })
                                .execute();
                        $asi_d.sync(
                                        mtx,
                                        (asi2) -> {
                                            // this one is a race
                                            // counter.enter();
                                        })
                                .execute();
                        $asi_c.sync(
                                        mtx,
                                        (asi2) -> {
                                            counter.enter();
                                            asi2.tool()
                                                    .immediate(
                                                            () -> {
                                                                $asi_c.cancel();
                                                            });
                                            asi2.relinquish();
                                            asi2.setCancel(
                                                    (asi3) -> {
                                                        asi3.newInstance()
                                                                .sync(
                                                                        mtx,
                                                                        (asi4) -> {
                                                                            done.complete(null);
                                                                        })
                                                                .execute();
                                                    });
                                        })
                                .execute();
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(2, counter.counter.get());
        }
    }

    // --------------------------------------------------------------------
    @Test
    void mutexStress() throws Throwable {
        try (var at_a = new AsyncToolRI();
                var at_b = new AsyncToolRI();
                var at_c = new AsyncToolRI()) {
            var COUNT = 1000;
            var CONCURRENT = 2;
            Mutex mtx = new MutexRI(CONCURRENT, Integer.MAX_VALUE);

            var counter = new EntryCounter();
            var counter_a = new EntryCounter();
            var counter_b = new EntryCounter();
            var counter_c = new EntryCounter();

            var done_a = new CompletableFuture<Void>();
            var done_c = new CompletableFuture<Void>();
            AsyncSteps.State.CatchTrace catch_trace =
                    (asi, ex) -> {
                        ex.printStackTrace(System.err);
                    };

            for (int i = 1; i <= COUNT; ++i) {
                final var variant = i;

                var $asi_a = new AsyncStepsRI(at_a);
                var $asi_b = new AsyncStepsRI(at_b);
                var $asi_c = new AsyncStepsRI(at_c);

                $asi_a.state().set_catch_trace(catch_trace);
                $asi_b.state().set_catch_trace(catch_trace);
                $asi_c.state().set_catch_trace(catch_trace);

                $asi_a.add(
                                (asi) -> {
                                    asi.sync(
                                            mtx,
                                            (asi2) -> {
                                                counter.enter();
                                                counter_a.enter();
                                                asi2.setCancel(
                                                        (asi3) -> {
                                                            counter.leave();
                                                            counter_a.leave();
                                                        });
                                                asi2.setTimeout(Math.max(variant % 10, 1));
                                            });
                                },
                                (asi, err) -> {
                                    if (err.equals(Error.Timeout)) {
                                        asi.success();
                                    }

                                    if (counter_a.counter.get() == COUNT) {
                                        done_a.complete(null);
                                    }
                                })
                        .execute();

                $asi_b.sync(
                                mtx,
                                (asi) -> {
                                    counter.enter();
                                    counter_b.enter();
                                    asi.setCancel(
                                            (asi2) -> {
                                                counter.leave();
                                                counter_b.leave();
                                            });
                                    asi.add(
                                            (asi2) -> {
                                                asi2.setTimeout(Math.max(variant % 5, 1));
                                            },
                                            (asi2, err) -> {
                                                if (err.equals(Error.Timeout)) {
                                                    asi2.success();
                                                }
                                            });
                                    asi.add(
                                            (asi2) -> {
                                                counter.leave();
                                                counter_b.leave();
                                            });
                                })
                        .execute();
                $asi_c.sync(
                                mtx,
                                (asi) -> {
                                    counter.enter();
                                    counter_c.enter();
                                    if (variant % 2 != 0 && variant != COUNT) {
                                        $asi_b.cancel();
                                    }
                                    asi.relinquish();
                                    asi.add(
                                            (asi2) -> {
                                                counter.leave();
                                                counter_c.leave();
                                            });
                                })
                        .add(
                                (asi) -> {
                                    if (counter_c.counter.get() == COUNT) {
                                        done_c.complete(null);
                                    }
                                })
                        .execute();
            }

            try {
                done_a.get(10, TimeUnit.SECONDS);
                done_c.get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                assertTrue(
                        counter_a.max <= CONCURRENT
                                && counter_b.max <= CONCURRENT
                                && counter_c.max <= CONCURRENT
                                && counter.max <= CONCURRENT,
                        "a/b/c = "
                                + counter_a.max
                                + "/"
                                + counter_b.max
                                + "/"
                                + counter_c.max
                                + "/"
                                + counter.max);
                assertEquals(COUNT, counter_a.counter.get());
                assertTrue(
                        COUNT >= counter_b.counter.get(),
                        "counter_b.counter=" + counter_b.counter.get());
                assertEquals(COUNT, counter_c.counter.get());
                assertEquals(CONCURRENT, counter.max);
            }
        }
    }
}
