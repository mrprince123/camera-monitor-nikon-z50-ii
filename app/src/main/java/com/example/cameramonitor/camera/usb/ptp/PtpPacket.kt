package com.example.cameramonitor.camera.usb.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encapsulates a standard PTP / MTP binary packet container.
 * Header format:
 *   [0..3]   Container Length (UInt32 little-endian, includes 12-byte header)
 *   [4..5]   Container Type   (UInt16 little-endian: Command=1, Data=2, Response=3, Event=4)
 *   [6..7]   Code             (UInt16 little-endian: OpCode / RespCode / EventCode)
 *   [8..11]  Transaction ID   (UInt32 little-endian)
 *   [12..N]  Payload / Parameters (UInt32 parameters or raw data bytes)
 */
class PtpPacket(
    val length: Int,
    val type: Int,
    val code: Int,
    val transactionId: Int,
    val payload: ByteArray = ByteArray(0)
) {

    val isOk: Boolean
        get() = (type == PtpConstants.CONTAINER_RESPONSE && code == PtpConstants.RESPONSE_OK)

    val isBusy: Boolean
        get() = (type == PtpConstants.CONTAINER_RESPONSE && code == PtpConstants.RESPONSE_DEVICE_BUSY)

    /** Read a 32-bit integer parameter at parameter index (0..4) */
    fun getParam(index: Int): Int {
        val offset = index * 4
        if (offset + 4 > payload.size) return 0
        val bb = ByteBuffer.wrap(payload, offset, 4).order(ByteOrder.LITTLE_ENDIAN)
        return bb.int
    }

    /** Serializes this packet into a ByteBuffer ready for UsbDeviceConnection.bulkTransfer */
    fun toByteArray(): ByteArray {
        val totalLength = 12 + payload.size
        val bb = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(totalLength)
        bb.putShort(type.toShort())
        bb.putShort(code.toShort())
        bb.putInt(transactionId)
        if (payload.isNotEmpty()) {
            bb.put(payload)
        }
        return bb.array()
    }

    override fun toString(): String {
        val typeStr = when (type) {
            PtpConstants.CONTAINER_COMMAND -> "CMD"
            PtpConstants.CONTAINER_DATA -> "DATA"
            PtpConstants.CONTAINER_RESPONSE -> "RESP"
            PtpConstants.CONTAINER_EVENT -> "EVENT"
            else -> "TYPE_$type"
        }
        val codeHex = String.format("0x%04X", code)
        return "PtpPacket[$typeStr code=$codeHex txId=$transactionId len=$length payloadLen=${payload.size}]"
    }

    companion object {
        const val HEADER_SIZE = 12

        /** Create a PTP Command Packet */
        fun createCommand(code: Int, transactionId: Int, vararg params: Int): PtpPacket {
            val payloadBytes = ByteArray(params.size * 4)
            val bb = ByteBuffer.wrap(payloadBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (p in params) {
                bb.putInt(p)
            }
            return PtpPacket(
                length = HEADER_SIZE + payloadBytes.size,
                type = PtpConstants.CONTAINER_COMMAND,
                code = code,
                transactionId = transactionId,
                payload = payloadBytes
            )
        }

        /** Create a PTP Data Packet */
        fun createData(code: Int, transactionId: Int, data: ByteArray): PtpPacket {
            return PtpPacket(
                length = HEADER_SIZE + data.size,
                type = PtpConstants.CONTAINER_DATA,
                code = code,
                transactionId = transactionId,
                payload = data
            )
        }

        /** Parse a PTP packet from a raw received byte buffer */
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size): PtpPacket {
            require(length >= HEADER_SIZE) { "PTP packet too short: $length < $HEADER_SIZE bytes" }
            val bb = ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN)
            val totalLength = bb.int
            val type = bb.short.toInt() and 0xFFFF
            val code = bb.short.toInt() and 0xFFFF
            val transactionId = bb.int

            val payloadLength = (totalLength - HEADER_SIZE).coerceAtLeast(0)
            val availablePayload = (length - HEADER_SIZE).coerceAtLeast(0)
            val readLength = minOf(payloadLength, availablePayload)

            val payload = ByteArray(readLength)
            if (readLength > 0) {
                bb.get(payload)
            }

            return PtpPacket(
                length = totalLength,
                type = type,
                code = code,
                transactionId = transactionId,
                payload = payload
            )
        }
    }
}

