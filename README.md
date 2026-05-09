# DirectLink

Encrypted peer-to-peer tunnel MVP built with Kotlin, Compose Multiplatform, and UDP hole punching.

## Current State

This repository currently contains a compact, build-oriented MVP scaffold:

- `shared`: Kotlin Multiplatform shared model, peer facade, encrypted MVP DLP packet serializer, STUN/NAT detector, UDP hole-punching manager, UDP tunnel session for text and file frames, and Compose UI.
- `desktop`: Compose Desktop application entrypoint.
- `android`: Android application entrypoint and manifest.
- GitHub Actions CI runs Gradle project checks, shared desktop tests, desktop compilation, and Android debug APK assembly through the Gradle Wrapper.

The DLP packet layer now has an explicit model, encrypted container format, password validation through AEAD authentication, TTL validation, peer public endpoint fields, and unit tests. The current MVP container uses PBKDF2-HMAC-SHA256 plus AES-GCM because those primitives are available from the JDK on desktop and Android. The next production hardening step is to switch the packet KDF/cipher suite to the intended Argon2id + ChaCha20-Poly1305 implementation.

Networking now has a typed NAT detection contract, local IP lookup, UDP/TCP port selection, an RFC 5389-style STUN Binding Request client/parser for `MAPPED-ADDRESS` and `XOR-MAPPED-ADDRESS`, an initial UDP hole-punching flow, and a UDP tunnel session for text and chunked file frames. The peer facade stores imported DLP endpoint details, attempts a direct UDP punch, keeps the socket open after a successful punch, sends text through the tunnel session, and can frame outgoing files with SHA-256 verification on receive. File chunks are acknowledged by the receiver, and missing chunks are retried before the final file frame is sent. Relay fallback and encryption of tunnel payloads are still future milestones.

## Build

The repository includes Gradle Wrapper files, so a local Gradle installation is no longer required. JDK 17 is still required.

Windows PowerShell commands from the repository root:

```powershell
.\gradlew.bat projects
.\gradlew.bat :shared:desktopTest
.\gradlew.bat :desktop:compileKotlinJvm
.\gradlew.bat :android:assembleDebug
```

macOS/Linux commands from the repository root:

```bash
./gradlew projects
./gradlew :shared:desktopTest
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

## Next Milestones

1. Encrypt UDP tunnel payloads after peer connection is established.
2. Upgrade encrypted DLP packet storage to Argon2id + ChaCha20-Poly1305.
3. Add relay fallback and stronger NAT classification around the UDP punch flow.
4. Add end-to-end two-peer integration tests for DLP import, punch, send, receive, and file transfer.
5. Surface incoming text/file events in the desktop and Android UI.
