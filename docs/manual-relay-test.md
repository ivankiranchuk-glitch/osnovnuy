# Manual Relay Test

This guide validates the desktop UI relay path with two app windows and one local relay server.

## Prerequisites

- Windows PowerShell, regular user mode, not administrator.
- Repository folder: `C:\Users\kiran\osnovnuy`.
- JDK 17 installed.
- Project already builds with `./gradlew :shared:desktopTest` or `./gradlew.bat :shared:desktopTest`.

## Terminal 1: Relay Server

Open a regular PowerShell window in:

```text
C:\Users\kiran\osnovnuy
```

Run:

```powershell
cd C:\Users\kiran\osnovnuy
.\gradlew.bat :desktop:runRelayServer
```

Expected output:

```text
DirectLink relay server listening on 0.0.0.0:47777
Press Ctrl+C to stop.
```

Leave this terminal open. This is not a hang; the relay server is expected to keep running.

## Terminal 2: App Window A

Open a second regular PowerShell window in:

```text
C:\Users\kiran\osnovnuy
```

Run:

```powershell
cd C:\Users\kiran\osnovnuy
.\gradlew.bat :desktop:run
```

## Terminal 3: App Window B

Open a third regular PowerShell window in:

```text
C:\Users\kiran\osnovnuy
```

Run:

```powershell
cd C:\Users\kiran\osnovnuy
.\gradlew.bat :desktop:run
```

## App Steps

Use the same packet password in both windows:

```text
directlink
```

Use the same relay URL in both windows:

```text
tcp://127.0.0.1:47777
```

1. In window A, click `Create .dlp`.
2. Copy the generated `.dlp` file path from `Activity` in window A.
3. In window B, paste that path into `DLP file path` and click `Import`.
4. In window B, click `Create .dlp`.
5. Copy the generated `.dlp` file path from `Activity` in window B.
6. In window A, paste that path into `DLP file path` and click `Import`.
7. In window A, click `Host relay`.
8. Copy the generated `Session` value from the relay connected card or the `Relay session` field.
9. In window B, paste that session id into `Relay session`.
10. In window B, click `Join relay`.
11. Confirm both windows show `Relay connected`.
12. Send text from A to B.
13. Send text from B to A.
14. Send a file from A to B.
15. Send a file from B to A.

## Expected Result

- Incoming text appears in the receiving window's `Activity` list.
- Incoming files appear in the receiving window's `Activity` list with saved paths.
- File transfer progress appears in `Transfers`.
- The relay server terminal remains running until stopped with `Ctrl+C`.

## Troubleshooting

If sending fails with `Remote peer id is missing`, one side did not import the other side's `.dlp` packet before relay connect.

If joining fails, confirm the session id from window A was copied into window B exactly.

If relay connection fails, confirm the relay server terminal is still running and both app windows use:

```text
tcp://127.0.0.1:47777
```
