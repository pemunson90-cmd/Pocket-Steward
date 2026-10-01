# AppFunctions maturity decision

Checked 2026-10-01 for the master plan's explicitly maturity-dependent integration.

Google's [AppFunctions release page](https://developer.android.com/jetpack/androidx/releases/appfunctions) lists **1.0.0-alpha12**, released September 23, 2026. Its stable, release-candidate and beta columns are empty. The official [Google Maven metadata](https://dl.google.com/android/maven2/androidx/appfunctions/appfunctions/maven-metadata.xml) likewise lists only alpha versions and identifies alpha12 as the latest release; metadata was last updated 20260923170017. The alpha12 notes include API changes to serialization metadata and experimental callback APIs.

**Decision: defer production AppFunctions integration at this maturity gate.** This resolves the required SDK usefulness/maturity review; it does not label an AppFunctions service as implemented. No AppFunctions service, exported execution endpoint or new mutation authority is included in dev9. Existing launcher shortcuts, share intake and reviewed workflows remain the available integrations.

Reopen the conditional item when a stable SDK and a compatible target-phone invocation surface are available. Any future integration must accept bounded requests, verify storage permissions, prepare an ordinary review and require the same approval, durable task, validator and journal used by the app. It must never execute arbitrary paths or silently approve a file operation. Framework class availability alone does not establish a usable, tested product integration.

The optional provider/local-runtime adapters, broader integration coverage and phone acceptance are separate remaining requirements; this decision does not close them.
