# Zulip

An unofficial Zulip client for the Light Phone. It is not affiliated with Zulip, Kandra Labs, or The Light Phone.

The tool id is `com.jhellerstedt.zulip`. The current version is in [`tool/lighttool.toml`](tool/lighttool.toml).

## What it does

You sign in to one HTTPS Zulip server with an email and password, or with an API key. The menu lists streams and direct messages, and keeps the two most recent of each on the home screen. An asterisk marks a stream or conversation that still has unread messages.

Opening a stream shows its topics. Opening a topic or a direct message shows the conversation. Compose sends text, and it can attach a photo from the album. A received photo opens full screen. Other attachments show as `File: name`.

A long-press on a message opens reactions. LIKE adds a heart. REACT opens an emoji grid. A new reaction replaces the one you already have on that message, and double-tap toggles the heart. Other people's reactions show under the message.

A web address in a message shows as `URL:` plus the site, so `https://theconversation.com/jensen-huangs-...` reads `URL: theconversation.com`. A leading `www.` is dropped. Photos and uploaded files stay as they are.

Messages written while the server is unreachable stay on the phone and send when it is back. Settings can hold more than one realm, switch the active one, or remove it.

## What leaves the phone

The tool talks only to the Zulip server URL you enter, and that address must use HTTPS.

Signing in sends your email and password once so the server can return an API key. Later requests send that API key. Messages you write, and the list of people in the realm, also go to that server.

The API key stays on the phone, encrypted in the Android keystore. Recent messages and the realm directory are cached locally so the tool can open offline. There is no analytics. Zulip does not push to this tool. The asterisk beside Zulip on the phone is the phone's own unread mark, fed by that local cache.

## Building

Day-to-day builds come from this checkout and install over one another when they share a signing key. A Light portal build is separate: Light compiles only the `tool` module against a clean published SDK and signs it with Light's key. Bump `versionCode` in [`tool/lighttool.toml`](tool/lighttool.toml) when you submit there, not for every local install.

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

`scripts/store-build-check.sh` copies the tool onto a clean `upstream/main` SDK with Light's extractor and runs the portal Gradle command. The APK it writes is unsigned. SDK edits in this checkout are included in `scripts/sideload.sh` and left out of the check, which is what the portal will do. `--offline` uses only the local Gradle cache. `--sdk-ref REF` checks another SDK commit. `--head` checks the last commit instead of the working tree.

## Releases

A `v*` tag builds the release APK and attaches it to a GitHub Release. Bump `versionCode` and `versionName` in [`tool/lighttool.toml`](tool/lighttool.toml) first. The tag is `v` plus `versionName` (`versionName = "1.1.8"` becomes tag `v1.1.8`).

```bash
git tag v1.1.8
git push origin v1.1.8
```

The workflow checks that the tag matches `versionName`, runs the tool tests, and uploads `zulip-1.1.8.apk`. Actions → Release APK can cut the same release from `main` without pushing a tag first. CI has no `keystore.properties`, so that APK is signed with the shared dev key and updates other dev-key installs. A portal build is still signed by Light.

## Light SDK

This checkout vendors [lightphone/light-sdk](https://github.com/lightphone/light-sdk) so the tool compiles here. `sdkVersion` in [`gradle.properties`](gradle.properties) is the SDK a portal build will use. SDK notes live under [docs/](docs/). Upstream merges land on `main` when this tool needs a newer SDK.
