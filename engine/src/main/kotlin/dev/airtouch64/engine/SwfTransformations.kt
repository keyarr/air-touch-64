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

    fun patchStartupSyncAndBrush(raw: ByteArray): ByteArray = patchBrushHelper(patchStartupAndLocalSync(raw))

    fun patchBrushHelper(raw: ByteArray): ByteArray = transformAbcBytes(raw) { abc ->
        val owner = "com.adobe.ttpixel.editor.tool::TTBrushTool"
        val classInfo = abc.classes.single { abc.qualifiedName(it.nameIndex) == owner }
        val packageNamespace = abc.namespaceIndex(classInfo.nameIndex)
            ?: throw IllegalArgumentException("TTBrushTool is not a QName")
        val (withQName, traitName) = abc.appendQName(packageNamespace, "getDrawingColorTransform")
        val methodName = withQName.stringIndex("getDrawingColorTransform")!!
        val returnType = withQName.multinameIndices("ColorTransform")
            .single { withQName.qualifiedName(it) == "flash.geom::ColorTransform" }
        val brushField = withQName.findTraits(owner, "iBrush", isStatic = false).single().trait.nameIndex
        val brushMethod = withQName.multinameIndices("createDrawingColorTransform")
            .single { withQName.qualifiedName(it) == "createDrawingColorTransform" }
        val methodIndex = withQName.methods.size
        val doWorkTemplate = withQName.findMethods(owner, "doWork").single()
        val templateBody = withQName.methodBody(doWorkTemplate.methodIndex)
            ?: throw IllegalArgumentException("TTBrushTool.doWork has no body")
        var updated = withQName.appendMethod(
            AbcFile.MethodInfo(returnType, emptyList(), methodName, 0),
        ).first
        val code = byteArrayOf(0xD0.toByte(), 0x30, 0xD0.toByte(), 0x66) + encodeU30(brushField) +
            byteArrayOf(0x46) + encodeU30(brushMethod) + byteArrayOf(0, 0x48)
        updated = updated.appendMethodBody(methodIndex, 1, 1,
            templateBody.initialScopeDepth, templateBody.maxScopeDepth, code)
        updated = updated.appendMethodTrait(owner, traitName, methodIndex)

        val doWork = updated.findMethods(owner, "doWork").single()
        val doWorkCode = updated.methodCode(doWork.methodIndex)!!
        val callOffset = findReceiverCall(doWorkCode, brushField, brushMethod)
        val oldCall = callLength(doWorkCode, callOffset)
        val newCall = byteArrayOf(0xD0.toByte(), 0x46) + encodeU30(traitName) + byteArrayOf(0)
        require(newCall.size <= oldCall) { "Brush helper call does not fit original method span" }
        val replacement = newCall + ByteArray(oldCall - newCall.size) { 0x02 }
        updated = AbcFile.parse(updated.serialize(listOf(updated.methodCodePatch(
            doWork.methodIndex,
            callOffset,
            doWorkCode.copyOfRange(callOffset, callOffset + oldCall),
            replacement,
        ))))
        updated.serialize()
    }

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
        return transformAbcBytes(raw) { abc -> abc.serialize(patches(abc)) }
    }

    private fun transformAbcBytes(raw: ByteArray, transform: (AbcFile) -> ByteArray): ByteArray {
        val doAbc = Swf.tags(raw).single { it.type == 82 }.payload
        val start = abcStart(doAbc)
        val abc = AbcFile.parse(doAbc.copyOfRange(start, doAbc.size))
        val replacementDoAbc = doAbc.copyOfRange(0, start) + transform(abc)
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

    private fun findReceiverCall(code: ByteArray, receiverProperty: Int, method: Int): Int {
        val matches = code.indices.filter { start ->
            if (byteAt(code, start) != 0xD0 || byteAt(code, start + 1) != 0x66) return@filter false
            val property = readU30(code, start + 2) ?: return@filter false
            if (property.first != receiverProperty) return@filter false
            var cursor = start + 2 + property.second
            if (byteAt(code, cursor++) != 0x46) return@filter false
            val target = readU30(code, cursor) ?: return@filter false
            if (target.first != method) return@filter false
            cursor += target.second
            readU30(code, cursor)?.first == 0
        }
        require(matches.size == 1) { "Expected one brush color-transform call, found ${matches.size}" }
        return matches.single()
    }

    private fun callLength(code: ByteArray, start: Int): Int {
        val property = requireNotNull(readU30(code, start + 2))
        val methodOperandOffset = start + 2 + property.second + 1
        val target = requireNotNull(readU30(code, methodOperandOffset))
        val argCount = requireNotNull(readU30(code, methodOperandOffset + target.second))
        return methodOperandOffset + target.second + argCount.second - start
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

    private fun encodeU30(value: Int): ByteArray {
        require(value in 0..0x3FFFFFFF)
        var remaining = value
        val result = ArrayList<Byte>(5)
        while (remaining > 0x7F) {
            result += ((remaining and 0x7F) or 0x80).toByte()
            remaining = remaining ushr 7
        }
        result += remaining.toByte()
        return result.toByteArray()
    }

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
