# Camera Monitor (USB-C UVC)

Shows a UVC/webcam-mode camera (e.g. Nikon Z50 II) full-screen on your phone,
with monitoring aids. Tap the screen to hide/show the buttons.

## Aids
- Grid: rule of thirds
- Level: shows the PHONE's tilt (accurate if the phone is mounted level with the camera)
- Histo: luma histogram of the incoming video
- Zebra: red stripes on highlights (luma >= 235)
- Peak: green edge highlight for focus peaking

Histogram/zebra/peaking are computed from the compressed webcam video,
so treat them as a guide, not exact readings from the camera sensor.

## Build
1. Open in Android Studio, let Gradle sync, run on your phone.
2. Camera: set USB mode to webcam, connect with a data USB-C to USB-C cable.
3. Grant camera + USB permission.

If sync fails on the library API, check the libausbc README for current
CameraActivity method names: https://github.com/jiangdongguo/AndroidUSBCamera
