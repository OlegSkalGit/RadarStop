# RadarStop

Ultra-lightweight, high-performance Android speed camera detector operating as a background Foreground Service with a real-time interactive vector radar map.

Developed using **pure native Android SDK (Kotlin)** without external heavy dependencies (Google Play Services, Room DB, WorkManager, Retrofit, etc.), providing:
- ⚡ **APK Size:** **~180 KB** (strict budget < 1 MB).
- 🧠 **RAM Usage:** **< 20 MB**.
- 🔋 **Zero CPU usage** during stops, traffic jams, and deep sleep.
- 📱 **Uninterrupted Background Operation:** Runs smoothly in the Android notification drawer with instant access to the vector radar map.

---

## 🚀 Cross-Platform One-Click Build

To build and package the project on any computer without manual toolchain setup:

### 🪟 Windows:
```cmd
_BUILD_apk_.bat
```

### 🐧 Linux & 🍎 macOS:
```bash
chmod +x _BUILD_apk_.sh
./_BUILD_apk_.sh
```

The build scripts automatically:
1. Detect host OS (Windows / Linux / macOS) and CPU architecture (x86_64 / ARM64).
2. Download portable **Android Command-Line Tools** into `.android-sdk` if no local Android SDK is present.
3. Configure portable **OpenJDK 17** and **Gradle 8.7**.
4. Sign the output APK with the bundled project keystore (`app/keystore/debug.keystore`), ensuring **identical digital signatures** across any development machine for seamless in-place updates.
5. Produce a release APK in the project root: `RadarStop_yy.MM.dd_HHmm.apk`.

---

## 🛠️ System Architecture & Core Modules

### 1. Entry Point & Permissions (`SplashActivity`)
- Transparent launcher activity (`Theme.Transparent`).
- Verifies location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) and notification permissions.
- Launches `RadarForegroundService`.
- If the service is already active, clicking the app icon displays a quick `RadarStop Active` toast without intercepting focus. The map is opened exclusively by clicking the persistent Android notification.

### 2. Foreground Engine (`RadarForegroundService`)
- Manages background lifecycle, GPS location listeners, audio alerts, and power states.
- Displays persistent ongoing notification in the Android status bar.
- **Zero-Allocation Notification Builder:** Re-renders system notifications only on state transitions.

### 3. Interactive Vector Radar Map (`RadarMapActivity`)
- **Radar Canvas:** 3 km radius (6 km visual diameter) circular vector radar oriented North-Up with 1 km, 2 km, and 3 km distance rings.
- **Outer Ring Projection:** Speed cameras loaded in the 10×10 km RAM cache that lie beyond the 3 km screen radius are projected onto the outer 3 km ring along their exact bearing.
- **UI HUD:** High-visibility digital speedometer, GPS accuracy status, and camera proximity warnings (`cam near`) themed in dark `#121212`.
- **Top Control Bar:** **Close** button on the left (minimizes map to background) and **Menu** button on the right.
- **Main Menu Dialog:**
  - **Enable / Disable Autostart:** Toggles boot receiver with instant feedback.
  - **Load country cameras:** Direct download and offline caching of national camera databases.
  - **Check updates:** Checks GitHub releases for new APK versions.
  - **Help:** Opens user reference documentation.
  - **Enable / Disable Debug:** Toggles status spoiler panel, real-time trajectory visualization tail on the radar, file logging, and diagnostics (**Logs**, **Test Beep**).
  - **Quit:** Stops foreground service, cancels watchdogs, and exits cleanly.

---

## 🧠 Core Algorithms & Mathematical Logic

### 1. Geodesic Camera Caching & Overpass OSM Sync (`OverpassSyncManager`, `DatabaseHelper`)
* **Network Sync (100×100 km Bounding Box):** Calculates spatial bounds ($\pm 0.45^\circ$ latitude, $0.45^\circ \dots 0.9^\circ$ longitude adjusted by $\cos(\text{lat})$). Downloads speed camera nodes from 5 Overpass API mirrors with 25–60s timeouts using memory-efficient streaming `android.util.JsonReader`.
* **Sync Triggers:** Every 24 hours or when vehicle moves $\ge 40\text{ km}$ from the last sync center. On network failure, pauses for 5 minutes before retrying.
* **RAM Cache (10×10 km):** Maintains instant-access memory cache covering $\pm 0.045^\circ$ around vehicle plus all linear section cameras.
* **Cache Reload Trigger:** Re-queries SQLite when moving $\ge 4\text{ km}$ from last load point or exiting the active 10×10 km bounding box.
* **Indexed SQLite Database:** Spatial queries backed by `CREATE INDEX idx_coords ON cameras(lat, lon)` execute in $< 1\text{ ms}$.

### 2. Multi-Provider GPS Arbitration & Noise Filtering (`RadarForegroundService`)
* **Provider Fusion:** Concurrently registers GPS, Network, and Passive location providers.
* **Stationary Noise Filter:** If new coordinates shift with higher inaccuracy than the displacement distance, the point is rejected as satellite jitter.
* **Weak Signal Protection:** If GPS accuracy drops $> 100\text{ m}$, alerts are paused, speed is set to 0, and the stationary sleep countdown begins.

