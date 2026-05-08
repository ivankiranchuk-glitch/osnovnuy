# DirectLink

Encrypted peer-to-peer tunnel MVP built with Kotlin, Compose Multiplatform, and UDP hole punching.

## Current State

This repository currently contains a compact, build-oriented MVP scaffold:

- `shared`: Kotlin Multiplatform shared model, peer facade, and Compose UI.
- `desktop`: Compose Desktop application entrypoint.
- `android`: Android application entrypoint and manifest.
- GitHub Actions CI runs Gradle project checks, shared desktop tests, desktop compilation, and Android debug APK assembly.

The networking and cryptography layer is intentionally minimal in this checkpoint. The next milestones are to replace the placeholder `NetworkPeer` behavior with the full DirectLink protocol pieces: DLP packet crypto, STUN/NAT detection, UDP hole punching, and encrypted tunnel sessions.

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
2. Restore/port the DLP serializer and cryptography layer into the JVM/Android shared source set.
3. Restore/port STUN, NAT detection, and UDP hole punching.
4. Add integration tests around packet generation/import and tunnel session behavior.
