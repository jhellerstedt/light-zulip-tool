# Chat for Zulip (unofficial)

An unofficial Zulip client for LightOS. It is not affiliated with Zulip, Kandra Labs, or The Light Phone.

It talks only to the Zulip server URL you enter, and that address must use HTTPS.

Signing in sends your email and password once so the server can return an API key. Later requests send that API key. Messages you write, and the list of people in the realm, also go to that server.

The API key stays on the phone, encrypted in the Android keystore. Recent messages and the realm directory are cached locally so the tool can open offline. There is no analytics and no push notification.

## Images and files

Compose has a photo button. It asks for `READ_MEDIA_IMAGES`, lists recent album photos, and copies the chosen file into a `ComposeAttachmentDraft`. Send uploads that file and posts it as a Zulip link.

A received photo is shown in the conversation. Other attachments still show as `File: name`. If the image cannot be downloaded, the row falls back to `Photo: name`.

```bash
./gradlew :tool:installDebug
adb shell am start -n com.jhellerstedt.zulip/com.thelightphone.sdk.LightActivity
```
