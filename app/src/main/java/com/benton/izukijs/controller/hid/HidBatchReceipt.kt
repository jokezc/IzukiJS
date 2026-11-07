package com.benton.izukijs.controller.hid

/** These are GATT transport states, never firmware/HID/phone execution acknowledgements. */
enum class HidBatchStatus { ACCEPTED, TRANSPORT_COMPLETE, FAILED, CANCELLED, EXPIRED, REJECTED }

data class HidBatchReceipt(
    val id: String,
    val session: Long,
    val status: HidBatchStatus,
    val frameCount: Int,
    val transportCompletedFrames: Int = 0,
    val transportStartedFrames: Int = 0,
    val reason: String? = null,
) {
    val terminal: Boolean get() = status != HidBatchStatus.ACCEPTED
    val accepted: Boolean get() = status != HidBatchStatus.REJECTED
    val executionConfirmed: Boolean get() = false
    // Once a frame starts, timeout/disconnect cannot prove it was not executed.
    val executionUncertain: Boolean get() = transportStartedFrames > 0
}
