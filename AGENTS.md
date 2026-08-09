# OmniAnd contributor guide

## Project purpose

OmniAnd is an Android proof of concept for a phone-hosted Web platform. The APK is simultaneously:

- an Android HOME launcher;
- an HTTP application server;
- a repository of packaged Web applications;
- a capability provider for Android services such as installed apps and SMS.

The Android WebView and desktop browsers are clients of the same HTTP platform. The phone remains the source of truth.

## Core architectural rule

Android capabilities must be exposed to Web applications through standard HTTP APIs. Do not add `addJavascriptInterface`, custom JavaScript bridges, or direct Android calls from Web content.

Web applications should remain ordinary HTML, CSS, and JavaScript applications using standard primitives such as `fetch()`.

## Project layout

```text
app/src/main/
├── AndroidManifest.xml
├── java/dev/omniand/launcher/
│   ├── MainActivity.kt
│   ├── permissions/
│   │   └── PermissionManager.kt
│   ├── server/
│   │   ├── CspBuilder.kt
│   │   └── PlatformServer.kt
│   ├── services/
│   │   ├── AndroidAppsService.kt
│   │   └── SmsService.kt
│   └── webapps/
│       └── WebAppRegistry.kt
└── assets/web/
    ├── shell/
    │   ├── index.html
    │   ├── app.js
    │   └── style.css
    └── apps/
        ├── messages/
        └── test/
```

The Web files live under Android assets so Gradle packages them into the APK. The HTTP server reads and serves those assets; the WebView must not load them with `file://` URLs.

## HTTP origins and ports

The POC uses separate ports as separate Web origins:

| Port | Application | Permissions |
|---|---|---|
| `8080` | Launcher shell | Launcher APIs |
| `8081` | Messages | `sms.read` |
| `8082` | Permission test | None |

Do not replace this with path-based isolation. Paths on one host and port share an origin and are not a security boundary.

`PlatformServer` maps the listener port to an application identity. `PermissionManager` checks that identity before allowing protected APIs. Keep routing structured so host-based origins can replace port-based origins later.

## Security expectations

- Generate CSP response headers in the Android server through `CspBuilder`; do not trust application-provided CSP as the security boundary.
- Keep Web apps inside sandboxed iframes.
- Do not enable popup or top-navigation iframe permissions without a specific requirement and review.
- Protected APIs must deny access by default.
- The unprivileged test app must continue receiving `403 Forbidden` from `/api/sms`.
- Do not add broad CORS access when a same-origin endpoint can be used.
- The current cleartext, unauthenticated LAN server is POC-only. Do not describe it as safe for untrusted networks.
- Preserve the separation between Android permission checks and Web-app capability checks; both are required.

## Adding a Web app

1. Create a directory under `app/src/main/assets/web/apps/<app-id>/`.
2. Add a small `manifest.json`, `index.html`, JavaScript, and optional CSS.
3. Register the app and a unique port in `WebAppRegistry.kt`.
4. Add its port to the listeners in `PlatformServer.kt`.
5. Add the origin to the launcher CSP in `CspBuilder.kt`.
6. Grant only the capabilities the app requires.
7. Ensure its frontend uses relative same-origin API URLs such as `fetch("/api/sms")`.

Avoid frontend frameworks or build pipelines unless they materially simplify a requested feature. The current apps should run directly from their source assets.

## Android development

The project uses Kotlin, Android SDK 35, and Java 17-compatible bytecode. Java 21 is suitable for running the Android build tools; avoid using Java 25 with the current Gradle and Android Gradle Plugin versions.

Build from the project root:

```sh
gradle assembleDebug
```

Install on the active device or AVD:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

When suggesting an interactive editor, use `vim`, not `nano`.

## Validation

For Web changes, check JavaScript and manifests:

```sh
node --check app/src/main/assets/web/shell/app.js
node --check app/src/main/assets/web/apps/messages/app.js
node --check app/src/main/assets/web/apps/test/app.js
```

For the full POC, verify all of the following on an Android device or AVD:

1. OmniAnd is selectable as the HOME launcher.
2. The launcher lists Android and Web applications.
3. An Android application can be launched through its HTTP endpoint.
4. Messages displays SMS after Android grants `READ_SMS`.
5. Messages reports a useful error when permission is denied.
6. The test app receives `403 Forbidden` from `/api/sms`.
7. With ports 8080–8082 forwarded or reachable over the LAN, desktop Firefox can use the same launcher and Messages app.

Useful ADB forwarding for an emulator:

```sh
adb forward tcp:8080 tcp:8080
adb forward tcp:8081 tcp:8081
adb forward tcp:8082 tcp:8082
```

## Scope control

This repository is intentionally a POC. Do not add an app store, SMS sending, calls, cloud synchronization, remote Internet access, JavaScript compatibility layers, WebRTC, WebSockets, or advanced package management unless explicitly requested.
