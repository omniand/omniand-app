# OmniAnd

OmniAnd is a normal Android application that hosts a Web application platform. It does not replace Android's HOME launcher. Android supplies capabilities behind same-origin HTTP APIs; Web content uses standard browser APIs and has no JavaScript/native bridge.

The current implementation status and remaining engineering work are tracked in [`STATUS.md`](STATUS.md).

## Architecture

Desktop clients use durable link-derived origins such as
`https://platform-<publicLinkId>.phone.example.org/` and
`https://messages-<publicLinkId>.phone.example.org/`. OmniAnd-created Android WebViews instead use
`http://localhost:8080/` for Home and `http://<app-id>.localhost:8080/` for applications.

Android WebViews reach Ktor over the loopback socket; there is no request interception or native
JavaScript bridge. Before navigation, OmniAnd starts the server and installs an HTTP-only,
same-site, exact-host session cookie derived from a process-lifetime secret. The server accepts a
`.localhost` request only from a loopback socket, on port 8080, for Home or an installed app host,
and with that host's credential. Unsafe API methods additionally require the exact matching
`Origin`. Restarting OmniAnd invalidates old credentials.

Ktor registers media, contacts, SMS/MMS, application-management, SSE, binary-resource,
and static-asset routes explicitly. Mutations use ordinary JSON or raw JPEG request bodies. Gallery
and Messages send a single `multipart/form-data` request per upload; the server streams each file to
bounded temporary storage, verifies its SHA-256 digest, and removes temporary data on every outcome.
Gallery accepts files through 500 MiB, while one staged MMS attachment is limited to 10 MiB.

For remote desktop access, wildcard DNS points the configured canonical hostname and its subdomains
at the separately deployed [OmniAndRelay](../omniAndRelay/README.md) edge. An opted-in Android
foreground service opens one outbound WebSocket tunnel whose multiplexed streams connect to the
existing loopback HTTP server. The relay authorizes a bounded first header block and preserves its
bytes and `Host` through the byte-transparent tunnel.

The relay-owned `connect.<base>` portal creates a short-lived QR request. Settings launches a native
 CameraX/ML Kit scanner; a first scan atomically enrolls the phone and links the browser, while later
scans use the encrypted existing device credential. Persistent browser/link records keep one stable
public link ID across restarts. The portal installs separate Secure, HttpOnly, SameSite=Lax host-only
sessions through one-minute single-use tickets. Phone-only setup and package-management operations
remain unavailable to linked desktops.

| Stable host | Role | Capabilities |
|---|---|---|
| `platform-<publicLinkId>.phone.example.org` | Platform Home | Lists Web apps |
| `messages-<publicLinkId>.phone.example.org` | Messages | `sms.read`, `sms.send`, `sms.modify` |
| `test-<publicLinkId>.phone.example.org` | Permission test | None |

The authenticated Platform Home Web source lives in `../omniAndStore/apps/shell/`. It is the
embedded React/shadcn Hub Shell, with Apps for all authenticated clients and Android-only Discover
catalog routes. Shell is not a catalog application: it has no manifest, package, wrapper, registry
identity, or separate hostname. The Android build produces and embeds its relative-asset bundle.
Messages, OmniAnd Test, and every other installable application appear in Apps only after
catalog installation.

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

The wrapper notification relay version 2 lets any installed app publish and cancel its own bounded
notifications after authenticating the OmniAnd caller and matching the requested app ID. Notification
clicks enter through the wrapper launcher before opening the app's authenticated WebView. Platform-owned
notifications remain the fallback when a wrapper relay is unavailable or notifications are denied.

## Build and install

Requirements are JDK 17 or 21 and Android SDK 35.

The canonical desktop domain defaults to `phone.example.org` only before enrollment and for legacy
development routes. A scanned HTTPS QR supplies the connect origin; the relay response supplies the
base host and authenticated tunnel URL. OmniAnd validates and persists that configuration, allowing
the same APK to enroll against independently hosted domains. The Gradle
`omniandPlatformHost`/`OMNIAND_PLATFORM_HOST` and `omniandRelayUrl`/`OMNIAND_RELAY_URL` settings
remain useful as pre-enrollment and debug fallbacks.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **OmniAnd** from the user's normal Android launcher. Installing an application that declares SMS capabilities starts or defers the required Android role and permission setup. Declining setup leaves the Web application installed, and Messages reports actionable API errors when a required permission or role is missing.

The catalog root is configured by Gradle property `omniandCatalogUrl` or environment variable
`OMNIAND_CATALOG_URL`; it retains the trusted-LAN development default when neither is set. The URL
must be absolute and end in `/`. Production-like deployments must use trusted HTTPS, for example
`https://catalog.phone.example.net/`.

## Relay integration

