# HyperLauncher 1.0.1

A lightweight, customizable Android launcher built with native Android views and Kotlin.

## Features

- Home screen with a minimal clock/date widget, first-run app placement and a customizable four-icon dock
- HyperOS-inspired app drawer with category chips, alphabetical sections and quick letter navigation
- App search with accent-insensitive matching and relevance ranking
- App folders, folder management and folder layout controls
- Hidden apps management
- Home and drawer grid, icon size, label and spacing controls
- Dock app selection, labels, sizing, alignment, background and appearance controls
- Custom wallpaper selection with dimming
- Swipe navigation between Home and All Apps
- Tap feedback and optional haptic feedback
- Android system-bar inset handling for modern edge-to-edge devices
- About screen with app version information

## Compatibility

- Android 8.0 (API 26) and newer
- Designed as a third-party launcher for Motorola and other Android devices
- It does not include Xiaomi's private MIUI framework, system widgets or privileged system integrations. It is HyperOS-inspired, not a modified Xiaomi System Launcher package.

## Build

Use Android Studio or Gradle 8.9 with JDK 17:

```bash
gradle assembleRelease
```

APK output: `app/build/outputs/apk/release/app-release.apk`

The public CI release is locally installable and signed with the CI runner's debug certificate. It is not Play Store production-signed. Since CI runners generate their own debug certificate, uninstall an earlier CI/debug-signed HyperLauncher before installing a separately built v1.0 APK if Android reports a signature conflict.
