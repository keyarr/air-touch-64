package dev.kptmanager.engine

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Classic BSDIFF40 patch application. The bundle is generated with bsdiff4 on the host, so the
 * format is fixed: header, then three bzip2 streams (control, diff, extra).
 */
object BsPatch {
    private const val MAGIC = "BSDIFF40"

    fun patch(old: ByteArray, patch: ByteArray): ByteArray {
        require(patch.size >= 32 && String(patch, 0, 8, Charsets.US_ASCII) == MAGIC) {
            "Not a BSDIFF40 patch"
        }
        val controlSize = readInt64(patch, 8).toInt()
        val diffSize = readInt64(patch, 16).toInt()
        val newSize = readInt64(patch, 24).toInt()
        require(controlSize > 0 && diffSize >= 0 && newSize >= 0) { "Bad patch header" }

        val bodyStart = 32
        val control = bzip2(patch, bodyStart, controlSize)
        val diff = bzip2(patch, bodyStart + controlSize, diffSize)
        val extra = bzip2(
            patch,
            bodyStart + controlSize + diffSize,
            patch.size - bodyStart - controlSize - diffSize,
        )

        val output = ByteArray(newSize)
        var oldPos = 0L
        var newPos = 0
        var controlPos = 0
        var diffPos = 0
        var extraPos = 0

        while (newPos < newSize) {
            require(controlPos + 24 <= control.size) { "Truncated control block" }
            val diffLen = readInt64(control, controlPos).toInt()
            val extraLen = readInt64(control, controlPos + 8).toInt()
            val seek = readInt64(control, controlPos + 16)
            controlPos += 24
            require(diffLen >= 0 && extraLen >= 0 && newPos + diffLen + extraLen <= newSize) {
                "Control block overflows output"
            }

            for (i in 0 until diffLen) {
                val oldIndex = oldPos + i
                val oldByte = if (oldIndex >= 0 && oldIndex < old.size) old[oldIndex.toInt()].toInt() else 0
                output[newPos + i] = (diff[diffPos + i].toInt() + oldByte).toByte()
            }
            diffPos += diffLen
            newPos += diffLen

            extra.copyInto(output, newPos, extraPos, extraPos + extraLen)
            extraPos += extraLen
            newPos += extraLen

            oldPos += diffLen + seek
        }

        return output
    }

    private fun bzip2(source: ByteArray, offset: Int, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val stream: InputStream = BZip2CompressorInputStream(ByteArrayInputStream(source, offset, length))
        val out = ByteArrayOutputStream()
        stream.use { it.copyTo(out) }
        return out.toByteArray()
    }

    /**
     * bsdiff4 stores int64 values as sign and magnitude, with the sign bit at position 63,
     * unlike the classic bsdiff two's complement layout.
     */
    private fun readInt64(source: ByteArray, offset: Int): Long {
        var raw = 0L
        for (i in 0 until 8) {
            raw = raw or ((source[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        val magnitude = raw and Long.MAX_VALUE
        return if (raw < 0) -magnitude else magnitude
    }
}
