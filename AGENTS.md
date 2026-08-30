# omniAndApp contributor guide

Read the workspace guide at [`../AGENTS.md`](../AGENTS.md) for project-wide architecture and
repository boundaries. This repository is the Android platform repository; the Web source and
catalog are in the sibling [`../omniAndStore`](../omniAndStore), and the remote edge is in
[`../omniAndRelay`](../omniAndRelay).

## Responsibilities and invariants

- Own the Android APK, Ktor/CIO HTTP server, WebView navigation, Android permissions/roles,
  SMS/MMS, Contacts, MediaStore, Files, application management, and generic wrapper generation.
- Expose Android capabilities only through standard same-origin HTTP APIs. Do not add
  `addJavascriptInterface`, request interception, or native calls from Web content.
- Keep app isolation on separate hostnames, with server-generated CSP and independent Android and
  Web capability checks. Protected APIs deny by default; OmniAnd Test's Permission Isolation checks
  must receive `403` from `/api/sms`.
- The Shell is embedded from `../omniAndStore/apps/shell/`; the public pairing portal belongs to the
  relay. Do not add catalog apps or pairing assets to Platform assets.
- `wrappers/template/` is generic. Install validates a catalog package, injects it under
  `assets/webapp/`, rewrites the manifest/icon, signs it with the Android Keystore wrapper key,
  and installs it through `PackageInstaller`.

Android WebViews use authenticated loopback HTTP with exact `.localhost:8080` hosts; desktop uses
stable link-derived HTTPS hosts. Do not replace host isolation with paths or weaken Origin/session
checks. The cleartext LAN listener is development-only.

## Development and validation

Requirements are Android SDK 35 and JDK 17 or 21. Use the repository wrapper, not a system Gradle:

```sh
./gradlew spotlessApply
./gradlew spotlessCheck :app:testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`./scripts/autodeploy.sh` automates the usual emulator cycle. When Web sources or embedded assets
change, read `../omniAndStore/AGENTS.md` and run its relevant checks, normally:

```sh
npm --prefix ../omniAndStore run check
```

For the complete remote path, configure and run `../omniAndRelay`, export matching
`OMNIAND_PLATFORM_HOST` and `OMNIAND_RELAY_URL`, and validate pairing, app access, uploads,
concurrency, SSE, shutdown, and protected-API denial.

Use `vim`, not `nano`, for interactive editing. Check the workspace `../MEMORY.md` before work;
update durable context only when the change makes it necessary.
