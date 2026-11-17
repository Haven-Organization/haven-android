# Releasing Haven Android

Releases are built, signed and published by GitHub Actions (`.github/workflows/release.yml`).

## Cutting a release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts` (both places) and merge to `master`.
2. Tag the commit on `master` and push the tag (only admins can do this):
   ```
   git tag -a v0.2.0 -m "Haven Android 0.2.0"
   git push haven v0.2.0
   ```
   The tag must match the start of `versionName` (for example `v0.1.0` for `0.1.0-ex_26_11_0`).
3. In the repository's **Actions** tab, open the "Release" run and **approve** the `release` environment deployment.
   Before approving, check that the tag points at a commit on `master` you expect.
4. About 30–40 minutes later the release appears under **Releases** with the arm64-v8a, armeabi-v7a and x86_64 APKs and a
   `SHA256SUMS` file. The workflow fails if any APK isn't signed with the Haven release key.

## How the signing key is protected

- The keystore (base64) and its passwords are secrets of the **`release` environment**, not repository secrets.
  Only jobs that declare `environment: release` can read them.
- The `release` environment only accepts deployments from **`v*` tags** and needs a **maintainer's approval** before
  each run, so neither a branch nor a pull request can read the secrets.
- A **tag ruleset** lets only repository admins create, move or delete `v*` tags.
- A **`master` ruleset** blocks force-pushes and deletion, and requires changes to come through pull requests
  (admins can bypass it).
- Workflows from **outside contributors' pull requests** need approval before they run, and GitHub never passes
  secrets to pull requests from forks.
- The default `GITHUB_TOKEN` is read-only. Only the release job gets `contents: write`.
- The keystore is decoded only after the third-party setup actions have run, and is deleted after the build.

Keep an offline backup of the keystore and passwords. If the key is lost, existing installs can't be updated.