### 3. Trajectory Filtering, OLS Trend & Maneuver Analysis (`TrajectoryFilter`, `RadarMath`)
* **Sliding Buffer:** Retains up to 10 points pushed at $\le 1\text{ Hz}$.
* **Dual-Subbuffer Vector Trend:** Splits points into Head (points 1..5) and Tail (points 6..10) to evaluate directional azimuths.
* **Adaptive Buffer Truncation:** Drops buffer to the 3 most recent points upon sharp turns (azimuth difference $\ge 30^\circ$) or high speed ($> 300\text{ km/h}$) for zero-lag heading updates.
* **Stationary Drift Detection:** If extreme point displacement to total step distance ratio $\le 0.5$ and displacement $< 2\times \text{accuracy}$ (or $< 15\text{ m}$), state resolves to stationary ($0\text{ km/h}$).
* **Composite Speed Derivation:** Evaluates $\max(\text{Sensor Speed}, \text{OLS Velocity}, \text{Direct Step Speed})$.
* **Speed Mode Hysteresis:** Switches to high-speed mode at $> 70\text{ km/h}$ and reverts at $< 50\text{ km/h}$ ($50\dots 70\text{ km/h}$ buffer zone).
* **Kinematic Projection:** Extrapolates position 2.0 seconds ahead along the velocity vector at speeds $\ge 30\text{ km/h}$.

### 4. Acoustic Radar Engine (`AcousticRadarEngine`, `RadarMath`)
* **Speed Threshold:** Audio alerts activate strictly above $30\text{ km/h}$. Muted at $\le 30\text{ km/h}$.
* **Fixed Camera Alert Zone:** 300 meters.
* **Dynamic Beep Intervals:**
  * **Approaching (300m $\to$ 0m):**
    * 200–300 m: Beep every 2.0 s (2000 ms).
    * 100–200 m: Beep every 1.0 s (1000 ms).
    * 0–100 m: Beep every 0.5 s (500 ms).
  * **Departing (after passing camera, distance increases by $+15\text{ m}$ from minimum):**
    * 0–100 m: Beep every 1.0 s (1000 ms).
    * 100–200 m: Beep every 2.0 s (2000 ms).
    * $> 200\text{ m}$: Alert terminates immediately.
* **Average Speed Enforcement (Linear Zones):** Starts alerting 300m before the entry camera and emits periodic beeps every 1.5 s (1500 ms) throughout the entire section until simultaneously departing from entry ($> 50\text{ m}$ or $+3\text{ m}$) and exit cameras ($+3\text{ m}$).
* **Bluetooth A2DP Warmup:** Acquires transient audio focus (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) with a 350 ms hardware warmup delay before first tone to prevent clipped audio over car Bluetooth head units.
* **Stale GPS Guard:** Automatically silences audio and drops audio focus if GPS updates stall for $> 1.5\times$ polling interval (max 10 s).

### 5. Adaptive Polling & Power Management (`RadarForegroundService`)
* **External Power Connected:** Continuous 1.0 s polling; deep sleep is disabled.
* **Battery Operation:**
  * Within 1000 m of camera (at $> 70\text{ km/h}$) or 500 m (at $\le 70\text{ km/h}$), or in linear zone: **1.0 s interval**.
  * Cameras present within 3 km: **3.0 s interval**.
  * No cameras within 3 km (Smart Sleep): **5.0 s interval**.
  * Vehicle stopped: 3-minute grace period at **3.0 s interval**.

### 6. Deep Sleep Mode & Hardware Motion Wakeup (`RadarForegroundService`)
* **Deep Sleep Entry:** Vehicle remains stationary ($0\text{ km/h}$, lost GPS, or disabled GPS provider) for $> 3\text{ minutes}$ (180 s) on battery.
* **Zero Power State:** Unregisters GPS listeners, releases CPU `PARTIAL_WAKE_LOCK`, allowing Android to enter deep Doze mode.
* **Motion Spike Filter:** Uses hardware `TYPE_SIGNIFICANT_MOTION` or fallback `TYPE_ACCELEROMETER`:
  * Spike #1 threshold: acceleration delta $\ge 0.8\text{ m/s}^2$ from gravity.
  * Spikes #2..5: confirmation deltas $\ge 0.4\text{ m/s}^2$ within $\le 2.0\text{ s}$ intervals.
  * Reaching 5 consecutive spikes triggers immediate wake-up, re-acquires WakeLock, and resumes 1.0 s GPS tracking.
* **Direct Wake-Up Triggers:** Power connection, Bluetooth audio profile connection, or GPS provider re-enabled in Android settings.

### 7. Watchdog Timers & Auto-Recovery (`AlarmWatchdogReceiver`)
* **Software Watchdog (every 2 s):** Resets displayed speed to 0 if location updates stall longer than $1.5\times$ interval.
* **GPS Recovery Watchdog (every 60 s):** Re-registers location listeners if no updates are received.
* **System AlarmManager (every 15 min):** Wakes CPU via `RTC_WAKEUP` exact alarm. If the service was killed by aggressive OS task killers, automatically relaunches it in Deep Sleep mode with active motion sensing.

### 8. GitHub In-App Auto-Updater (`AppUpdateManager`)
* Checks GitHub Releases API (`api.github.com/repos/OlegSkalGit/RadarStop/releases`) every 24 hours or on demand.
* Parses release asset versions, compares against installed version, and downloads APK via Android `DownloadManager` (or direct HTTP stream with up to 5 redirect handlings).
* Prompts user with a dialog/notification and triggers Android package installer.

---

## 📜 License

Distributed under the **MIT License**.
