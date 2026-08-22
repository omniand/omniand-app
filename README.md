# OmniAnd

OmniAnd is a normal Android application that hosts a Web application platform. It does not replace Android's HOME launcher. Android supplies capabilities behind same-origin HTTP APIs; Web content uses standard browser APIs and has no JavaScript/native bridge.

The current implementation status and remaining engineering work are tracked in [`STATUS.md`](STATUS.md).

## Architecture

Desktop clients retain the canonical Platform Home at `https://phone.example.org/` and distinct app
origins such as `https://messages.phone.example.org/`. OmniAnd-created Android WebViews instead use
`http://localhost:8080/` for Home and `http://<app-id>.localhost:8080/` for applications.

Android WebViews reach Ktor over the loopback socket; there is no request interception or native
JavaScript bridge. Before navigation, OmniAnd starts the server and installs an HTTP-only,
same-site, exact-host session cookie derived from a process-lifetime secret. The server accepts a
`.localhost` request only from a loopback socket, on port 8080, for Home or an installed app host,
and with that host's credential. Unsafe API methods additionally require the exact matching
`Origin`. Restarting OmniAnd invalidates old credentials.

Ktor registers pairing, media, contacts, SMS/MMS, application-management, SSE, binary-resource,
and static-asset routes explicitly. Mutations use ordinary JSON or raw JPEG request bodies. Gallery
and Messages send a single `multipart/form-data` request per upload; the server streams each file to
bounded temporary storage, verifies its SHA-256 digest, and removes temporary data on every outcome.
Gallery accepts files through 500 MiB, while one staged MMS attachment is limited to 10 MiB.

For desktop access, wildcard DNS must point the configured canonical hostname and its subdomains at
the phone. The embedded server listens on cleartext HTTP port 8080, so a trusted TLS reverse proxy
must terminate HTTPS and preserve the original `Host` header. The cleartext listener must not be
exposed to an untrusted network.

An unknown desktop receives a pairing page. Requesting access displays an Android notification, or
an approval dialog directly when OmniAnd is foregrounded. The phone owner reviews the socket peer
and browser description and explicitly allows or denies it. Approval installs an HttpOnly, Secure,
SameSite session cookie for the configured base domain; it authenticates that browser across the
Platform and app subdomains only until the browser session or OmniAnd process ends. Pairing requests
expire after two minutes and are rate-limited. Phone-only setup and package-management operations
remain unavailable to paired desktops.

| Canonical host | Role | Capabilities |
|---|---|---|
| `phone.example.org` | Shared Platform Home and management APIs | Lists and manages Web apps |
| `messages.phone.example.org` | Messages | `sms.read`, `sms.send`, `sms.modify` |
| `test.phone.example.org` | Permission test | None |

The canonical Platform Home Web source lives in `../omniAndStore/platform/shell/`. Its Installed
view is available to Android and paired desktop browsers; its Android-only Available view lists and
installs the external catalog. The Android build embeds only this Shell. Messages, Permission Test,
and every other application live in `../omniAndStore/` and appear in Installed only after catalog
installation.

Every catalog Web app is installed as an Android wrapper APK. OmniAnd freshly validates the static
catalog, resolves the selected package server-side, validates its ZIP,
injects it under `assets/webapp/`, assigns the stable package name derived from the app ID, signs the
APK on the phone, and starts Android's confirmed installer. The wrapper launcher delegates to
OmniAnd's generic `WebAppActivity`; OmniAnd serves the wrapper's assets over the canonical app HTTP
origin and remains the capability provider. Existing installed packages may still navigate legacy
canonical Messages links; OmniAnd opens those in the authenticated Messages WebView.

Messages uses the capability-gated `GET /api/sms/events` endpoint for live invalidations. A client with `sms.read` receives `text/event-stream` `sms-change` events after incoming persistence, final outgoing delivery changes, and successful read/unread mutations, then reloads authoritative SMS resources. Read events identify either `messageId` or `threadId`. Events are process-local and are not replayed. TLS reverse proxies must preserve the `Host` header, disable response buffering and compression/transformation for this endpoint, keep streaming connections open, and use idle timeouts longer than the 15-second heartbeat interval.

SMS histories are paged at the provider boundary. `GET /api/sms/threads?offset=0&limit=30`
returns `{ "threads": [...], "nextOffset": 30 }`; conversation messages use
`GET /api/sms/threads/{id}/messages?offset=0&limit=50` and return the corresponding `messages`
envelope. Limits must be between 1 and 100, offsets must be non-negative integers, and
`nextOffset` is `null` after the last page. Thread pages are newest-first. Each message page is
selected newest-first and returned chronologically so older pages can be prepended. For legacy
clients only, omitting both pagination parameters preserves the array response shape while capping
the result to the newest 100 records.

