package dev.airtouch64.engine

import java.io.File
import java.security.MessageDigest

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: transform-swf <input.swf> <output.swf>" }
    val source = File(args[0]).readBytes()
    val output = SwfTransformations.patchStartupSyncBrushAndPaint(source)
    File(args[1]).apply {
        parentFile?.mkdirs()
        writeBytes(output)
    }
    val hash = MessageDigest.getInstance("SHA-256").digest(output)
        .joinToString("") { "%02x".format(it) }
    println(hash)
}
