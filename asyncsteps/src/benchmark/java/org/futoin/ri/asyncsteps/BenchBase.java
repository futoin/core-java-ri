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

public class BenchBase {
    static final int Simple_COUNT = 1_000_000;
    static final int Parallel_COUNT = 1_000_000;
    static final int ThreadsParallel_COUNT = 100_000;
    static final int Parallel_LIMIT = 30_000;
    static final int ThreadsParallel_LIMIT = 10_000;
    static final int ParallelWaitLoop_COUNT = 10_000_000;
    static final int ThreadsParallelWaitLoop_COUNT = 100_000;
    static final int Warmup_TIME = 10;
    static final int Warmup_ITER = 3;
    static final int Measure_TIME = 60;
    static final int Measure_ITER = 3;
}
