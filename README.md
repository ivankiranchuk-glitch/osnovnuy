# DirectLink

Encrypted peer-to-peer tunnel MVP built with Kotlin, Compose Multiplatform, and UDP hole punching.

## Current State

This repository currently contains a compact, build-oriented MVP scaffold:

- `shared`: Kotlin Multiplatform shared model, peer facade, encrypted MVP DLP packet serializer, STUN/NAT detector, UDP hole-punching manager, encrypted UDP tunnel session for text and file frames, relay protocol handshake model, TCP relay transport, and Compose UI.
- `desktop`: Compose Desktop application entrypoint plus a standalone relay server entrypoint.
- `android`: Android application entrypoint and manifest.
- GitHub Actions CI runs Gradle project checks, shared desktop tests, desktop compilation, and Android debug APK assembly through the Gradle Wrapper.

The DLP packet layer now has an explicit model, encrypted container format, password validation through AEAD authentication, TTL validation, peer public endpoint fields, and unit tests. The current MVP container uses PBKDF2-HMAC-SHA256 plus AES-GCM because those primitives are available from the JDK on desktop and Android. The next production hardening step is to switch the packet KDF/cipher suite to the intended Argon2id + ChaCha20-Poly1305 implementation.

Networking now has a typed NAT detection contract, local IP lookup, UDP/TCP port selection, an RFC 5389-style STUN Binding Request client/parser for `MAPPED-ADDRESS` and `XOR-MAPPED-ADDRESS`, an initial UDP hole-punching flow, and a UDP tunnel session for text and chunked file frames. The peer facade stores imported DLP endpoint details, attempts a direct UDP punch, keeps the socket open after a successful punch, sends text through the tunnel session, and can frame outgoing files with SHA-256 verification on receive. File chunks are acknowledged by the receiver, missing chunks are retried before the final file frame is sent, and UDP tunnel frames are encrypted with an AES-GCM key derived from the shared DLP password. Outgoing file transfers can be cancelled while they are waiting for acknowledgements.

When direct punching fails and `relayUrl` is configured, the peer moves into an explicit `RelayRequired` phase instead of a generic error. The shared relay module defines JSON relay frames, client handshake state, a minimal in-memory relay coordinator, and a TCP line-delimited JSON relay transport for local register, join, payload routing, close, and error tests. The desktop module can run this relay transport as a standalone local server process, and the shared peer facade can connect to that server as either the relay host or relay guest. Text messages and file payloads can be routed through the established relay session using the same encrypted tunnel frame format as the UDP path.

The shared Compose UI can initialize networking, create and import `.dlp` packets, connect to a peer, send text, pick a file for sending, and show a recent activity log for generated packets, incoming text, incoming files, send failures, and connection loss. It also surfaces sending and receiving progress for recent file transfers, with a control for cancelling active transfers. Relay-required connection attempts are shown as a dedicated state with the configured relay URL. The UI also includes relay URL/session fields plus Host relay and Join relay actions for opening a TCP relay session. Relay text and relay file sending are wired through the same send controls. After hosting a relay session, the UI copies the generated session id into the Relay session field for easier manual testing.

## Build

The repository includes Gradle Wrapper files, so a local Gradle installation is no longer required. JDK 17 is still required.

Windows PowerShell commands from the repository root:

```powershell
.\gradlew.bat projects
.\gradlew.bat :shared:desktopTest
.\gradlew.bat :shared:relaySmokeTest
.\gradlew.bat :desktop:compileKotlinJvm
.\gradlew.bat :android:assembleDebug
```

macOS/Linux commands from the repository root:

```bash
./gradlew projects
./gradlew :shared:desktopTest
./gradlew :shared:relaySmokeTest
./gradlew :desktop:compileKotlinJvm
./gradlew :android:assembleDebug
```

For a local Windows Android build, install Android Studio and let the first-run wizard install the SDK. Then create `local.properties` in the repository root:

```powershell
Set-Content local.properties "sdk.dir=$($env:LOCALAPPDATA.Replace('\\','/'))/Android/Sdk"
.\gradlew.bat :android:assembleDebug
```

Desktop run:

```powershell
.\gradlew.bat :desktop:run
```

Relay server run:

```powershell
.\gradlew.bat :desktop:runRelayServer
.\gradlew.bat :desktop:runRelayServer -PrelayPort=48888
```

Relay smoke test without opening app windows:

```powershell
.\gradlew.bat :shared:relaySmokeTest
```

This focused test starts an in-process relay server, creates two peers, imports each peer's `.dlp` packet, connects host and guest through relay, sends encrypted text, sends encrypted files, and verifies a bidirectional mixed payload flow.

## Local Relay Workflow

Use the relay server when direct UDP punching fails or when you want to test the fallback path locally. Relay still needs both app sessions to import each other's `.dlp` packet first, because the packet provides the remote peer id and the shared password used for encrypted payload routing.

1. Start the relay server in one terminal and leave it running:

```powershell
.\gradlew.bat :desktop:runRelayServer
```

2. Start two desktop app windows in two other terminals:

```powershell
.\gradlew.bat :desktop:run
```

3. In both app windows, use the same packet password, for example `directlink`.
4. In both app windows, keep the relay URL as `tcp://127.0.0.1:47777`.
5. In window A, click Create .dlp and copy the generated packet path from Activity.
6. In window B, paste that path into DLP file path and click Import.
7. In window B, click Create .dlp and copy the generated packet path from Activity.
8. In window A, paste that path into DLP file path and click Import.
9. In window A, click Host relay. The generated relay session id appears in the Relay session field and in the relay connected card.
10. Copy that relay session id into window B's Relay session field.
11. In window B, click Join relay.
12. After both sides show relay connected, text messages and files can be sent through the relay session.

Direct UDP remains the preferred path when hole punching succeeds; relay is the fallback path when direct connectivity is unavailable.

## Next Milestones

1. Run two-device/manual relay validation for text and file transfer.
2. Upgrade encrypted DLP packet storage to Argon2id + ChaCha20-Poly1305.
3. Add stronger NAT classification around the UDP punch flow.
4. Add broader end-to-end two-peer integration coverage for direct and relay workflows.
5. Improve desktop and Android file picker/share integration around real device workflows.
