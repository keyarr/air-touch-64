package dev.airtouch64.engine

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SwfTest {
    @Test
    fun roundTripsCwsAndFwsTagsAndReplacesOnlyDoAbc() {
        for (compressed in listOf(false, true)) {
            for (abcSize in listOf(10, 70)) {
                val originalBody = movieBody(listOf(1 to byteArrayOf(1, 2, 3), 82 to doAbc(abcSize)))
                val original = swf(originalBody, compressed)
                val oldAbc = Swf.tags(original).single { it.type == 82 }.payload

                val roundTrip = Swf.replaceDoAbc(original, oldAbc)
                assertContentEquals(originalBody, Swf.body(roundTrip))

                val replacement = doAbc(if (abcSize == 70) 10 else 90)
                val updated = Swf.replaceDoAbc(original, replacement)
                val tags = Swf.tags(updated)
                assertEquals(listOf(1, 82, 0), tags.map { it.type })
                assertContentEquals(byteArrayOf(1, 2, 3), tags[0].payload)
                assertContentEquals(replacement, tags[1].payload)
                val headerGrowth = if (abcSize == 10) 4 else 0
                assertEquals(originalBody.size + replacement.size - oldAbc.size + headerGrowth, Swf.body(updated).size)
                assertEquals((Swf.body(updated).size + 8).toLong(), uint32(updated, 4))
                if (abcSize == 70) {
                    val updatedBody = Swf.body(updated)
                    assertEquals(0x3F, uint16(updatedBody, 10) and 0x3F)
                }
            }
        }
    }

    @Test
    fun convertsCwsToFwsWithExactBodyAndLength() {
        val body = movieBody(listOf(1 to byteArrayOf(1, 2, 3), 82 to doAbc(70)))
        val converted = Swf.uncompressed(Swf.compress(19, body))
        assertEquals("FWS", String(converted, 0, 3, Charsets.US_ASCII))
        assertContentEquals(body, Swf.body(converted))
        assertEquals(body.size + 8, converted.size)
    }

    @Test
    fun rejectsMissingDuplicateAndTruncatedDoAbcTags() {
        val noAbc = swf(movieBody(listOf(1 to byteArrayOf(1))), false)
        assertFailsWith<IllegalArgumentException> {
            Swf.replaceDoAbc(noAbc, doAbc(5))
        }

        val duplicate = swf(movieBody(listOf(82 to doAbc(5), 82 to doAbc(5))), false)
        assertFailsWith<IllegalArgumentException> {
            Swf.replaceDoAbc(duplicate, doAbc(5))
        }

        val truncatedBody = movieBody(emptyList()) + byteArrayOf(0x7F, 0xFF.toByte())
        assertFailsWith<IllegalArgumentException> {
            Swf.tags(swf(truncatedBody, false))
        }
    }

    @Test
    fun rejectsInvalidContainerLengthsAndTruncatedCwsData() {
        val compressed = Swf.compress(19, movieBody(emptyList()))
        val wrongLength = compressed.copyOf().also { it[4]++ }
        assertFailsWith<IllegalArgumentException> { Swf.body(wrongLength) }
        assertFailsWith<IllegalArgumentException> { Swf.body(compressed.copyOf(compressed.size - 1)) }

        val uncompressed = swf(movieBody(emptyList()), false).also { it[4]++ }
        assertFailsWith<IllegalArgumentException> { Swf.body(uncompressed) }
    }

    private fun movieBody(tags: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 1, 0)) // Empty RECT, frame rate, and one frame.
        for ((type, payload) in tags) writeTag(out, type, payload)
        writeTag(out, 0, byteArrayOf())
        return out.toByteArray()
    }

    private fun doAbc(size: Int): ByteArray = ByteArray(5 + size).also {
        it[4] = 0
        for (index in 5 until it.size) it[index] = index.toByte()
    }

    private fun writeTag(out: ByteArrayOutputStream, type: Int, payload: ByteArray) {
        if (payload.size < 0x3F) {
            writeUInt16(out, (type shl 6) or payload.size)
        } else {
            writeUInt16(out, (type shl 6) or 0x3F)
            writeUInt32(out, payload.size)
        }
        out.write(payload)
    }

    private fun swf(body: ByteArray, compressed: Boolean): ByteArray {
        if (compressed) return Swf.compress(19, body)
        val out = ByteArrayOutputStream()
        out.write("FWS".toByteArray())
        out.write(19)
        writeUInt32(out, body.size + 8)
        out.write(body)
        return out.toByteArray()
    }

    private fun uint32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    private fun uint16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun writeUInt16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeUInt32(out: ByteArrayOutputStream, value: Int) {
        for (shift in 0..3) out.write((value ushr (shift * 8)) and 0xFF)
    }
}
