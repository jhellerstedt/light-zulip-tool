# Chat for Zulip (unofficial)

An unofficial Zulip client for LightOS. It is not affiliated with Zulip, Kandra Labs, or The Light Phone.

It talks only to the Zulip server URL you enter, and that address must use HTTPS.

Signing in sends your email and password once so the server can return an API key. Later requests send that API key. Messages you write, and the list of people in the realm, also go to that server.

The API key stays on the phone, encrypted in the Android keystore. Recent messages and the realm directory are cached locally so the tool can open offline. There is no analytics and no push notification.

## Images and files

Compose has a photo button. It asks for `READ_MEDIA_IMAGES`, shows recent album photos as previews, and copies each tapped photo into a `ComposeAttachmentDraft`. Send uploads those files and posts them as Zulip links.

A received photo is shown in the conversation. Tap it to open the picture full screen. Other attachments still show as `File: name`. If the image cannot be downloaded, the row falls back to `Photo: name`.

## Building

Day-to-day builds come from this checkout and install over one another when they share a signing key. A Light portal build is separate: Light compiles only the tool against a clean SDK and signs it with Light's key. Bump `versionCode` in `lighttool.toml` when you submit there, not for every local install.

```bash
scripts/sideload.sh              # debug APK, shared SDK dev key
scripts/sideload.sh release      # release APK
scripts/store-build-check.sh     # portal-shaped build, before you submit
```

`scripts/sideload.sh` builds, installs on the connected phone, and opens Zulip. A release install uses `keystore.properties` when that file names a keystore, and the shared dev key otherwise. Create a key once and keep it outside the repo:

```bash
keytool -genkeypair -v \
  -keystore "$HOME/keys/zulip-release.jks" \
  -alias zulip -keyalg RSA -keysize 2048 -validity 10000
cp keystore.properties.example keystore.properties
```

Fill in `storeFile` and the passwords. Installs signed with that key replace each other and keep the app's data. A portal APK is signed by Light, so moving between a local build and a portal build means uninstalling first.

`scripts/store-build-check.sh` copies the tool onto a clean `upstream/main` SDK with Light's extractor and runs the portal Gradle command. The APK it writes is unsigned. SDK edits in this fork are included in `scripts/sideload.sh` and left out of the check, which is what the portal will do. `--offline` uses only the local Gradle cache. `--sdk-ref REF` checks another SDK commit. `--head` checks the last commit instead of the working tree.

## Releases

GitHub releases follow the other Light tools: a `v*` tag builds the release APK and attaches it to the release. Bump `versionCode` and `versionName` in `tool/lighttool.toml` first. The tag is `v` plus `versionName` (`versionName = "1.1.8"` → tag `v1.1.8`).

```bash
git tag v1.1.8
git push origin v1.1.8
```

The workflow checks that the tag matches `versionName`, runs `:tool:test`, and uploads `zulip-1.1.8.apk`. Actions → Release APK can cut the same release from `main` without pushing a tag first. CI has no `keystore.properties`, so that APK is signed with the shared dev key and updates other dev-key installs. A Light portal build is still signed by Light.
