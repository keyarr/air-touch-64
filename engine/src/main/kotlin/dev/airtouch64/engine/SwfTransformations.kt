package dev.airtouch64.engine

import java.security.MessageDigest

object SwfTransformations {
    private val startupInputHashes = setOf(
        "1558aa31a62f23171a19ddd2c2c3147eed89a9b6280401e28ca0fd7491675231",
        "d941eab1489b086fadeddfa921d0ffd031a69113b058e81482732c9641664f21",
    )
    private val expectedCode = hex("d0 46 86 0a 00 66 81 1c  d0 46 86 0a 00 66 81 1c  66 ce 4c 93 66 a9 69")
    private val replacementCode = hex("60 aa 69 60 94 51 46 af 0a 01 29 20") + ByteArray(11) { 0x02 }
    private const val applicationClass = "com.adobe.ttpixel.app::TTApplication"

    fun patchStartup(raw: ByteArray): ByteArray {
        val hash = sha256(raw)
        require(hash in startupInputHashes) { "Unexpected startup SWF SHA-256: $hash" }
        require(String(raw, 0, 3, Charsets.US_ASCII) == "CWS") { "Expected compressed CWS input" }

        return patchStartupAbc(raw)
    }

    fun patchStartupAndLocalSync(raw: ByteArray): ByteArray = patchLocalSync(patchStartup(raw))

    fun patchLocalSync(raw: ByteArray): ByteArray = transformAbc(raw) { abc ->
        val owner = "com.adobe.ttpixel.media.creativecloud::TTSyncEngine"
        val method = abc.findMethods(owner, "initialize").single()
        val code = abc.methodCode(method.methodIndex) ?: error("initialize has no method body")
        val wrapper = abc.multinameIndices("TTExtensionWrapper").single { abc.qualifiedName(it) == "com.adobe.ttpixel.editor.processor::TTExtensionWrapper" }
        val instance = abc.multinameIndices("instance").toSet()
        val available = abc.multinameIndices("isAvailable").toSet()
        val useNative = abc.multinameIndices("useNativeExtensions").toSet()
        val start = findSequence(code, wrapper, instance, available, useNative)
        val expressionEnd = start + expressionLength(code, start)
        val expected = code.copyOfRange(start, expressionEnd)
        val replacement = byteArrayOf(0x27) + ByteArray(expected.size - 1) { 0x02 }
        listOf(abc.methodCodePatch(method.methodIndex, start, expected, replacement))
    }

    private fun patchStartupAbc(raw: ByteArray): ByteArray = transformAbc(raw) { abc ->
        val method = abc.findMethods(applicationClass, "applicationWillRun").single()
        require(method.methodIndex == 1901) { "Unexpected applicationWillRun method index" }

        val stageTrait = abc.findTraits(applicationClass, "cStage", isStatic = true).single().trait
        val stageNamespace = abc.namespaceIndex(stageTrait.nameIndex)
            ?: throw IllegalArgumentException("cStage is not a QName")
        require(stageNamespace != 1 && abc.namespaceInfo(stageNamespace) != null) {
            "Unexpected cStage namespace"
        }
        require(abc.namespaceInfo(1)?.let { it.kind == 0x16 && it.name.isEmpty() } == true) {
            "Unexpected package namespace at index 1"
        }

        val patches = listOf(
            abc.methodCodePatch(method.methodIndex, 96, expectedCode, replacementCode),
            abc.namespaceIndexPatch(stageTrait.nameIndex, 1),
        )
        patches
    }

    private fun transformAbc(raw: ByteArray, patches: (AbcFile) -> List<AbcFile.BytePatch>): ByteArray {
        val doAbc = Swf.tags(raw).single { it.type == 82 }.payload
        val start = abcStart(doAbc)
        val abc = AbcFile.parse(doAbc.copyOfRange(start, doAbc.size))
        val replacementDoAbc = doAbc.copyOfRange(0, start) + abc.serialize(patches(abc))
        return Swf.replaceDoAbc(raw, replacementDoAbc)
    }

    private fun findSequence(
        code: ByteArray,
        wrapper: Int,
        instance: Set<Int>,
        available: Set<Int>,
        useNative: Set<Int>,
    ): Int {
        val matches = code.indices.filter { start ->
            val first = readU30(code, start + 1)
            if (code[start].toInt() and 0xFF != 0x60 || first?.first != wrapper) return@filter false
            var cursor = start + 1 + first.second
            if (byteAt(code, cursor++) != 0x66) return@filter false
            val second = readU30(code, cursor) ?: return@filter false
            if (second.first !in instance) return@filter false
            cursor += second.second
            if (byteAt(code, cursor++) != 0x46) return@filter false
            val third = readU30(code, cursor) ?: return@filter false
            if (third.first !in available) return@filter false
            cursor += third.second
            if (readU30(code, cursor)?.first != 0) return@filter false
            cursor += 1
            if (byteAt(code, cursor++) != 0x61) return@filter false
            readU30(code, cursor)?.first in useNative
        }
        require(matches.size == 1) { "Expected one native sync assignment, found ${matches.size}" }
        return matches.single()
    }

    private fun expressionLength(code: ByteArray, start: Int): Int {
        var cursor = start + 1 + requireNotNull(readU30(code, start + 1)).second
        cursor += 1 + requireNotNull(readU30(code, cursor + 1)).second
        cursor += 1 + requireNotNull(readU30(code, cursor + 1)).second
        return cursor + requireNotNull(readU30(code, cursor)).second - start
    }

    private fun readU30(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset !in bytes.indices) return null
        var value = 0
        for (index in 0..4) {
            val position = offset + index
            if (position !in bytes.indices) return null
            val byte = bytes[position].toInt() and 0xFF
            value = value or ((byte and 0x7F) shl (index * 7))
            if (byte < 0x80) return value.takeIf { it <= 0x3FFFFFFF }?.let { it to index + 1 }
        }
        return null
    }

    private fun byteAt(bytes: ByteArray, index: Int): Int? = bytes.getOrNull(index)?.toInt()?.and(0xFF)

    private fun abcStart(payload: ByteArray): Int {
        require(payload.size >= 5) { "Invalid DoABC payload" }
        val nameEnd = (4 until payload.size).firstOrNull { payload[it] == 0.toByte() }
            ?: throw IllegalArgumentException("DoABC name is unterminated")
        return nameEnd + 1
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+")).map {
        it.toInt(16).toByte()
    }.toByteArray()
}
