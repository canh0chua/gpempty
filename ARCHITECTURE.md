# Architecture Documentation - GPEmpty v1.0.0

## Overview
GPEmpty is a hybrid Android application designed to automate the management and cleaning of Google Photos libraries. It leverages a native Android `WebView` to inject usage-specific JavaScript that interacts with the Google Photos web interface.

## Core Components

### 1. Native Layer (Android/Kotlin)
- **MainActivity.kt**: The central controller.
    - **WebView Management**: Initializes a `WebView` with Javascript enabled and custom User-Agent strings to mimic a desktop or supported mobile environment.
    - **JS Injection**: Listens for page loads (`onPageFinished`) and injects the automation bundle.
    - **Bridge**: Exposes a `ConsoleCallback` interface (`onConsoleMessage`) to receive signals from the injected JavaScript (e.g., `GP_AUTOMATOR:BATCH_COMPLETE`, `STOP`).
    - **Scheduling**: Uses a background `Handler` to check the time every minute and trigger the automation at 9 PM if enabled.
    - **Persistence**: Uses `SharedPreferences` to store:
        - Settings (Auto Run status, Limits, Schedule).
        - Activity Logs (JSON array, retained for 7 days).
    - **Foreground Service**: `KeepAliveService` runs as a foreground service with a persistent notification (permission `POST_NOTIFICATIONS`) to prevent the Android system from killing the app process, ensuring the 4-hour schedule interval remains active.

### 2. Automation Layer (JavaScript)
- **Injection Strategy**: The script is constructed as a self-executing anonymous function injected via `webView.evaluateJavascript`.
- **State Machine**:
    - **Selection**: Uses heuristic selectors (ARIA labels, Roles) to identify "Select" buttons or mimics Long-Press events on photos.
    - **Traversal**: Scrolls the infinite grid, identifying "Checkable" items based on the user's filtered mode (Skip Days vs. Delete All).
    - **Action**: Simulates user interactions (Click, Touch) to Select -> Delete -> Confirm.
    - **Verification**: Checks for "No photos" states or infinite scrolling limits.

### 3. Logic Flows

#### "Delete All" Flow
1. **Start**: Automation triggered manually or by schedule.
2. **Loop**:
    - Enter Selection Mode.
    - Select batch (up to 200 items).
    - Click Delete -> Confirm.
    - **Wait** for operation logic.
3. **Signal**: JS sends `BATCH_COMPLETE` with count.
4. **Reload**: Android Reloads the Library page (`https://photos.google.com/`).
5. **Repeat**: Until JS detects "No items" or "Empty State" and sends `FINISHED`.
6. **Trash**: Android navigates to `https://photos.google.com/trash`.
7. **Empty Trash**: JS detects Trash page -> Clicks "Empty Trash" -> Confirms.
8. **Verify**: JS sends `TRASH_CLEANED` / `TRASH_VERIFIED`.
9. **Return**: Android navigates back to Library and stops.

#### "Skip Days" Flow
- Similar to "Delete All", but the JS selector logic explicitly identifies and skips the header group for "Today" (and optionally "Yesterday" if configured, though currently hardcoded to 1 day). It calculates the pixel offset of the first allowed item and starts selection from there.

## Data Persistence
- **Logs**: Stored as a JSON Array string in `SharedPreferences`. Loaded into memory on app start. Pruned to last 7 days on save/load.
- **Settings**: Simple Boolean/Int/String values in default `SharedPreferences`.

## Security & Permissions
- **Web Login**: Relies on the user logging into Google Photos via the WebView. Session cookies are managed by the `CookieManager`.
- **Permissions**: Requires Internet access (`android.permission.INTERNET`). No fatal dangerous permissions required.
