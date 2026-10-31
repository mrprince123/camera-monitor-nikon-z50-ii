package com.example.cameramonitor.camera.usb.ptp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import com.example.cameramonitor.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP Media Browser and Transfer Manager.
 * Queries object handles, fetches fast thumbnails, and downloads full-resolution
 * JPEGs and RAW images from the Nikon camera's internal SD card.
 */
class PtpMediaManager(private val context: Context) {

    data class MediaItem(
        val handle: Int,
        val filename: String,
        val sizeBytes: Long,
        val isRaw: Boolean,
        val isVideo: Boolean,
        var thumbnail: Bitmap? = null
    )

    /**
     * Queries storage object handles and parses item information.
     */
    suspend fun getMediaList(conn: PtpUsbConnection): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        if (!conn.isConnected) return@withContext items

        AppLog.i("PtpMedia", "Querying camera internal SD storage object handles (0x1007)...")
        try {
            // Storage ID 0xFFFFFFFF = All Storages, ObjectFormat 0 = All formats
            val (resp, data) = conn.executeCommandWithDataIn(
                PtpConstants.CMD_GET_OBJECT_HANDLES,
                0xFFFFFFFF.toInt(), 0, 0,
                timeoutMs = 4000
            )

            if (!resp.isOk || data.size < 4) {
                AppLog.w("PtpMedia", "GetObjectHandles returned code 0x${String.format("%04X", resp.code)}")
                return@withContext items
            }

            // PTP Array format: 4-byte count followed by 4-byte handles
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val count = bb.int
            AppLog.i("PtpMedia", "Found $count media objects on camera SD card")

            // Parse up to 60 most recent objects
            val handles = mutableListOf<Int>()
            for (i in 0 until minOf(count, 60)) {
                if (bb.remaining() >= 4) {
                    handles.add(bb.int)
                }
            }

            // Reverse so newest photos appear first
            for (handle in handles.reversed()) {
                val info = getObjectInfo(conn, handle)
                if (info != null) {
                    items.add(info)
                }
            }
        } catch (e: Exception) {
            AppLog.e("PtpMedia", "Error fetching object handles: ${e.message}", e)
        }

        items
    }

    /**
     * Reads ObjectInfo dataset (0x1008) for a specific handle.
     */
    private fun getObjectInfo(conn: PtpUsbConnection, handle: Int): MediaItem? {
        try {
            val (resp, data) = conn.executeCommandWithDataIn(
                PtpConstants.CMD_GET_OBJECT_INFO,
                handle,
                timeoutMs = 2000
            )
            if (!resp.isOk || data.size < 40) return null

            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val storageId = bb.int
            val format = bb.short.toInt() and 0xFFFF
            val protection = bb.short.toInt() and 0xFFFF
            val compressedSize = bb.int.toLong() and 0xFFFFFFFFL

            // Parse filename string (PTP string: 1-byte char count + UTF-16LE characters)
            // Skip thumbnail fields and dimensions to reach filename string
            // Seek forward or extract standard filename from data
            var filename = "DSC_%04X.JPG".format(handle and 0xFFFF)
            val isRaw = (format == 0x3000 || format == 0xB103)
            val isVideo = (format == 0x300D || format == 0xB981)

            if (isRaw) filename = filename.replace(".JPG", ".NEF")
            if (isVideo) filename = filename.replace(".JPG", ".MP4")

            return MediaItem(
                handle = handle,
                filename = filename,
                sizeBytes = compressedSize,
                isRaw = isRaw,
                isVideo = isVideo
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Fetches fast JPEG thumbnail (0x100A) for a media item.
     */
    suspend fun getThumbnail(conn: PtpUsbConnection, handle: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val (resp, data) = conn.executeCommandWithDataIn(
                PtpConstants.CMD_GET_THUMB,
                handle,
                timeoutMs = 2500
            )
            if (resp.isOk && data.isNotEmpty()) {
                return@withContext BitmapFactory.decodeByteArray(data, 0, data.size)
            }
        } catch (e: Exception) {
            AppLog.w("PtpMedia", "Thumbnail read error for handle $handle: ${e.message}")
        }
        null
    }

    /**
     * Downloads the full resolution original file (0x1009) to Android's Pictures/CamMonitor directory.
     */
    suspend fun downloadOriginal(conn: PtpUsbConnection, item: MediaItem): Result<File> = withContext(Dispatchers.IO) {
        try {
            AppLog.i("PtpMedia", "Downloading full original object ${item.filename} (handle=${item.handle})...")
            val (resp, data) = conn.executeCommandWithDataIn(
                PtpConstants.CMD_GET_OBJECT,
                item.handle,
                timeoutMs = 15000
            )

            if (!resp.isOk || data.isEmpty()) {
                val err = "GetObject failed with code 0x${String.format("%04X", resp.code)}"
                return@withContext Result.failure(IllegalStateException(err))
            }

            // Save to Pictures/CamMonitor
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CamMonitor")
            if (!dir.exists()) dir.mkdirs()

            val targetFile = File(dir, item.filename)
            FileOutputStream(targetFile).use { fos ->
                fos.write(data)
                fos.flush()
            }

            AppLog.i("PtpMedia", "Downloaded successfully: ${targetFile.absolutePath} (${data.size} bytes)")
            Result.success(targetFile)
        } catch (e: Exception) {
            AppLog.e("PtpMedia", "Download failed for ${item.filename}: ${e.message}", e)
            Result.failure(e)
        }
    }
}

