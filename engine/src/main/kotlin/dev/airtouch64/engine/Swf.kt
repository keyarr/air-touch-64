package dev.airtouch64.engine

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** SWF container helpers. Only the compressed flag and the 32-bit length field are touched. */
object Swf {
    data class Tag(val type: Int, val payload: ByteArray)

    private data class TagSpan(
        val type: Int,
        val start: Int,
        val payloadStart: Int,
        val end: Int,
        val headerSize: Int,
    )

    fun isCompressed(raw: ByteArray): Boolean = raw.size >= 8 && raw[0] == 'C'.code.toByte()

    fun body(raw: ByteArray): ByteArray {
        require(raw.size >= 8) { "SWF too short" }
        val body = when (String(raw, 0, 3, Charsets.US_ASCII)) {
            "CWS" -> inflate(raw, 8)
            "FWS" -> raw.copyOfRange(8, raw.size)
            else -> throw IllegalArgumentException("Unsupported SWF signature")
        }
        require(readUInt32(raw, 4) == body.size.toLong() + 8) { "SWF length mismatch" }
        return body
    }

    fun compress(version: Byte, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 64)
        out.write("CWS".toByteArray(Charsets.US_ASCII))
        out.write(version.toInt())
        writeInt32(out, body.size + 8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(body)
        deflater.finish()
        val buffer = ByteArray(64 * 1024)
        try {
            while (!deflater.finished()) {
                val read = deflater.deflate(buffer)
                out.write(buffer, 0, read)
            }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    fun tags(raw: ByteArray): List<Tag> {
        val body = body(raw)
        return tagSpans(body).map { span ->
            Tag(span.type, body.copyOfRange(span.payloadStart, span.end))
        }
    }

    fun replaceDoAbc(raw: ByteArray, payload: ByteArray): ByteArray {
        require(payload.size >= 5 && payload.sliceArray(4 until payload.size).contains(0.toByte())) {
            "Invalid DoABC payload"
        }
        val body = body(raw)
        val spans = tagSpans(body)
        val abcTags = spans.filter { it.type == 82 }
        require(abcTags.size == 1) { "Expected one DoABC tag, found ${abcTags.size}" }

        val out = ByteArrayOutputStream(body.size + payload.size)
        val tagsStart = tagsStart(body)
        out.write(body, 0, tagsStart)
        for (span in spans) {
            if (span.type == 82) {
                writeTag(out, span.type, payload, span.headerSize == 6)
            } else {
                out.write(body, span.start, span.end - span.start)
            }
        }
        val updatedBody = out.toByteArray()
        return when (String(raw, 0, 3, Charsets.US_ASCII)) {
            "CWS" -> compress(version(raw), updatedBody)
            "FWS" -> uncompressed(version(raw), updatedBody)
            else -> throw IllegalArgumentException("Unsupported SWF signature")
        }
    }

    fun version(raw: ByteArray): Byte = raw[3]

    private fun uncompressed(version: Byte, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 8)
        out.write("FWS".toByteArray(Charsets.US_ASCII))
        out.write(version.toInt())
        writeInt32(out, body.size + 8)
        out.write(body)
        return out.toByteArray()
    }

    private fun tagSpans(body: ByteArray): List<TagSpan> {
        val start = tagsStart(body)
        val result = mutableListOf<TagSpan>()
        var pos = start
        var ended = false
        while (pos < body.size) {
            require(pos + 2 <= body.size) { "Truncated SWF tag header" }
            val tagStart = pos
            val header = readUInt16(body, pos)
            pos += 2
            val type = header ushr 6
            var length = header and 0x3F
            var headerSize = 2
            if (length == 0x3F) {
                require(pos + 4 <= body.size) { "Truncated SWF long tag header" }
                val longLength = readUInt32(body, pos)
                require(longLength <= Int.MAX_VALUE) { "SWF tag is too large" }
                length = longLength.toInt()
                pos += 4
                headerSize += 4
            }
            val end = pos.toLong() + length
            require(end <= body.size) { "SWF tag exceeds body" }
            result += TagSpan(type, tagStart, pos, end.toInt(), headerSize)
            pos = end.toInt()
            if (type == 0) {
                require(length == 0) { "Invalid SWF End tag" }
                ended = true
                break
            }
        }
        require(ended) { "Missing SWF End tag" }
        require(pos == body.size) { "Unexpected bytes after SWF End tag" }
        return result
    }

    private fun tagsStart(body: ByteArray): Int {
        require(body.isNotEmpty()) { "SWF body too short" }
        val rectBits = 5 + 4 * ((body[0].toInt() and 0xFF) ushr 3)
        val rectBytes = (rectBits + 7) / 8
        val start = rectBytes + 4
        require(start <= body.size) { "Truncated SWF frame header" }
        return start
    }

    private fun writeTag(out: ByteArrayOutputStream, type: Int, payload: ByteArray, forceLong: Boolean) {
        require(type in 0..1023) { "Invalid SWF tag type" }
        if (payload.size < 0x3F && !forceLong) {
            writeUInt16(out, (type shl 6) or payload.size)
        } else {
            writeUInt16(out, (type shl 6) or 0x3F)
            writeInt32(out, payload.size)
        }
        out.write(payload)
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    private fun writeUInt16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun inflate(raw: ByteArray, offset: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(raw, offset, raw.size - offset)
        val out = ByteArrayOutputStream(raw.size * 2)
        val buffer = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0) {
                    if (inflater.needsDictionary()) throw IllegalArgumentException("CWS needs a dictionary")
                    if (inflater.needsInput()) throw IllegalArgumentException("Truncated CWS data")
                    throw IllegalArgumentException("Invalid CWS data")
                }
                out.write(buffer, 0, read)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun writeInt32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}
