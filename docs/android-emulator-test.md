# Android Emulator Smoke Test

This guide installs and launches the Android debug APK on a connected emulator.

## Prerequisites

- Android Studio or Android SDK installed.
- Android emulator already running.
- Repository folder: `C:\Users\kiran\osnovnuy`.
- Android SDK path: `%LOCALAPPDATA%\Android\Sdk`.

## 1. Build Current APK

Terminal: regular PowerShell, not administrator.

Folder:

```text
C:\Users\kiran\osnovnuy
```

Commands:

```powershell
cd C:\Users\kiran\osnovnuy
.\gradlew.bat :android:assembleDebug
```

Expected APK:

```text
C:\Users\kiran\osnovnuy\android\build\outputs\apk\debug\android-debug.apk
```

## 2. Confirm Emulator Is Connected

Terminal: regular PowerShell, not administrator.

Folder:

```text
C:\Users\kiran\osnovnuy
```

Command:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
```

Expected result includes one device, usually like:

```text
emulator-5554    device
```

If it says `unauthorized`, unlock the emulator and approve USB debugging.

## 3. Install APK

Command:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r "C:\Users\kiran\osnovnuy\android\build\outputs\apk\debug\android-debug.apk"
```

Expected result:

```text
Success
```

## 4. Launch App

Command:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell monkey -p com.kirivsoft.directlink -c android.intent.category.LAUNCHER 1
```

Expected result: DirectLink opens on the emulator.

## 5. Basic Smoke Checklist

In the emulator app:

1. Confirm the DirectLink screen opens.
2. Enter packet password, for example `directlink`.
3. Tap `Initialize`.
4. Tap `Create .dlp`.
5. Confirm the activity log shows a generated DLP packet.
6. Confirm the app does not crash.

## 6. Optional: Capture Logs

Use this when the app crashes or behaves unexpectedly.

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -c
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell monkey -p com.kirivsoft.directlink -c android.intent.category.LAUNCHER 1
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -d -t 300 > android-logcat.txt
```

The log file will be created in the current PowerShell folder:

```text
C:\Users\kiran\osnovnuy\android-logcat.txt
```
