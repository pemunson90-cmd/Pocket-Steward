# Pocket Steward — build environment reference

Everything a fresh Claude Code session needs to compile this project without
rediscovering it. All statements below are from actual build output or library
source in this environment, not from recollection.

## Environment that produced the Milestone 1 APK

| Component | Version |
| --- | --- |
| JDK | OpenJDK 21.0.10 |
| Gradle | 8.13 (project wrapper, `./gradlew`) |
| Android SDK Platform | android-36 |
| Android SDK Build-Tools | 36.1.0 |
| Android SDK cmdline-tools | 19.0 |
| Platform-Tools | r37.0.1 |

The build needs outbound HTTPS to `dl.google.com`, `repo1.maven.org`,
`plugins.gradle.org` and `services.gradle.org`. A sandbox that blocks
`dl.google.com` cannot build this project at all, which is why the authoring
assistant has never compiled it.

## Fresh-sandbox SDK setup

```bash
# 1. command line tools
mkdir -p ~/android-sdk/cmdline-tools
curl -sSL -o /tmp/cmdline.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q /tmp/cmdline.zip -d ~/android-sdk/cmdline-tools
mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest

# 2. licenses + packages
export ANDROID_HOME=~/android-sdk ANDROID_SDK_ROOT=~/android-sdk
yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses --sdk_root=$ANDROID_HOME
~/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME \
  "platform-tools" "platforms;android-36" "build-tools;36.1.0"

# 3. point the project at it (this file is gitignored, recreate per machine)
echo "sdk.dir=$HOME/android-sdk" > local.properties

# 4. build
./gradlew assembleDebug --stacktrace
./gradlew testDebugUnitTest --stacktrace
```

## Version constraints that are load-bearing

These are not preferences. Each one broke a real build.

**Compose BOM is capped at 2026.06.01 while AGP is 8.13.2 / compileSdk 36.**
BOM 2026.08.00 resolves Compose to 1.12.0, whose AAR metadata declares
`minCompileSdk=37` and a minimum AGP of 9.1.0. It fails
`checkDebugAarMetadata` with 22 issues across ui, foundation, animation,
material-ripple and runtime-saveable. BOM 2026.06.01 resolves Compose 1.11.4,
whose AAR metadata declares `minCompileSdk=35` and no AGP floor. Moving to
Compose 1.12 requires moving AGP to 9.x and compileSdk to 37 in the same
change.

**Kotlin 2.3.x rejects `kotlinOptions { jvmTarget = "17" }` as an error**, not a
warning, so the build script will not even compile. The replacement lives
outside the `android {}` block:

```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
```

**material3 no longer brings `material-icons-core` in transitively.** If
`material-icons-extended` is removed, `material-icons-core` has to be added
explicitly or every `Icons.*` reference fails with `Unresolved reference`.
`material-icons-extended` costs roughly 23 MB of dex in an unminified debug
build; the app currently uses two icons, both in core.

**`androidx.documentfile:documentfile:1.0.1` — `DocumentFile.fromSingleUri` is
annotated `@Nullable`.** Kotlin sees a nullable receiver and refuses
non-null calls on it. Verified in `documentfile-1.0.1-sources.jar`.

## APK size measurement gotcha

AGP's incremental repackaging leaves dead space inside the APK when the dex
shrinks between builds. Observed in Milestone 0: the dex dropped from 41 MB to
18.5 MB while the APK file size stayed at 63.7 MB, because 31 MB of hole sat
between two zip entries. `./gradlew clean assembleDebug` collapses it. Size
figures taken off an incremental build are not trustworthy.

## Runtime findings from the first build — since fixed

Found by reading the code and library source while getting the Milestone 1
build green; at the time they were left alone as behavior changes rather than
build fixes. All three are now fixed in source (not yet re-verified by an
actual build — that's the next round):

1. **SAF mode couldn't scan.** `listChildren`/`stat` in `SafStorageGateway`
   now use `DocumentFile.fromTreeUri` instead of `fromSingleUri`. The latter
   returns a `SingleDocumentFile`, whose `listFiles()` unconditionally
   throws `UnsupportedOperationException` — that's why the old code compiled
   but failed immediately at runtime in SAF mode. `fromTreeUri` checks
   `DocumentsContract.isDocumentUri` and, when true, honors the specific
   document it's given rather than always resolving the tree's root:
   ```java
   // androidx/documentfile/provider/DocumentFile.java
   public static DocumentFile fromTreeUri(@NonNull Context context, @NonNull Uri treeUri) {
       String documentId = DocumentsContract.getTreeDocumentId(treeUri);
       if (DocumentsContract.isDocumentUri(context, treeUri)) {
           documentId = DocumentsContract.getDocumentId(treeUri);   // <- honours the child
       }
       return new TreeDocumentFile(null, context,
               DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId));
   }
   ```
   `rootOf()`'s document-URI normalization is exactly what makes that check
   pass for every node, root or descendant. `ScanViewModel.resolveRoot` also
   now calls `gateway.rootOf()` for the SAF branch instead of wrapping the
   raw stored tree URI directly, so that normalization actually runs on the
   scan root too — previously it only ran on nodes reached by walking, never
   on the starting point itself.
2. **Scanning ran on the main thread.** `ScanViewModel.startScan` used
   `viewModelScope.launch` with no dispatcher, so `DirectStorageGateway`'s
   blocking `java.io.File` work ran on `Dispatchers.Main.immediate`. Now
   wrapped in `withContext(Dispatchers.IO)`.
3. **Version still read `0.1.0-milestone0`.** Bumped to `versionCode = 2`,
   `versionName = "0.2.0-milestone1"`.

## Room schemas

`exportSchema = true` and KSP writes to `app/schemas/`, which `.gitignore`
currently excludes. This build generated
`app/schemas/com.pocketsteward.app.data.db.AppDatabase/2.json`, included in
the source bundle. `AppDatabase` is on
`fallbackToDestructiveMigration(dropAllTables = true)`, so the historical
schema files are not load-bearing yet; they become load-bearing when real
migrations arrive (the file itself points at Milestone 7). Committing them
from now on costs nothing and is the cheap version of that decision. The
`1.json` from Milestone 0 was generated in a previous sandbox and is not
recoverable from this source tree.

## Current state

`./gradlew clean assembleDebug testDebugUnitTest` is green. Unit tests: 7
passing across `SettingsDefaultsTest` (2), `FileCategoryTest` (3),
`FileRefCodecTest` (2). Debug APK is 31.11 MiB, debug-signed,
`com.pocketsteward.app` / `MainActivity`, minSdk 30, targetSdk 36.
