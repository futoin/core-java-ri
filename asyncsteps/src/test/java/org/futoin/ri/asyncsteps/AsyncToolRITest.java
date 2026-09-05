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
import org.junit.jupiter.api.Test;

class AsyncToolRITest {
    @Test
    void createInnerLoop() {
        var at = new AsyncToolRI();
        at.shutdown();
        at.shutdown();
    }

    @Test
    void createExternalLoop() {
        var at = new AsyncToolRI(() -> {});
        at.shutdown();
        at.shutdown();
    }

    @Test
    void createCloseable() {
        try (var at = new AsyncToolRI()) {
            at.immediate(() -> {}).cancel();
            at.deferred(100, () -> {}).cancel();
        }
    }

    @Test
    void verboseException() throws Throwable {
        var f = new CompletableFuture<Void>();
        var at = AsyncToolRI.shared();

        at.immediate(
                () -> {
                    throw new RuntimeException("TestVerboseError");
                });
        at.immediate(
                () -> {
                    f.complete(null);
                });

        f.get(1, TimeUnit.SECONDS);
    }

    @Test
    void immediate() throws Exception {
        var f = new CompletableFuture<Integer>();
        var h = AsyncToolRI.shared().immediate(() -> f.complete(1));

        assertEquals(1, f.get(1, TimeUnit.SECONDS));

        assertFalse(h.is_valid());
    }

    @Test
    void deferred() throws Exception {
        var f = new CompletableFuture<Integer>();
        var h = AsyncToolRI.shared().deferred(1, () -> f.complete(2));

        assertEquals(2, f.get(1, TimeUnit.SECONDS));

        assertFalse(h.is_valid());
    }

    @Test
    void cancel_iterate() throws Exception {
        try (var at = new AsyncToolRI(() -> {})) {
            var imm =
                    at.immediate(
                            () -> {
                                throw new RuntimeException("Must not be executed imm");
                            });
            at.immediate(() -> {});
            var defer =
                    at.deferred(
                            300,
                            () -> {
                                throw new RuntimeException("Must not be executed defer");
                            });
            var defer2 =
                    at.deferred(
                            150,
                            () -> {
                                throw new RuntimeException("Must not be executed defer2");
                            });
            at.deferred(500, () -> {});
            at.deferred(200, () -> {});

            imm.cancel(); // expected to be left and discarded

            defer.cancel(); // expected to be removed immediately
            AsyncToolRI.shared().immediate(defer2::cancel);

            var res = at.iterate();
            assertTrue(res.haveWork());
            assertTrue(res.delayNs() > 10_000_000);
            assertTrue(res.delayNs() < 200_000_000);

            Thread.sleep(200);

            res = at.iterate();
            assertTrue(res.haveWork());
            assertTrue(res.delayNs() > 150_000_000);
            assertTrue(res.delayNs() < 300_000_000);

            Thread.sleep(300);
            res = at.iterate();
            assertFalse(res.haveWork());
            assertEquals(0, res.delayNs());

            at.immediate(
                    () -> {
                        at.immediate(() -> {});
                    });

            res = at.iterate();
            assertTrue(res.haveWork());
            assertEquals(0, res.delayNs());

            res = at.iterate();
            assertFalse(res.haveWork());
            assertEquals(0, res.delayNs());
        }
    }

    @Test
    void foreignIterate() {
        assertThrows(
                RuntimeException.class,
                () -> {
                    ((AsyncToolRI) AsyncToolRI.shared()).iterate();
                });
    }

    @Test
    void foreignShutdown() throws Exception {
        var f = new CompletableFuture<AsyncToolRI>();
        AsyncToolRI.shared().immediate(() -> f.complete(new AsyncToolRI(() -> {})));
        var at = f.get();

        assertThrows(
                RuntimeException.class,
                () -> {
                    at.shutdown();
                });
    }

    @Test
    void foreignHandle() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    try (var at1 = new AsyncToolRI(() -> {});
                            var at2 = new AsyncToolRI(() -> {})) {
                        at2.cancel(at1.immediate(() -> {}));
                    }
                });
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    try (var at1 = new AsyncToolRI(() -> {});
                            var at2 = new AsyncToolRI(() -> {})) {
                        at2.cancel(at1.deferred(100, () -> {}));
                    }
                });
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    try (var at1 = new AsyncToolRI(() -> {});
                            var at2 = new AsyncToolRI(() -> {})) {
                        at2.is_valid(at1.immediate(() -> {}));
                    }
                });
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    try (var at1 = new AsyncToolRI(() -> {});
                            var at2 = new AsyncToolRI(() -> {})) {
                        at2.is_valid(at1.deferred(100, () -> {}));
                    }
                });
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    AsyncToolRI.shared()
                            .cancel(
                                    new AsyncToolRI.Handle() {
                                        @Override
                                        public void cancel() {}

                                        @Override
                                        public boolean is_valid() {
                                            return false;
                                        }
                                    });
                });
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    AsyncToolRI.shared()
                            .is_valid(
                                    new AsyncToolRI.Handle() {
                                        @Override
                                        public void cancel() {}

                                        @Override
                                        public boolean is_valid() {
                                            return false;
                                        }
                                    });
                });
    }

    static class Handle {
        @Test
        void is_valid() {
            try (var at = new AsyncToolRI(() -> {})) {
                var imm = at.immediate(() -> {});
                var def = at.deferred(100, () -> {});
                assertTrue(imm.is_valid());
                assertTrue(def.is_valid());

                imm.cancel();
                def.cancel();

                assertFalse(imm.is_valid());
                assertFalse(def.is_valid());
            }
        }

        @Test
        void object_overrides() {
            try (var at = new AsyncToolRI(() -> {})) {
                AsyncToolRI.Callback cb = () -> {};
                var a = at.deferred(100, cb);
                var b = at.deferred(100, cb);
                assertFalse(a.equals(b));
                assertTrue(a.equals(a));
                assertNotEquals(a.hashCode(), b.hashCode());
            }
        }

        @Test
        void double_cancel() {
            try (var at = new AsyncToolRI(() -> {})) {
                var imm = at.immediate(() -> {});
                assertTrue(imm.is_valid());

                imm.cancel();

                assertFalse(imm.is_valid());

                imm.cancel();
            }
        }
    }
}
