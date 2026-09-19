# Pocket Steward signing identity

Pocket Steward development builds from M8 onward use one persistent signing
identity whenever the build environment supplies it.

Canonical development certificate SHA-256:

`B9:05:7D:D6:49:DA:EA:23:39:03:37:F2:C3:58:92:D0:EF:85:91:E5:7E:9E:9A:27:35:5E:7C:D1:1A:84:19:35`

The private keystore and its passwords are deliberately **not** stored in this
public repository.

Gradle reads these environment variables:

- `POCKET_STEWARD_KEYSTORE_PATH`
- `POCKET_STEWARD_KEYSTORE_PASSWORD`
- `POCKET_STEWARD_KEY_ALIAS`
- `POCKET_STEWARD_KEY_PASSWORD`

If all four are present, the debug APK is signed with that keystore. If any are
missing, Android's ordinary disposable debug signing is used for build/testing
only and the resulting APK must not be treated as an upgrade artifact.

The owner's canonical key package is named
`Pocket-Steward-CANONICAL-SIGNING-KEY-2026-09-19.zip`.

## GitHub Actions

Store the keystore as base64 in repository secret
`POCKET_STEWARD_KEYSTORE_B64`, and store the three credential values in:

- `POCKET_STEWARD_KEYSTORE_PASSWORD`
- `POCKET_STEWARD_KEY_ALIAS`
- `POCKET_STEWARD_KEY_PASSWORD`

A release/update artifact is valid only when its certificate fingerprint
matches the canonical SHA-256 above.
