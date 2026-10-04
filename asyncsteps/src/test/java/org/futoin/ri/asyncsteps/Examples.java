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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.futoin.api.AsyncSteps;
import org.futoin.api.Error;

class Examples {
    boolean WITH_EXCEPTIONS = true;

    void do_some_non_blocking_stuff() {}

    void this_code_is_not_executed() {
        assert (false);
    }

    void this_code_IS_executed() {}

    Object schedule_external_callback(java.util.function.Consumer<Boolean> cb) {
        cb.accept(false);
        return null;
    }

    void external_cancel(Object handle) {}

    final MutexRI mutex = new MutexRI();

    AsyncSteps.ISync get_some_synchronization_object() {
        return mutex;
    }

    void example_business_logic(AsyncSteps asi) {
        // ---------------------------------------------------------------------
        // 1. regular step example
        //
        // Prototypes:
        // - asi.add(func(asi));
        // - asi.add(func(asi), on_error(asi, code));
        // - asi.add(func(asi, [A [,B [,C [,D]]]]));
        // - asi.add(func(asi, [A [,B [,C [,D]]]]), on_error(asi, code));
        //
        asi.add((asi2) -> do_some_non_blocking_stuff());

        // ---------------------------------------------------------------------
        // 2. try {} catch {} block example
        asi.add(
                (asi2) -> {
                    // regular step example
                    do_some_non_blocking_stuff();

                    if (WITH_EXCEPTIONS) {
                        asi2.error("MyError"); // throw error

                        this_code_is_not_executed();
                    } else {
                        asi2.errorNoThrow("MyError"); // not exception
                        return; // ensure manual return without exceptions
                    }
                },
                (asi2, error_code) -> {
                    if (error_code.equals("MyError")) {
                        // Override error unwind with success
                        asi2.success();
                    } else {
                        asi2.error("OverrideErrorCode");
                    }
                });

        // ---------------------------------------------------------------------
        // 3. Inner steps
        asi.add(
                (asi2) -> {
                    // regular step example
                    do_some_non_blocking_stuff();

                    asi2.add(
                            (asi3) -> {
                                asi3.error("MyError"); // throw error
                            });

                    // NOTE: inner steps are executed AFTER outer step body
                    this_code_IS_executed();
                },
                (asi2, error_code) -> {
                    if (error_code.equals("MyError")) {
                        asi2.success();
                    }
                });

        // ---------------------------------------------------------------------
        // 4. Passing arbitrary parameters
        asi.add(
                (AsyncSteps asi2) -> {
                    asi2.success(123, true, "SomeString", List.of(1, 2, 3));
                });
        asi.<Integer, Boolean, String, List<Integer>>add(
                (asi2, a, b, c, d) -> {
                    // Generic type inference example.
                    // NOTE: Maximum of 4 arguments is supported based on best practices.
                    assert (a == 123);
                    assert (b);
                    assert (c.equals("SomeString"));
                    assert (d.get(0) == 1);
                    asi2.success(a, b);
                });
        asi.add(
                (AsyncSteps asi2, Integer a, Boolean b) -> {
                    // Explicit parameter types example.
                    assert (a == 123);
                    assert (b);
                });

        // ---------------------------------------------------------------------
        // 5. state() - Thread Local Storage emulation
        //
        // Some predefined properties are set directly on State object for
        // performance reasons.
        //
        // Business logic can use custom dynamic items as associative key-value map.
        // Key is a String, value is of Object type with handy type casing getters.
        {
            // get reference to state object
            var state = asi.state();

            // Get-or-set-default variable
            var some_var = state.set_default("SomeVar", 123);
            // Same, but a shortcut
            var some_var2 = asi.state("SomeVar", 123);

            // Set
            state.set("SomeVector", List.of(1, 2, 3));
        }

        asi.add(
                (AsyncSteps asi2) -> {
                    // Get
                    var v = asi2.state().<List<Integer>>get("SomeVector");
                    // Get, but a shortcut
                    var i = asi2.<Integer>state("SomeVar");

                    try {
                        asi2.state().<Integer>get("SomeVector");
                    } catch (ClassCastException ex) {
                        // ...
                    }
                });

        // ---------------------------------------------------------------------
        // 6. Advanced state handling
        var sample_asi = asi.newInstance();
        sample_asi
                .state()
                .set_catch_trace(
                        (AsyncSteps asi2, Throwable t) -> {
                            // Mostly a helper for debugging purposes
                        });
        sample_asi
                .state()
                .set_unhandled_error(
                        (AsyncSteps asi2, String err) -> {
                            // Handle unexpected exception that cancels whole execution
                        });
        sample_asi
                .state()
                .set_cancel_handler(
                        (AsyncSteps asi2) -> {
                            // Handle situations of AsyncSteps execution cancel
                        });

        asi.add(
                (asi2) -> {
                    // NOTE: error codes are associative, but not somes integers
                    // to be more network-friendly.
                    asi2.error("MyError", "Some arbitrary description of the error");
                },
                (asi2, error_code) -> {
                    // ErrorCode is wrapper around const char*
                    assert (error_code.equals("MyError"));
                    // Error info is stored in state
                    assert (asi2.state()
                            .error_info()
                            .equals("Some arbitrary description of the error"));
                    // Last exception thrown is also available in state
                    Throwable e = asi2.state().last_exception();

                    asi2.success(e);
                });

        // ---------------------------------------------------------------------
        /// 7. Synchronization
        //
        // Unlike hardware race conditions, AsyncSteps synchronization serves
        // logical purposes to limit concurrency of execution or rate of calls or
        // both.
        //
        // FTN12 concept defines Mutex, Throttle and Limiter primitives which
        // implement a single ISync interface.

        var syncObj = get_some_synchronization_object();

        // The same interface as asi.add(), but with extra synchronization object
        // parameter.
        asi.sync(
                syncObj,
                (asi2) -> {
                    // a critical section
                },
                (asi2, error_code) -> {
                    // an optional error handler for the critical section
                });

        // ---------------------------------------------------------------------
        // 8. Loops
        asi.loop(
                (asi2) -> {
                    // infinite loop
                    if (WITH_EXCEPTIONS) {
                        asi2.breakLoop();
                    } else {
                        asi2.breakLoopNoThrow();
                    }
                });
        asi.repeat(
                10,
                (asi2, i) -> {
                    // range loop from i=0 till i=9 (inclusive)
                });
        asi.forEach(
                List.of(1, 2, 3),
                (asi2, index, value) -> {
                    // Iteration of arrays and sequences
                });
        asi.forEach(
                new HashMap<String, String>(),
                (asi2, key, value) -> {
                    // Iteration of map-like objects
                });

        // ---------------------------------------------------------------------
        // 9. Timeout support
        asi.add(
                (asi2) -> {
                    // Raises Timeout error after specified period
                    asi2.setTimeout(Duration.ofMillis(10));

                    asi2.loop(
                            (asi3) -> {
                                // infinite loop
                                asi3.relinquish();
                            });
                },
                (asi2, error_code) -> {
                    if (error_code.equals(Error.Timeout)) {
                        asi2.success();
                    }
                });

        // ---------------------------------------------------------------------
        // 10. External event integration
        asi.add(
                (asi2) -> {
                    var handle =
                            schedule_external_callback(
                                    (isError) -> {
                                        if (isError) {
                                            asi2.errorNoThrow("ExternalError");
                                        } else {
                                            asi2.success();
                                        }
                                    });

                    // Handle an edge case when callbacks fires immediately
                    // and invalidates the current step.
                    if (asi2.state() != null) {
                        asi2.setCancel((asi3) -> external_cancel(handle));
                    }
                });
        asi.add(
                (asi2) -> {
                    var handle =
                            schedule_external_callback(
                                    (isError) -> {
                                        if (asi2.state() == null) {
                                            // AsyncSteps object is invalidated
                                            // due to external cancel.
                                        } else if (isError) {
                                            asi2.errorNoThrow("ExternalError");
                                        } else {
                                            asi2.success();
                                        }
                                    });

                    // Handle an edge case when callbacks fires immediately
                    // and invalidates the current step.
                    if (asi2.state() != null) {
                        // alias for setCancel() with noop handler
                        asi2.waitExternal();
                    }
                });

        // ---------------------------------------------------------------------
        // 11. Standard Promise/Await integration
        asi.add(
                (asi2) -> {
                    // The proper way to create new AsyncSteps instances
                    // without hard dependency on implementation.
                    var new_steps = asi2.newInstance();
                    new_steps.add(
                            (new_asi) -> {
                                new_asi.success(123);
                            });

                    // Proper way to wait for standard java.util.concurrent.Future
                    asi2.await(new_steps.<Integer>promise());
                    asi2.<Integer>add(
                            (asi3, i) -> {
                                assert (i == 123);
                            });
                });

        // ---------------------------------------------------------------------
        // 12. Parallel execution
        //
        // It's designed for concurrent execution of sub-flows
        // with shared state() on the same platform thread.
        //
        // Unhandled error in sub-flows lead to abort of all non-executed
        // parallel steps.
        asi.state().set("order", new ArrayList<Integer>());

        var p =
                asi.parallel(
                        (asi2, error_code) -> {
                            // Overall error handler
                            asi2.success();
                        });
        p.add(
                (asi2) -> {
                    // regular flow
                    asi2.state().<ArrayList<Integer>>get("order").add(1);

                    // Break execution burst - a CPU cache optimization, for demo
                    asi2.relinquish();
                    asi2.add(
                            (asi3) -> {
                                asi3.state().<ArrayList<Integer>>get("order").add(4);
                            });
                });
        p.add(
                (asi2) -> {
                    asi2.state().<ArrayList<Integer>>get("order").add(2);

                    asi2.relinquish(); // Break execution burst
                    asi2.add(
                            (asi3) -> {
                                asi3.state().<ArrayList<Integer>>get("order").add(5);
                                asi3.error("SomeError");
                            });
                });
        p.add(
                (asi2) -> {
                    asi2.state().<ArrayList<Integer>>get("order").add(3);

                    asi2.relinquish(); // Break execution burst
                    asi2.add(
                            (asi3) -> {
                                asi3.state().<ArrayList<Integer>>get("order").add(6);
                            });
                });

        asi.add(
                (asi2) -> {
                    asi2.state().<ArrayList<Integer>>get("order"); // 1, 2, 3, 4, 5
                });

        // ---------------------------------------------------------------------
        // 13. Control of AsyncSteps flow
        {
            // A new instance inherits the special handlers and use the same
            // event loop.
            var new_steps = asi.newInstance();

            assert (new_steps.tool() == asi.tool());

            // Add steps
            new_steps.add((new_asi) -> {});
            new_steps.loop((new_asi) -> new_asi.relinquish());

            // Schedule execution of AsyncSteps flow
            new_steps.execute();

            // Cancel execution of AsyncSteps flow
            new_steps.cancel();
        }
    }

    @org.junit.jupiter.api.Test
    void testExample() throws Throwable {
        var $as = new AsyncStepsRI();
        $as.add(this::example_business_logic);
        $as.promise().get();
    }
}
