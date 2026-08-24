# RadarStop

Ultra-lightweight, high-performance Android application operating as a Foreground Service with a real-time interactive radar map for speed camera detection.

Built with **native Android SDK (Kotlin)** without heavyweight third-party libraries (Google Play Services, Room DB, WorkManager, Retrofit, etc.), ensuring:
- ⚡ **APK Size:** **~180 KB** (strict limit < 1 MB).
- 🧠 **RAM Usage:** **< 20 MB**.
- 🔋 **Zero CPU overhead** when stopped or when no speed cameras are nearby.
- 📱 **Background Mode & Controls:** Runs persistently in the Android notification shade with quick access to the radar map.

---

## 🚀 Automated APK Build & Cross-Platform Support

For fast and automated builds on any machine, use the provided scripts:

### 🪟 Windows:
```cmd
_BUILD_apk_.bat
```

### 🐧 Linux & 🍎 macOS (Universal):
```bash
chmod +x _BUILD_apk_.sh
./_BUILD_apk_.sh
```

The build scripts automatically:
1. Detect host platform (Windows / Linux / macOS) and CPU architecture (x86_64 / ARM64).
2. Scan for existing Android SDK installations, and if missing, download portable **Android Command-Line Tools** into `.android-sdk`.
3. Set up portable **OpenJDK 17** and **Gradle 8.7**.
4. Use shared project keystore **`app/keystore/debug.keystore`**, guaranteeing **100% identical APK digital signatures** across all machines for seamless updates without reinstallation.
5. Compile the project and copy the signed **Release APK** to the project root named:
   `RadarStop_yy.MM.dd_HHmm.apk`

---

## 🛠️ Architecture & Modules

### 1. Entry Point & Permissions (`SplashActivity`)
- Transparent launch Activity (`Theme.Transparent`).
- Verifies location and notification permissions.
- Starts `RadarForegroundService`.
- If the service is already running, launching the app icon shows a short Toast `RadarStop Active` and finishes without stealing window focus. The map opens strictly via tapping the notification in the Android shade.

### 2. Foreground Service & Notifications (`RadarForegroundService`)
- Persistent notification in the status bar: `RadarStop Active`.
- Tapping the notification opens the main `RadarMapActivity`.
- **Zero-Allocation Notification Builder:** Updates the status bar notification only upon state changes to minimize system resource usage.

### 3. Main Map Screen & Menu (`RadarMapActivity`)
- **Map View:** Overview map with auto-orientation, distance grid, current speed display, heading direction, gray trajectory history tail, 3 km awareness ring, and speed cameras.
- **Unified Dark Theme:** Speed and nearby camera (`cam near`) indicator panels match the map background color palette (`#121212`).
- **Top Bar:** **Close** button on the left (minimizes/closes the activity), **Menu** button on the right.
- **Status Spoiler (Status ▲/▼):** Displayed at the bottom of the map only when **Debug** mode is enabled.
- **Main Pop-up Menu:**
  - **Enable / Disable Autostart**: Toggles system boot autostart with Toast feedback `Start with system - Enable` / `Disable`.
  - *Divider*
  - **Load country cameras**: Convenient camera database download dialog by country.
  - **Check updates**: Automatic in-app update check and download from GitHub releases.
  - *Divider*
  - **Help**: Displays the built-in help and user guide screen.
  - *Divider*
  - **Enable / Disable Debug**: Persists debug state across app restarts. Toggles menu access to **Logs** and **Test Beep**, controls bottom status panel visibility, and automatically disables file logging when turned off.
  - **Logs** (in Debug): Opens the built-in log viewer `LogViewerActivity`.
  - **Test Beep** (in Debug): Plays a test sound alert.
  - *Divider*
  - **Quit**: Fully stops the background foreground service and terminates the app.

