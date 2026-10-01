# Release packaging

Use JDK 21 and Android SDK 36. Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` on committed source. The app-completion workflow runs these checks and checks that built APKs have no INTERNET permission. Instrumentation compilation is not a device test.

For the installable release, provide the four `POCKET_STEWARD_KEYSTORE_*` / `POCKET_STEWARD_KEY_ALIAS` environment variables consumed by `app/build.gradle.kts`, using the private canonical key outside the repository, then run `./gradlew assembleRelease`. Never commit or upload the key or passwords. The release environment variables are `POCKET_STEWARD_KEYSTORE_PATH`, `POCKET_STEWARD_KEYSTORE_PASSWORD`, `POCKET_STEWARD_KEY_ALIAS`, and `POCKET_STEWARD_KEY_PASSWORD`.

After publishing the identical source, package with:

```sh
python tools/package_release.py --apk app/build/outputs/apk/release/app-release.apk --build-tools "$ANDROID_HOME/build-tools/36.1.0" --output /tmp/pocket-release --published-source SOURCE_COMMIT
```

The script verifies the canonical signer, offline permission invariant, matching version, clean tracked source and passing unit reports, then emits an APK ZIP, tracked-source ZIP and provenance. It never reads signing credentials. Run validation on the same source before packaging; existing test reports alone cannot prove which commit was tested. Verify the uploaded ZIP hash against the local package before sharing its download link. Hardware acceptance remains a separate gate.
