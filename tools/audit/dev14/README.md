# Dev14 audit probes

These six host probes preserve observations from the 2026-10-02 audit. They assert the defective behavior described in the audit, except for the timing probe. They are deliberately outside the app's normal test suite and must not be used as acceptance tests after repairs.

The cancellation probe invokes the actual ViewModel cancellation/Idle methods while bypassing its Android constructor dependencies. The collision probe executes the gateway's filesystem primitive with a controlled external-writer interleaving; it does not execute the Android gateway. The other functional probes execute the actual repository or detector with controlled gateways and DAO fixtures.

To reproduce on the matching source in an isolated checkout, temporarily copy `Dev14AuditProbeTest.kt` to `app/src/test/java/com/pocketsteward/app/audit/Dev14AuditProbeTest.kt`, then run `:app:testDebugUnitTest --tests com.pocketsteward.app.audit.Dev14AuditProbeTest`. Remove that temporary test afterwards and rerun the normal unit suite. The prepared workspace uses `/workspace/build-pocket-steward-upgrade.sh` for these Gradle arguments.

`PROBE_RESULTS.txt` contains the successful final probe run. Benchmarks measure the host JVM, exclude Compose/Android IO, and are not phone performance acceptance.
