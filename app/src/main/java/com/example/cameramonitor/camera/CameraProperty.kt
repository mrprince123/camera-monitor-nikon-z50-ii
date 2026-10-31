package com.example.cameramonitor.camera

import android.graphics.RectF

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
) {
    companion object {
        val ISO_PRESETS = listOf(
            "Auto", "100", "125", "160", "200", "250", "320", "400",
            "500", "640", "800", "1000", "1250", "1600", "2000", "2500",
            "3200", "4000", "5000", "6400", "12800", "25600", "51200"
        )

        val SHUTTER_PRESETS = listOf(
            "1/8000", "1/4000", "1/2000", "1/1000", "1/500", "1/250",
            "1/125", "1/60", "1/30", "1/15", "1/8", "1/4", "1/2",
            "1\"", "2\"", "4\"", "8\"", "15\"", "30\""
        )

        val APERTURE_PRESETS = listOf(
            "f/1.4", "f/1.8", "f/2.0", "f/2.8", "f/3.5", "f/4.0",
            "f/4.5", "f/5.6", "f/6.3", "f/8.0", "f/11", "f/16", "f/22"
        )

        val EV_PRESETS = listOf(
            "-3.0", "-2.7", "-2.3", "-2.0", "-1.7", "-1.3", "-1.0",
            "-0.7", "-0.3", "0.0", "+0.3", "+0.7", "+1.0", "+1.3",
            "+1.7", "+2.0", "+2.3", "+2.7", "+3.0"
        )

        val WB_PRESETS = listOf(
            "Auto", "Daylight", "Cloudy", "Shade", "Incandescent", "Fluorescent", "Flash"
        )
    }
}

/**
 * Real-time autofocus box detected from Nikon live view stream or user touch-to-focus.
 */
data class AfTargetState(
    val rect: RectF, // 0.0 .. 1.0 normalized coordinates
    val status: Int  // 0 = searching (yellow/white), 1 = locked (bright green), 2 = failed (red)
)
