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
     * AsyncSteps exec burst
     */
    static final int BURST_SIZE = 100;

    /**
     * Native entry-point for wrapping ABI into Java API.
     * A native library must be bundled indepdently.
     *
     * @param ptr Native ABI pointer
     * @return Java API
     */
    public static native AsyncSteps jni_wrap(long ptr);

    /**
     * Native entry-point for wrapping Java API into ABI.
     * A native library must be bundled indepdently.
     *
     * @param asi Java API
     * @return Native ABI pointer
     */
    public static native long jni_binary(AsyncSteps asi);

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
            var ei = error_info_;
            if (ei == null) {
                return "";
            }
            return ei;
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
        CatchTrace catch_trace_ =
                (ex) -> {
                    last_exception_ = ex;
                };

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
                o.stack_.forEach((p) -> this.addRaw(p.exec_cb_, p.error_cb_));

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
            return async_tool_;
        }
    }

    /**
     * Make stupid doclint happy
     * @hidden
     */
    private final class Protector extends BaseSteps {
        /** ignore */
        Protector parent_;

        /** ignore */
        ExecuteCallback exec_cb_;

        /** ignore */
        ErrorCallback error_cb_;

        /** ignore */
        AsyncTool.Handle limit_handle_;

        /** ignore */
        CancelCallback on_cancel_;

        /** ignore */
        int sub_queue_start_ = 0;

        /** ignore */
        int sub_queue_front_ = 0;

        /**
         * ignore
         * @param parent ignore
         * @param exec_cb ignore
         * @param error_cb ignore
         */
        Protector(Protector parent, ExecuteCallback exec_cb, ErrorCallback error_cb) {
            parent_ = parent;
            exec_cb_ = exec_cb;
            error_cb_ = error_cb;
        }

        /** ignore */
        void cleanup() {
            // Help GC
            parent_ = null;
            exec_cb_ = null;
            error_cb_ = null;

            cleanupExternalWait();
        }

        /** ignore */
        void cleanupExternalWait() {
            on_cancel_ = null;

            var lh = limit_handle_;
            if (lh != null) {
                lh.cancel();
                limit_handle_ = null;
            }
        }

        @Override
        public AsyncSteps addRaw(ExecuteCallback exec_cb, ErrorCallback error_cb) {
            stack_.add(new Protector(this, exec_cb, error_cb));
            return this;
        }

        @Override
        public AsyncSteps parallel(ErrorCallback error_cb) {
            return new ParallelStep(this, error_cb);
        }

        @Override
        public State state() {
            if (exec_top_ != this) {
                return null;
            }
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
        public AsyncSteps newInstance() {
            var ret = new AsyncStepsRI(async_tool_);
            var thisState = state_;
            var retState = ret.state_;

            retState.unhandled_error_ = thisState.unhandled_error_;
            retState.catch_trace_ = thisState.catch_trace_;

            return ret;
        }

        @Override
        public long binary() {
            return jni_binary(this);
        }

        @Override
        public AsyncSteps wrap(long ptr) {
            return jni_wrap(ptr);
        }

        @Override
        public void success(Object... args) {
            if (async_tool_.is_same_thread()) {
                handle_success_sync(this, args);
            } else {
                async_tool_.immediate(
                        () -> {
                            if (exec_top_ == this) {
                                handle_success_sync(this, args);
                            }
                        });
            }
        }

        @Override
        public void errorNoThrow(String error_code, String error_info) {
            if (async_tool_.is_same_thread()) {
                handle_error_sync(this, error_code, error_info, !in_exec_);
            } else {
                async_tool_.immediate(
                        () -> {
                            if (exec_top_ == this) {
                                handle_error_sync(this, error_code, error_info, true);
                            }
                        });
            }
        }

        /**
         * ignore
         * @return ignore
         */
        boolean is_sub_queue_empty() {
            return sub_queue_front_ >= stack_.size();
        }

        /** ignore */
        void sub_queue_free() {
            var stack = stack_;

            for (int i = stack.size() - 1, s = sub_queue_start_; i >= s; --i) {
                stack.remove(i).cleanup();
            }
        }

        @Override
        public void setTimeout(long timeout_ms) {
            if (error_code_ != null) {
                coverage_proof();
                on_invalid_call("setTimeout() call on protector");
            }

            limit_handle_ =
                    async_tool_.deferred(
                            timeout_ms,
                            () -> {
                                exec_top_.errorNoThrow(Error.Timeout);
                            });
        }

        @Override
        public void setCancel(CancelCallback on_cancel) {
            if (error_code_ != null) {
                coverage_proof();
                on_invalid_call("setCancel() call on protector");
            }

            on_cancel_ = on_cancel;
        }

        @Override
        public void waitExternal() {
            if (error_code_ != null) {
                coverage_proof();
                on_invalid_call("waitExternal() call on protector");
            }

            on_cancel_ = (AsyncSteps asi) -> {};
        }

        @Override
        public void execute() {
            coverage_proof();
            on_invalid_call("execute() call on protector");
        }

        @Override
        public void cancel() {
            coverage_proof();
            on_invalid_call("cancel() call on protector");
        }

        @Override
        public void loop(LoopCallback func, String label) {
            addRaw(
                    (asi, args) -> {
                        --sub_queue_front_;
                        func.call(asi);
                    },
                    (asi, error) -> {
                        if (error.equals(Error.LoopBreak)) {
                            ++sub_queue_front_;

                            var error_info = state_.error_info_;

                            if (error_info == null
                                    || error_info.length() == 0
                                    || error_info.equals(label)) {
                                asi.success();
                            }
                        } else if (error.equals(Error.LoopCont)) {
                            var error_info = state_.error_info_;

                            if (error_info == null
                                    || error_info.length() == 0
                                    || error_info.equals(label)) {
                                asi.success();
                            } else {
                                ++sub_queue_front_;
                            }
                        } else {
                            ++sub_queue_front_;
                        }
                    });
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
                            asi.breakLoopNoThrow(label);
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
                            asi.breakLoopNoThrow(label);
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
                            asi.breakLoopNoThrow(label);
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
                        if (steps_.isEmpty()) {
                            return;
                        }

                        on_parallel_error_ =
                                (inner_asi, error) -> {
                                    for (var s : steps_) {
                                        s.cancel();
                                    }
                                    asi.errorNoThrow(error, state_.error_info_);
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

                        asi.waitExternal();
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
            coverage_proof();
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
            coverage_proof();
            on_invalid_call("successStep() call on parallel()");
            return null;
        }

        @Override
        public <T> AsyncSteps await(Future<T> obj) {
            coverage_proof();
            on_invalid_call("await() call on parallel()");
            return null;
        }

        @Override
        public AsyncSteps newInstance() {
            return AsyncStepsRI.this.newInstance();
        }

        @Override
        public long binary() {
            coverage_proof();
            on_invalid_call("binary() call on parallel()");
            return 0;
        }

        @Override
        public AsyncSteps wrap(long ptr) {
            coverage_proof();
            on_invalid_call("wrap() call on parallel()");
            return null;
        }

        @Override
        public void success(Object... args) {
            coverage_proof();
            on_invalid_call("success() call on parallel()");
        }

        @Override
        public void errorNoThrow(String error_code, String error_info) {
            coverage_proof();
            on_invalid_call("errorNoThrow() call on parallel()");
        }

        @Override
        public void setTimeout(long timeout_ms) {
            coverage_proof();
            on_invalid_call("setTimeout() call on parallel()");
        }

        @Override
        public void setCancel(CancelCallback on_cancel) {
            coverage_proof();
            on_invalid_call("setCancel() call on parallel()");
        }

        @Override
        public void waitExternal() {
            coverage_proof();
            on_invalid_call("waitExternal() call on parallel()");
        }

        @Override
        public void execute() {
            coverage_proof();
            on_invalid_call("execute() call on parallel()");
        }

        @Override
        public void cancel() {
            coverage_proof();
            on_invalid_call("cancel() call on parallel()");
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
            coverage_proof();
            on_invalid_call("breakLoopNoThrow() call on parallel()");
        }

        @Override
        public void continueLoopNoThrow(String label) {
            coverage_proof();
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
    private final ArrayList<Protector> stack_;

    /**
     * Optimized accessor to the root step.
     * @hidden
     */
    private final Protector root_;

    /**
     * Empty arguments.
     * @hidden
     */
    private static final NextArgs empty_args_ = new NextArgs(new Object[0]);

    /**
     * Args for the next step.
     * @hidden
     */
    private NextArgs next_args_ = empty_args_;

    /**
     * Current step in execution
     * @hidden
     */
    private Protector exec_top_;

    /**
     * Current step error code, if any
     * @hidden
     */
    private String error_code_;

    /**
     * If AsyncSteps are with execute on stack.
     * @hidden
     */
    private boolean in_exec_;

    /**
     * Event loop execution handle
     * @hidden
     */
    private AsyncTool.Handle exec_handle_;

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
                            } else {
                                System.err.println("AsyncStepsRI unhandled error: " + error);

                                var last_exception = state.last_exception_;

                                if (last_exception != null) {
                                    last_exception.printStackTrace(System.err);
                                }
                            }
                        };
        var root = new Protector(null, (asi, args) -> {}, overall_error_handler);
        var stack = new ArrayList<Protector>();
        stack.add(root);

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
     * A helper to workaround jacoco coverage on throw issue.
     * @hidden
     */
    private static void coverage_proof() {}

    /**
     * For user error detection.
     * @param reason Detailed error info.
     * @hidden
     */
    private static void on_invalid_call(String reason) {
        throw new IllegalStateException(reason);
    }

    /**
     * Check the state of root asyncsteps
     * @hidden
     */
    private void root_sanity_check() {
        if (root_.sub_queue_front_ != 0) {
            coverage_proof();
            on_invalid_call("Root steps have been already executed!");
        }
    }

    @Override
    public AsyncSteps addRaw(ExecuteCallback exec_cb, ErrorCallback error_cb) {
        root_sanity_check();
        root_.addRaw(exec_cb, error_cb);
        return this;
    }

    @Override
    public AsyncSteps parallel(ErrorCallback error_cb) {
        root_sanity_check();
        return root_.parallel(error_cb);
    }

    @Override
    public State state() {
        if (root_.sub_queue_front_ != 0) {
            return null;
        }
        return state_;
    }

    @Override
    public AsyncSteps copyFrom(AsyncSteps other) {
        root_sanity_check();
        root_.copyFrom(other);
        return this;
    }

    @Override
    public AsyncSteps syncRaw(ISync obj, ExecuteCallback exec_cb, ErrorCallback error_cb) {
        root_sanity_check();
        root_.syncRaw(obj, exec_cb, error_cb);
        return this;
    }

    @Override
    public AsyncSteps successStep(Object... args) {
        root_sanity_check();
        root_.successStep(args);
        return this;
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
        coverage_proof();
        on_invalid_call("success() on root");
    }

    @Override
    public void errorNoThrow(String error_code, String error_info) {
        coverage_proof();
        on_invalid_call("errorNoThrow() on root");
    }

    @Override
    public void setTimeout(long timeout_ms) {
        coverage_proof();
        on_invalid_call("setTimeout() on root");
    }

    @Override
    public void setCancel(CancelCallback on_cancel) {
        coverage_proof();
        on_invalid_call("setCancel() on root");
    }

    @Override
    public void waitExternal() {
        coverage_proof();
        on_invalid_call("waitExternal() on root");
    }

    @Override
    public void execute() {
        if (root_.sub_queue_front_ != 0) {
            coverage_proof();
            on_invalid_call("execute() on active instance");
        }

        root_.sub_queue_front_ = 2;
        root_.sub_queue_start_ = 1;

        if (stack_.size() > 1) {
            exec_top_ = stack_.get(1);
            // Do not assign exec_handle_ because of race.
            async_tool_.immediate(this::schedule_exec);
        }
    }

    /** ignore */
    private void schedule_exec() {
        if (!in_exec_ && exec_top_ != null) {
            if (exec_handle_ != null) {
                coverage_proof();
                on_invalid_call("sched_execute() on active instance");
            }

            exec_handle_ = async_tool_.immediate(this::handle_execute);
        }
    }

    /** ignore */
    private void handle_execute() {
        in_exec_ = true;
        exec_handle_ = null;

        boolean sched_exec = true;

        for (var burst = BURST_SIZE; burst > 0; --burst) {
            var current = exec_top_;

            if (current == null) {
                return;
            }

            var qs = stack_.size();
            current.sub_queue_start_ = qs;
            current.sub_queue_front_ = qs;

            var current_args = next_args_;
            next_args_ = empty_args_;

            try {
                current.exec_cb_.call(current, current_args);

                if (exec_top_ != current) {
                    // explicit success()
                } else if (error_code_ != null) {
                    // errorNoThrow()
                    handle_error_sync(current, error_code_, state_.error_info_, true);
                } else if (!current.is_sub_queue_empty()) {
                    // implicit success with substeps
                    exec_top_ = stack_.get(current.sub_queue_front_);
                    ++(current.sub_queue_front_);
                } else if (current.on_cancel_ == null && current.limit_handle_ == null) {
                    // implicit success
                    handle_success_sync(current);
                } else {
                    // external wait
                    in_exec_ = false;
                    return;
                }
            } catch (UnwindException ex) {
                state_.catch_trace_.call(ex);
                handle_error_sync(current, error_code_, state_.error_info_, true);
            } catch (ExtError ex) {
                state_.catch_trace_.call(ex);
                handle_error_sync(current, ex.getMessage(), ex.getErrorInfo(), true);
            } catch (Throwable ex) {
                state_.catch_trace_.call(ex);
                handle_error_sync(current, ex.getMessage(), null, true);
            }
        }

        in_exec_ = false;

        schedule_exec();
    }

    @Override
    public void cancel() {
        if (async_tool_.is_same_thread()) {
            handle_cancel();
        } else {
            async_tool_.immediate(this::handle_cancel);
        }
    }

    /** ignore */
    private void handle_cancel() {
        var eh = exec_handle_;

        if (eh != null) {
            eh.cancel();
            exec_handle_ = null;
        }

        for (var current = exec_top_; current != null; ) {
            var on_cancel = current.on_cancel_;
            if (on_cancel != null) {
                on_cancel.call(current);
            }

            var parent = current.parent_;
            current.cleanup();
            current = parent;
        }

        exec_top_ = null;
        stack_.clear();
    }

    /**
     * ignore
     * @param current ignore
     * @param args ignore
     */
    private void handle_success_sync(Protector current, Object... args) {
        if (current != exec_top_) {
            coverage_proof();
            on_invalid_call("success() out of order");
        }

        if (!current.is_sub_queue_empty()) {
            coverage_proof();
            on_invalid_call("success() with sub-steps");
        }

        if (error_code_ != null) {
            error_code_ = null;
            state_.error_info_ = null;
        }

        next_args_ = (args.length > 0) ? new NextArgs(args) : empty_args_;
        current.cleanupExternalWait();

        for (current = current.parent_; current != null; current = current.parent_) {
            if (!current.is_sub_queue_empty()) {
                exec_top_ = stack_.get(current.sub_queue_front_);
                ++(current.sub_queue_front_);
                schedule_exec();
                return;
            }

            current.sub_queue_free();
        }

        root_.cleanup();
        stack_.clear();
        exec_top_ = null;
    }

    /**
     * ignore
     * @param current ignore
     * @param error_code ignore
     * @param error_info ignore
     * @param unwind ignore
     */
    private void handle_error_sync(
            Protector current, String error_code, String error_info, boolean unwind) {
        if (current != exec_top_) {
            coverage_proof();
            on_invalid_call("error*() out of order");
        }

        if (error_code == null) {
            coverage_proof();
            on_invalid_call("error*() code must be set");
        }

        error_code_ = error_code;
        state_.error_info_ = error_info;

        if (!unwind) {
            return;
        }

        while (current != null) {
            var on_cancel = current.on_cancel_;
            if (on_cancel != null) {
                on_cancel.call(current);
            }

            current.cleanupExternalWait();
            current.sub_queue_front_ = current.sub_queue_start_;
            current.sub_queue_free();

            var on_error = current.error_cb_;

            if (on_error != null) {
                try {
                    on_error.call(current, error_code_);

                    if (exec_top_ != current || error_code_ == null) {
                        // success() called or loop continue
                        return;
                    }

                    if (!current.is_sub_queue_empty()) {
                        // implicit success() via substeps
                        exec_top_ = stack_.get(current.sub_queue_front_);
                        ++(current.sub_queue_front_);
                        current.error_cb_ = null;
                        error_code_ = null;
                        state_.error_info_ = null;
                        schedule_exec();
                        return;
                    }
                } catch (UnwindException ex) {
                    state_.catch_trace_.call(ex);
                } catch (ExtError ex) {
                    state_.catch_trace_.call(ex);
                    state_.error_info_ = ex.getErrorInfo();
                    error_code_ = ex.getMessage();
                } catch (Throwable ex) {
                    state_.catch_trace_.call(ex);
                    error_code_ = ex.getMessage();
                }
            }

            current = current.parent_;
            exec_top_ = current;
        }

        stack_.clear();
    }

    @Override
    public void loop(LoopCallback func, String label) {
        root_sanity_check();
        root_.loop(func, label);
    }

    @Override
    public <K, V> void forEach(Map<K, V> map, ForEachMapCallback<K, V> func, String label) {
        root_sanity_check();
        root_.<K, V>forEach(map, func, label);
    }

    @Override
    public <V> void forEach(Iterator<V> iter, ForEachIterCallback<V> func, String label) {
        root_sanity_check();
        root_.<V>forEach(iter, func, label);
    }

    @Override
    public void repeat(long count, RepeatCallback func, String label) {
        root_sanity_check();
        root_.repeat(count, func, label);
    }

    @Override
    public void breakLoopNoThrow(String label) {
        coverage_proof();
        on_invalid_call("breakLoopNoThrow() on root");
    }

    @Override
    public void continueLoopNoThrow(String label) {
        coverage_proof();
        on_invalid_call("continueLoopNoThrow() on root");
    }
}
