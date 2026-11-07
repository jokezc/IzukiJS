package com.benton.izukijs.controller.hid

import android.view.KeyEvent
import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.controller.GestureStroke
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.core.log.LogBus
import kotlin.math.roundToInt

/**
 * 蓝牙 HID 控制后端。手机作 HID 主机，通过外部数位板狗以绝对坐标注入输入。
 *
 * 能力：绝对坐标指针、单指手势、键盘、文本。无控件树/截图/Shell。
 */
class HidController(
    private val client: HidGattClient,
    private val logBus: LogBus,
) : DeviceController {

    override val mode: ControlMode = ControlMode.HID

    private val cachedCapabilities: Set<Capability> = setOf(
        Capability.ABSOLUTE_POINTER,
        Capability.GESTURE,
        Capability.KEY,
        Capability.TEXT,
    )

    override fun capabilities(): Set<Capability> = cachedCapabilities

    override fun isReady(): Boolean = client.isReady

    override fun click(x: Float, y: Float): Boolean =
        client.send(tapFrame(x.toInt(), y.toInt(), TAP_DURATION_MS))

    override fun longClick(x: Float, y: Float, durationMs: Long): Boolean =
        client.send(tapFrame(x.toInt(), y.toInt(), durationMs.toInt()))

    private fun tapFrame(x: Int, y: Int, durationMs: Int) = HidProtocol.tap(x, y, durationMs)

    private fun swipeFrame(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) =
        HidProtocol.swipe(x1, y1, x2, y2, durationMs.toInt(), steps = (durationMs / 16L).toInt().coerceIn(2, 120))

    override fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): Boolean = client.send(swipeFrame(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), durationMs))

    override fun pressKey(keyCode: Int): Boolean {
        return client.sendBatch(keyFrames(keyCode) ?: return false)
    }

    private fun keyFrames(keyCode: Int): List<ByteArray>? = keyUsage(keyCode)?.let { usage ->
        listOf(HidProtocol.key(usage, 0, true), HidProtocol.key(usage, 0, false))
    }

    override fun inputText(text: String): Boolean {
        if (text.isEmpty()) return true
        return client.sendBatch(textFrames(text) ?: return false)
    }

    private fun textFrames(text: String): List<ByteArray>? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val frames = ArrayList<ByteArray>()
        var offset = 0
        val chunkSize = minOf(MAX_TEXT_CHUNK_BYTES, client.maxPayload - 3)
        if (chunkSize < 4) return null
        while (offset < bytes.size) {
            val end = alignToUtf8Boundary(bytes, minOf(offset + chunkSize, bytes.size))
            frames.add(HidProtocol.text(bytes.copyOfRange(offset, end)))
            offset = end
        }
        return frames
    }

    fun submitTap(x: Int, y: Int, durationMs: Int): HidBatchReceipt {
        trackedTapRejection(x, y, durationMs)?.let { return rejectSubmission(it) }
        return client.submitBatch(listOf(tapFrame(x, y, durationMs)))
    }

    fun submitSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): HidBatchReceipt =
        if (listOf(x1, y1, x2, y2).any { it !in 0..65535 } || durationMs !in 1..65535) rejectSubmission("invalid_swipe")
        else client.submitBatch(listOf(swipeFrame(x1, y1, x2, y2, durationMs.toLong())))

    fun submitKey(keyCode: Int): HidBatchReceipt = keyFrames(keyCode)?.let(client::submitBatch)
        ?: rejectSubmission("unsupported_key")

    fun submitText(text: String): HidBatchReceipt = when {
        text.isEmpty() -> rejectSubmission("empty_text")
        else -> textFrames(text)?.let(client::submitBatch) ?: rejectSubmission("text_payload_unavailable")
    }

    fun receipt(id: String): HidBatchReceipt? = client.receipt(id)

    fun rejectSubmission(reason: String): HidBatchReceipt = client.rejectBatch(0, reason)

    /** 回退到 UTF-8 起始字节，避免把一个多字节字符拆到两帧。 */
    private fun alignToUtf8Boundary(bytes: ByteArray, end: Int): Int {
        if (end >= bytes.size) return bytes.size
        var boundary = end
        while (boundary > 0 && (bytes[boundary].toInt() and 0xC0) == 0x80) boundary--
        return if (boundary > 0) boundary else end
    }

    override fun supportsGesture(): Boolean = client.supportsGesture()

    override fun gesture(strokes: List<GestureStroke>): Boolean {
        if (!client.isReady || !client.supportsGesture()) return false
        val frames = ArrayList<ByteArray>()
        for (stroke in strokes) {
            for (frame in strokeToFrames(stroke)) {
                frames.add(frame)
            }
        }
        return client.sendBatch(frames)
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }

    /**
     * 把一条带时间戳的笔画转成若干 CMD_GESTURE 帧。每点 dt = 与上一点的时间差；
     * 超过 [HidProtocol.MAX_GESTURE_POINTS] 时拆帧并用 KEEP_DOWN 续传，中途不抬手。
     */
    private fun strokeToFrames(stroke: GestureStroke): List<ByteArray> {
        val points = stroke.points
        if (points.size < 2) return emptyList()
        val samples = ArrayList<HidProtocol.GestureSample>(points.size)
        for (i in points.indices) {
            val delta = if (i == 0) {
                0L
            } else {
                (points[i].timeMs - points[i - 1].timeMs).coerceAtLeast(0L)
            }
            samples.add(
                HidProtocol.GestureSample(
                    x = points[i].x.roundToInt(),
                    y = points[i].y.roundToInt(),
                    deltaMs = delta.coerceAtMost(0xFFFFL).toInt(),
                ),
            )
        }
        val frames = ArrayList<ByteArray>()
        var index = 0
        var first = true
        while (index < samples.size) {
            val capacity = minOf(HidProtocol.MAX_GESTURE_POINTS, (client.maxPayload - 3) / 6)
            if (capacity < 2) return emptyList()
            val end = minOf(index + capacity, samples.size)
            var flags = if (end < samples.size) HidProtocol.GESTURE_KEEP_DOWN else 0
            // 首帧标记 START：让固件先抬手，避免上一条被打断的手势残留触点导致
            // 本次从陈旧坐标（常见为 0,0）起滑。
            if (first) {
                flags = flags or HidProtocol.GESTURE_START
                first = false
            }
            frames.add(HidProtocol.gesture(flags, samples.subList(index, end)))
            index = end
        }
        return frames
    }

    private fun keyUsage(keyCode: Int): Int? = when {
        keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> HID_USAGE_A + (keyCode - KeyEvent.KEYCODE_A)
        keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> HID_USAGE_1 + (keyCode - KeyEvent.KEYCODE_1)
        keyCode == KeyEvent.KEYCODE_0 -> HID_USAGE_0
        keyCode == KeyEvent.KEYCODE_ENTER -> HID_USAGE_ENTER
        keyCode == KeyEvent.KEYCODE_ESCAPE -> HID_USAGE_ESCAPE
        keyCode == KeyEvent.KEYCODE_DEL -> HID_USAGE_BACKSPACE
        keyCode == KeyEvent.KEYCODE_TAB -> HID_USAGE_TAB
        keyCode == KeyEvent.KEYCODE_SPACE -> HID_USAGE_SPACE
        keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> HID_USAGE_DELETE
        keyCode == KeyEvent.KEYCODE_DPAD_UP -> HID_USAGE_UP
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> HID_USAGE_DOWN
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> HID_USAGE_LEFT
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> HID_USAGE_RIGHT
        else -> null
    }

    companion object {
        internal fun trackedTapRejection(x: Int, y: Int, durationMs: Int): String? = when {
            x !in 0..65535 || y !in 0..65535 || durationMs !in 0..65535 -> "invalid_tap"
            durationMs >= 65534 -> "reserved_repeat_control_duration"
            else -> null
        }

        private const val TAP_DURATION_MS = 50

        /** 单帧文本上限（字节）。预留 MTU 余量，同时避免协议 u16 长度回绕。 */
        private const val MAX_TEXT_CHUNK_BYTES = 257

        private const val HID_USAGE_A = 0x04
        private const val HID_USAGE_1 = 0x1E
        private const val HID_USAGE_0 = 0x27
        private const val HID_USAGE_ENTER = 0x28
        private const val HID_USAGE_ESCAPE = 0x29
        private const val HID_USAGE_BACKSPACE = 0x2A
        private const val HID_USAGE_TAB = 0x2B
        private const val HID_USAGE_SPACE = 0x2C
        private const val HID_USAGE_DELETE = 0x4C
        private const val HID_USAGE_RIGHT = 0x4F
        private const val HID_USAGE_LEFT = 0x50
        private const val HID_USAGE_DOWN = 0x51
        private const val HID_USAGE_UP = 0x52
    }
}