`wrappers/template/` is compiled and embedded as a generic APK template. OmniAnd rewrites its binary manifest for the selected application, injects all validated Web files and the PNG referenced by the app manifest's `icon` field, and signs the result using a stable RSA key generated inside Android Keystore. The Platform Home discovers only wrappers with that signature and a consistent embedded manifest. Removing OmniAnd also removes the signing key, so previously installed wrappers must be uninstalled before wrappers generated by a fresh OmniAnd installation can replace them.

## Build and install

Requirements are JDK 17 or 21 and Android SDK 35.

The canonical desktop domain defaults to `phone.example.org`. Configure it at build time with
either `-PomniandPlatformHost=phone.your-domain.example` or the
`OMNIAND_PLATFORM_HOST=phone.your-domain.example` environment variable. The value must be a DNS
hostname with at least two labels; configure the same base and wildcard names in DNS and TLS.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **OmniAnd** from the user's normal Android launcher. Installing an application that declares SMS capabilities starts or defers the required Android role and permission setup. Declining setup leaves the Web application installed, and Messages reports actionable API errors when a required permission or role is missing.

The development catalog URL is configured with `CATALOG_URL` in `app/build.gradle.kts`. Its current HTTP value is intended only for trusted local development; production-like deployments should use HTTPS.

## Desktop development proxy

The repository includes a variable-driven Caddy proxy in [`compose.yaml`](compose.yaml). Its
Caddyfile and example environment contain no private material; `.env` and `.tls/` are ignored.

Create the local configuration and export the same values for Gradle:

```sh
cp .env.example .env
set -a
. ./.env
set +a
```

Generate and trust a development certificate on the desktop:

```sh
mkcert -install
mkdir -p "$OMNIAND_CERT_DIR"
mkcert \
  -cert-file "$OMNIAND_CERT_DIR/$OMNIAND_CERT_FILE" \
  -key-file "$OMNIAND_CERT_DIR/$OMNIAND_KEY_FILE" \
  "$OMNIAND_PLATFORM_HOST" \
  "*.$OMNIAND_PLATFORM_HOST"
chmod 600 "$OMNIAND_CERT_DIR/$OMNIAND_KEY_FILE"
```

Build with that domain, forward an emulator, and start the proxy:

```sh
./gradlew assembleDebug
adb -s emulator-5554 forward tcp:18080 tcp:8080
docker compose up -d
```

`OMNIAND_BACKEND` defaults to the host's ADB-forwarded `127.0.0.1:18080`. Set it to a reachable
phone address such as `192.0.2.10:8080` when using a physical device. The Compose service uses host
networking, so do not add published ports. Inspect or stop it with:

```sh
docker compose logs -f desktop-proxy
docker compose down
```

## Formatting

Spotless pins the repository formatters and applies the shared UTF-8, LF, final-newline, and
100-column conventions. Kotlin and Gradle Kotlin scripts use ktfmt's Kotlin style, and wrapper Java
uses google-java-format's AOSP style. Web sources use Prettier 3.9.6 in `../omniAndStore` before
Gradle copies them into generated assets.

```sh
./gradlew spotlessApply
./gradlew spotlessCheck
```

`spotlessCheck` is also part of each Android module's normal `check` lifecycle. Generated build
outputs and the generated wrapper APK assets are outside the formatter targets.

## Validation

```sh
node --check ../omniAndStore/platform/shell/app.js
./gradlew spotlessCheck :app:testDebugUnitTest assembleDebug
```

On API 26 and API 35 devices, verify the launcher starts OmniAnd, each mobile URL uses the expected
`.localhost:8080` authority, and `window.isSecureContext` is true. Confirm Messages works when
Android grants the required role and permissions, while Permission test receives `403 Forbidden`.
Verify Platform Home catalog operations, multipart uploads and cancellation, file selection, and multiple events over one SSE
connection. Disable Wi-Fi and mobile data and confirm loopback operation continues. Raw, cross-host,
alternate-port, and non-loopback `.localhost` requests must receive `401`. For desktop testing,
configure wildcard DNS and trusted TLS termination, open the configured canonical Home, request
access, and approve it on the phone. Confirm APIs return `401` before approval, Platform and app
subdomains work afterward, denial never creates a session, and stopping OmniAnd requires pairing
again.

For wrapper validation, install an app from Platform Home's Available view, approve Android's package-installer flow, and verify that a launcher entry appears and that its Web files work offline through the canonical origin. Installing a newer catalog package should atomically update the same Android package and retain browser origin data.

## Security scope

Authenticated phone-client identity, exact host identity, `PermissionManager`, Android runtime
permission checks, and server-generated CSP are all required. CSP includes `form-action 'self'`,
CORS remains denied by default, and third-party WebView cookies are disabled. Desktop traffic
requires a separately approved, process-lifetime session; Host identity alone never authorizes it.
Generated wrappers are signed, but downloaded Web packages are not yet cryptographically verified.
There is no embedded LAN TLS; keep port 8080 on a trusted development network only.
