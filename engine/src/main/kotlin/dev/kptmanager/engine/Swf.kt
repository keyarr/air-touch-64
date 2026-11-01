package dev.kptmanager.engine

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** SWF container helpers. Only the compressed flag and the 32-bit length field are touched. */
object Swf {
    fun isCompressed(raw: ByteArray): Boolean = raw.size >= 8 && raw[0] == 'C'.code.toByte()

    fun body(raw: ByteArray): ByteArray {
        require(raw.size >= 8) { "SWF too short" }
        return when (String(raw, 0, 3, Charsets.US_ASCII)) {
            "CWS" -> inflate(raw, 8)
            "FWS" -> raw.copyOfRange(8, raw.size)
            else -> throw IllegalArgumentException("Unsupported SWF signature")
        }
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
        while (!deflater.finished()) {
            val read = deflater.deflate(buffer)
            out.write(buffer, 0, read)
        }
        deflater.end()
        return out.toByteArray()
    }

    fun version(raw: ByteArray): Byte = raw[3]

    private fun inflate(raw: ByteArray, offset: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(raw, offset, raw.size - offset)
        val out = ByteArrayOutputStream(raw.size * 2)
        val buffer = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    continue
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
