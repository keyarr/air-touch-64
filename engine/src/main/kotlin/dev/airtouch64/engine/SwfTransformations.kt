package dev.airtouch64.engine

import java.security.MessageDigest

object SwfTransformations {
    private data class CallRef(val start: Int, val end: Int, val name: Int, val argumentCount: Int)

    private val startupInputHashes = setOf(
        "1558aa31a62f23171a19ddd2c2c3147eed89a9b6280401e28ca0fd7491675231",
        "d941eab1489b086fadeddfa921d0ffd031a69113b058e81482732c9641664f21",
    )
    private const val supportedTransformInputHash = "d941eab1489b086fadeddfa921d0ffd031a69113b058e81482732c9641664f21"
    private const val supportedTransformOutputHash = "496c3e5009b94ebabecf57beb3f5a0dfade967b8ba6e626cadd2446d0bd538c5"
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

    fun patchPaintMethods(raw: ByteArray): ByteArray = transformAbcBytes(raw) { sourceAbc ->
        val owner = "com.adobe.ttpixel.editor.tool::TTPaintTool"
        sourceAbc.classes.single { sourceAbc.qualifiedName(it.nameIndex) == owner }
        val scopeTemplate = sourceAbc.methodBody(
            sourceAbc.findMethods(owner, "startPointTracking").single().methodIndex,
        ) ?: throw IllegalArgumentException("TTPaintTool.startPointTracking has no body")
        val packageNamespace = sourceAbc.namespaceIndices(0x16, "").single()
        var abc = sourceAbc
        var (withPublicName, publicName) = abc.appendQName(packageNamespace, "getDrawingColorTransform")
        abc = withPublicName
        val publicString = abc.stringIndex("getDrawingColorTransform")!!
        val colorTransformType = abc.multinameIndices("ColorTransform")
            .single { abc.qualifiedName(it) == "flash.geom::ColorTransform" }
        val numberType = abc.multinameIndices("Number").single { abc.qualifiedName(it) == "Number" }
        val protectedNamespace = abc.namespaceIndices(
            0x18,
            "com.adobe.ttpixel.editor.tool:TTPaintTool",
        ).single()
        val (withCreateName, createName) = abc.appendQName(protectedNamespace, "createPaintColorTransform")
        abc = withCreateName
        val createString = abc.stringIndex("createPaintColorTransform")!!

        val brushOwner = "com.adobe.ttpixel.editor.tool::TTBrushTool"
        val brushField = abc.findTraits(brushOwner, "iBrush", false).single().trait.nameIndex
        val eraseField = publicQName(abc, "doesErase")
        val opacityProperty = publicQName(abc, "opacity")
        val drawingColor = publicQName(abc, "getDrawingColor")
        val colorTransformClass = abc.multinameIndices("ColorTransform").single {
            abc.qualifiedName(it) == "flash.geom::ColorTransform"
        }
        val colorTransformProperty = publicQName(abc, "colorTransform")
        val transformProperty = publicQName(abc, "transform")
        val workingBitmapField = abc.findTraits(brushOwner, "iWorkingBitmap", false).single().trait.nameIndex
        val penEraser = protectedQName(abc, "iPenEraser", "com.adobe.ttpixel.editor.tool:TTTool")
        val voidType = publicQName(abc, "void")
        val editorViewType = abc.multinameIndices("TTEditorView").single {
            abc.qualifiedName(it) == "com.adobe.ttpixel.editor.app::TTEditorView"
        }

        val createMethodIndex = abc.methods.size
        abc = abc.appendMethod(AbcFile.MethodInfo(
            colorTransformType, listOf(numberType), createString, 0,
        )).first
        val createCode = CodeBuilder().apply {
            op(0xD0); op(0x30)
            op(0xD0); call(0x46, drawingColor, 0); op(0x73); op(0xD6)
            operand(0x5D, colorTransformClass)
            op(0x24, 0); op(0x24, 0); op(0x24, 0); op(0xD1)
            op(0xD2); op(0x24, 16); op(0xA6); op(0x25); u30(255); op(0xA8)
            op(0xD2); op(0x24, 8); op(0xA6); op(0x25); u30(255); op(0xA8)
            op(0xD2); op(0x25); u30(255); op(0xA8); op(0x24, 0)
            call(0x4A, colorTransformClass, 8); op(0x48)
        }.build()
        abc = abc.appendMethodBody(
            createMethodIndex, 9, 3, scopeTemplate.initialScopeDepth, scopeTemplate.maxScopeDepth, createCode,
        )
        abc = abc.appendMethodTrait(owner, createName, createMethodIndex)

        val drawingMethodIndex = abc.methods.size
        abc = abc.appendMethod(AbcFile.MethodInfo(colorTransformType, emptyList(), publicString, 0)).first
        val drawingCode = CodeBuilder().apply {
            op(0xD0); op(0x30)
            op(0xD0); operand(0x66, brushField); operand(0x66, eraseField)
            branch(0x11, "erase")
            operand(0x60, penEraser)
            branch(0x12, "paint")
            label("erase")
            op(0xD0); call(0x45, publicName, 0); op(0x48)
            label("paint")
            op(0xD0); op(0xD0); operand(0x66, brushField); operand(0x66, opacityProperty)
            call(0x46, createName, 1); op(0x48)
        }.build()
        abc = abc.appendMethodBody(
            drawingMethodIndex, 2, 1, scopeTemplate.initialScopeDepth, scopeTemplate.maxScopeDepth, drawingCode,
        )
        abc = abc.appendMethodTrait(owner, publicName, drawingMethodIndex, isOverride = true)

        val finalizeName = abc.multinameIndices("finalizeWork").single { abc.qualifiedName(it) == "finalizeWork" }
        val finalizeString = abc.stringIndex("finalizeWork")!!
        val finalizeMethodIndex = abc.methods.size
        abc = abc.appendMethod(AbcFile.MethodInfo(voidType, listOf(editorViewType), finalizeString, 0)).first
        val finalizeCode = CodeBuilder().apply {
            op(0xD0); op(0x30)
            op(0xD0); op(0xD1); call(0x4E, finalizeName, 1)
            op(0xD0); operand(0x66, workingBitmapField); operand(0x66, transformProperty)
            operand(0x5D, colorTransformClass); call(0x4A, colorTransformClass, 0)
            operand(0x61, colorTransformProperty); op(0x47)
        }.build()
        abc = abc.appendMethodBody(
            finalizeMethodIndex, 2, 2, scopeTemplate.initialScopeDepth, scopeTemplate.maxScopeDepth, finalizeCode,
        )
        abc = abc.appendMethodTrait(owner, finalizeName, finalizeMethodIndex, isOverride = true)
        abc.serialize()
    }

