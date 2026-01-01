# <img src="app_logo.png" width="48" height="48" alt="Logo" style="vertical-align: middle;"/> GP Empty Trash - User Guide
 - GPEmpty v1.0.0

## Getting Started

### Installation
1. Download the `app-release.apk` file to your Android device.
2. Tap the file to install. You may need to allow "Install from Unknown Sources" in your browser/file manager settings.
3. Open **GPEmpty**.

### Setup
1. **Login**: When you first open the app, you will see the Google Login page inside the main view. Log in with your Google account.
2. **Permissions**: Ensure the app has Internet access (default).

## Usage

### Manual Controls
The Control Panel at the top allows you to manage the cleaner manually:
- **Start/Stop**: The green "Start" button initiates the cleaning process based on your settings. Clicking it again ("Stop") runs a safety reload to halt operations immediately.
- **Logs**: View the activity history for the last 30 days. You can clear logs here.
- **Modes**:
    - **Skip**: Deletes photos but keeps the most recent $X$ days safe. Use the slider to adjust the skipped days (1-14 days).
    - **All**: Aggressively monitors and deletes **ALL** photos in the library. **Use with caution.**

### Automation Settings
- **Smart Daily Schedule**:
    - Toggle the switch to **Enable**.
    - **Logic**: The app will check for maintenance every **4 hours** (during the day, 8 AM - 10 PM).
    - **Doze Friendly**: This schedule is "passive," meaning it will **NOT** wake up your phone if it is sleeping to save battery.
    - **Catch-Up**: If your phone sleeps all day, the app will wait **2 minutes** after you wake it up before running. This prevents it from slowing down your phone the moment you unlock it.
    - **Persistent Service**: When enabled, you will see a small, compact notification ("Smart Schedule standing by..."). This prevents Android from closing the app when it is in the background.
    - **Note**: The app must be running (minimized) for this to work.

### What happens during a run?
1. The app scans your Google Photos library.
2. It selects photos based on your "Skip/All" setting.
3. It moves them to the Trash.
4. **For "All" Mode**: It will repeatedly reload and delete until the library is empty, then go to the Trash and permanently empty it.
5. Once finished, it returns to the main library page.

## Troubleshooting
- **Stuck Script**: If the automation seems stuck, just click "Stop" to reload the page.
- **Login Issues**: If you are logged out, simply log back in via the web interface.
- **Not Deleting**: Google Photos web interface changes occasionally. If selectors break, check for app updates.
