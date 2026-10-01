# Build and release Pocket Steward

The core release is offline. Every distributed update must use the canonical signer, preserve the application ID, increase the version code, and verify that INTERNET permission remains absent. GitHub Actions independently builds/tests the source; it does not hold the private signing bundle.

## Validate

Use JDK 21, Android SDK with platform 36 and Build Tools 36.1.0, and the checked-in Gradle wrapper. Set JAVA_HOME and ANDROID_HOME for the development machine, not the phone.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python3 tools/verify_content_scope_sql.py
python3 tools/verify_project_knowledge_sql.py
python3 tools/verify_library_refresh_sql.py
```

Instrumentation compilation does not execute tests on a phone/emulator. Keep device acceptance separate from the build receipt.

## Sign without putting keys in the checkout

Keep the canonical signing ZIP outside the repository. The helper reads its bounded credential/key entries, creates a temporary private keystore, passes credentials only in the child build environment, removes the temporary key, and verifies the output certificate, version, phone ABI and offline permission. Build diagnostics go into the private log. The final output is replaced only after verification passes. The ZIP, passwords and logs must not be committed or included in a release archive.

```sh
python3 tools/build_signed_release.py \
  --signing-bundle /private/Pocket-Steward-CANONICAL-SIGNING-KEY-2026-09-19.zip \
  --sdk /path/to/android-sdk \
  --java-home /path/to/jdk-21 \
  --output /private/releases/PocketSteward-arm64.apk \
  --log /private/releases/build.log
```

In environments requiring a Gradle dependency mirror, supply `--gradle-init /path/to/mirror.init.gradle`. The helper inherits the development machine's network/proxy configuration; it does not change APK permissions.

## Package and publish

Commit the source and tutorial after validation. With authenticated GitHub CLI access, publish the tested source to the app-completion branch. Supply the full original baseline commit SHA and a commit-message file:

```sh
python3 tools/publish_source.py \
  --baseline ORIGINAL_BASELINE_SHA \
  --message-file /private/releases/source-message.txt \
  --receipt /private/releases/source-receipt.json
```

This keeps existing planning files in the remote tree, applies committed changes and refuses publication unless the complete app tree matches the local tree. It never forces a branch update. Its receipt contains the published source commit. Then package the signed APK using that commit:

```sh
python3 tools/package_release.py \
  --apk /private/releases/PocketSteward-arm64.apk \
  --build-tools /path/to/android-sdk/build-tools/36.1.0 \
  --output /private/releases/packages \
  --published-source PUBLIC_COMMIT_SHA
```

The package includes the APK, signer/version/hash/source provenance, current DEV-number tutorial and completion progress; a separate tracked-source ZIP excludes untracked private keys. The packaging helper requires clean tracked files and passing unit reports. Run validation on that source before packaging; report files alone do not prove the current commit was tested.

Publish immutable download URLs and retrieve the public ZIP again. Verify both its ZIP digest and its embedded APK digest against the local package/provenance before announcing it. Reference the exact source commit and state device acceptance honestly. Install the canonical update over the existing app; uninstalling would lose its private data.

The publication helper uploads the known package/source/provenance files, checks uploaded blob identities, retrieves the immutable public ZIP and verifies its embedded APK. It refuses to overwrite a version with different packages and can resume an identical publication. GitHub CLI and curl are required. Write release notes describing the actual changes, validation and device limits, then run:

```sh
python3 tools/publish_release.py \
  --package-dir /private/releases/packages \
  --version 1.4.0-dev11 \
  --notes-file /private/releases/release-notes.md \
  --receipt /private/releases/download-receipt.json
```

The verified download URL is recorded in the receipt. A prerelease is created only after public download verification passes. Treat signed development updates as acceptance checkpoints until phone testing is complete.
