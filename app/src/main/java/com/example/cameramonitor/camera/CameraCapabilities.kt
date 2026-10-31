package com.example.cameramonitor.camera

/**
 * Describes the operational capabilities supported by the active camera backend.
 * UI controls query this to enable/disable camera features dynamically.
 */
data class CameraCapabilities(
    val supportsLiveView: Boolean = false,
    val supportsZoomableLiveView: Boolean = false,
    val supportsPhysicalShutter: Boolean = false,
    val supportsRemoteShutter: Boolean = false,
    val supportsMovieRecording: Boolean = false,
    val supportsAutofocusTrigger: Boolean = false,
    val supportsTouchToFocus: Boolean = false,
    val supportsManualFocusDrive: Boolean = false,
    val supportsExposureSettings: Boolean = false,
    val supportsBatteryStatus: Boolean = false,
    val supportsMediaTransfer: Boolean = false
) {
    companion object {
        /** Capabilities when operating in UVC / Webcam streaming mode */
        val UVC_DEFAULT = CameraCapabilities(
            supportsLiveView = true,
            supportsZoomableLiveView = true,
            supportsPhysicalShutter = false, // Locked out by camera firmware in UVC mode
            supportsRemoteShutter = false,
            supportsMovieRecording = false,  // Internal card recording disabled in UVC mode
            supportsAutofocusTrigger = false,
            supportsTouchToFocus = false,
            supportsManualFocusDrive = false,
            supportsExposureSettings = false,
            supportsBatteryStatus = false,
            supportsMediaTransfer = false
        )

        /** Capabilities when operating via HDMI Video Capture Card */
        val HDMI_CAPTURE = CameraCapabilities(
            supportsLiveView = true,
            supportsZoomableLiveView = true,
            supportsPhysicalShutter = true,  // Full physical camera operation
            supportsRemoteShutter = false,   // Requires secondary PTP connection
            supportsMovieRecording = true,   // Camera body video button works
            supportsAutofocusTrigger = true, // Camera half-press works
            supportsTouchToFocus = false,
            supportsManualFocusDrive = false,
            supportsExposureSettings = false,
            supportsBatteryStatus = false,
            supportsMediaTransfer = false
        )

        /** Capabilities when operating via PTP over USB Host API */
        val PTP_DEFAULT = CameraCapabilities(
            supportsLiveView = true,
            supportsZoomableLiveView = true,
            supportsPhysicalShutter = true,  // Camera body shutter operable
            supportsRemoteShutter = true,   // PTP 0x90C0
            supportsMovieRecording = true,  // PTP 0x920A (record to SD card)
            supportsAutofocusTrigger = true,// PTP 0x90C1 (AF drive)
            supportsTouchToFocus = true,    // PTP 0x9205 (ChangeAfArea)
            supportsManualFocusDrive = true,// PTP 0x9204 (MfDrive)
            supportsExposureSettings = true,// PTP DeviceProperties (ISO, Shutter, Aperture, EV, WB)
            supportsBatteryStatus = true,   // PTP 0x5001
            supportsMediaTransfer = true    // PTP Object download
        )
    }
}

