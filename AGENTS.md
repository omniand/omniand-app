# OmniAnd contributor guide

## Project purpose

OmniAnd is an Android phone-hosted Web platform. The APK is simultaneously:

- a normal Android application with a shared Platform Home;
- an HTTP application server;
- a repository of packaged Web applications;
- a capability provider for Android services such as SMS.

The Android WebView and desktop browsers are clients of the same HTTP platform. The phone remains the source of truth.

## Core architectural rule

Android capabilities must be exposed to Web applications through standard HTTP APIs. Do not add `addJavascriptInterface`, custom JavaScript bridges, or direct Android calls from Web content.

Web applications should remain ordinary HTML, CSS, and JavaScript applications using standard primitives such as `fetch()`.

## Project layout

```text
app/src/main/
├── AndroidManifest.xml
├── java/dev/omniand/hub/
│   ├── MainActivity.kt
│   ├── WebAppActivity.kt
│   ├── permissions/
│   │   └── PermissionManager.kt
│   ├── server/
│   │   ├── CspBuilder.kt
│   │   ├── LocalOriginRouter.kt
│   │   └── PlatformServer.kt
│   ├── services/
│   │   └── SmsService.kt
│   └── webapps/
│       ├── WebAppInstaller.kt
│       └── WebAppRegistry.kt
│   └── wrappers/
│       └── WrapperInstaller.kt
```

```text
app/build/generated/embeddedWebAssets/
└── web/                          # copied from ../omniAndStore at build time
    ├── shell/
    └── apps/store/
```

The canonical Platform Home files live in `../omniAndStore/platform/shell/`. The built-in Store is
authored in `../omniAndStore/apps/store/`, but Gradle runs its shared Vite build and embeds only
`../omniAndStore/build/apps/store/`. Generated app output must never be edited directly. The HTTP
server reads and serves the generated assets; the WebView must not load them with `file://` URLs.

The `wrappers/template/` Gradle application module is the generic Android wrapper template. It must
not contain a prebuilt Web package or app-specific native logic. During installation the Platform
injects the validated catalog package under `assets/webapp/`, rewrites the manifest and icon, signs
the result with the Android-Keystore wrapper key, and commits it through `PackageInstaller`.

## HTTP origins and virtual hosts

The pjoject uses separate hostnames on one port as separate Web origins:

| Host | Application | Permissions |
|---|---|---|
| `phone.example.org` | Shared Platform Home | Platform management APIs |
| `messages.phone.example.org` | Messages | `sms.read` |
| `test.phone.example.org` | Permission test | None |
| `store.phone.example.org` | Store manager | `apps.install` |

Do not replace this with path-based isolation. Paths on one host and port share an origin and are not a security boundary.

`PlatformServer` treats a host whose leading label matches an installed app ID as that app origin; other hosts serve the Platform Home. A registered app ID is prepended to the current base host to form that app's origin. `PermissionManager` checks this identity before allowing protected APIs. Android WebViews keep canonical HTTPS origins while `LocalOriginRouter` serves them directly without DNS.

## Security expectations

- Generate CSP response headers in the Android server through `CspBuilder`; do not trust application-provided CSP as the security boundary.
- Keep Web apps inside sandboxed iframes.
- Do not enable popup or top-navigation iframe permissions without a specific requirement and review.
- Protected APIs must deny access by default.
- The unprivileged test app must continue receiving `403 Forbidden` from `/api/sms`.
- Do not add broad CORS access when a same-origin endpoint can be used.
- The current cleartext, unauthenticated LAN server is dev-only. Do not describe it as safe for untrusted networks.
- Preserve the separation between Android permission checks and Web-app capability checks; both are required.

## Adding a Web app

1. Create the application only under `../omniAndStore/apps/<app-id>/`; never add catalog apps to
   Platform assets. `apps/store/` is the special built-in Store and is excluded from catalog ZIPs.
2. Add a small `manifest.json`, `index.html`, JavaScript/JSX under `src/`, optional CSS, and a PNG icon referenced by `"icon": "icon.png"`.
3. Add the application to the Store catalog and regenerate its ZIP; installed packages are discovered from their manifests and the ID becomes the hostname label.
4. Ensure its permissions are understood by `WebAppInstaller` and its generated CSP is restrictive.
6. Grant only the capabilities the app requires.
7. Ensure its frontend uses relative same-origin API URLs such as `fetch("/api/sms")`.

Use the shared Vite/React/Vitest project in `../omniAndStore`; do not add a per-app package or lockfile.

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
node --check ../omniAndStore/apps/messages/app.js
node --check ../omniAndStore/apps/test/app.js
```

For the project, verify all of the following on an Android device or AVD:

1. OmniAnd is launchable from the user's normal Android launcher and is not registered as HOME.
2. The shared Platform Home lists only installed Web applications, not native Android applications.
3. Messages displays SMS after Android grants `READ_SMS`.
4. Messages reports a useful error when permission is denied.
5. The test app receives `403 Forbidden` from `/api/sms`.
6. With wildcard DNS and trusted TLS termination, desktop Firefox can use the same Platform Home and applications through their canonical hostnames.
7. With Android networking disabled, canonical HTTPS origins still load through local WebView routing.

## Scope control

Preserve the existing Store/package installation path, SMS read/send/modify support, incoming-message handling, notifications, and generic wrapper generation. Do not add calls, cloud synchronization, remote Internet access, JavaScript compatibility layers, WebRTC, WebSockets, MMS completion, or advanced package management unless explicitly requested. Catalog Web packages are installed only as assets in their generated wrappers; do not also store private file-backed copies.

OmniAnd is an actively developed, production-oriented platform. Describe limitations using their concrete security, deployment, feature, or validation status rather than applying a general maturity label.


Check and update the ./MEMORY.md file everytime.
