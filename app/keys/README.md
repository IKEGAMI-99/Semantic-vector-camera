# App signing key

This project intentionally uses a repository-embedded stable development/release keystore for personal distribution only.

- keystore: `svcam-release.jks`
- alias: `svcam`
- password: `svcam2026`

Both Debug and Release builds use the same package id and signing key so APKs can overwrite each other during personal testing and the in-app updater can continue to install newer builds.

Do not reuse this key for Play Store or security-sensitive public distribution.
