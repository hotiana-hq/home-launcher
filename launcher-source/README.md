# HOTIANA Home source build

This standalone build is an Android home app built on the official Home app catalogue and responsive grid policy in the adjacent launcher-core directory. It uses its own application ID and storage, so it can be installed alongside the official app. It is selectable as Android's default Home. Switching the default does not clear its saved app pins, grid density, or widget setup.

## Build and run

From the repository root, run the checked-in Gradle wrapper with the source build as its project:

On macOS or Linux:

```sh
./gradlew -p launcher-source assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat -p launcher-source assembleDebug
```

The build needs JDK 17 or 21 and Android SDK Platform 36. The wrapper downloads the pinned Gradle 8.11.1 distribution and checks its SHA-256. You can also use a local Gradle 8.11.1 installation with the same arguments. The debug APK is at `launcher-source/build/outputs/apk/debug/launcher-source-debug.apk`.

[Gradle Wrapper attribution and license](../gradle/THIRD-PARTY-GRADLE-WRAPPER.md)

Open HOTIANA Home Source from the app drawer. Tap Apps to find and launch installed apps, or hold an app to pin it to Home. Customise offers grid density, Android widgets and Default Home settings. Widget binding and widget setup use Android's confirmation screens.

## Scope

The source build currently covers local app search, app launching, ordered pins, grid density, and local widget hosting with resize and removal. The official app's visual themes, hosted services, device connections and release signing are not part of this build. App launches are counted by hour of day and weekday or weekend in the launcher's own private preferences to rank suggestions.
