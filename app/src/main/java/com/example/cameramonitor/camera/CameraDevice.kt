package com.example.cameramonitor.camera

/**
 * Representation of a detected or connected camera device.
 */
data class CameraDevice(
    val deviceId: Int,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String = "",
    val productName: String = "",
    val connectionType: ConnectionType = ConnectionType.USB_UVC
) {
    enum class ConnectionType {
        USB_UVC,
        USB_PTP,
        WIFI_PTP,
        HDMI_CAPTURE
    }

    val vidHex: String = String.format("%04X", vendorId)
    val pidHex: String = String.format("%04X", productId)

    val displayName: String
        get() = when {
            productName.isNotEmpty() -> productName
            vendorId == 0x04B0 && productId == 0x0455 -> "Nikon Z50 II"
            vendorId == 0x04B0 -> "Nikon Camera ($vidHex:$pidHex)"
            else -> "$deviceName ($vidHex:$pidHex)"
        }
}

