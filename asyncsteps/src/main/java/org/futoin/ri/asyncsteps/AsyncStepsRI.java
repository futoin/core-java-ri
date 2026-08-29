/*
 * Copyright 2026 FutoIn Project (https://futoin.org)
 * Copyright 2026 Andrey Galkin <andrey@futoin.org>
 *
 * <p>Licensed under the FutoIn Public License 1.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.futoin.ri.asyncsteps;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Future;
import org.futoin.api.AsyncSteps;
import org.futoin.api.AsyncTool;
import org.futoin.api.Error;
import org.futoin.api.ExtError;

/**
 * FutoIn Core AsyncSteps Reference Implementation
 */
public final class AsyncStepsRI implements AsyncSteps {
    /**
     * Make stupid doclint happy
     * @hidden
     */
    private static final class StateImpl implements State {
        /**
         * Make stupid doclint happy
         * @hidden
         */
        StateImpl() {}

        @Override
        public Map<String, Object> dynamic_items() {
            if (state_vars_ == null) {
                synchronized (this) {
                    if (state_vars_ == null) {
                        state_vars_ = new HashMap<>();
                    }
                }
            }
            return state_vars_;
        }

        @Override
        public String error_info() {
            return error_info_;
        }

        @Override
        public Throwable last_exception() {
            return last_exception_;
        }

        @Override
        public void set_catch_trace(CatchTrace cb) {
            catch_trace_ = cb;
        }

        @Override
        public void set_unhandled_error(UnhandledError cb) {
            unhandled_error_ = cb;
        }

        /** Last error info, if any */
        String error_info_;

        /** Last exception, if any */
        Throwable last_exception_;

        /** Tracer for any exception caught during step execution */
        CatchTrace catch_trace_;

        /** Handler for unhandled errors when running out of the steps. */
        UnhandledError unhandled_error_;

        /** State variables */
        private volatile Map<String, Object> state_vars_;
    }

    /**
     * Make stupid doclint happy
     * @hidden
     */
    private abstract class BaseSteps implements AsyncSteps {
        /**
         * Make stupid doclint happy
         * @hidden
         */
        BaseSteps() {}

        @Override
        public AsyncSteps copyFrom(AsyncSteps other) {
            if (other instanceof AsyncStepsRI o) {
                var other_queue = o.root_.queue_;
                if (other_queue != null) {
                    other_queue.forEach((p) -> this.addRaw(p.exec_cb_, p.error_cb_));
                }

                var other_state_vars = o.state_.state_vars_;

                if (other_state_vars != null) {
                    var this_state_vars = state_.dynamic_items();

                    other_state_vars.forEach(this_state_vars::putIfAbsent);
                }
            } else {
                throw new ExtError(Error.NotImplemented, "copyFrom() foreign model");
            }

            return this;
        }

        @Override
        public AsyncTool tool() {
            return AsyncStepsRI.this.tool();
        }
    }

    /**
     * Make stupid doclint happy
     * @hidden
     */
    private final class Protector extends BaseSteps {
        /** ignore */
        ExecuteCallback exec_cb_;

        /** ignore */
        ErrorCallback error_cb_;

        /** ignore */
        ArrayDeque<Protector> queue_;

        /**
         * ignore
         * @param exec_cb ignore
         * @param error_cb ignore
         */
        Protector(ExecuteCallback exec_cb, ErrorCallback error_cb) {
            exec_cb_ = exec_cb;
            error_cb_ = error_cb;
        }

        @Override
        public AsyncSteps addRaw(ExecuteCallback exec_cb, ErrorCallback error_cb) {
            if (queue_ == null) {
                queue_ = new ArrayDeque<>();
            }
            queue_.add(new Protector(exec_cb, error_cb));
            return this;
        }

        @Override
        public AsyncSteps parallel(ErrorCallback error_cb) {
            return new ParallelStep(this, error_cb);
        }

        @Override
        public State state() {
            return state_;
        }

        @Override
        public AsyncSteps syncRaw(ISync obj, ExecuteCallback exec_cb, ErrorCallback error_cb) {
            obj.sync(this, exec_cb, error_cb);
            return this;
        }

