# Zulip

A Zulip client for LightOS. It talks only to the Zulip server URL you enter, and that address must use HTTPS.

Signing in sends your email and password once so the server can return an API key. Later requests send that API key. Messages you write, and the list of people in the realm, also go to that server.

The API key stays on the phone, encrypted in the Android keystore. Recent messages and the realm directory are cached locally so the tool can open offline. There is no analytics and no push notification.
