# Review scale measurements

Recorded 2026-10-01 in the controlled workspace JVM. Fixture: 200 distinct numbered project homes; each text file names its correct home and Notes role. Every run asserts exact ownership, strong confidence and selected source count. Source: ReviewScaleBenchmarkTest. These measurements establish engine/adaptor behavior only, not target-phone performance.

| Files | Pass | Matching | Typed plan | Combined |
|---:|---|---:|---:|---:|
| 1,000 | Cold | 397 ms | 10 ms | 407 ms |
| 1,000 | Warm | 201 ms | 8 ms | 209 ms |
| 4,000 | Cold | 881 ms | 18 ms | 899 ms |
| 4,000 | Warm | 880 ms | 9 ms | 889 ms |
| 16,000 | Cold | 3,376 ms | 80 ms | 3,456 ms |
| 16,000 | Warm | 2,906 ms | 60 ms | 2,966 ms |

Cold means the first pass for that corpus size in this JVM, rather than an isolated newly started Android app. Warm repeats the same fixture. Times vary by machine/load; the test enforces relationship correctness, not a phone latency promise. Android scan/index/IO/ML time, peak memory, responsiveness and thermal behavior remain unmeasured.

Image admission tests separately use 16,000 supported image records and a fake evidence provider. They prove at most 200 fresh image calls and 40 OCR calls per review, cache retention beyond those budgets, eventual continuation, failure fairness, cancellation and privacy gating. They do not time the real ML Kit models.
