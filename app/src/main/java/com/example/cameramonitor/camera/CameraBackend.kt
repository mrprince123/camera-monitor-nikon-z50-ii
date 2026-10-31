package com.example.cameramonitor.camera

import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import kotlinx.coroutines.flow.StateFlow

/**
 * Unified camera communication backend interface.
 * Implemented independently by UvcCameraBackend (UVC streaming / HDMI capture)
 * and PtpUsbCameraBackend (PTP remote shooting & live view).
 */
interface CameraBackend {

    /** Type of backend connection */
    val backendType: CameraDevice.ConnectionType

    /** Observable state flow for UI and lifecycle observers */
    val state: StateFlow<CameraState>

    /** Capabilities supported by this specific backend */
    val capabilities: CameraCapabilities

    /** Diagnostic info for on-screen HUD and debug logs */
    val diagnosticSummary: String

    /** Connect to the camera using the verified device and session token */
    suspend fun connect(device: UsbDevice, sessionId: Long): Result<Unit>

    /** Gracefully disconnect and release all hardware and native resources */
    suspend fun disconnect(reason: String)

    /** Connect preview SurfaceTexture directly to video sink */
    fun setPreviewSurface(surfaceTexture: SurfaceTexture?)

    // ── Remote Controls (Only supported backends implement these) ──────────

    suspend fun triggerShutter(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Shutter trigger not supported on $backendType"))

    suspend fun startMovieRecording(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Movie recording not supported on $backendType"))

    suspend fun stopMovieRecording(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Movie recording not supported on $backendType"))

    suspend fun triggerAutofocus(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Autofocus not supported on $backendType"))

    suspend fun setTouchFocusPoint(point: PointF): Result<Unit> =
        Result.failure(UnsupportedOperationException("Touch-to-focus not supported on $backendType"))

    suspend fun setProperty(property: CameraPropertyType, value: CameraPropertyValue): Result<Unit> =
        Result.failure(UnsupportedOperationException("Settings control not supported on $backendType"))

    suspend fun getProperty(property: CameraPropertyType): Result<CameraPropertyValue> =
        Result.failure(UnsupportedOperationException("Settings read not supported on $backendType"))
}

