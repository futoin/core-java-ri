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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.futoin.api.AsyncSteps;
import org.futoin.api.Error;
import org.futoin.api.ExtError;
import org.junit.jupiter.api.Test;

class AsyncStepsRITest {
    static class CommonAPI {
        // --------------------------------------------------------------------
        @Test
        void successFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            var data =
                    new Object() {
                        int counter = 0;
                    };

            $as.add(
                    (asi) -> {
                        assertNull($as.state());
                        data.counter++;
                        assertNotNull(asi.state());
                    });

            // ---
            $as.add(
                    (asi) -> {
                        data.counter++;
                        asi.success(3, 3.23f, "str3");
                    });
            $as.add(
                    (AsyncSteps asi, Integer i, Float f, String s) -> {
                        data.counter++;
                        assertEquals(3, i);
                        assertEquals(3.23f, f);
                        assertEquals("str3", s);
                    });

            // ---
            $as.add(
                    (asi) -> {
                        data.counter++;
                        asi.success(2, 1.23f);
                    });
            $as.add(
                    (AsyncSteps asi, Integer i, Float f) -> {
                        data.counter++;
                        assertEquals(2, i);
                        assertEquals(1.23f, f);
                    });

            // ---
            $as.add(
                    (asi) -> {
                        data.counter++;
                        asi.success(2);
                    });
            $as.add(
                    (AsyncSteps asi, Integer i) -> {
                        data.counter++;
                        assertEquals(2, i);
                    });

            // ---
            $as.add(
                    (asi) -> {
                        data.counter++;
                        asi.success(2, 1.23f, "str", true);
                    },
                    (asi, err) -> {});

            $as.<Integer, Float, String, Boolean>add(
                    (asi, i, f, s, b) -> {
                        data.counter++;
                        assertEquals(2, i);
                        assertEquals(1.23f, f);
                        assertEquals("str", s);
                        assertEquals(true, b);

                        asi.add(
                                (asi2) -> {
                                    asi2.success(List.of(1, 2, 3));
                                });
                    });

            $as.<List<Integer>>add(
                    (asi, list) -> {
                        data.counter++;
                        assertEquals(1, list.get(0));
                        assertEquals(2, list.get(1));
                        assertEquals(3, list.get(2));
                    });

            $as.addRaw(
                    (asi, args) -> {
                        assertEquals(0, args.args.length);
                        done.complete(null);
                    });
            $as.execute();

            done.get(1, TimeUnit.SECONDS);
            assertEquals(10, data.counter);
        }