        @Override
        public AsyncSteps successStep(Object... args) {
            return addRaw((asi, ignoreArgs) -> asi.success(args));
        }

        @Override
        public <T> AsyncSteps await(Future<T> obj) {
            // TODO
            throw new ExtError(Error.NotImplemented, "AsyncSteps.await()");
        }

        @Override
        public AsyncSteps newInstance() {
            return new AsyncStepsRI(async_tool_);
        }

        @Override
        public long binary() {
            // TODO
            throw new ExtError(Error.NotImplemented, "AsyncSteps.binary()");
        }

        @Override
        public AsyncSteps wrap(long ptr) {
            // TODO
            throw new ExtError(Error.NotImplemented, "AsyncSteps.wrap()");
        }

        @Override
        public void success(Object... args) {}

        @Override
        public void errorNoThrow(String error_code, String error_info) {}

        @Override
        public void setTimeout(long timeout_ms) {}

        @Override
        public void setCancel(CancelCallback on_cancel) {}

        @Override
        public void waitExternal() {}

        @Override
        public void execute() {
            on_invalid_call("execute() call on protector");
        }

        @Override
        public void cancel() {
            on_invalid_call("cancel() call on protector");
        }

        @Override
        public <T> Future<T> promise() {
            on_invalid_call("promise() call on protector");
            return null;
        }

        @Override
        public void loop(LoopCallback func, String label) {
            var storage =
                    new Object() {
                        void iteration(AsyncSteps loop_asi) {
                            loop_asi.addRaw(
                                    (asi, args) -> {
                                        func.call(asi);
                                        iteration(asi);
                                    },
                                    (asi, error) -> {
                                        if (error.equals(Error.LoopBreak)) {
                                            var error_info = state_.error_info_;

                                            if (error_info == null || error_info.equals(label)) {
                                                asi.success();
                                            }
                                        } else if (error.equals(Error.LoopCont)) {
                                            var error_info = state_.error_info_;

                                            if (error_info == null || error_info.equals(label)) {
                                                asi.success();
                                                iteration(asi);
                                            }
                                        }
                                    });
                        }
                    };
            storage.iteration(this);
        }

        @Override
        public <K, V> void forEach(Map<K, V> map, ForEachMapCallback<K, V> func, String label) {
            var iter = map.entrySet().iterator();
            loop(
                    (asi) -> {
                        if (iter.hasNext()) {
                            var entry = iter.next();
                            func.call(asi, entry.getKey(), entry.getValue());
                        } else {
                            asi.breakLoop(label);
                        }
                    },
                    label);
        }

        @Override
        public <V> void forEach(Iterator<V> iter, ForEachIterCallback<V> func, String label) {
            var loopState =
                    new Object() {
                        public long counter = 0;
                    };

            loop(
                    (asi) -> {
                        if (iter.hasNext()) {
                            func.call(asi, loopState.counter++, iter.next());
                        } else {
                            asi.breakLoop(label);
                        }
                    },
                    label);
        }

        @Override
        public void repeat(long count, RepeatCallback func, String label) {
            var loopState =
                    new Object() {
                        public long counter = 0;
                    };

            loop(
                    (asi) -> {
                        if (loopState.counter < count) {
                            func.call(asi, loopState.counter++);
                        } else {
                            asi.breakLoop(label);
                        }
                    },
                    label);
        }
    }

    /**
     * Parallel step implementation.
     * @hidden
     */
    private final class ParallelStep extends BaseSteps {
        /** ignore */
        private ArrayList<AsyncStepsRI> steps_ = new ArrayList<>();

        /** ignore */
        private ErrorCallback on_parallel_error_ = null;

        /** ignore */
        private int on_complete_count_ = 0;

        /**
         * ignore
         * @param step ignore
         * @param error_cb ignore
         */
        ParallelStep(Protector step, ErrorCallback error_cb) {
            step.addRaw(
                    (asi, args) -> {
                        on_parallel_error_ =
                                (inner_asi, error) -> {
                                    for (var s : steps_) {
                                        s.cancel();
                                    }
                                    try {
                                        asi.error(error);
                                    } catch (Error e) {
                                        // pass
                                    }
                                };

                        for (var s : steps_) {
                            s.add(
                                    (inner_asi) -> {
                                        ++on_complete_count_;
                                        if (on_complete_count_ == steps_.size()) {
                                            asi.success();
                                        }
                                    });
                            s.execute();
                        }
                    },
                    error_cb);
        }

