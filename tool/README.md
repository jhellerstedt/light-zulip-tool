# Chat for Zulip (unofficial)

An unofficial Zulip client for LightOS. It is not affiliated with Zulip, Kandra Labs, or The Light Phone.

It talks only to the Zulip server URL you enter, and that address must use HTTPS.

Signing in sends your email and password once so the server can return an API key. Later requests send that API key. Messages you write, and the list of people in the realm, also go to that server.

The API key stays on the phone, encrypted in the Android keystore. Recent messages and the realm directory are cached locally so the tool can open offline. There is no analytics and no push notification.

## Images and files

Received attachments show as names: `Photo: name` for images and `File: name` for other links. The tool does not read the photo album, and it does not request media permissions.

Sending a file already works once a local file exists. The compose screen takes that file through `replaceAttachments`, the outbox uploads it, and the message is sent as a Zulip link. What is waiting is however Light lets a tool obtain a file. When that exists, copy the file into a `ComposeAttachmentDraft` and call `replaceAttachments`. Richer display of a received image stays inside `presentMessageContent`.

```bash
./gradlew :tool:installDebug
adb shell am start -n com.jhellerstedt.zulip/com.thelightphone.sdk.LightActivity
```
