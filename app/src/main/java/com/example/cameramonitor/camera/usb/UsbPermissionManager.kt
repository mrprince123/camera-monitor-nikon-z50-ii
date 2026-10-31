package com.example.cameramonitor.camera.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.example.cameramonitor.AppLog

/**
 * Manages USB permission requests with session validation, rejecting stale callbacks.
 */
class UsbPermissionManager(
    private val context: Context,
    private val usbManager: UsbManager,
    private val onPermissionResult: (device: UsbDevice, granted: Boolean, sessionId: Long) -> Unit
) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.cameramonitor.USB_PERMISSION"
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"
    }

    private var isReceiverRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action != ACTION_USB_PERMISSION) return

            val intentSessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            if (device != null) {
                AppLog.i("UsbPermission", "Permission callback received: granted=$granted, dev=${device.deviceName}, session=$intentSessionId")
                onPermissionResult(device, granted, intentSessionId)
            } else {
                AppLog.e("UsbPermission", "Permission callback received with null UsbDevice!")
            }
        }
    }

    fun start() {
        if (isReceiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }
        isReceiverRegistered = true
        AppLog.d("UsbPermission", "UsbPermissionManager registered")
    }

    fun stop() {
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(permissionReceiver)
        } catch (_: Exception) {}
        isReceiverRegistered = false
        AppLog.d("UsbPermission", "UsbPermissionManager unregistered")
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice, sessionId: Long) {
        AppLog.i("UsbPermission", "[Session #$sessionId] Requesting USB permission for ${device.deviceName}...")

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            sessionId.toInt(),
            intent,
            flags
        )

        usbManager.requestPermission(device, pendingIntent)
    }
}