        /**
         * ignore
         * @return ignore
         */
        private AsyncStepsRI addCommon() {
            var step = new AsyncStepsRI(state_, async_tool_, true);
            steps_.add(step);
            return step;
        }

        @Override
        public AsyncSteps addRaw(ExecuteCallback exec_cb, ErrorCallback error_cb) {
            addCommon()
                    .addRaw(
                            (asi, args) -> {
                                asi.addRaw(exec_cb, error_cb);
                            },
                            (asi, error) -> {
                                on_parallel_error_.call(asi, error);
                            });
            return this;
        }

        @Override
        public AsyncSteps parallel(ErrorCallback error_cb) {
            return this;
        }

        @Override
        public State state() {
            on_invalid_call("state() call on parallel()");
            return null;
        }

        @Override
        public AsyncSteps syncRaw(ISync obj, ExecuteCallback exec_cb, ErrorCallback error_cb) {
            addCommon().syncRaw(obj, exec_cb, error_cb);
            return this;
        }

        @Override
        public AsyncSteps successStep(Object... args) {
            on_invalid_call("successStep() call on parallel()");
            return null;
        }

        @Override
        public <T> AsyncSteps await(Future<T> obj) {
            on_invalid_call("await() call on parallel()");
            return null;
        }

        @Override
        public AsyncSteps newInstance() {
            return AsyncStepsRI.this.newInstance();
        }

        @Override
        public long binary() {
            on_invalid_call("binary() call on parallel()");
            return 0;
        }

        @Override
        public AsyncSteps wrap(long ptr) {
            on_invalid_call("wrap() call on parallel()");
            return null;
        }

        @Override
        public void success(Object... args) {
            on_invalid_call("success() call on parallel()");
        }

        @Override
        public void errorNoThrow(String error_code, String error_info) {
            on_invalid_call("errorNoThrow() call on parallel()");
        }

        @Override
        public void setTimeout(long timeout_ms) {
            on_invalid_call("setTimeout() call on parallel()");
        }

        @Override
        public void setCancel(CancelCallback on_cancel) {
            on_invalid_call("setCancel() call on parallel()");
        }

        @Override
        public void waitExternal() {
            on_invalid_call("waitExternal() call on parallel()");
        }

        @Override
        public void execute() {
            on_invalid_call("execute() call on parallel()");
        }

        @Override
        public void cancel() {
            on_invalid_call("cancel() call on parallel()");
        }

        @Override
        public <T> Future<T> promise() {
            on_invalid_call("promise() call on parallel()");
            return null;
        }

        @Override
        public void loop(LoopCallback func, String label) {
            addCommon().loop(func, label);
        }

        @Override
        public <K, V> void forEach(Map<K, V> map, ForEachMapCallback<K, V> func, String label) {
            addCommon().<K, V>forEach(map, func, label);
        }

        @Override
        public <V> void forEach(Iterator<V> iter, ForEachIterCallback<V> func, String label) {
            addCommon().<V>forEach(iter, func, label);
        }

        @Override
        public void repeat(long count, RepeatCallback func, String label) {
            addCommon().repeat(count, func, label);
        }

        @Override
        public void breakLoopNoThrow(String label) {
            on_invalid_call("breakLoopNoThrow() call on parallel()");
        }

        @Override
        public void continueLoopNoThrow(String label) {
            on_invalid_call("continueLoopNoThrow() call on parallel()");
        }
    }

    /**
     * State
     * @hidden
     */
    private final StateImpl state_;

    /**
     * Associated event loop
     * @hidden
     */
    private final AsyncTool async_tool_;

    /**
     * Steps stack, starting from the root one.
     * @hidden
     */
    private final ArrayDeque<Protector> stack_;

    /**
     * Optimized accessor to the root step.
     * @hidden
     */
    private final Protector root_;

