# Miyahna (مياهنا) — Native Android Kotlin + Jetpack Compose Port

This workspace contains the rewritten native Android (`com.miyahna.water`) project alongside the original web source files (kept intact because this build environment runs Node.js 22 without the Android SDK/Gradle).

## Architecture Overview
- **Domain (`app/src/main/java/com/miyahna/water/domain/`)**:
  - `Models.kt`: Full domain schemas, enums (10 trip states, order statuses, stop statuses, GPS events, directives, notifications), `SystemSettings`, `Truck`.
  - `AlWadiDistricts.kt`: 16 Marib / Al-Wadi neighborhoods (`حصون آل جلال`, `العرقين`, etc.) + custom district merging.
  - `ClusteringEngine.kt`: Haversine + Marib road factor (`1.35x`), Nearest-Neighbor TSP sequencing, 6,000L capacity batching, unit-economics feasibility scoring (`GO_NOW`, `URGENT_OVERRIDE`, `FEASIBLE_PARTIAL`, `WAIT_MORE`).
  - `GpsTrackingEngine.kt`: Departure/return geofence verification, spike/drift jitter filtering, GPS gap detection, prolonged stop detection, and Plan-vs-Actual comparison.
  - `UnitEconomicsEngine.kt`: Overall profitability KPIs + Saturday–Friday weekly driver fuel efficiency reporting.
- **Data (`app/src/main/java/com/miyahna/water/data/`)**:
  - `local/`: Room database (`MiyahnaDatabase`) storing offline operational state + `pending_offline_ops` sync queue.
  - `cloud/FirebaseSyncManager.kt`: Defensive Firebase initialization from `res/values/firebase_applet_config.xml`, Google Sign-In via Jetpack `CredentialManager`, and multi-DB Firestore sync (`ai-studio-a10bda36-8b0b-4d37-bbad-aba175fc020a`) aligned with `firestore.rules`.
  - `location/LocationTrackingService.kt`: Android Foreground Service (`foregroundServiceType="location"`) backed by `FusedLocationProviderClient`.
  - `MiyahnaRepository.kt`: Single source of truth connecting Room, connectivity monitoring, GPS tracking triggers, and Firestore sync.
- **UI (`app/src/main/java/com/miyahna/water/ui/`)**:
  - RTL Arabic Material 3 UI (`MiyahnaTheme`) with `RoleSelectionPortalScreen`, PIN-protected `ManagerShellScreen` (12 operational modules, adaptive Drawer/Rail), `DriverAuthScreen`, and dark high-contrast `DriverAppScreen`.
  - Custom Compose `Canvas` `MaribInteractiveMap` & `LocationPickerDialog` + 7-day `DriverWeeklyFuelReportCard`.

## Building & Running in Android Studio
1. Export or open the repository root in **Android Studio** (AGP 8.9.1 / Gradle JDK 17).
2. Extract debug keystore SHA-1/SHA-256 from `debug.keystore` (`keytool -list -v -keystore debug.keystore -alias androiddebugkey`) and register the Android app `com.miyahna.water` + SHA fingerprints in Firebase Console project `boxwood-climber-66m9v` if using Google Sign-In & Firestore on a device.
3. Run `gradle :app:assembleDebug` and `gradle :app:testDebugUnitTest`.