    fun patchPaintTracking(raw: ByteArray): ByteArray = transformAbcBytes(raw) { abc ->
        val owner = "com.adobe.ttpixel.editor.tool::TTPaintTool"
        val method = abc.findMethods(owner, "startPointTracking").single()
        val code = abc.methodCode(method.methodIndex)!!
        val brushOwner = "com.adobe.ttpixel.editor.tool::TTBrushTool"
        val brushField = abc.findTraits(brushOwner, "iBrush", false).single().trait.nameIndex
        val bitmapField = abc.findTraits(brushOwner, "iWorkingBitmap", false).single().trait.nameIndex
        val doesErase = publicQName(abc, "doesErase")
        val opacity = publicQName(abc, "opacity")
        val drawingColor = publicQName(abc, "getDrawingColor")
        val drawingColorTransform = publicQName(abc, "getDrawingColorTransform")
        val solidColorPatch = publicQName(abc, "getSolidColorPatch")
        val transform = publicQName(abc, "transform")
        val colorTransform = publicQName(abc, "colorTransform")
        val superStartTracking = publicQName(abc, "startPointTracking")
        val penEraser = protectedQName(abc, "iPenEraser", "com.adobe.ttpixel.editor.tool:TTTool")

        val colorCall = findThisCall(code, drawingColor)
        val colorPatchCall = readCall(code, colorCall.end, 0x4F)
            ?: throw IllegalArgumentException("Missing getSolidColorPatch call")
        require(colorPatchCall.name == solidColorPatch && colorPatchCall.argumentCount == 1) {
            "Unexpected paint solid-color patch call"
        }
        val whiteBranch = CodeBuilder().apply {
            op(0xD0); operand(0x66, brushField); operand(0x66, doesErase); op(0x2A)
            branch(0x11, "color")
            op(0x29); operand(0x60, penEraser)
            label("color")
            branch(0x12, "white")
            op(0xD0); call(0x46, drawingColor, 0); branch(0x10, "end")
            label("white")
            op(0x25); u30(255); op(0x24, 16); op(0xA5)
            op(0x25); u30(255); op(0x24, 8); op(0xA5); op(0xA0)
            op(0x25); u30(255); op(0xA0)
            label("end")
        }.build()
        val colorExpression = Avm2Code.Replacement(
            colorCall.start, colorCall.end - colorCall.start, whiteBranch,
        )

        val superCall = findSuperStartTracking(code, superStartTracking)
        val transformBranch = CodeBuilder().apply {
            op(0xD0); operand(0x66, brushField); operand(0x66, doesErase); op(0x96); op(0x2A)
            branch(0x12, "checkPenEraser")
            op(0x29); operand(0x60, penEraser); op(0x96)
            label("checkPenEraser")
            branch(0x12, "end")
            op(0xD0); operand(0x66, bitmapField); operand(0x66, transform)
            op(0xD0); call(0x46, drawingColorTransform, 0)
            operand(0x61, colorTransform)
            label("end")
        }.build()
        val replacements = listOf(
            colorExpression,
            Avm2Code.Replacement(superCall, 0, transformBranch, branchTargetsAfter = true),
        )
        val updatedCode = Avm2Code.replace(code, replacements)
        abc.replaceMethodCode(method.methodIndex, updatedCode).serialize()
    }

