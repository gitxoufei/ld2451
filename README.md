# FogRadar Android V1 - LD2451 experimental visualizer

This project is an experimental prototype, **not a certified collision warning system**.

## Included
- Android Kotlin app project, minimum Android 8 (API 26)
- BLE scan/connect and test subscription with AE00/AE02 UUIDs extracted from the supplied multi-device HLK demo
- Stateful LD2451 V1.03 UART binary frame parser (works when identical frames arrive over BLE)
- Five-lane 100m grid, ±5°/10°/15° rays, simultaneous target dots and TTC labels
- Demo mode, local correction parameters, test sound, CSV/raw frames written to app external files directory

## Important compatibility boundary
The provided HLK demo's BLE list routes LD2451 into `NewActivity` (generic/firmware UI). The AE00/AE02 UUID mapping is explicitly coded for LD2450, **not independently documented for LD2451**. No proof was found that the LD2451 BLE characteristic outputs the same `F4 F3 F2 F1` binary frames as UART. This release therefore treats BLE reception as a **hardware experiment**, rather than verified functionality. App demo mode is usable without hardware.

The parser follows the USER SUPPLIED LD2451 Serial Protocol V1.03; if actual BLE payload is framed differently, BLE adaptation is required. Keep official app disconnected while testing this app (most BLE peripherals do not allow two phone clients at once).

## Build
Open folder in Android Studio with Android SDK installed; Gradle sync and build APK. The archive does **not** include a compiled APK, Gradle wrapper or Android SDK. Compile has not been independently verified in this environment.

## Caution
The 'target hold time' slider is a local display/testing parameter only; it does not send radar configuration commands. The app does not claim lane identification or robust moving-car detection. No driving reliance.
