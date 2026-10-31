package com.example.cameramonitor.camera

/**
 * Standard photographic exposure and focus properties controllable via PTP.
 */
enum class CameraPropertyType {
    ISO,
    SHUTTER_SPEED,
    APERTURE,
    EXPOSURE_COMPENSATION,
    WHITE_BALANCE,
    FOCUS_MODE,
    AUTOFOCUS_AREA_MODE,
    DRIVE_MODE,
    BATTERY_LEVEL
}

data class CameraPropertyValue(
    val type: CameraPropertyType,
    val rawCode: Int,
    val displayValue: String,
    val isReadOnly: Boolean = false,
    val allowedValues: List<String> = emptyList()
)

