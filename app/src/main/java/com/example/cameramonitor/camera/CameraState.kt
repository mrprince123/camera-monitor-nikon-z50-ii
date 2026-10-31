package com.example.cameramonitor.camera

/**
 * High-level state representing the camera connection and streaming lifecycle.
 */
sealed class CameraState {
    object Disconnected : CameraState()
    data class DeviceDetected(val deviceName: String, val vidPid: String) : CameraState()
    data class PermissionPending(val deviceName: String) : CameraState()
    data class Connecting(val stage: String) : CameraState()
    data class Connected(val device: CameraDevice, val capabilities: CameraCapabilities) : CameraState()
    data class Streaming(
        val fps: Int,
        val totalFrames: Long,
        val resolution: String,
        val isRendering: Boolean
    ) : CameraState()
    data class Error(val message: String, val recoverable: Boolean = true) : CameraState()

    override fun toString(): String = when (this) {
        is Disconnected -> "DISCONNECTED"
        is DeviceDetected -> "DEVICE_DETECTED ($deviceName)"
        is PermissionPending -> "PERMISSION_PENDING"
        is Connecting -> "CONNECTING ($stage)"
        is Connected -> "CONNECTED (${device.displayName})"
        is Streaming -> "STREAMING ($fps fps, $resolution)"
        is Error -> "ERROR: $message"
    }
}