### 4. Trajectory Filtering & 2D OLS Trend Analysis (`TrajectoryFilter`, `RadarMath`)
- **GPS Accuracy Filter ($> 100$ m):** Coordinates with accuracy weaker than 100 meters temporarily pause audio alerts.
- **Stationary Detection:** When displacement ratio $\le 0.5$ and bounding distance $< 2 \times \text{accuracy}$, the vehicle is marked stationary, speed is zeroed, and buffer is truncated to 3 points.
- **Parametric 2D OLS Trend (10-point sliding window):** Split into Head (points 1..5) and Tail (points 6..10). Evaluates spatial movement vectors $\vec{V}_1$ and $\vec{V}_2$ in angular degrees ($0^\circ \dots 360^\circ$).
- **Adaptive Buffer Truncation:** Truncation down to 3 points occurs only during sharp turns ($\Delta\theta \ge 30^\circ$), stationary stops, or extreme speed (> 300 km/h). Straight driving and smooth deceleration retain the full 10-point history tail rendered on the map.
- **Smoothed Vector Speed:** Derived directly from spatial velocity components $\sqrt{v_x^2 + v_y^2} \times 3.6$.

### 5. Help Screen (`HelpActivity`)
- Clean, concise English user guide explaining the key features and operational modes.
- Top navigation bar includes a **Back** button to return to the map.

### 6. ADB & File Log Viewer (`LogViewerActivity`, `AppLogger`)
- Real-time in-app inspection of background service logs.
- **Enable / Disable Logging** toggle, file selection, log clearing, and system share dialog.
- Logging state is synchronized with the **Debug** toggle.

### 7. System Boot Autostart (`BootReceiver`)
- Handles `BOOT_COMPLETED` and `QUICKBOOT_POWERON` broadcast intents.
- Automatically launches `RadarForegroundService` if Autostart is enabled in preferences.

### 8. Overpass API Sync & Streaming Parser (`OverpassSyncManager`)
- **Auto-Sync on Launch:** Downloads speed cameras within a **100×100 km** bounding box (`lat/lon ± 0.45`).
- High-speed streaming JSON parser using **`android.util.JsonReader`** directly over `InputStream`.
- Auto-sync triggers: 24 hours elapsed or vehicle moved $> 40$ km from last sync location.

### 9. Cumulative SQLite Database & RAM Cache (`DatabaseHelper`)
- **Targeted Region Replacement:** Camera updates are localized within the 100×100 km bounding box (`DELETE WHERE lat BETWEEN ... AND lon BETWEEN ...`). Previously visited regions are preserved in SQLite.
- Spatial index `CREATE INDEX idx_coords ON cameras(lat, lon)` guarantees query execution $< 1$ ms.
- RAM cache for immediate 10×10 km bounding box (`lat/lon ± 0.045`).

### 10. Adaptive GPS Monitoring & Power Saving Modes
- **31–70 km/h (City):** Polling interval 3 sec.
- **> 70 km/h (Highway):** Polling interval 1 sec / 5 sec (Smart Sleep when no cameras within 3 km).
- **≤ 30 km/h (Stops & Traffic):** Initial 3 minutes polling at 3 sec, transitioning after 3 minutes to **30 sec** interval (deep power saving). Sound alerts active only at $> 30$ km/h.

### 11. Dynamic Acoustic Radar (`AcousticRadarEngine`, `RadarMath`)
- Audio playback via `AudioManager.STREAM_MUSIC` with Audio Focus Ducking.
- **Point Cameras (up to 300 m zone):**
  - **Approaching:** 200–300 m (2.0 s), 100–200 m (1.0 s), 0–100 m (0.5 s).
  - **Departing Detection:** When distance increases by $\ge 15$ m from the minimum recorded distance, mode switches to departing and minimum tracking is frozen.
  - **Departing:** 0–100 m (2.0 s), > 100 m (alerts stop and state resets).
- **Linear Section Cameras:** Continuous alert tone (1.5 s interval) across the entire average speed enforcement zone.

---

## 📜 License

MIT License.
