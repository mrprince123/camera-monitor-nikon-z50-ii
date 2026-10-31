package com.example.cameramonitor.camera.usb.ptp

/**
 * Standard PTP (Picture Transfer Protocol, ISO 15740) and Nikon Vendor Extension constants.
 */
object PtpConstants {

    // ── Container Types ────────────────────────────────────────────────────
    const val CONTAINER_UNDEFINED = 0x0000
    const val CONTAINER_COMMAND   = 0x0001
    const val CONTAINER_DATA      = 0x0002
    const val CONTAINER_RESPONSE  = 0x0003
    const val CONTAINER_EVENT     = 0x0004

    // ── Standard PTP Operations (ISO 15740) ────────────────────────────────
    const val CMD_GET_DEVICE_INFO         = 0x1001
    const val CMD_OPEN_SESSION            = 0x1002
    const val CMD_CLOSE_SESSION           = 0x1003
    const val CMD_GET_STORAGE_IDS         = 0x1004
    const val CMD_GET_STORAGE_INFO        = 0x1005
    const val CMD_GET_NUM_OBJECTS         = 0x1006
    const val CMD_GET_OBJECT_HANDLES      = 0x1007
    const val CMD_GET_OBJECT_INFO         = 0x1008
    const val CMD_GET_OBJECT              = 0x1009
    const val CMD_GET_THUMB               = 0x100A
    const val CMD_DELETE_OBJECT           = 0x100B
    const val CMD_INITIATE_CAPTURE        = 0x100E
    const val CMD_GET_DEVICE_PROP_DESC    = 0x1014
    const val CMD_GET_DEVICE_PROP_VALUE   = 0x1015
    const val CMD_SET_DEVICE_PROP_VALUE   = 0x1016
    const val CMD_RESET_DEVICE_PROP_VALUE = 0x1017

    // ── Nikon Vendor Extension Operations (Vendor ID: 0x0000000A) ──────────
    // Core Remote Control & Live View
    const val NIKON_CMD_CAPTURE               = 0x90C0 // Initiate capture (Remote Shutter)
    const val NIKON_CMD_AF_DRIVE              = 0x90C1 // Drive autofocus
    const val NIKON_CMD_START_LIVE_VIEW       = 0x9201 // Enable live view stream
    const val NIKON_CMD_END_LIVE_VIEW         = 0x9202 // Disable live view stream
    const val NIKON_CMD_GET_LIVE_VIEW_IMAGE   = 0x9203 // Fetch live view JPEG frame + AF metadata
    const val NIKON_CMD_MF_DRIVE              = 0x9204 // Manual focus drive (step forward/back)
    const val NIKON_CMD_CHANGE_AF_AREA        = 0x9205 // Touch-to-focus area coordinates
    const val NIKON_CMD_AF_AND_CAPTURE        = 0x9207 // Combined AF check + shutter trigger
    const val NIKON_CMD_START_MOVIE_REC       = 0x920A // Record video to internal SD card
    const val NIKON_CMD_END_MOVIE_REC         = 0x920B // Stop internal video recording
    const val NIKON_CMD_GET_VENDOR_PROP_CODES = 0x90C3 // List vendor-specific properties
    const val NIKON_CMD_GET_VENDOR_PROP_DESC  = 0x9801 // Vendor property descriptor
    const val NIKON_CMD_GET_VENDOR_PROP_VALUE = 0x9802 // Vendor property value
    const val NIKON_CMD_SET_VENDOR_PROP_VALUE = 0x9803 // Set vendor property value

