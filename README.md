
### FutoIn Core Java Reference Implementation

FutoIn is documentation-driven foundation of neutral software concepts and
reference implementations. In addition to its uniform approach, FutoIn aims to
excel in performance and resource efficiency.

FutoIn provides easy mixing of incompatible technologies in projects with loose
coupling of all parts.

Please visit the official [FutoIn Guide](https://futoin.org/docs/) and 
[FutoIn Specs](https://specs.futoin.org/) for more information.

#### Spec Reference Implementation

* [**FTN12: AsyncSteps** Guide](https://futoin.org/docs/asyncsteps/)
    - See `org.futoin.api.AsyncSteps` interface and helpers.
    - See [Spec](https://specs.futoin.org/final/preview/ftn12_async_api.html).

### Benchmark

AsyncSteps benchmarks are available under [asyncsteps/src/benchmark/](). Those
can be launched via `cte gradle asyncsteps:benchmark`.

AsyncSteps operate on a single platform thread intentionally. This allows
concurrent use of thread-unsafe resource without extra synchronization overhead.

For benchmarking purposes, Java virtual thread executor pool is limited to one
thread. Multiple threads must be compared to multiple `AsyncTool` instances.

The figures below are approximations, which show the magnitude of the
difference in performance. FutoIn AsyncSteps also have smaller memory footprint
and remain compatible with JNI.

**Raw sequential spawning and execution**:

This measures raw relative overhead of technology. These results reflect the 
overhead of launching from sleep state.

| Technology | Throughput | Times **Slower** |
| :--- | ---: | ---: |
| FutoIn AsyncSteps for Java RI | 696778.701 ops/s | 1x |
| Java Virtual Thread | 40515.222 ops/s | 17x |
| Platform Threads | 4364.575 ops/s | 160x |

**Heavy concurrency with message exchange and spawning pool**:

The objective is to create 1,000,000 threads with concurrency limits of 30,000.
Threads bench use lower values due to resource restrictions: 100,000 count and
10,000 concurrency limit.

The objective of each thread is to wait for an external event and updated an
atomic counter based on the event parameter.

| Technology | Throughput | Times **Slower** |
| :--- | ---: | ---: |
| FutoIn AsyncSteps for Java RI | 6381969.507 ops/s | 1x |
| Java Virtual Thread | 1095367.098 ops/s | 5.8x |
| Platform Threads | 2065.953 ops/s | 3,089x |

**Heavy concurrency with message exchange and persistent pool**:

The objective is to pass 10,000,000 iteration with concurrency limits of 30,000.
Threads bench use lower values due to resource restrictions: 100,000 count and
10,000 concurrency limit.

The objective of each thread is to compete with other in work-stealing approach
to complete the target iteration count overall using atomic counter.

| Technology | Throughput | Times **Slower** |
| :--- | ---: | ---: |
| FutoIn AsyncSteps for Java RI | 7996924.472 ops/s | 1x |
| Java Virtual Thread | 3463208.853 ops/s | 2.3x |
| Platform Threads | 17595.940 ops/s | 454x |

### Usage

The primary purpose for of the Core Reference Implementation project is
proof-of-concept and a ready production baseline.