        // --------------------------------------------------------------------
        @Test
        void errorFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                // ex.printStackTrace(System.err);
                            });

            var required = List.of(10, 100, 1000, 10000, 1001, 101, 11, 20, 21, 210);
            var result = new ArrayList<Integer>();

            $as.add(
                    (asi) -> {
                        result.add(10);
                        asi.add(
                                (asi2) -> {
                                    result.add(100);

                                    asi2.add(
                                            (asi3) -> {
                                                result.add(1000);

                                                asi3.add(
                                                        (asi4) -> {
                                                            result.add(10000);
                                                            asi4.error("FirstError", "FirstInfo");
                                                        });
                                            },
                                            (asi3, err) -> {
                                                result.add(1001);
                                                assertEquals("FirstError", err);
                                                assertEquals(
                                                        "FirstInfo", asi3.state().error_info());
                                            });
                                },
                                (asi2, err) -> {
                                    result.add(101);

                                    assertEquals("FirstError", err);
                                    assertEquals("FirstInfo", asi2.state().error_info());
                                    asi2.error("SecondError", "SecondInfo");
                                });
                        asi.add(
                                (asi2) -> {
                                    result.add(102);
                                });
                    },
                    (asi, err) -> {
                        result.add(11);
                        assertEquals("SecondError", err);
                        assertEquals("SecondInfo", asi.state().error_info());
                        asi.success("Yes");
                    });
            $as.<String>add(
                    (asi, res) -> {
                        result.add(20);

                        assertEquals("Yes", res);

                        asi.error("ThirdError");
                    },
                    (asi, err) -> {
                        result.add(21);

                        assertEquals("ThirdError", err);
                        assertEquals("", asi.state().error_info());

                        asi.add(
                                (asi2) -> {
                                    result.add(210);
                                });
                    });

            $as.add(
                    (asi) -> {
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(required, result);
        }

        // --------------------------------------------------------------------
        @Test
        void errorNoThrowFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            var required = List.of(10, 100, 1000, 10000, 1001, 101, 11, 20, 21, 210);
            var result = new ArrayList<Integer>();

            $as.add(
                    (asi) -> {
                        result.add(10);
                        asi.add(
                                (asi2) -> {
                                    result.add(100);

                                    asi2.add(
                                            (asi3) -> {
                                                result.add(1000);

                                                asi3.add(
                                                        (asi4) -> {
                                                            result.add(10000);
                                                            asi4.errorNoThrow(
                                                                    "FirstError", "FirstInfo");
                                                        });
                                            },
                                            (asi3, err) -> {
                                                result.add(1001);
                                                assertEquals("FirstError", err);
                                                assertEquals(
                                                        "FirstInfo", asi3.state().error_info());
                                            });
                                },
                                (asi2, err) -> {
                                    result.add(101);

                                    assertEquals("FirstError", err);
                                    assertEquals("FirstInfo", asi2.state().error_info());
                                    asi2.errorNoThrow("SecondError", "SecondInfo");
                                });
                        asi.add(
                                (asi2) -> {
                                    result.add(102);
                                });
                    },
                    (asi, err) -> {
                        result.add(11);
                        assertEquals("SecondError", err);
                        assertEquals("SecondInfo", asi.state().error_info());
                        asi.success("Yes");
                    });
            $as.<String>add(
                    (asi, res) -> {
                        result.add(20);

                        assertEquals("Yes", res);

                        asi.errorNoThrow("ThirdError");
                    },
                    (asi, err) -> {
                        result.add(21);

                        assertEquals("ThirdError", err);
                        assertEquals("", asi.state().error_info());

                        asi.add(
                                (asi2) -> {
                                    result.add(210);
                                });
                    });
            $as.add(
                    (asi) -> {
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(required, result);
        }

        // --------------------------------------------------------------------
        @Test
        void errorExceptionFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                // ex.printStackTrace(System.err);
                            });

            var required = List.of(10, 100, 1000, 10000, 1001, 101, 11, 20, 21, 210);
            var result = new ArrayList<Integer>();

            $as.add(
                    (asi) -> {
                        result.add(10);
                        asi.add(
                                (asi2) -> {
                                    result.add(100);

                                    asi2.add(
                                            (asi3) -> {
                                                result.add(1000);

                                                asi3.add(
                                                        (asi4) -> {
                                                            result.add(10000);
                                                            throw new ExtError(
                                                                    "FirstError", "FirstInfo");
                                                        });
                                            },
                                            (asi3, err) -> {
                                                result.add(1001);
                                                assertEquals("FirstError", err);
                                                assertEquals(
                                                        "FirstInfo", asi3.state().error_info());
                                            });
                                },
                                (asi2, err) -> {
                                    result.add(101);

                                    assertEquals("FirstError", err);
                                    assertEquals("FirstInfo", asi2.state().error_info());
                                    throw new ExtError("SecondError", "SecondInfo");
                                });
                        asi.add(
                                (asi2) -> {
                                    result.add(102);
                                });
                    },
                    (asi, err) -> {
                        result.add(11);
                        assertEquals("SecondError", err);
                        assertEquals("SecondInfo", asi.state().error_info());
                        asi.success("Yes");
                    });
            $as.<String>add(
                    (asi, res) -> {
                        result.add(20);

                        assertEquals("Yes", res);

                        throw new Error("ThirdError");
                    },
                    (asi, err) -> {
                        result.add(21);

                        assertEquals("ThirdError", err);
                        assertEquals("", asi.state().error_info());

                        asi.add(
                                (asi2) -> {
                                    result.add(210);
                                });
                    });
            $as.add(
                    (asi) -> {
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(required, result);
        }

        // --------------------------------------------------------------------
        @Test
        void setCancelSuccessFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<AsyncSteps> wait = new CompletableFuture<>();
            CompletableFuture<Void> done = new CompletableFuture<>();

            var data =
                    new Object() {
                        int counter = 0;
                    };

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        data.counter++;
                        asi.setCancel((asi2) -> {});
                        wait.complete(asi);
                    });
            $as.add(
                    (asi) -> {
                        data.counter++;
                        done.complete(null);
                    });

            $as.execute();

            wait.get(1, TimeUnit.SECONDS).success();
            done.get(1, TimeUnit.SECONDS);

            assertEquals(2, data.counter);
        }

        // --------------------------------------------------------------------
        @Test
        void setCancelErrorFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<AsyncSteps> wait = new CompletableFuture<>();
            CompletableFuture<Void> done = new CompletableFuture<>();

            var data =
                    new Object() {
                        int counter = 0;
                    };

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        data.counter++;
                        asi.setCancel(
                                (asi2) -> {
                                    data.counter++;
                                });
                        wait.complete(asi);
                    },
                    (asi, err) -> {
                        assertEquals("MyError", err);
                        data.counter++;
                        asi.success();
                    });
            $as.add(
                    (asi) -> {
                        data.counter++;
                        done.complete(null);
                    });

            $as.execute();

            wait.get(1, TimeUnit.SECONDS).errorNoThrow("MyError");
            done.get(1, TimeUnit.SECONDS);

            assertEquals(4, data.counter);
        }

        // --------------------------------------------------------------------
        @Test
        void waitExternalErrorNoThrowFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<AsyncSteps> wait = new CompletableFuture<>();
            CompletableFuture<Void> done = new CompletableFuture<>();

            var data =
                    new Object() {
                        int counter = 0;
                    };

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        data.counter++;
                        asi.waitExternal();
                        wait.complete(asi);
                    },
                    (asi, err) -> {
                        data.counter++;
                        assertEquals("SomeError", err);
                        asi.success();
                    });
            $as.add(
                    (asi) -> {
                        data.counter++;
                        done.complete(null);
                    });

            $as.execute();

            wait.get(1, TimeUnit.SECONDS).errorNoThrow("SomeError");
            done.get(1, TimeUnit.SECONDS);

            assertEquals(3, data.counter);
        }

        // --------------------------------------------------------------------
        @Test
        void waitExternalErrorFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<AsyncSteps> wait = new CompletableFuture<>();
            CompletableFuture<Void> done = new CompletableFuture<>();

            var data =
                    new Object() {
                        int counter = 0;
                    };

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        data.counter++;
                        asi.waitExternal();
                        wait.complete(asi);
                    },
                    (asi, err) -> {
                        data.counter++;
                        assertEquals("SomeError", err);
                        asi.success();
                    });
            $as.add(
                    (asi) -> {
                        data.counter++;
                        done.complete(null);
                    });

            $as.execute();

            assertThrows(
                    ExtError.class,
                    () -> {
                        wait.get(1, TimeUnit.SECONDS).error("SomeError");
                    });
            done.get(1, TimeUnit.SECONDS);

            assertEquals(3, data.counter);
        }

        // --------------------------------------------------------------------
        @Test
        void externalCancelFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<AsyncSteps> wait = new CompletableFuture<>();
            CompletableFuture<Void> done = new CompletableFuture<>();

            var data =
                    new Object() {
                        int counter = 0;
                    };

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        data.counter++;
                        asi.setCancel(
                                (asi2) -> {
                                    data.counter++;
                                    done.complete(null);
                                });
                        wait.complete(asi);
                    });

            $as.execute();

            wait.get(1, TimeUnit.SECONDS);
            $as.cancel();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(2, data.counter);
        }

        // --------------------------------------------------------------------
        @Test
        void externalSuccessCancelRace() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<AsyncSteps> wait = new CompletableFuture<>();
            CompletableFuture<Void> done = new CompletableFuture<>();

            var data =
                    new Object() {
                        int counter = 0;
                    };

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        data.counter++;
                        wait.complete(asi);
                        asi.waitExternal();
                    });
            $as.add(
                    (asi) -> {
                        data.counter++;
                    });

            $as.execute();

            var inner_asi = wait.get(1, TimeUnit.SECONDS);
            $as.cancel();
            inner_asi.success();
            $as.tool()
                    .immediate(
                            () -> {
                                done.complete(null);
                            });

            done.get(1, TimeUnit.SECONDS);

            assertEquals(1, data.counter);
        }

        // --------------------------------------------------------------------
        @Test
        void externalErrorCancelRace() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<AsyncSteps> wait = new CompletableFuture<>();
            CompletableFuture<Void> done = new CompletableFuture<>();

            var data =
                    new Object() {
                        int counter = 0;
                    };

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        data.counter++;
                        wait.complete(asi);
                        asi.waitExternal();
                    },
                    (asi, err) -> {
                        data.counter++;
                    });

            $as.execute();

            var inner_asi = wait.get(1, TimeUnit.SECONDS);
            $as.cancel();
            inner_asi.errorNoThrow("Test");
            $as.tool()
                    .immediate(
                            () -> {
                                done.complete(null);
                            });

            done.get(1, TimeUnit.SECONDS);

            assertEquals(1, data.counter);
        }

        // --------------------------------------------------------------------
        @Test
        void exceptionsOnCancel() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        asi.setCancel(
                                (asi2) -> {
                                    throw new RuntimeException("VerboseTestError1");
                                });
                        asi.setTimeout(1);
                    },
                    (asi, err) -> {
                        assertEquals(Error.Timeout, err);
                        asi.success();
                    });
            $as.add(
                    (asi) -> {
                        asi.setCancel(
                                (asi2) -> {
                                    throw new RuntimeException("VerboseTestError2");
                                });
                        asi.tool().immediate(() -> $as.cancel());
                    });

            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> {
                        $as.promise().get(1, TimeUnit.SECONDS);
                    });
        }

        // --------------------------------------------------------------------
        @Test
        void exceptionsNoMessage() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        throw new RuntimeException((String) null);
                    },
                    (asi, err) -> {
                        assertEquals("java.lang.RuntimeException", err);
                        asi.success();
                    });
            $as.add(
                    (asi) -> {
                        throw new RuntimeException("");
                    },
                    (asi, err) -> {
                        assertEquals("java.lang.RuntimeException", err);
                        asi.success();
                    });
            $as.add(
                    (asi) -> {
                        asi.add(
                                (asi2) -> {
                                    asi.error("SomeError");
                                },
                                (asi2, err) -> {
                                    throw new RuntimeException((String) null);
                                });
                    },
                    (asi, err) -> {
                        assertEquals("java.lang.RuntimeException", err);
                        asi.success();
                    });
            $as.add(
                    (asi) -> {
                        asi.add(
                                (asi2) -> {
                                    asi.error("SomeError");
                                },
                                (asi2, err) -> {
                                    throw new RuntimeException("");
                                });
                    },
                    (asi, err) -> {
                        assertEquals("java.lang.RuntimeException", err);
                        asi.success();
                    });

            $as.promise().get(1, TimeUnit.SECONDS);
        }

        // --------------------------------------------------------------------
        @Test
        void setTimeoutSuccessFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<AsyncSteps> wait = new CompletableFuture<>();
            CompletableFuture<Void> done = new CompletableFuture<>();

            var data =
                    new Object() {
                        int counter = 0;
                    };

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        data.counter++;
                        asi.setTimeout(3000);
                        wait.complete(asi);
                    });
            $as.add(
                    (asi) -> {
                        data.counter++;
                        done.complete(null);
                    });

            $as.execute();

            wait.get(1, TimeUnit.SECONDS).success();
            done.get(1, TimeUnit.SECONDS);

            assertEquals(2, data.counter);
        }

        // --------------------------------------------------------------------
        @Test
        void setTimeoutFailFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            var data =
                    new Object() {
                        int counter = 0;
                    };

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        data.counter++;
                        asi.setTimeout(100);
                    },
                    (asi, err) -> {
                        data.counter++;
                        assertEquals("Timeout", err);
                        asi.success();
                    });
            $as.add(
                    (asi) -> {
                        data.counter++;
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(3, data.counter);
        }

        // --------------------------------------------------------------------
        @Test
        void setTimeoutInnerFailFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            var data =
                    new Object() {
                        int counter = 0;
                    };

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        data.counter++;
                        asi.setTimeout(100);
                        asi.add(
                                (asi2) -> {
                                    asi2.waitExternal();
                                });
                    },
                    (asi, err) -> {
                        data.counter++;
                        assertEquals("Timeout", err);
                        asi.success();
                    });
            $as.add(
                    (asi) -> {
                        data.counter++;
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(3, data.counter);
        }

        // --------------------------------------------------------------------
        @Test
        void outOfOrderCalls() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    $as.add((asi2) -> {});
                                });
                        asi.add(
                                (asi2) -> {
                                    assertThrows(
                                            IllegalStateException.class,
                                            () -> {
                                                asi.setTimeout(1);
                                            });
                                    assertThrows(
                                            IllegalStateException.class,
                                            () -> {
                                                asi.setCancel((asi3) -> {});
                                            });
                                    assertThrows(
                                            IllegalStateException.class,
                                            () -> {
                                                asi.waitExternal();
                                            });
                                });

                        var p = asi.parallel();
                        p.add(
                                (asi2) -> {
                                    assertThrows(
                                            IllegalStateException.class,
                                            () -> {
                                                p.add((asi3) -> {});
                                            });
                                });
                    });

            $as.promise().get(1, TimeUnit.SECONDS);
        }
    }

    static class LoopAPI {
        // --------------------------------------------------------------------
        @Test
        void repeatFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            var data =
                    new Object() {
                        int counter = 0;
                    };

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                // ex.printStackTrace(System.err);
                            });

            $as.repeat(
                    99,
                    (asi, i) -> {
                        assertEquals(data.counter, i);
                        data.counter++;
                    });
            $as.add(
                    (asi) -> {
                        data.counter++;
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(100, data.counter);
        }

        // --------------------------------------------------------------------
        @Test
        void breakNoThrowFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            var required = List.of(1, 2, 3, 3, 2, 3, 3);
            var result = new ArrayList<Integer>();

            $as.loop(
                    (asi) -> {
                        result.add(1);

                        asi.forEach(
                                new int[] {1, 2, 3, 4},
                                (asi2, k, v) -> {
                                    result.add(2);

                                    asi2.repeat(
                                            3,
                                            (asi3, i) -> {
                                                result.add(3);

                                                if (i == 1) {
                                                    if (result.size() == 4) {
                                                        asi3.breakLoopNoThrow();
                                                    } else {
                                                        asi3.breakLoopNoThrow("Outer");
                                                    }
                                                }
                                            });
                                },
                                "Middle");
                    },
                    "Outer");

            $as.add(
                    (asi) -> {
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(required, result);
        }

        // --------------------------------------------------------------------
        @Test
        void breakFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                // ex.printStackTrace(System.err);
                            });

            var required = List.of(1, 2, 3, 3, 2, 3, 3);
            var result = new ArrayList<Integer>();

            $as.loop(
                    (asi) -> {
                        result.add(1);

                        asi.forEach(
                                new int[] {1, 2, 3, 4},
                                (asi2, k, v) -> {
                                    result.add(2);

                                    asi2.repeat(
                                            3,
                                            (asi3, i) -> {
                                                result.add(3);

                                                if (i == 1) {
                                                    if (result.size() == 4) {
                                                        asi3.breakLoop();
                                                    } else {
                                                        asi3.breakLoop("Outer");
                                                    }
                                                }
                                            });
                                },
                                "Middle");
                    },
                    "Outer");

            $as.add(
                    (asi) -> {
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(required, result);
        }

        // --------------------------------------------------------------------
        @Test
        void continueNoThrowFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                // ex.printStackTrace(System.err);
                            });

            var required = List.of(1, 2, 3, 3, 3, 2, 3, 3, 1);
            var result = new ArrayList<Integer>();

            $as.loop(
                    (asi) -> {
                        result.add(1);

                        if (result.size() > 1) {
                            asi.breakLoopNoThrow();
                            return;
                        }

                        asi.forEach(
                                new int[] {1, 2, 3, 4},
                                (asi2, k, v) -> {
                                    result.add(2);

                                    asi2.repeat(
                                            3,
                                            (asi3, i) -> {
                                                result.add(3);

                                                if (i == 1) {
                                                    if (result.size() == 4) {
                                                        asi3.continueLoopNoThrow();
                                                    } else {
                                                        asi3.continueLoopNoThrow("Outer");
                                                    }
                                                }
                                            });
                                },
                                "Middle");
                    },
                    "Outer");

            $as.add(
                    (asi) -> {
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(required, result);
        }

        // --------------------------------------------------------------------
        @Test
        void continueFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                // ex.printStackTrace(System.err);
                            });

            var required = List.of(1, 2, 3, 3, 3, 2, 3, 3, 1);
            var result = new ArrayList<Integer>();

            $as.loop(
                    (asi) -> {
                        result.add(1);

                        if (result.size() > 1) {
                            asi.breakLoop();
                        }

                        asi.forEach(
                                new int[] {1, 2, 3, 4},
                                (asi2, k, v) -> {
                                    result.add(2);

                                    asi2.repeat(
                                            3,
                                            (asi3, i) -> {
                                                result.add(3);

                                                if (i == 1) {
                                                    if (result.size() == 4) {
                                                        asi3.continueLoop();
                                                    } else {
                                                        asi3.continueLoop("Outer");
                                                    }
                                                }
                                            });
                                },
                                "Middle");
                    },
                    "Outer");

            $as.add(
                    (asi) -> {
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(required, result);
        }

        // --------------------------------------------------------------------
        @Test
        void loopErrorNoThrowFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                // ex.printStackTrace(System.err);
                            });

            var required = List.of(1, 2, 3);
            var result = new ArrayList<Integer>();

            $as.loop(
                    (asi) -> {
                        result.add(1);

                        asi.setCancel(
                                (asi2) -> {
                                    done.complete(null);
                                });

                        if (result.size() > 1) {
                            asi.breakLoopNoThrow();
                            return;
                        }

                        asi.forEach(
                                new int[] {1, 2, 3, 4},
                                (asi2, k, v) -> {
                                    result.add(2);

                                    asi2.repeat(
                                            3,
                                            (asi3, i) -> {
                                                result.add(3);

                                                asi3.errorNoThrow("MyError");
                                            });
                                },
                                "Middle");
                    },
                    "Outer");
            $as.state()
                    .set_unhandled_error(
                            (err) -> {
                                assertEquals("MyError", err);
                            });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(required, result);
        }

        // --------------------------------------------------------------------
        @Test
        void loopErrorFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                // ex.printStackTrace(System.err);
                            });

            var required = List.of(1, 2, 3);
            var result = new ArrayList<Integer>();

            $as.loop(
                    (asi) -> {
                        result.add(1);

                        asi.setCancel(
                                (asi2) -> {
                                    done.complete(null);
                                });

                        if (result.size() > 1) {
                            asi.breakLoopNoThrow();
                            return;
                        }

                        asi.forEach(
                                new int[] {1, 2, 3, 4},
                                (asi2, k, v) -> {
                                    result.add(2);

                                    asi2.repeat(
                                            3,
                                            (asi3, i) -> {
                                                result.add(3);

                                                asi3.error("MyError");
                                            });
                                },
                                "Middle");
                    },
                    "Outer");

            $as.state()
                    .set_unhandled_error(
                            (err) -> {
                                assertEquals("MyError", err);
                            });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(required, result);
        }

        // --------------------------------------------------------------------
        @Test
        void forEachVectorFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                // ex.printStackTrace(System.err);
                            });

            var required = List.of(0L, 1L, 2L, 0L, 1L, 2L, 0L, 1L, 2L);
            var result = new ArrayList<Long>();

            $as.forEach(
                    new int[] {3, 4, 5},
                    (asi, k, v) -> {
                        assertEquals(k + 3, v.longValue());
                        result.add(k);
                    });

            $as.forEach(
                    List.of(3, 4, 5),
                    (asi, k, v) -> {
                        assertEquals(k + 3, v.longValue());
                        result.add(k);
                    });

            $as.forEach(
                    List.of(3, 4, 5).stream(),
                    (asi, k, v) -> {
                        assertEquals(k + 3, v.longValue());
                        result.add(k);
                    });

            $as.add(
                    (asi) -> {
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(required, result);
        }

        // --------------------------------------------------------------------
        @Test
        void forEachMapFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                // ex.printStackTrace(System.err);
                            });

            var required = List.of(0, 1, 2);
            var result = new ArrayList<Integer>();

            var map = new HashMap<String, String>();
            map.put("Key0", "ValueForKey0");
            map.put("Key1", "ValueForKey1");
            map.put("Key2", "ValueForKey2");

            $as.forEach(
                    map,
                    (asi, k, v) -> {
                        assertEquals("ValueFor" + k, v);
                        result.add(Integer.parseInt(k.replace("Key", "")));
                    });

            $as.add(
                    (asi) -> {
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            Collections.sort(result);
            assertEquals(required, result);
        }
    }

    static class ParallelAPI {
        // --------------------------------------------------------------------
        @Test
        void parallelFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            var required = List.of(1, 11, 2, 21, 3, 31, 32, 4, 5, 5, 6, 7, 12, 22);
            var result = new ArrayList<Integer>();

            var p = $as.parallel();

            p.add(
                    (asi) -> {
                        result.add(1);

                        asi.add(
                                (asi2) -> {
                                    result.add(11);
                                });
                        asi.relinquish();
                        asi.add(
                                (asi2) -> {
                                    result.add(12);
                                });
                    });

            p.add(
                    (asi) -> {
                        result.add(2);

                        asi.add(
                                (asi2) -> {
                                    result.add(21);
                                });
                        asi.relinquish();
                        asi.add(
                                (asi2) -> {
                                    result.add(22);
                                });
                    });

            p.add(
                    (asi) -> {
                        result.add(3);

                        asi.add(
                                (asi2) -> {
                                    result.add(31);
                                });
                        asi.add(
                                (asi2) -> {
                                    result.add(32);
                                });
                    });

            p.loop(
                    (asi) -> {
                        result.add(4);
                        asi.breakLoopNoThrow();
                    });

            p.repeat(
                    2,
                    (asi, i) -> {
                        result.add(5);
                    });

            p.forEach(
                    List.of(1),
                    (asi, k, v) -> {
                        result.add(6);
                    });
            var m = new HashMap<String, String>();
            m.put("k", "v");
            p.forEach(
                    m,
                    (asi, k, v) -> {
                        result.add(7);
                    });

            $as.add(
                    (asi) -> {
                        done.complete(null);
                    });

            // empty test
            $as.parallel();

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(required, result);
        }

        // --------------------------------------------------------------------
        @Test
        void parallelErrorFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            var required = List.of(1, 11, 2, 21, 3, 31, 32);
            var result = new ArrayList<Integer>();

            var p =
                    $as.parallel(
                            (asi, err) -> {
                                assertEquals("Parallel", err);
                                assertEquals("ParallelInfo", asi.state().error_info());
                                asi.success();
                            });

            p.add(
                    (asi) -> {
                        result.add(1);

                        asi.add(
                                (asi2) -> {
                                    result.add(11);
                                });
                        asi.relinquish();
                        asi.add(
                                (asi2) -> {
                                    asi2.errorNoThrow("Parallel", "ParallelInfo");
                                });
                    });

            p.add(
                    (asi) -> {
                        result.add(2);

                        asi.add(
                                (asi2) -> {
                                    result.add(21);
                                });
                        asi.relinquish();
                        asi.add(
                                (asi2) -> {
                                    result.add(22);
                                });
                    });

            p.add(
                    (asi) -> {
                        result.add(3);

                        asi.add(
                                (asi2) -> {
                                    result.add(31);
                                });
                        asi.add(
                                (asi2) -> {
                                    result.add(32);
                                });
                    });

            $as.add(
                    (asi) -> {
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);

            assertEquals(required, result);
        }
    }

    static class PromiseAPI {
        // --------------------------------------------------------------------
        @Test
        void promiseResultFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        asi.add(
                                (asi2) -> {
                                    asi2.success("RESULT", "ignore");
                                });
                    });

            var result = $as.<String>promise().get(1, TimeUnit.SECONDS);
            assertEquals("RESULT", result);
        }

        // --------------------------------------------------------------------
        @Test
        void promiseVoidFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        asi.add(
                                (asi2) -> {
                                    asi2.success();
                                });
                    });

            var result = $as.<Void>promise().get(1, TimeUnit.SECONDS);
            assertEquals(null, result);
        }

        // --------------------------------------------------------------------
        @Test
        void promiseErrorFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        asi.add(
                                (asi2) -> {
                                    asi2.errorNoThrow("SomeError");
                                });
                    });

            try {
                $as.promise().get(1, TimeUnit.SECONDS);
                assertTrue(false);
            } catch (Throwable ex) {
                assertEquals(ex.getCause().getMessage(), "SomeError");
            }
        }

        // --------------------------------------------------------------------
        @Test
        void promiseInnerCancelFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        asi.tool().immediate(() -> $as.cancel());
                        asi.waitExternal();
                    });

            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> {
                        $as.promise().get(1, TimeUnit.SECONDS);
                    });
        }

        // --------------------------------------------------------------------
        @Test
        void promiseOuterCancelFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();

            var data =
                    new Object() {
                        boolean cancel_called;
                    };

            CompletableFuture<Void> wait = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.add(
                    (asi) -> {
                        asi.setCancel(
                                (asi2) -> {
                                    data.cancel_called = true;
                                });
                        wait.complete(null);
                    });

            var p = $as.promise();
            wait.get(1, TimeUnit.SECONDS);
            p.cancel(true);

            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> {
                        p.get(1, TimeUnit.SECONDS);
                    });

            assertTrue(data.cancel_called);
        }

        // --------------------------------------------------------------------
        @Test
        void awaitFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> wait = new CompletableFuture<>();
            CompletableFuture<String> wait2 = new CompletableFuture<>();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.await(wait);
            $as.add(
                    (asi) -> {
                        asi.await(wait2);
                        asi.<String>add(
                                (asi2, s) -> {
                                    assertEquals("str", s);
                                });
                    });
            $as.add(
                    (asi) -> {
                        done.complete(null);
                    });

            $as.execute();

            $as.tool().deferred(100, () -> wait.complete(null));
            $as.tool().deferred(200, () -> wait2.complete("str"));

            done.get(1, TimeUnit.SECONDS);
        }

        // --------------------------------------------------------------------
        @Test
        void awaitErrorFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> wait = new CompletableFuture<>();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.state()
                    .set_catch_trace(
                            (ex) -> {
                                ex.printStackTrace(System.err);
                            });

            $as.await(
                    wait,
                    (asi, err) -> {
                        assertEquals("SomeError", err);
                        asi.success();
                        done.complete(null);
                    });

            $as.execute();

            $as.tool()
                    .deferred(
                            100,
                            () -> wait.completeExceptionally(new RuntimeException("SomeError")));

            done.get(1, TimeUnit.SECONDS);
        }
    }

    static class MiscAPI {
        // --------------------------------------------------------------------
        @Test
        void stateVarsTest() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            {
                synchronized ($as.state()) {
                    $as.tool()
                            .immediate(
                                    () -> {
                                        // Temporary blocks
                                        $as.state().dynamic_items();
                                    });
                    Thread.sleep(100);
                    $as.state().set("myVar", "myValue");
                }
            }

            $as.state().set("myInt", 123);

            $as.add(
                    (asi) -> {
                        assertNull($as.state());
                        assertEquals("myValue", asi.state().<String>get("myVar"));
                        assertEquals(123, asi.state().<Integer>get("myInt"));

                        asi.add(
                                (asi2) -> {
                                    assertNull(asi.state());
                                    assertNotNull(asi2.state());
                                    asi2.error("MyError");
                                });
                    },
                    (asi, err) -> {
                        assertEquals("MyError", err);
                        asi.success();
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);
        }

        // --------------------------------------------------------------------
        @Test
        void copyFromTest() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            AsyncSteps model = new AsyncStepsRI();
            AsyncSteps empty = new AsyncStepsRI();
            var mockSteps = mock(AsyncSteps.class);
            CompletableFuture<Void> done = new CompletableFuture<>();

            model.state().set("myVar", "myValue");
            model.state().set("myInt", 123);

            model.add(
                    (asi) -> {
                        asi.state().set("myLong", 234L);
                    });

            $as.state().set("myInt", 567);
            $as.copyFrom(model);

            {
                CompletableFuture<Void> emptyDone = new CompletableFuture<>();
                empty.add(
                                (asi) -> {
                                    asi.success();
                                    emptyDone.complete(null);
                                })
                        .execute();

                emptyDone.get(1, TimeUnit.SECONDS);
            }

            $as.copyFrom(empty);

            assertThrows(
                    ExtError.class,
                    () -> {
                        $as.copyFrom(mockSteps);
                    });

            $as.add(
                    (asi) -> {
                        assertEquals("myValue", asi.state().<String>get("myVar"));
                        assertEquals(567, asi.state().<Integer>get("myInt"));
                        assertEquals(234L, asi.state().<Long>get("myLong"));
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);
        }

        // --------------------------------------------------------------------
        @Test
        void foreignException() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.add(
                    (asi) -> {
                        asi.add(
                                (asi2) -> {
                                    throw new RuntimeException("Foreign1");
                                },
                                (asi2, err) -> {
                                    assertEquals("Foreign1", err);
                                    throw new RuntimeException("Foreign2");
                                });
                    },
                    (asi, err) -> {
                        assertEquals("Foreign2", err);
                        asi.success();
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);
        }

        // --------------------------------------------------------------------
        @Test
        void successStep() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            $as.successStep("str", 10L);
            $as.<String, Long>add(
                    (asi, s, l) -> {
                        assertEquals("str", s);
                        assertEquals(10, l);
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);
        }

        // --------------------------------------------------------------------
        @Test
        void emptySteps() throws Throwable {
            try (var at = new AsyncToolRI(() -> {})) {
                (new AsyncStepsRI(at)).execute();

                while (at.iterate().haveWork()) {}
            }
        }

        // --------------------------------------------------------------------
        @Test
        void newInstance() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> traceDone = new CompletableFuture<>();
            CompletableFuture<Void> errorDone = new CompletableFuture<>();
            CompletableFuture<Void> cancelDone = new CompletableFuture<>();

            assertTrue($as.newInstance() instanceof AsyncStepsRI);
            assertTrue($as.parallel().newInstance() instanceof AsyncStepsRI);

            $as.add(
                    (asi) -> {
                        var nasi = asi.newInstance();
                        assertTrue(nasi instanceof AsyncStepsRI);

                        var orig_state = asi.state();

                        nasi.add(
                                (asi2) -> {
                                    assertEquals(
                                            asi2.state().get_catch_trace(),
                                            orig_state.get_catch_trace());
                                    assertEquals(
                                            asi2.state().get_unhandled_error(),
                                            orig_state.get_unhandled_error());
                                    assertEquals(
                                            asi2.state().get_cancel_handler(),
                                            orig_state.get_cancel_handler());
                                });

                        nasi.add(
                                (asi2) -> {
                                    asi2.error("MyError");
                                });
                        nasi.execute();
                    });

            $as.add(
                    (asi) -> {
                        var nasi = asi.newInstance();
                        assertTrue(nasi instanceof AsyncStepsRI);

                        nasi.add(
                                (asi2) -> {
                                    nasi.waitExternal();
                                });
                        nasi.execute();
                        nasi.tool().immediate(() -> nasi.cancel());
                    });

            $as.state()
                    .set_catch_trace(
                            (asi, ex) -> {
                                assertEquals("MyError", ex.getMessage());
                                traceDone.complete(null);
                            });
            $as.state()
                    .set_unhandled_error(
                            (asi, err) -> {
                                assertEquals("MyError", err);

                                try {
                                    errorDone.complete(null);
                                } catch (Throwable ex) {
                                    throw new RuntimeException(ex);
                                }
                            });

            $as.state()
                    .set_cancel_handler(
                            (asi) -> {
                                assertNotEquals($as, asi);

                                try {
                                    cancelDone.complete(null);
                                } catch (Throwable ex) {
                                    throw new RuntimeException(ex);
                                }
                            });

            $as.execute();

            traceDone.get(1, TimeUnit.SECONDS);
            errorDone.get(1, TimeUnit.SECONDS);
            cancelDone.get(1, TimeUnit.SECONDS);
        }

        // --------------------------------------------------------------------
        @Test
        void cancelFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();

            var data =
                    new Object() {
                        boolean cancel_called;
                        boolean step1_called;
                        boolean step2_called;
                        boolean step3_called;
                    };

            $as.add(
                    (asi) -> {
                        asi.setCancel(
                                (asi2) -> {
                                    data.cancel_called = true;
                                });
                        asi.add(
                                (asi2) -> {
                                    data.step1_called = true;
                                    asi2.tool()
                                            .immediate(
                                                    () -> {
                                                        asi2.success();
                                                    });
                                    asi2.waitExternal();
                                });
                        asi.add(
                                (asi2) -> {
                                    data.step2_called = true;
                                    asi2.tool()
                                            .immediate(
                                                    () -> {
                                                        $as.cancel();
                                                    });
                                    asi2.relinquish();
                                });
                        asi.add(
                                (asi2) -> {
                                    data.step3_called = true;
                                });
                    });

            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> {
                        $as.promise().get(1, TimeUnit.SECONDS);
                    });

            assertTrue(data.cancel_called);
            assertTrue(data.step1_called);
            assertTrue(data.step2_called);
            assertFalse(data.step3_called);
        }

        // --------------------------------------------------------------------
        @Test
        void immediateCancelFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();

            $as.add(
                    (asi) -> {
                        var new_asi = asi.newInstance();
                        new_asi.execute();
                        new_asi.cancel();
                        asi.relinquish();
                    });

            $as.promise().get(1, TimeUnit.SECONDS);
        }

        // --------------------------------------------------------------------
        @Test
        void cancelInExecFLow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();

            var data =
                    new Object() {
                        boolean cancel_called;
                        boolean step1_called;
                        boolean step2_called;
                        boolean step3_called;
                    };

            $as.add(
                    (asi) -> {
                        $as.cancel();
                        asi.relinquish();
                        asi.add(
                                (asi2) -> {
                                    assertFalse(true);
                                });
                    });

            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> {
                        $as.promise().get(1, TimeUnit.SECONDS);
                    });
        }

        // --------------------------------------------------------------------
        @Test
        void syncFlow() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            var syncObj =
                    new AsyncSteps.ISync() {
                        @Override
                        public void lock(AsyncSteps asi) {
                            assertEquals($as.syncRoot(), asi.syncRoot());
                        }

                        @Override
                        public void unlock(AsyncSteps asi) {}
                    };

            $as.successStep(1, "2", true);
            $as.<Integer, String, Boolean>sync(
                    syncObj,
                    (asi, i, s, b) -> {
                        assertEquals(1, i);
                        assertEquals("2", s);
                        assertTrue(b);

                        var p = asi.parallel();
                        p.sync(
                                new AsyncSteps.ISync() {
                                    @Override
                                    public void lock(AsyncSteps asi2) {
                                        assertNotEquals($as.syncRoot(), asi2.syncRoot());

                                        asi2.add(
                                                (asi3) -> {
                                                    assertEquals(asi2.syncRoot(), asi3.syncRoot());
                                                });
                                    }

                                    @Override
                                    public void unlock(AsyncSteps asi2) {}
                                },
                                (asi2) -> {});

                        asi.sync(
                                syncObj,
                                (asi2) -> {
                                    asi2.success(2, "3", false);
                                });
                    });

            $as.<Integer, String, Boolean>add(
                    (asi, i, s, b) -> {
                        assertEquals(2, i);
                        assertEquals("3", s);
                        assertFalse(b);
                        done.complete(null);
                    });

            $as.execute();

            done.get(1, TimeUnit.SECONDS);
        }

        // --------------------------------------------------------------------
        @Test
        void verboseUnhandled() throws Throwable {
            {
                AsyncSteps $as = new AsyncStepsRI();
                $as.add((asi) -> asi.errorNoThrow("VerboseUnhandledTest"));
                $as.execute();
            }
            {
                AsyncSteps $as = new AsyncStepsRI();
                $as.add(
                        (asi) -> {
                            throw new RuntimeException("VerboseUnhandledTest");
                        });
                $as.execute();
            }

            {
                CompletableFuture<Void> done = new CompletableFuture<>();

                (new AsyncStepsRI()).add((asi) -> done.complete(null)).execute();
                done.get(1, TimeUnit.SECONDS);
            }
        }

        // --------------------------------------------------------------------
        @Test
        void abi() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();

            assertThrows(
                    UnsatisfiedLinkError.class,
                    () -> {
                        $as.binary();
                    });
            assertThrows(
                    UnsatisfiedLinkError.class,
                    () -> {
                        $as.wrap(0);
                    });
        }

        // --------------------------------------------------------------------
        @Test
        void sanityChecks() throws Throwable {
            AsyncSteps $as = new AsyncStepsRI();
            CompletableFuture<Void> done = new CompletableFuture<>();

            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        $as.success();
                    });
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        $as.errorNoThrow("E");
                    });
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        $as.breakLoopNoThrow();
                    });
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        $as.continueLoopNoThrow();
                    });
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        $as.setTimeout(100);
                    });
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        $as.setCancel((asi2) -> {});
                    });
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        $as.waitExternal();
                    });

            $as.add(
                    (asi) -> {
                        asi.add(
                                (asi2) -> {
                                    assertThrows(
                                            IllegalStateException.class,
                                            () -> {
                                                asi.success();
                                            });
                                    assertThrows(
                                            IllegalStateException.class,
                                            () -> {
                                                asi.errorNoThrow("MyError");
                                            });
                                    assertThrows(
                                            IllegalStateException.class,
                                            () -> {
                                                asi2.errorNoThrow(null);
                                            });
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    asi.success();
                                });
                    });

            $as.add(
                    (asi) -> {
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    asi.execute();
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    asi.cancel();
                                });

                        asi.errorNoThrow("MyError");
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    asi.setTimeout(100);
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    asi.setCancel((asi2) -> {});
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    asi.waitExternal();
                                });
                    },
                    (asi, err) -> {
                        assertEquals("MyError", err);
                        asi.success();
                    });

            $as.add(
                    (asi) -> {
                        asi.repeat(
                                3,
                                (asi2, i) -> {
                                    if (i == 0) {
                                        asi2.continueLoop("");
                                    } else if (i == 1) {
                                        asi2.breakLoop("");
                                    }
                                });
                        asi.repeat(0, (asi2, i) -> {});
                        asi.forEach(new HashMap<String, String>(), (asi2, k, v) -> {});
                        asi.forEach(new Object[0], (asi2, k, v) -> {});
                    });

            $as.add(
                    (asi) -> {
                        var p = asi.parallel();
                        assertEquals(p, p.parallel());

                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.state();
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.syncRoot();
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.successStep(true);
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.await(null);
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.binary();
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.wrap(0);
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.success();
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.errorNoThrow("E");
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.breakLoopNoThrow();
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.continueLoopNoThrow();
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.setTimeout(100);
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.setCancel((asi2) -> {});
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.waitExternal();
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.execute();
                                });
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    p.cancel();
                                });
                    });

            $as.add(
                    (asi) -> {
                        done.complete(null);
                        asi.waitExternal();
                    });

            $as.execute();

            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        $as.add((asi) -> {});
                    });

            done.get(1, TimeUnit.SECONDS);

            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        $as.execute();
                    });

            $as.cancel();
        }
    }
}