    // ── Response Codes ─────────────────────────────────────────────────────
    const val RESPONSE_UNDEFINED                  = 0x2000
    const val RESPONSE_OK                         = 0x2001
    const val RESPONSE_GENERAL_ERROR              = 0x2002
    const val RESPONSE_SESSION_NOT_OPEN           = 0x2003
    const val RESPONSE_INVALID_TRANSACTION_ID     = 0x2004
    const val RESPONSE_OPERATION_NOT_SUPPORTED    = 0x2005
    const val RESPONSE_PARAMETER_NOT_SUPPORTED    = 0x2006
    const val RESPONSE_INCOMPLETE_TRANSFER        = 0x2007
    const val RESPONSE_INVALID_STORAGE_ID         = 0x2008
    const val RESPONSE_INVALID_OBJECT_HANDLE      = 0x2009
    const val RESPONSE_DEVICE_PROP_NOT_SUPPORTED  = 0x200A
    const val RESPONSE_INVALID_OBJECT_FORMAT_CODE = 0x200B
    const val RESPONSE_STORE_FULL                 = 0x200C
    const val RESPONSE_OBJECT_WRITE_PROTECTED     = 0x200D
    const val RESPONSE_STORE_READ_ONLY            = 0x200E
    const val RESPONSE_ACCESS_DENIED              = 0x200F
    const val RESPONSE_NO_THUMBNAIL_PRESENT       = 0x2010
    const val RESPONSE_SELF_TEST_FAILED           = 0x2011
    const val RESPONSE_PARTIAL_DELETION           = 0x2012
    const val RESPONSE_STORE_NOT_AVAILABLE        = 0x2013
    const val RESPONSE_SPECIFICATION_BY_FORMAT_UNSUPPORTED = 0x2014
    const val RESPONSE_NO_VALID_OBJECT_INFO       = 0x2015
    const val RESPONSE_INVALID_CODE_FORMAT        = 0x2016
    const val RESPONSE_UNKNOWN_VENDOR_CODE        = 0x2017
    const val RESPONSE_CAPTURE_ALREADY_TERMINATED = 0x2018
    const val RESPONSE_DEVICE_BUSY                = 0x2019
    const val RESPONSE_INVALID_PARENT_OBJECT      = 0x201A
    const val RESPONSE_INVALID_DEVICE_PROP_FORMAT = 0x201B
    const val RESPONSE_INVALID_DEVICE_PROP_VALUE  = 0x201C
    const val RESPONSE_INVALID_PARAMETER          = 0x201D
    const val RESPONSE_SESSION_ALREADY_OPEN       = 0x201E
    const val RESPONSE_TRANSACTION_CANCELLED      = 0x201F
    const val RESPONSE_DESTINATION_UNSUPPORTED    = 0x2020

    // ── Nikon Vendor Device Properties ─────────────────────────────────────
    const val PROP_BATTERY_LEVEL             = 0x5001 // Standard PTP Battery Level (0-100)
    const val PROP_WHITE_BALANCE             = 0x5005 // Standard White Balance
    const val PROP_EXPOSURE_PROGRAM_MODE     = 0x500E // PASM mode
    const val PROP_EXPOSURE_BIAS_COMPENSATION= 0x5010 // Standard EV Bias
    const val PROP_STILL_CAPTURE_MODE        = 0x5013 // Single, Continuous, Timer

    const val NIKON_PROP_SHUTTER_SPEED       = 0xD100 // Nikon Shutter Speed (packed uint64/32)
    const val NIKON_PROP_APERTURE            = 0xD101 // Nikon Aperture (f-number * 100)
    const val NIKON_PROP_ISO                 = 0xD102 // Nikon ISO Sensitivity
    const val NIKON_PROP_WHITE_BALANCE       = 0xD109 // Nikon WB enum
    const val NIKON_PROP_EXPOSURE_COMP       = 0xD108 // Nikon EV bias
    const val NIKON_PROP_FOCUS_MODE          = 0xD161 // AF-S, AF-C, AF-A, MF
    const val NIKON_PROP_AF_AREA_MODE        = 0xD162 // Single, Dynamic, Wide, Auto
    const val NIKON_PROP_LIVE_VIEW_STATUS    = 0xD1A2 // 0=Off, 1=On
    const val NIKON_PROP_RECORDING_STATUS    = 0xD1B0 // Video recording state

    // ── Standard Events ────────────────────────────────────────────────────
    const val EVENT_UNDEFINED               = 0x4000
    const val EVENT_CANCEL_TRANSACTION      = 0x4001
    const val EVENT_OBJECT_ADDED            = 0x4002
    const val EVENT_OBJECT_REMOVED          = 0x4003
    const val EVENT_STORE_ADDED             = 0x4004
    const val EVENT_STORE_REMOVED           = 0x4005
    const val EVENT_DEVICE_PROP_CHANGED     = 0x4006
    const val EVENT_OBJECT_INFO_CHANGED     = 0x4007
    const val EVENT_DEVICE_INFO_CHANGED     = 0x4008
    const val EVENT_REQUEST_OBJECT_TRANSFER = 0x4009
    const val EVENT_STORE_FULL              = 0x400A
    const val EVENT_DEVICE_RESET            = 0x400B
    const val EVENT_STORAGE_INFO_CHANGED    = 0x400C
    const val EVENT_CAPTURE_COMPLETE        = 0x400D
    const val EVENT_UNREPORTED_STATUS       = 0x400E
}

