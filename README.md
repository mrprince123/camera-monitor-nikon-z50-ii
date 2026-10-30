# CamMonitor — Nikon Z50 II Field Monitor

> A full-screen Android field monitor for the **Nikon Z50 II** (and any UVC-capable camera) over a USB-C to USB-C cable. Plug in the camera and the live feed starts automatically — no setup screens, no extra taps.

---

## Features

| Overlay | What it does |
|---|---|
| **Grid** | Rule-of-thirds lines + centre cross |
| **Level** | Digital horizon driven by the phone's accelerometer — green when level, yellow when tilted |
| **Histo** | Luminance histogram with gradient bars |
| **Zebra** | Red diagonal stripes over blown highlights (luma ≥ 235) |
| **Focus** | Green edge-detection highlight for focus peaking |

- **Plug-and-play** — Android launches the app automatically the moment you connect the camera
- **Tap anywhere** to show / hide the control buttons (smooth fade animation)
- **Screen always on** while the app is open
- **Full-screen landscape**, system bars hidden
- **No runtime permissions** — USB host mode needs none
- **Dark premium UI** with glassmorphic toggle buttons and a custom app icon

---

## Camera Setup (Nikon Z50 II)

1. On the camera: **Menu → Setup → USB → UVC / Webcam** (exact label may vary by firmware)
2. Connect with a **data-capable USB-C to USB-C cable** (charge-only cables won't work)
3. Android will prompt to open **CamMonitor** — tap **Always** for true plug-and-play

> **Note:** Histogram, zebra, and focus peaking are computed from the compressed webcam stream, not the raw sensor. Use them as a guide rather than a precise meter.

---

## Build & Install

### Requirements
- Android Studio **Iguana / Jellyfish** or newer
- Android phone running **Android 7.0+** (API 24) with USB host support
- USB-C port that supports USB host / OTG

### Steps
```bash
# Clone
git clone https://github.com/mrprince123/camera-monitor-nikon-z50-ii.git
cd camera-monitor-nikon-z50-ii

# Build debug APK (or just open in Android Studio and Run)
./gradlew assembleDebug
```

The signed APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Architecture

```
CameraMonitor/
├── app/src/main/
│   ├── AndroidManifest.xml          # USB_DEVICE_ATTACHED for auto-launch
│   ├── java/com/example/cameramonitor/
│   │   ├── MainActivity.kt          # CameraActivity subclass, analysis loop
│   │   └── OverlayView.kt           # Custom Canvas overlay (grid/level/hist/zebra/peak)
│   └── res/
│       ├── drawable/bg_toggle_btn.xml   # Glassmorphic toggle selector
│       ├── layout/activity_main.xml     # Splash screen + overlay + controls
│       ├── mipmap-*/                    # Launcher icons (mdpi → xxxhdpi + adaptive)
│       ├── values/colors.xml            # Brand palette
│       ├── values/themes.xml            # Full-screen dark theme
│       └── xml/usb_device_filter.xml    # USB device filter for intent-filter
```

**Key library:** [`AndroidUSBCamera / libausbc`](https://github.com/ernestp/AndroidUSBCamera) — handles all UVC/USB-host plumbing via `CameraActivity`.

---

## Overlay Details

### Frame Analysis
Pixel analysis runs on a dedicated background thread at ~7 fps to avoid blocking the UI. Each frame is downscaled to 320 px wide before processing.

### Zebra Threshold
Pixels with luma ≥ **235** (out of 255) are flagged. You can adjust `zebraThreshold` in `MainActivity.kt`.

### Focus Peaking Sensitivity
Edge strength threshold is **40** (sum of horizontal + vertical Sobel-like gradient). Raise `peakThreshold` for fewer highlights, lower it for more.

---

## License

MIT — do whatever you like, attribution appreciated.