    /**
     * Internal c-tor
     * @param state Shared state
     * @param async_tool Instance of event loop to use.
     * @param is_sub_step Flag, if the current step is a [parallel] substep of another root.
     */
    private AsyncStepsRI(StateImpl state, AsyncTool async_tool, boolean is_sub_step) {
        state_ = state;
        async_tool_ = async_tool;

        ErrorCallback overall_error_handler =
                is_sub_step
                        ? null
                        : (asi, error) -> {
                            var unhandled_error = state.unhandled_error_;

                            if (unhandled_error != null) {
                                unhandled_error.call(error);
                                asi.success();
                            }
                        };
        var root = new Protector((asi, args) -> {}, overall_error_handler);
        var stack = new ArrayDeque<Protector>();
        stack.push(root);

        stack_ = stack;
        root_ = root;
    }

    /**
     * C-tor with custom AsyncTool
     * @param async_tool Instance of event loop to use.
     */
    public AsyncStepsRI(AsyncTool async_tool) {
        this(new StateImpl(), async_tool, false);
    }

    /**
     * Default c-tor with shared AsyncToolRI
     */
    public AsyncStepsRI() {
        this(AsyncToolRI.shared());
    }

    /**
     * For user error detection.
     * @param reason Detailed error info.
     * @hidden
     */
    private static void on_invalid_call(String reason) {
        throw new ExtError(Error.InternalError, reason);
    }

    @Override
    public AsyncSteps addRaw(ExecuteCallback exec_cb, ErrorCallback error_cb) {
        root_.addRaw(exec_cb, error_cb);
        return this;
    }

    @Override
    public AsyncSteps parallel(ErrorCallback error_cb) {
        return root_.parallel(error_cb);
    }

    @Override
    public State state() {
        return state_;
    }

    @Override
    public AsyncSteps copyFrom(AsyncSteps other) {
        root_.copyFrom(other);
        return this;
    }

    @Override
    public AsyncSteps syncRaw(ISync obj, ExecuteCallback exec_cb, ErrorCallback error_cb) {
        root_.syncRaw(obj, exec_cb, error_cb);
        return this;
    }

    @Override
    public AsyncSteps successStep(Object... args) {
        root_.successStep(args);
        return this;
    }

    @Override
    public <T> AsyncSteps await(Future<T> obj) {
        return root_.await(obj);
    }

    @Override
    public AsyncSteps newInstance() {
        return new AsyncStepsRI(async_tool_);
    }

    @Override
    public long binary() {
        return root_.binary();
    }

    @Override
    public AsyncSteps wrap(long ptr) {
        return root_.wrap(ptr);
    }

    @Override
    public AsyncTool tool() {
        return async_tool_;
    }

    @Override
    public void success(Object... args) {
        on_invalid_call("success() on root");
    }

    @Override
    public void errorNoThrow(String error_code, String error_info) {
        on_invalid_call("errorNoThrow() on root");
    }

    @Override
    public void setTimeout(long timeout_ms) {
        on_invalid_call("setTimeout() on root");
    }

    @Override
    public void setCancel(CancelCallback on_cancel) {
        on_invalid_call("setCancel() on root");
    }

    @Override
    public void waitExternal() {
        on_invalid_call("waitExternal() on root");
    }

    @Override
    public void execute() {
        // TODO
    }

    @Override
    public void cancel() {
        // TODO
    }

    @Override
    public <T> Future<T> promise() {
        // TODO
        return null;
    }

    @Override
    public void loop(LoopCallback func, String label) {
        root_.loop(func, label);
    }

    @Override
    public <K, V> void forEach(Map<K, V> map, ForEachMapCallback<K, V> func, String label) {
        root_.<K, V>forEach(map, func, label);
    }

    @Override
    public <V> void forEach(Iterator<V> iter, ForEachIterCallback<V> func, String label) {
        root_.<V>forEach(iter, func, label);
    }

    @Override
    public void repeat(long count, RepeatCallback func, String label) {
        root_.repeat(count, func, label);
    }

    @Override
    public void breakLoopNoThrow(String label) {
        on_invalid_call("breakLoopNoThrow() on root");
    }

    @Override
    public void continueLoopNoThrow(String label) {
        on_invalid_call("continueLoopNoThrow() on root");
    }
}
