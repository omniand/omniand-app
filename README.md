# OmniAnd POC

OmniAnd is an Android HOME launcher whose interface and applications are ordinary web apps served by the phone. Android supplies capabilities behind same-origin HTTP endpoints; web content has no JavaScript bridge.

## Architecture

The APK starts three HTTP listeners bound to all network interfaces:

| Port | Origin role | Capabilities |
|---|---|---|
| `8080` | Launcher shell and Android-app API | Lists and launches Android apps |
| `8081` | Messages web app | `sms.read` |
| `8082` | Permission test web app | None |

The port is the trusted app identity for this POC. Consequently, `/api/sms` returns data on port 8081 and `403 Forbidden` on port 8082. This routing boundary can later resolve virtual hosts instead, without changing the apps or permission manager. Every static response receives a server-generated CSP header.

## Build and install

Requirements: JDK 17, Android SDK 35, and Gradle 8.9 (or use the generated Gradle wrapper once available).

```sh
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

After installation, press Home and select **OmniAnd**. Android asks for SMS read permission on first launch. A denial is safe: Messages displays the API error and can be retried after granting permission in Android settings.

Android 11+ package visibility is enabled with `QUERY_ALL_PACKAGES` because enumerating a launcher is central to this proof of concept. A production/store release should replace it with a policy-compliant visibility strategy.

## Desktop test

Keep the phone and desktop on the same trusted LAN and find the phone's Wi-Fi address. In desktop Firefox, open:

```text
http://PHONE_IP:8080/
```

Open **Messages** to read the phone's latest 100 SMS messages. Open **Permission test** to see the automatic `403 Forbidden` isolation check pass. An Android app button asks the phone to launch that app, even when the click comes from the desktop shell.

For an ADB-only development connection, forward all origins:

```sh
adb forward tcp:8080 tcp:8080
adb forward tcp:8081 tcp:8081
adb forward tcp:8082 tcp:8082
```

## Security scope

This is intentionally a LAN POC using cleartext HTTP. Anyone able to reach port 8081 can read SMS after Android grants the APK permission; there is no user authentication or TLS yet. Do not expose these ports to an untrusted network. The origin/capability split, `PermissionManager`, and centralized CSP builder are the extension points for authenticated HttpOnly sessions, hostname routing, and HTTPS.