The Rust service, Caddy/Compose deployment, protocol documentation, and server tests live in the
sibling [`../omniAndRelay`](../omniAndRelay/README.md) repository. Production enrollment discovers
the selected instance from its QR and response rather than requiring those values at build time.

Before enrollment, the fallback relay URL comes from Gradle property `omniandRelayUrl` or environment variable
`OMNIAND_RELAY_URL`; it defaults to
`wss://relay.<platform-host>/_omniand/tunnel/v1`. Release builds reject cleartext. Debug builds may
use `ws://10.0.2.2:18082/_omniand/tunnel/v1` for an emulator. Enable **Background hosting** only
after installing the build. The foreground service starts the Platform server first, then reconnects
with full-jitter exponential backoff and Android network wakeups. Disabling the setting closes the tunnel and all relay-created loopback
sockets immediately.

Debug emulator builds reroute non-`localhost` DNS names that resolve exclusively to loopback through
the emulator host at `10.0.2.2`, while retaining the original hostname for TLS verification. For a
local Caddy certificate signed by `mkcert`, set `OMNIAND_DEBUG_CA_CERT` to its public root CA PEM.
Gradle embeds that CA only in the generated debug resources; release builds never include it and no
certificate needs to be installed into Android's system or user trust store.

The credential is encrypted with Android Keystore AES-GCM. Tunnel upgrades send it as a bearer token
plus the persistent device ID; the protocol-v1 HELLO must contain the same ID. Scanning a different
relay origin bootstraps that instance and replaces the active enrollment. Credential rejection
returns the phone to the unenrolled state and requires another QR bootstrap.

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
npm --prefix ../omniAndStore run check
./gradlew spotlessCheck :app:testDebugUnitTest assembleDebug
```

On API 26 and API 35 devices, verify the launcher starts OmniAnd, each mobile URL uses the expected
`.localhost:8080` authority, and `window.isSecureContext` is true. Confirm Messages works when
Android grants the required role and permissions, while Permission test receives `403 Forbidden`.
Verify Shell Discover catalog operations, multipart uploads and cancellation, file selection, and multiple events over one SSE
connection. Disable Wi-Fi and mobile data and confirm loopback operation continues. Raw, cross-host,
alternate-port, and non-loopback `.localhost` requests must receive `401`. For desktop testing,
configure wildcard DNS and trusted TLS termination, enable background hosting, and confirm exactly
one outbound tunnel connects. Open `connect.<base>` and scan its QR in phone Settings. Confirm copied
stable URLs return `401` without their host-only cookie; Platform and app hosts work afterward;
GET, large POST/upload, multi-megabyte response, at least 20 concurrent requests, and SSE survive the
tunnel; denial never creates a session; and disabling hosting immediately removes remote access
without breaking loopback WebViews. OmniAnd Test's Permission Isolation checks must still receive
`403` from `/api/sms`.

For wrapper validation, install an app from Shell's Discover tab, approve Android's package-installer flow, and verify that a launcher entry appears and that its Web files work offline through the canonical origin. Installing a newer catalog package should atomically update the same Android package and retain browser origin data.

## Security scope

Authenticated phone-client identity, exact host identity, `PermissionManager`, Android runtime
permission checks, and server-generated CSP are all required. CSP includes `form-action 'self'`,
CORS remains denied by default, and third-party WebView cookies are disabled. Desktop traffic
requires a relay-issued, host-only signed link session; Host identity alone never authorizes it.
Generated wrappers are signed, but downloaded Web packages are not yet cryptographically verified.
There is no embedded LAN TLS; keep port 8080 on a trusted development network only. The external
Connected-browser management, revocation, and immediate termination of already active revoked
streams remain deferred.

## Camera streaming (Phase 8)

The Camera 0.2.5 catalog app requests a paired desktop view through a desktop-only, versioned and
bounded signaling WebSocket. The phone exposes pending requests only to its local Camera origin;
approval is explicit, notification-first, expires after 60 seconds, and starts a separate
camera/microphone foreground service with a persistent Stop action. The service is never restored
from boot and tears down capture, media, timers and signaling on every terminal path.

CameraX owns `ImageAnalysis` capture with keep-latest backpressure, a 1280×720/30 fps ceiling and
stride/crop/rotation-aware I420 conversion. A physical-orientation listener updates the analysis
target while streaming so desktop video follows portrait and landscape changes. WebRTC attaches
audio/video to the transceivers created by the browser offer, reports actual
camera/torch/zoom/microphone capabilities, and validates every
remote control without stopping a healthy stream for a rejected control. TURN credentials are
opaque, link-owned and renewable; renewal reconfigures the existing peer and performs make-before-
break ICE restart. Direct ICE remains preferred outside the explicit debug emulator relay profile,
and media never enters v1 tunnel DATA frames. Goldfish/ranchu emulator cameras do not expose zoom
controls because their HAL advertises zoom ratios but rejects the corresponding capture requests.
