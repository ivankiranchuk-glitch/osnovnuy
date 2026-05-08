# DirectLink

Encrypted peer-to-peer tunnel MVP built with Kotlin, Compose Multiplatform, and UDP hole punching.

## Current State

This repository currently contains a compact, build-oriented MVP scaffold:

- `shared`: Kotlin Multiplatform shared model, peer facade, encrypted MVP DLP packet serializer, NAT detector contract, and Compose UI.
- `desktop`: Compose Desktop application entrypoint.
- `android`: Android application entrypoint and manifest.
- GitHub Actions CI runs Gradle project checks, shared desktop tests, desktop compilation, and Android debug APK assembly.

The DLP packet layer now has an explicit model, encrypted container format, password validation through AEAD authentication, TTL validation, and unit tests. The current MVP container uses PBKDF2-HMAC-SHA256 plus AES-GCM because those primitives are available from the JDK on desktop and Android. The next production hardening step is to switch the packet KDF/cipher suite to the intended Argon2id + ChaCha20-Poly1305 implementation.

Networking now has a typed NAT detection contract, local IP lookup, and UDP/TCP port selection. It intentionally reports `UNKNOWN` NAT until STUN probing is added.

## Build

CI uses Gradle 8.7 via `gradle/actions/setup-gradle`, so the repository can build even before a Gradle wrapper is committed.

Local commands after installing JDK 17 and Gradle:

```bash
gradle projects
gradle :shared:desktopTest
gradle :desktop:compileKotlinJvm
gradle :android:assembleDebug
```

Desktop run:

```bash
gradle :desktop:run
```

## Next Milestones

1. Add a Gradle wrapper once a local JDK/Gradle environment is available.
2. Upgrade encrypted DLP packet storage to Argon2id + ChaCha20-Poly1305.
3. Add STUN probing and UDP hole punching on top of the NAT detector contract.
4. Add encrypted tunnel session tests around packet generation/import and tunnel behavior.
