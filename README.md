# OmniAnd POC

OmniAnd is an Android HOME launcher whose interface and applications are ordinary web apps served by the phone. Android supplies capabilities behind same-origin HTTP endpoints; web content has no JavaScript bridge.

## Architecture

The APK starts one HTTP listener on port 8080, bound to all network interfaces. Virtual hosts provide separate Web origins:

| Host | Origin role | Capabilities |
|---|---|---|
| `localhost:8080` | Launcher shell and Android-app API | Lists and launches Android apps |
| `messages.localhost:8080` | Messages web app | `sms.read` |
| `test.localhost:8080` | Permission test web app | None |

The HTTP `Host` label is the app identity for this POC. Consequently, `/api/sms` returns data on the Messages origin and `403 Forbidden` on the test origin. Every static response receives a server-generated CSP header.

## Build and install

Requirements: JDK 17 or 21 and Android SDK 35. The repository includes the Gradle 8.9 wrapper.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

After installation, press Home and select **OmniAnd**. Android asks for SMS read permission on first launch. A denial is safe: Messages displays the API error and can be retried after granting permission in Android settings.

For development, install `inotify-tools`, then start the auto-deployer with one authorized device or emulator connected:

```sh
./scripts/autodeploy.sh
```

It performs an initial debug build and install, launches OmniAnd, then uses `inotifywait` to watch the Android sources, Web assets, and Gradle configuration without polling. Each change triggers a new build and `adb install -r`. If several devices are connected, select one first with `ANDROID_SERIAL=<serial>`.

Android 11+ package visibility is enabled with `QUERY_ALL_PACKAGES` because enumerating a launcher is central to this proof of concept. A production/store release should replace it with a policy-compliant visibility strategy.

## Desktop test

Keep the phone and desktop on the same trusted LAN and find the phone's Wi-Fi address. In desktop Firefox, open:

```text
http://omniand:8080/
```

First add all three names to the desktop's `/etc/hosts`, replacing the example address with the phone's LAN IP:

```text
192.168.1.42 omniand messages.omniand test.omniand
```

Then open `http://omniand:8080/`. Open **Messages** to read the phone's latest 100 SMS messages. Open **Permission test** to see the automatic `403 Forbidden` isolation check pass. Native Android apps are only listed inside the phone's OmniAnd launcher.

For an ADB-only development connection, forward the single server port:

```sh
adb forward tcp:8080 tcp:8080
```

The reserved `localhost` and `*.localhost` names resolve to the development machine automatically, so no hosts-file entry is needed with ADB forwarding. Open `http://localhost:8080/`.

## Security scope

This is intentionally a LAN POC using cleartext HTTP. A client able to reach the server and use the Messages host can read SMS after Android grants the APK permission; there is no user authentication or TLS yet. Do not expose port 8080 to an untrusted network. The origin/capability split, `PermissionManager`, and centralized CSP builder are the extension points for authenticated HttpOnly sessions and HTTPS.