    fun patchStartupSyncBrushAndPaint(raw: ByteArray): ByteArray {
        require(sha256(raw) == supportedTransformInputHash) { "Unsupported SWF transformation input" }
        val output = Swf.uncompressed(patchPaintTracking(
            patchPaintMethods(patchBrushHelper(patchStartupAndLocalSync(raw))),
        ))
        require(sha256(output) == supportedTransformOutputHash) { "Unexpected SWF transformation output" }
        return output
    }

    fun patchBrushHelper(raw: ByteArray): ByteArray = transformAbcBytes(raw) { abc ->
        val owner = "com.adobe.ttpixel.editor.tool::TTBrushTool"
        abc.classes.single { abc.qualifiedName(it.nameIndex) == owner }
        val packageNamespace = abc.namespaceIndices(0x16, "").single()
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

    private fun findThisCall(code: ByteArray, method: Int): CallRef {
        val matches = code.indices.mapNotNull { start ->
            if (byteAt(code, start) != 0xD0) return@mapNotNull null
            val call = readCall(code, start + 1, 0x46) ?: return@mapNotNull null
            call.takeIf { it.name == method && it.argumentCount == 0 }?.let {
                CallRef(start, it.end, it.name, it.argumentCount)
            }
        }
        require(matches.size == 1) { "Expected one getDrawingColor call, found ${matches.size}" }
        return matches.single()
    }

    private fun readCall(code: ByteArray, offset: Int, opcode: Int): CallRef? {
        if (byteAt(code, offset) != opcode) return null
        val name = readU30(code, offset + 1) ?: return null
        val argumentOffset = offset + 1 + name.second
        val count = readU30(code, argumentOffset) ?: return null
        return CallRef(offset, argumentOffset + count.second, name.first, count.first)
    }

    private fun findSuperStartTracking(code: ByteArray, name: Int): Int {
        val matches = code.indices.filter { start ->
            if (byteAt(code, start) != 0xD0 || byteAt(code, start + 1) != 0xD1 ||
                byteAt(code, start + 2) != 0xD2 || byteAt(code, start + 3) != 0xD3) return@filter false
            val call = readCall(code, start + 4, 0x4E) ?: return@filter false
            call.name == name && call.argumentCount == 3
        }
        require(matches.size == 1) { "Expected one super.startPointTracking call, found ${matches.size}" }
        val call = readCall(code, matches.single() + 4, 0x4E)!!
        return call.end
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

    private fun publicQName(abc: AbcFile, localName: String): Int = abc.multinameIndices(localName).single { index ->
        val namespaceIndex = abc.namespaceIndex(index) ?: return@single false
        abc.namespaceInfo(namespaceIndex)?.let { it.kind == 0x16 && it.name.isEmpty() } == true
    }

    private fun protectedQName(abc: AbcFile, localName: String, owner: String): Int =
        abc.multinameIndices(localName).single { index ->
            val namespaceIndex = abc.namespaceIndex(index) ?: return@single false
            abc.namespaceInfo(namespaceIndex)?.let { it.kind == 0x18 && it.name == owner } == true
        }

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

    private class CodeBuilder {
        private data class Branch(val operandOffset: Int, val label: String)

        private val bytes = java.io.ByteArrayOutputStream()
        private val labels = mutableMapOf<String, Int>()
        private val branches = mutableListOf<Branch>()

        fun op(vararg values: Int) {
            values.forEach(bytes::write)
        }

        fun u30(value: Int) {
            var remaining = value
            require(remaining in 0..0x3FFFFFFF)
            while (remaining > 0x7F) {
                bytes.write((remaining and 0x7F) or 0x80)
                remaining = remaining ushr 7
            }
            bytes.write(remaining)
        }

        fun operand(opcode: Int, value: Int) {
            op(opcode)
            u30(value)
        }

        fun call(opcode: Int, method: Int, argumentCount: Int) {
            op(opcode)
            u30(method)
            u30(argumentCount)
        }

        fun branch(opcode: Int, label: String) {
            op(opcode)
            branches += Branch(bytes.size(), label)
            op(0, 0, 0)
        }

        fun label(name: String) {
            require(labels.put(name, bytes.size()) == null) { "Duplicate bytecode label" }
        }

        fun build(): ByteArray {
            val result = bytes.toByteArray()
            for ((offset, name) in branches) {
                val target = labels[name] ?: throw IllegalArgumentException("Unknown bytecode label: $name")
                val delta = target - (offset + 3)
                require(delta in -0x800000..0x7FFFFF) { "AVM2 branch is out of range" }
                result[offset] = delta.toByte()
                result[offset + 1] = (delta ushr 8).toByte()
                result[offset + 2] = (delta ushr 16).toByte()
            }
            return result
        }
    }
}
