package com.example.cameramonitor.camera.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.example.cameramonitor.AppLog
import com.example.cameramonitor.camera.CameraDevice

/**
 * Detects, inspects, and categorizes USB devices attached to Android in Host mode.
 */
class UsbDeviceDetector(private val usbManager: UsbManager) {

    companion object {
        const val USB_CLASS_AUDIO = 1
        const val USB_CLASS_STILL_IMAGE_PTP = 6
        const val USB_CLASS_MASS_STORAGE = 8
        const val USB_CLASS_VIDEO_UVC = 14

        const val NIKON_VENDOR_ID = 0x04B0
        const val NIKON_Z50_II_PRODUCT_ID = 0x0455
    }

    enum class DeviceCategory {
        UVC_VIDEO,         // Standard UVC webcam or USB HDMI capture card
        PTP_CAMERA,        // Still Image PTP camera (tethering / remote control mode)
        COMPOSITE_UVC_PTP, // Composite camera exposing both UVC and PTP
        UNSUPPORTED
    }

    data class DeviceInspectionResult(
        val device: UsbDevice,
        val category: DeviceCategory,
        val isNikonCamera: Boolean,
        val interfaceSummary: String
    )

    fun inspectDevice(device: UsbDevice): DeviceInspectionResult {
        var hasUvc = false
        var hasPtp = false
        val ifaceSummary = StringBuilder()

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val desc = when (iface.interfaceClass) {
                USB_CLASS_VIDEO_UVC -> { hasUvc = true; "Video/UVC (Class 14)" }
                USB_CLASS_STILL_IMAGE_PTP -> { hasPtp = true; "StillImage/PTP (Class 6)" }
                USB_CLASS_AUDIO -> "Audio/UAC (Class 1)"
                USB_CLASS_MASS_STORAGE -> "MassStorage (Class 8)"
                else -> "Class ${iface.interfaceClass}"
            }
            ifaceSummary.append("\n  • Iface #$i: $desc (endpoints=${iface.endpointCount})")
        }

        val category = when {
            hasUvc && hasPtp -> DeviceCategory.COMPOSITE_UVC_PTP
            hasUvc -> DeviceCategory.UVC_VIDEO
            hasPtp -> DeviceCategory.PTP_CAMERA
            else -> DeviceCategory.UNSUPPORTED
        }

        val isNikon = (device.vendorId == NIKON_VENDOR_ID)
        return DeviceInspectionResult(
            device = device,
            category = category,
            isNikonCamera = isNikon,
            interfaceSummary = ifaceSummary.toString()
        )
    }

    fun findFirstSupportedDevice(): DeviceInspectionResult? {
        val deviceList = usbManager.deviceList
        AppLog.d("UsbDetector", "Scanning USB deviceList (${deviceList.size} devices connected)")

        // 1. Prioritize Nikon Camera if present
        for (dev in deviceList.values) {
            val result = inspectDevice(dev)
            if (result.isNikonCamera && result.category != DeviceCategory.UNSUPPORTED) {
                AppLog.i("UsbDetector", "Found target Nikon camera: ${dev.deviceName} [${result.category}]")
                return result
            }
        }

        // 2. Otherwise return any supported UVC or PTP device (e.g. HDMI capture card)
        for (dev in deviceList.values) {
            val result = inspectDevice(dev)
            if (result.category != DeviceCategory.UNSUPPORTED) {
                AppLog.i("UsbDetector", "Found compatible device: ${dev.deviceName} [${result.category}]")
                return result
            }
        }

        return null
    }

    fun toCameraDevice(device: UsbDevice, category: DeviceCategory): CameraDevice {
        val connectionType = when (category) {
            DeviceCategory.PTP_CAMERA -> CameraDevice.ConnectionType.USB_PTP
            DeviceCategory.COMPOSITE_UVC_PTP,
            DeviceCategory.UVC_VIDEO -> {
                // Check if it's an HDMI video capture card
                if (device.productName?.contains("Capture", ignoreCase = true) == true ||
                    device.productName?.contains("HDMI", ignoreCase = true) == true ||
                    device.productName?.contains("Cam Link", ignoreCase = true) == true) {
                    CameraDevice.ConnectionType.HDMI_CAPTURE
                } else {
                    CameraDevice.ConnectionType.USB_UVC
                }
            }
            else -> CameraDevice.ConnectionType.USB_UVC
        }

        return CameraDevice(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            productName = device.productName ?: "",
            connectionType = connectionType
        )
    }
}

