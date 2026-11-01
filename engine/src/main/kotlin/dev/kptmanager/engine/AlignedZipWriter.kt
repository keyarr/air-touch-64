package dev.kptmanager.engine

import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Minimal ZIP writer that can align the start of stored entries. Android requires
 * `resources.arsc` and native libraries to be page aligned inside the APK, which a plain
 * ZipOutputStream cannot express.
 */
class AlignedZipWriter(path: File) : Closeable {
    private data class Record(
        val name: ByteArray,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val flags: Int,
        val time: Int,
        val date: Int,
    )

    private val raf = RandomAccessFile(path, "rw")
    private val records = mutableListOf<Record>()

    init {
        raf.setLength(0)
    }

    fun add(name: String, data: ByteArray, store: Boolean, alignment: Int) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val flags = if (nameBytes.any { it.toInt() and 0xFF > 0x7F }) 0x0800 else 0
        val method = if (store) 0 else 8
        val headerOffset = raf.length()

        var padding = ((alignment - ((headerOffset + 30 + nameBytes.size) % alignment)) % alignment).toInt()
        if (padding in 1..3) padding += 4

        val crc = CRC32().apply { update(data) }.value
        val payload = if (store) data else deflate(data)

        raf.seek(headerOffset)
        writeLe32(0x04034b50)
        writeLe16(20)
        writeLe16(flags)
        writeLe16(method)
        writeLe16(0)
        writeLe16(0x21)
        writeLe32(crc.toInt())
        writeLe32(payload.size)
        writeLe32(data.size)
        writeLe16(nameBytes.size)
        writeLe16(padding)
        raf.write(nameBytes)
        writeExtra(padding)
        raf.write(payload)

        records += Record(
            name = nameBytes,
            method = method,
            crc = crc,
            compressedSize = payload.size.toLong(),
            uncompressedSize = data.size.toLong(),
            localHeaderOffset = headerOffset,
            flags = flags,
            time = 0,
            date = 0x21,
        )
    }

    fun finish() {
        val directoryOffset = raf.length()
        for (record in records) {
            writeLe32(0x02014b50)
            writeLe16(20)
            writeLe16(20)
            writeLe16(record.flags)
            writeLe16(record.method)
            writeLe16(record.time)
            writeLe16(record.date)
            writeLe32(record.crc.toInt())
            writeLe32(record.compressedSize.toInt())
            writeLe32(record.uncompressedSize.toInt())
            writeLe16(record.name.size)
            writeLe16(0)
            writeLe16(0)
            writeLe16(0)
            writeLe16(0)
            writeLe32(0)
            writeLe32(record.localHeaderOffset.toInt())
            raf.write(record.name)
        }
        val directorySize = raf.length() - directoryOffset
        writeLe32(0x06054b50)
        writeLe16(0)
        writeLe16(0)
        writeLe16(records.size)
        writeLe16(records.size)
        writeLe32(directorySize.toInt())
        writeLe32(directoryOffset.toInt())
        writeLe16(0)
        raf.fd.sync()
    }

    override fun close() {
        raf.close()
    }

    private fun writeExtra(padding: Int) {
        if (padding == 0) return
        writeLe16(0xD935)
        writeLe16(padding - 4)
        repeat(padding - 4) { raf.write(0) }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size / 2 + 64)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            DeflaterOutputStream(out, deflater).use { it.write(data) }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    private fun writeLe16(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
    }

    private fun writeLe32(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
        raf.write((value ushr 16) and 0xFF)
        raf.write((value ushr 24) and 0xFF)
    }
}

/**
 * Writes to a RandomAccessFile while tracking the position, so the entry header can be written
 * with sizes already known.
 */
private class RafOutputStream(private val raf: RandomAccessFile) : OutputStream() {
    override fun write(b: Int) {
        raf.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        raf.write(b, off, len)
    }
}
