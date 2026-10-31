package com.example.cameramonitor.camera.usb.ptp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.example.cameramonitor.AppLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-level USB PTP transport implementing ISO 15740 framing over USB Bulk endpoints.
 * Handles single-phase (Command -> Response) and two-phase (Command -> Data In/Out -> Response)
 * transactions with timeout management and payload reassembly.
 */
class PtpUsbConnection(
    private val usbManager: UsbManager,
    val device: UsbDevice
) {

    companion object {
        const val USB_CLASS_STILL_IMAGE = 6
        const val DEFAULT_TIMEOUT_MS = 3000
        const val LIVE_VIEW_TIMEOUT_MS = 2000
        const val MAX_BULK_BUFFER_SIZE = 65536 // 64KB read chunks
    }

    private var connection: UsbDeviceConnection? = null
    private var ptpInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var endpointInterrupt: UsbEndpoint? = null

    private val transactionCounter = AtomicInteger(1)
    private val lock = Any()

    val isConnected: Boolean
        get() = connection != null && endpointIn != null && endpointOut != null

    fun nextTransactionId(): Int = transactionCounter.getAndIncrement()

    /**
     * Opens the USB connection, claims the PTP interface, and locates endpoints.
     */
    fun open(): Boolean {
        synchronized(lock) {
            AppLog.i("PtpUsb", "Opening PTP USB connection for ${device.deviceName} (VID=${String.format("%04X", device.vendorId)}, PID=${String.format("%04X", device.productId)})...")

            // Locate Class 6 Still Image Interface
            var selectedIface: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == USB_CLASS_STILL_IMAGE) {
                    selectedIface = iface
                    break
                }
            }

            if (selectedIface == null) {
                AppLog.e("PtpUsb", "No Still Image (Class 6) PTP interface found on ${device.deviceName}!")
                return false
            }

            // Locate Bulk In, Bulk Out, and Interrupt In endpoints
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            var interruptIn: UsbEndpoint? = null

            for (i in 0 until selectedIface.endpointCount) {
                val ep = selectedIface.getEndpoint(i)
                when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            bulkIn = ep
                        } else {
                            bulkOut = ep
                        }
                    }
                    UsbConstants.USB_ENDPOINT_XFER_INT -> {
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            interruptIn = ep
                        }
                    }
                }
            }

            if (bulkIn == null || bulkOut == null) {
                AppLog.e("PtpUsb", "Failed to locate required Bulk IN and Bulk OUT endpoints on PTP interface!")
                return false
            }

            val conn = usbManager.openDevice(device)
            if (conn == null) {
                AppLog.e("PtpUsb", "UsbManager.openDevice() returned null! Permission or system error.")
                return false
            }

            val claimed = conn.claimInterface(selectedIface, true)
            if (!claimed) {
                AppLog.e("PtpUsb", "Failed to claim PTP interface!")
                conn.close()
                return false
            }

            this.connection = conn
            this.ptpInterface = selectedIface
            this.endpointIn = bulkIn
            this.endpointOut = bulkOut
            this.endpointInterrupt = interruptIn

            AppLog.i("PtpUsb", "PTP interface claimed successfully! Endpoints: IN=${bulkIn.address}, OUT=${bulkOut.address}, INT=${interruptIn?.address}")
            return true
        }
    }

    /**
     * Executes a single-phase PTP command (Command -> Response).
     */
    fun executeCommand(code: Int, vararg params: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): PtpPacket {
        synchronized(lock) {
            val conn = connection ?: throw IOException("PTP connection closed")
            val epOut = endpointOut ?: throw IOException("Bulk OUT endpoint missing")
            val epIn = endpointIn ?: throw IOException("Bulk IN endpoint missing")

            val txId = nextTransactionId()
            val cmdPacket = PtpPacket.createCommand(code, txId, *params)
            val cmdBytes = cmdPacket.toByteArray()

            // 1. Send Command
            val sent = conn.bulkTransfer(epOut, cmdBytes, cmdBytes.size, timeoutMs)
            if (sent < cmdBytes.size) {
                throw IOException("PTP write failed: sent $sent of ${cmdBytes.size} bytes for cmd 0x${String.format("%04X", code)}")
            }

            // 2. Read Response
            val readBuffer = ByteArray(1024)
            val received = conn.bulkTransfer(epIn, readBuffer, readBuffer.size, timeoutMs)
            if (received < PtpPacket.HEADER_SIZE) {
                throw IOException("PTP read failed: received only $received bytes for cmd 0x${String.format("%04X", code)}")
            }

            return PtpPacket.parse(readBuffer, 0, received)
        }
    }

    /**
     * Executes a two-phase PTP command that receives data (Command -> Data In -> Response).
     * Used for Live View frames, Device Info, and Property values.
     */
    fun executeCommandWithDataIn(code: Int, vararg params: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Pair<PtpPacket, ByteArray> {
        synchronized(lock) {
            val conn = connection ?: throw IOException("PTP connection closed")
            val epOut = endpointOut ?: throw IOException("Bulk OUT endpoint missing")
            val epIn = endpointIn ?: throw IOException("Bulk IN endpoint missing")

            val txId = nextTransactionId()
            val cmdPacket = PtpPacket.createCommand(code, txId, *params)
            val cmdBytes = cmdPacket.toByteArray()

            // 1. Send Command
            val sent = conn.bulkTransfer(epOut, cmdBytes, cmdBytes.size, timeoutMs)
            if (sent < cmdBytes.size) {
                throw IOException("PTP write failed for cmd 0x${String.format("%04X", code)}")
            }

            // 2. Read Data Phase
            // Read initial chunk to get the container length and type
            val initialBuffer = ByteArray(MAX_BULK_BUFFER_SIZE)
            val firstRead = conn.bulkTransfer(epIn, initialBuffer, initialBuffer.size, timeoutMs)
            if (firstRead < PtpPacket.HEADER_SIZE) {
                throw IOException("PTP read data phase failed: received $firstRead bytes")
            }

            val bb = ByteBuffer.wrap(initialBuffer, 0, firstRead).order(ByteOrder.LITTLE_ENDIAN)
            val totalContainerLength = bb.int
            val containerType = bb.short.toInt() and 0xFFFF

            // If the camera returned an immediate Response packet instead of Data (e.g. Device Busy or Error)
            if (containerType == PtpConstants.CONTAINER_RESPONSE) {
                val respPacket = PtpPacket.parse(initialBuffer, 0, firstRead)
                return Pair(respPacket, ByteArray(0))
            }

            val expectedPayloadSize = totalContainerLength - PtpPacket.HEADER_SIZE
            val dataStream = ByteArrayOutputStream(expectedPayloadSize.coerceAtLeast(1024))

            // Write payload from first read
            val firstPayloadLen = firstRead - PtpPacket.HEADER_SIZE
            if (firstPayloadLen > 0) {
                dataStream.write(initialBuffer, PtpPacket.HEADER_SIZE, firstPayloadLen)
            }

            // Continue reading until all expected data bytes are received
            var bytesRemaining = expectedPayloadSize - firstPayloadLen
            val chunkBuffer = ByteArray(MAX_BULK_BUFFER_SIZE)

            while (bytesRemaining > 0) {
                val readChunk = conn.bulkTransfer(epIn, chunkBuffer, minOf(chunkBuffer.size, bytesRemaining), timeoutMs)
                if (readChunk <= 0) {
                    AppLog.w("PtpUsb", "PTP data read timed out or truncated: $bytesRemaining bytes remaining")
                    break
                }
                dataStream.write(chunkBuffer, 0, readChunk)
                bytesRemaining -= readChunk
            }

            // 3. Read Final Response Phase (CONTAINER_RESPONSE)
            val respBuffer = ByteArray(1024)
            val respRead = conn.bulkTransfer(epIn, respBuffer, respBuffer.size, timeoutMs)
            val respPacket = if (respRead >= PtpPacket.HEADER_SIZE) {
                PtpPacket.parse(respBuffer, 0, respRead)
            } else {
                PtpPacket(PtpPacket.HEADER_SIZE, PtpConstants.CONTAINER_RESPONSE, PtpConstants.RESPONSE_OK, txId)
            }

            return Pair(respPacket, dataStream.toByteArray())
        }
    }

    /**
     * Executes a two-phase PTP command that sends data (Command -> Data Out -> Response).
     * Used for setting device properties.
     */
    fun executeCommandWithDataOut(code: Int, data: ByteArray, vararg params: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): PtpPacket {
        synchronized(lock) {
            val conn = connection ?: throw IOException("PTP connection closed")
            val epOut = endpointOut ?: throw IOException("Bulk OUT endpoint missing")
            val epIn = endpointIn ?: throw IOException("Bulk IN endpoint missing")

            val txId = nextTransactionId()
            val cmdPacket = PtpPacket.createCommand(code, txId, *params)
            val cmdBytes = cmdPacket.toByteArray()

            // 1. Send Command
            val sentCmd = conn.bulkTransfer(epOut, cmdBytes, cmdBytes.size, timeoutMs)
            if (sentCmd < cmdBytes.size) {
                throw IOException("PTP write command failed")
            }

            // 2. Send Data
            val dataPacket = PtpPacket.createData(code, txId, data)
            val dataBytes = dataPacket.toByteArray()
            val sentData = conn.bulkTransfer(epOut, dataBytes, dataBytes.size, timeoutMs)
            if (sentData < dataBytes.size) {
                throw IOException("PTP write data failed")
            }

            // 3. Read Response
            val respBuffer = ByteArray(1024)
            val respRead = conn.bulkTransfer(epIn, respBuffer, respBuffer.size, timeoutMs)
            if (respRead < PtpPacket.HEADER_SIZE) {
                throw IOException("PTP read response failed")
            }

            return PtpPacket.parse(respBuffer, 0, respRead)
        }
    }

    /**
     * Polls the interrupt endpoint for asynchronous camera events (non-blocking / short timeout).
     */
    fun pollEvent(timeoutMs: Int = 50): PtpPacket? {
        val epInt = endpointInterrupt ?: return null
        val conn = connection ?: return null
        val buffer = ByteArray(64)
        val read = conn.bulkTransfer(epInt, buffer, buffer.size, timeoutMs)
        return if (read >= PtpPacket.HEADER_SIZE) {
            PtpPacket.parse(buffer, 0, read)
        } else {
            null
        }
    }

    /**
     * Closes the connection and releases claimed interfaces.
     */
    fun close() {
        synchronized(lock) {
            AppLog.i("PtpUsb", "Closing PTP USB connection...")
            try {
                ptpInterface?.let { iface ->
                    connection?.releaseInterface(iface)
                }
            } catch (e: Exception) {
                AppLog.w("PtpUsb", "Error releasing PTP interface: ${e.message}")
            }

            try {
                connection?.close()
            } catch (e: Exception) {
                AppLog.w("PtpUsb", "Error closing UsbDeviceConnection: ${e.message}")
            }

            connection = null
            ptpInterface = null
            endpointIn = null
            endpointOut = null
            endpointInterrupt = null
        }
    }
}

