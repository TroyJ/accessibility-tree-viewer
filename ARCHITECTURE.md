# Accessibility Tree Viewer — Architecture

## What It Is

A standalone Android app that displays the accessibility tree of whatever is currently on screen, rendered as a semi-transparent overlay on the left half of the display. The right half shows interactive blocks for clickable/long-clickable elements. The log overlay is fully pass-through (taps go to the app underneath); only the control buttons and blocks intercept touch.

Target: Android 13 (API 33). Distributed via F-Droid repo or direct APK sideload.

## Install via F-Droid

Add this repository to the F-Droid client:

- **URL**: `https://troyj.github.io/accessibility-tree-viewer/fdroid/repo`
- **Fingerprint**: `F806C2E41CB326FB47D7F6D708BED0E6CB255F87DCAE5D122D4B47A433218BAB`

Then search for "Tree Viewer" and install. Updates are published to the same repo.

## Project Layout

```
accessibility-tree-viewer/
├── build.gradle.kts                 # Root: AGP 8.2.2, Kotlin 1.9.22, bumpVersion task
├── settings.gradle.kts
├── gradle.properties                # Points JAVA_HOME to Corretto 17
├── local.properties                 # Points sdk.dir (not in git)
├── version.properties               # versionCode + versionName (auto-incremented)
├── gradlew / gradlew.bat
├── gradle/wrapper/                  # Gradle 8.5 wrapper
├── fastlane/metadata/android/       # F-Droid app metadata + changelogs
├── fdroid-repo/                     # Local fdroidserver workspace (not in git)
├── docs/fdroid/repo/                # Published F-Droid repo (GitHub Pages)
└── app/
    ├── build.gradle.kts             # compileSdk=33, minSdk=26, targetSdk=33
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/prado/treeviewer/
        │   ├── MainActivity.kt      # Launcher, permission setup, shows version
        │   └── TreeOverlayService.kt # AccessibilityService + all overlays
        └── res/
            ├── xml/accessibility_config.xml
            ├── layout/activity_main.xml
            └── values/strings.xml
```

## Source Files

### MainActivity.kt
Launcher activity. Handles permission setup:
1. Checks `Settings.canDrawOverlays()` — if false, sends user to overlay permission screen
2. Checks if `TreeOverlayService` is enabled — if not, sends user to Accessibility Settings
3. Displays version string from `BuildConfig.VERSION_DISPLAY`

### TreeOverlayService.kt
An `AccessibilityService` subclass. Core of the app. Manages four types of overlay windows:

#### Left Side — Log Overlay

| Window | Touch | Content |
|---|---|---|
| Log window | `FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE` (pass-through) | Left half, semi-transparent black, green monospace 7sp text |
| Button bar | `FLAG_NOT_FOCUSABLE` (touchable) | PgUp, PgDn, Show/Hide (R), Close (X) |

#### Right Side — Interactive Blocks

| Window | Touch | Content |
|---|---|---|
| Block windows (up to 40) | `FLAG_NOT_FOCUSABLE` (touchable) | 1/8 screen width x 1/10 screen height, color-coded |
| Yellow indicator | `FLAG_NOT_TOUCHABLE` (pass-through) | 1/16 screen square at bottom-right, overflow indicator |

Blocks are individual overlay windows so gaps between them pass touch through to the underlying app.

#### Tree Traversal (log)
- Triggered by `TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOW_CONTENT_CHANGED`, `TYPE_VIEW_SCROLLED`, `TYPE_WINDOWS_CHANGED`
- Filters out events from own package to prevent feedback loops
- Debounced at 500ms
- DFS of `AccessibilityNodeInfo` tree producing indented lines with capability tags: `[CLICK]`, `[EDIT]`, `[SCROLL]`, `[L-CLICK]`
- Rolling buffer capped at 3000 lines
- Initial scan triggered in `onServiceConnected()`

#### Block Scanning
- Separate pass over the tree via `scanForBlocks()`
- Finds parent nodes with clickable or long-clickable children
- Collects text via DFS into all children (`collectTextDfs`):
  - TextViews: formatted as `<resourceId> text`
  - Leaf nodes with contentDescription used as fallback
  - Concatenated with ` | ` separator
- Each block has two halves:

| Half | Action | Colors |
|---|---|---|
| Left | `ACTION_CLICK` on first clickable child | Green=1 click, Red=multiple, Grey=none |
| Right | `ACTION_LONG_CLICK` on first long-clickable child | Blue=1 lclick, Red=multiple, Grey=none |

Label text spans both halves, auto-sized 6-12sp to fit.

## Versioning

Version is stored in `version.properties`:
```
versionCode=4
versionName=1.4
```

- `./gradlew bumpVersion` increments both (`versionCode` +1, `versionName` = `1.<new code>`)
- `app/build.gradle.kts` reads the file at **configuration time** (before tasks run)
- **IMPORTANT**: `bumpVersion` and `assembleDebug` must be separate Gradle invocations so the build picks up the new version. Use `./gradlew bumpVersion && ./gradlew assembleDebug` (not `./gradlew bumpVersion assembleDebug`)
- `BuildConfig.VERSION_DISPLAY` is generated as `v1.4 (4)` and shown on the setup screen and in the overlay log at startup

## Permissions

| Permission | Declared in | Granted by |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` | AndroidManifest.xml | User toggles in Settings > Apps > Display over other apps |
| `BIND_ACCESSIBILITY_SERVICE` | AndroidManifest.xml (on the service) | User enables in Settings > Accessibility > Tree Viewer |

No runtime permission dialogs. No root. No Play Store account.

## Build

```bash
# Standard build
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleDebug

# Bump version + build (must be separate invocations)
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=~/Library/Android/sdk ./gradlew bumpVersion && \
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (~3 MB, debug-signed)

## F-Droid Repo Update Process

After building a new APK:

```bash
# 1. Copy APK to fdroid workspace
cp app/build/outputs/apk/debug/app-debug.apk fdroid-repo/repo/

# 2. Regenerate index
cd fdroid-repo && ANDROID_HOME=~/Library/Android/sdk fdroid update --rename-apks

# 3. Publish to GitHub Pages
rm -rf docs/fdroid/repo && cp -r fdroid-repo/repo docs/fdroid/repo

# 4. Commit and push
git add docs/ version.properties && git commit && git push
```

The F-Droid client will pick up the new version on its next refresh.

Repo URL: `https://troyj.github.io/accessibility-tree-viewer/fdroid/repo`

## Key Design Decisions

- **No XML layouts for overlays** — views constructed programmatically in Kotlin
- **Multiple WindowManager windows** — only way to get pass-through on log/gaps while keeping buttons and blocks touchable
- **Individual block windows** — gaps between blocks pass touch through naturally (no window = no interception)
- **Rolling buffer capped at 3000 lines** — prevents unbounded memory growth
- **500ms debounce** — balances responsiveness with not flooding the display
- **`disableSelf()` for shutdown** — cleanest way to fully stop an AccessibilityService
- **Two-pass tree traversal** — separate passes for log display and block scanning, avoids node lifecycle complexity
- **version.properties** — single source of truth for version, read by Gradle and exposed via BuildConfig
