package dev.airtouch64.engine

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class AbcFileTest {
    @Test
    fun rejectsUnsupportedCompositeTransformInput() {
        assertFailsWith<IllegalArgumentException> {
            SwfTransformations.patchStartupSyncBrushAndPaint(byteArrayOf())
        }
    }

    @Test
    fun roundTripsEmptyAbcAndPreservesOverlongU30Width() {
        val canonical = emptyAbc()
        assertContentEquals(canonical, AbcFile.parse(canonical).serialize())

        val overlong = emptyAbc(overlongFirstPoolCount = true)
        assertContentEquals(overlong, AbcFile.parse(overlong).serialize())
    }

    @Test
    fun growsMethodCountAcrossU30WidthBoundary() {
        val source = abcWithMethodCount(127)
        val abc = AbcFile.parse(source)
        val (updated, methodIndex) = abc.appendMethod(AbcFile.MethodInfo(0, emptyList(), 0, 0))

        assertEquals(127, methodIndex)
        assertEquals(128, updated.methods.size)
        assertEquals(source.size + 5, updated.serialize().size)
        assertContentEquals(updated.serialize(), AbcFile.parse(updated.serialize()).serialize())
    }

    @Test
    fun rejectsResizingMethodWithExceptionHandler() {
        val base = emptyAbc()
        var abc = AbcFile.parse(base).appendMethod(AbcFile.MethodInfo(0, emptyList(), 0, 0)).first
        abc = abc.appendMethodBody(0, 0, 1, 0, 0, byteArrayOf(0x47))
        val body = abc.methodBody(0)!!
        val bodySectionStart = base.size - 1 + 4
        val exceptionOffset = body.codeOffset + body.codeLength - bodySectionStart
        val exceptionTable = byteArrayOf(1, 0, 1, 0, 0, 0)
        val withException = AbcFile.parse(abc.rewriteSections(listOf(
            AbcFile.SectionEdit(6, exceptionOffset, byteArrayOf(0), exceptionTable),
        )))

        assertEquals(1, withException.methodBody(0)!!.exceptions.size)
        assertFailsWith<IllegalArgumentException> {
            withException.replaceMethodCode(0, byteArrayOf(0x47, 0x47))
        }
    }

    @Test
    fun rewritesSelectedAbcSectionWhilePreservingOtherSections() {
        val source = emptyAbc()
        val rewritten = AbcFile.parse(source).rewriteSections(
            listOf(AbcFile.SectionEdit(2, 0, byteArrayOf(0), byteArrayOf(1, 2))),
        )
        assertContentEquals(source.copyOfRange(0, 11) + byteArrayOf(1, 2) + source.copyOfRange(12, source.size), rewritten)
    }

    @Test
    fun rejectsTruncatedAbcAndUnsupportedMultinameKinds() {
        assertFailsWith<IllegalArgumentException> {
            AbcFile.parse(byteArrayOf(0x10, 0, 0x2E))
        }

        val invalid = ByteArrayOutputStream().also { out ->
            out.write(byteArrayOf(0x10, 0, 0x2E, 0))
            repeat(6) { writeU30(out, 0) }
            writeU30(out, 2)
            out.write(0xFF)
        }.toByteArray()
        assertFailsWith<IllegalArgumentException> { AbcFile.parse(invalid) }
    }

    @Test
    fun roundTripsLocalSourceAndReferenceAbcsWhenPathsAreProvided() {
        val sourcePath = System.getenv("AT64_SOURCE_SWF")
        val referencePath = System.getenv("AT64_REFERENCE_SWF")
        assumeTrue(!sourcePath.isNullOrBlank() && !referencePath.isNullOrBlank())

        val abcs = mutableListOf<AbcFile>()
        for (path in listOf(sourcePath!!, referencePath!!)) {
            val tags = Swf.tags(File(path).readBytes())
            val payload = tags.single { it.type == 82 }.payload
            val nameEnd = (4 until payload.size).first { payload[it] == 0.toByte() }
            val abc = payload.copyOfRange(nameEnd + 1, payload.size)
            val parsed = AbcFile.parse(abc)
            assertContentEquals(abc, parsed.serialize())
            assertEquals(1209, parsed.classes.size)
            assertEquals(1191, parsed.scriptCount)
            abcs += parsed
        }

        val sourceAbc = abcs[0]
        val referenceAbc = abcs[1]
        assertEquals(1, sourceAbc.findMethods("com.adobe.ttpixel.app::TTApplication", "applicationWillRun").size)
        assertEquals(0, sourceAbc.findMethods("com.adobe.ttpixel.editor.tool::TTBrushTool", "getDrawingColorTransform").size)
        assertEquals(1, referenceAbc.findMethods("com.adobe.ttpixel.editor.tool::TTBrushTool", "getDrawingColorTransform").size)
        assertEquals(1, referenceAbc.findMethods("com.adobe.ttpixel.editor.tool::TTPaintTool", "createPaintColorTransform").size)
        assertEquals(1, referenceAbc.findMethods("com.adobe.ttpixel.editor.tool::TTPaintTool", "finalizeWork").size)
    }

    @Test
    fun matchesPythonStartupPatchWhenLocalFixturesAreProvided() {
        val sourcePath = System.getenv("AT64_SOURCE_SWF")
        val startupPath = System.getenv("AT64_STARTUP_SWF")
        assumeTrue(!sourcePath.isNullOrBlank() && !startupPath.isNullOrBlank())

        val actual = SwfTransformations.patchStartup(File(sourcePath!!).readBytes())
        val expected = File(startupPath!!).readBytes()
        assertContentEquals(Swf.body(expected), Swf.body(actual))
    }

    @Test
    fun appliesStartupAndLocalSyncEditsToSourceFixture() {
        val sourcePath = System.getenv("AT64_SOURCE_SWF")
        assumeTrue(!sourcePath.isNullOrBlank())
        val source = File(sourcePath!!).readBytes()
        val output = SwfTransformations.patchStartupAndLocalSync(source)
        val sourceTags = Swf.tags(source)
        val outputTags = Swf.tags(output)
        assertEquals(sourceTags.map { it.type }, outputTags.map { it.type })
        val untouchedSource = sourceTags.filter { it.type != 82 }
        val untouchedOutput = outputTags.filter { it.type != 82 }
        assertEquals(untouchedSource.size, untouchedOutput.size)
        for (index in untouchedSource.indices) {
            assertContentEquals(untouchedSource[index].payload, untouchedOutput[index].payload)
        }

        fun body(swf: ByteArray, owner: String, name: String): ByteArray {
            val payload = Swf.tags(swf).single { it.type == 82 }.payload
            val start = (4 until payload.size).first { payload[it] == 0.toByte() } + 1
            val abc = AbcFile.parse(payload.copyOfRange(start, payload.size))
            val method = abc.findMethods(owner, name).single()
            return abc.methodCode(method.methodIndex)!!
        }

        val owner = "com.adobe.ttpixel.media.creativecloud::TTSyncEngine"
        val before = body(source, owner, "initialize")
        val after = body(output, owner, "initialize")
        assertEquals(before.size, after.size)
        assertEquals(-1, after.count { it == 0x46.toByte() } - before.count { it == 0x46.toByte() })
        assertEquals(1, after.count { it == 0x27.toByte() } - before.count { it == 0x27.toByte() })
    }

    @Test
    fun appendsAndResolvesQNameInLocalSourcePool() {
        val sourcePath = System.getenv("AT64_SOURCE_SWF")
        assumeTrue(!sourcePath.isNullOrBlank())
        val payload = Swf.tags(File(sourcePath!!).readBytes()).single { it.type == 82 }.payload
        val start = (4 until payload.size).first { payload[it] == 0.toByte() } + 1
        val original = AbcFile.parse(payload.copyOfRange(start, payload.size))
        val owner = "com.adobe.ttpixel.editor.tool::TTBrushTool"
        original.classes.single { original.qualifiedName(it.nameIndex) == owner }
        val namespace = original.namespaceIndices(0x16, "").single()
        val (updated, index) = original.appendQName(namespace, "__at64PoolProbe")
        assertEquals("__at64PoolProbe", updated.qualifiedName(index))
        val (again, sameIndex) = updated.appendQName(namespace, "__at64PoolProbe")
        assertEquals(index, sameIndex)
        assertContentEquals(updated.serialize(), again.serialize())
    }

    @Test
    fun methodLookupExposesAmbiguousStaticAndInstanceTraits() {
        val sourcePath = System.getenv("AT64_SOURCE_SWF")
        assumeTrue(!sourcePath.isNullOrBlank())
        val payload = Swf.tags(File(sourcePath!!).readBytes()).single { it.type == 82 }.payload
        val start = (4 until payload.size).first { payload[it] == 0.toByte() } + 1
        val abc = AbcFile.parse(payload.copyOfRange(start, payload.size))
        val owner = "com.adobe.ttpixel.editor.tool::TTBrushTool"
        val method = abc.findMethods(owner, "doWork").single()
        val nameIndex = abc.findTraits(owner, "doWork", isStatic = false).single().trait.nameIndex
        val ambiguous = abc.appendMethodTrait(owner, nameIndex, method.methodIndex, isStatic = true)

        assertEquals(2, ambiguous.findMethods(owner, "doWork").size)
        assertFailsWith<IllegalArgumentException> { ambiguous.findMethods(owner, "doWork").single() }
    }

    @Test
    fun appendsMethodInfoAndBodyWithoutRewritingExistingBodies() {
        val sourcePath = System.getenv("AT64_SOURCE_SWF")
        assumeTrue(!sourcePath.isNullOrBlank())
        val payload = Swf.tags(File(sourcePath!!).readBytes()).single { it.type == 82 }.payload
        val start = (4 until payload.size).first { payload[it] == 0.toByte() } + 1
        val original = AbcFile.parse(payload.copyOfRange(start, payload.size))
        val owner = "com.adobe.ttpixel.editor.tool::TTBrushTool"
        val className = original.classes.single { original.qualifiedName(it.nameIndex) == owner }.nameIndex
        val namespace = original.namespaceIndex(className)!!
        val (withName, _) = original.appendQName(namespace, "__at64AddedMethod")
        val methodName = withName.stringIndex("__at64AddedMethod")!!
        val methodIndex = withName.methods.size
        val (withMethod, appendedIndex) = withName.appendMethod(AbcFile.MethodInfo(0, emptyList(), methodName, 0))
        assertEquals(methodIndex, appendedIndex)
        val updated = withMethod.appendMethodBody(methodIndex, 0, 1, 0, 0, byteArrayOf(0x47))
        assertEquals(original.methods.size + 1, updated.methods.size)
        assertContentEquals(byteArrayOf(0x47), updated.methodCode(methodIndex))
        val expanded = updated.replaceMethodCode(methodIndex, byteArrayOf(0x47, 0x02))
        assertContentEquals(byteArrayOf(0x47, 0x02), expanded.methodCode(methodIndex))
        assertContentEquals(withMethod.methodCode(1901), expanded.methodCode(1901))
        assertContentEquals(expanded.serialize(), AbcFile.parse(expanded.serialize()).serialize())
    }

    @Test
    fun addsBrushColorTransformHelperTraitAndBody() {
        val sourcePath = System.getenv("AT64_SOURCE_SWF")
        assumeTrue(!sourcePath.isNullOrBlank())
        val payload = Swf.tags(File(sourcePath!!).readBytes()).single { it.type == 82 }.payload
        val start = (4 until payload.size).first { payload[it] == 0.toByte() } + 1
        var abc = AbcFile.parse(payload.copyOfRange(start, payload.size))
        val owner = "com.adobe.ttpixel.editor.tool::TTBrushTool"
        abc.classes.single { abc.qualifiedName(it.nameIndex) == owner }
        val packageNamespace = abc.namespaceIndices(0x16, "").single()
        val (withQName, traitName) = abc.appendQName(packageNamespace, "getDrawingColorTransform")
        abc = withQName
        val methodName = abc.stringIndex("getDrawingColorTransform")!!
        val returnType = abc.multinameIndices("ColorTransform")
            .single { abc.qualifiedName(it) == "flash.geom::ColorTransform" }
        val brushField = abc.findTraits(owner, "iBrush", isStatic = false).single().trait.nameIndex
        val brushMethod = abc.multinameIndices("createDrawingColorTransform")
            .single { abc.qualifiedName(it) == "createDrawingColorTransform" }
        val methodIndex = abc.methods.size
        val templateBody = abc.methodBody(abc.findMethods(owner, "doWork").single().methodIndex)!!
        abc = abc.appendMethod(AbcFile.MethodInfo(returnType, emptyList(), methodName, 0)).first
        val code = byteArrayOf(0xD0.toByte(), 0x30, 0xD0.toByte(), 0x66) + writeU30Bytes(brushField) +
            byteArrayOf(0x46) + writeU30Bytes(brushMethod) + byteArrayOf(0, 0x48)
        abc = abc.appendMethodBody(
            methodIndex, 1, 1, templateBody.initialScopeDepth, templateBody.maxScopeDepth, code,
        )
        abc = abc.appendMethodTrait(owner, traitName, methodIndex)

        val method = abc.findMethods(owner, "getDrawingColorTransform").single()
        assertEquals(methodIndex, method.methodIndex)
        assertContentEquals(code, abc.methodCode(methodIndex))
        assertEquals(1, abc.findTraits(owner, "getDrawingColorTransform", isStatic = false).size)
    }

    @Test
    fun routesBrushWorkThroughVirtualColorTransformHelper() {
        val sourcePath = System.getenv("AT64_SOURCE_SWF")
        assumeTrue(!sourcePath.isNullOrBlank())
        val output = SwfTransformations.patchStartupSyncAndBrush(File(sourcePath!!).readBytes())
        val payload = Swf.tags(output).single { it.type == 82 }.payload
        val start = (4 until payload.size).first { payload[it] == 0.toByte() } + 1
        val abc = AbcFile.parse(payload.copyOfRange(start, payload.size))
        val owner = "com.adobe.ttpixel.editor.tool::TTBrushTool"
        val helper = abc.findMethods(owner, "getDrawingColorTransform").single()
        assertEquals(false, helper.isStatic)
        assertEquals(1, abc.findTraits(owner, "getDrawingColorTransform", isStatic = false).size)
        val sourcePayload = Swf.tags(File(sourcePath).readBytes()).single { it.type == 82 }.payload
        val sourceStart = (4 until sourcePayload.size).first { sourcePayload[it] == 0.toByte() } + 1
        val sourceAbc = AbcFile.parse(sourcePayload.copyOfRange(sourceStart, sourcePayload.size))
        val oldDoWork = sourceAbc.findMethods(owner, "doWork").single()
        val newDoWork = abc.findMethods(owner, "doWork").single()
        val oldCode = sourceAbc.methodCode(oldDoWork.methodIndex)!!
        val newCode = abc.methodCode(newDoWork.methodIndex)!!
        assertEquals(oldCode.size, newCode.size)
        assertFalse(newCode.contentEquals(oldCode))
    }

    @Test
    fun addsPaintColorTransformOverrideAndFinalizer() {
        val sourcePath = System.getenv("AT64_SOURCE_SWF")
        assumeTrue(!sourcePath.isNullOrBlank())
        val output = SwfTransformations.patchPaintMethods(File(sourcePath!!).readBytes())
        val payload = Swf.tags(output).single { it.type == 82 }.payload
        val start = (4 until payload.size).first { payload[it] == 0.toByte() } + 1
        val abc = AbcFile.parse(payload.copyOfRange(start, payload.size))
        val owner = "com.adobe.ttpixel.editor.tool::TTPaintTool"
        for (name in listOf("createPaintColorTransform", "getDrawingColorTransform", "finalizeWork")) {
            assertEquals(1, abc.findMethods(owner, name).size)
            assertEquals(1, abc.findTraits(owner, name, isStatic = false).size)
        }
        assertEquals(2, abc.findTraits(owner, "getDrawingColorTransform", false).single().trait.attributes)
        assertEquals(2, abc.findTraits(owner, "finalizeWork", false).single().trait.attributes)
        assertEquals("flash.geom::ColorTransform", abc.qualifiedName(abc.findMethods(owner, "createPaintColorTransform").single().let { abc.methods[it.methodIndex].returnType }))
        assertEquals("void", abc.qualifiedName(abc.methods[abc.findMethods(owner, "finalizeWork").single().methodIndex].returnType))
    }

    @Test
    fun paintTrackingBodyUsesSupportedBranchRelocationOpcodes() {
        val sourcePath = System.getenv("AT64_SOURCE_SWF")
        assumeTrue(!sourcePath.isNullOrBlank())
        val payload = Swf.tags(File(sourcePath!!).readBytes()).single { it.type == 82 }.payload
        val start = (4 until payload.size).first { payload[it] == 0.toByte() } + 1
        val abc = AbcFile.parse(payload.copyOfRange(start, payload.size))
        val owner = "com.adobe.ttpixel.editor.tool::TTPaintTool"
        val method = abc.findMethods(owner, "startPointTracking").single()
        val body = abc.methodBody(method.methodIndex)!!
        assertTrue(body.exceptions.isEmpty())
        val code = abc.methodCode(method.methodIndex)!!
        Avm2Code.insert(code, listOf(Avm2Code.Insertion(code.size, byteArrayOf(0x02))))
    }

    @Test
    fun composesAuditedStartupSyncBrushAndPaintTransformations() {
        val sourcePath = System.getenv("AT64_SOURCE_SWF")
        assumeTrue(!sourcePath.isNullOrBlank())
        val source = File(sourcePath!!).readBytes()
        val output = SwfTransformations.patchStartupSyncBrushAndPaint(source)
        assertContentEquals(output, SwfTransformations.patchStartupSyncBrushAndPaint(source))
        assertEquals("496c3e5009b94ebabecf57beb3f5a0dfade967b8ba6e626cadd2446d0bd538c5", PatchEngine.sha256(output))
        assertEquals("FWS", String(output, 0, 3, Charsets.US_ASCII))
        assertEquals(Swf.body(output).size + 8, output.size)
        val sourcePayload = Swf.tags(source).single { it.type == 82 }.payload
        val outputPayload = Swf.tags(output).single { it.type == 82 }.payload
        val sourceStart = (4 until sourcePayload.size).first { sourcePayload[it] == 0.toByte() } + 1
        val outputStart = (4 until outputPayload.size).first { outputPayload[it] == 0.toByte() } + 1
        val sourceAbc = AbcFile.parse(sourcePayload.copyOfRange(sourceStart, sourcePayload.size))
        val outputAbc = AbcFile.parse(outputPayload.copyOfRange(outputStart, outputPayload.size))
        assertEquals(1, outputAbc.findMethods("com.adobe.ttpixel.app::TTApplication", "applicationWillRun").size)
        assertEquals(1, outputAbc.findMethods("com.adobe.ttpixel.media.creativecloud::TTSyncEngine", "initialize").size)
        assertEquals(1, outputAbc.findMethods("com.adobe.ttpixel.editor.tool::TTBrushTool", "getDrawingColorTransform").size)
        for (method in listOf("createPaintColorTransform", "getDrawingColorTransform", "finalizeWork")) {
            assertEquals(1, outputAbc.findMethods("com.adobe.ttpixel.editor.tool::TTPaintTool", method).size)
        }
        val owner = "com.adobe.ttpixel.editor.tool::TTPaintTool"
        val sourceMethod = sourceAbc.findMethods(owner, "startPointTracking").single()
        val outputMethod = outputAbc.findMethods(owner, "startPointTracking").single()
        assertTrue(outputAbc.methodCode(outputMethod.methodIndex)!!.size > sourceAbc.methodCode(sourceMethod.methodIndex)!!.size)
        assertEquals(0, outputAbc.methodBody(outputMethod.methodIndex)!!.exceptions.size)
        for (owner in listOf(
            "com.adobe.ttpixel.editor.tool::TTBrushTool",
            "com.adobe.ttpixel.editor.tool::TTPaintTool",
        )) {
            assertEquals(1, outputAbc.classes.count { outputAbc.qualifiedName(it.nameIndex) == owner })
        }
        val sourceOtherTags = Swf.tags(source).filter { it.type != 82 }
        val outputOtherTags = Swf.tags(output).filter { it.type != 82 }
        assertEquals(sourceOtherTags.size, outputOtherTags.size)
        sourceOtherTags.indices.forEach { assertContentEquals(sourceOtherTags[it].payload, outputOtherTags[it].payload) }
    }

    private fun emptyAbc(overlongFirstPoolCount: Boolean = false): ByteArray =
        ByteArrayOutputStream().also { out ->
            out.write(byteArrayOf(0x10, 0, 0x2E, 0))
            if (overlongFirstPoolCount) {
                out.write(byteArrayOf(0x80.toByte(), 0))
                repeat(6) { writeU30(out, 0) }
            } else {
                repeat(7) { writeU30(out, 0) }
            }
            repeat(5) { writeU30(out, 0) }
        }.toByteArray()

    private fun abcWithMethodCount(count: Int): ByteArray = ByteArrayOutputStream().also { out ->
        out.write(byteArrayOf(0x10, 0, 0x2E, 0))
        repeat(7) { writeU30(out, 0) }
        writeU30(out, count)
        repeat(count) {
            writeU30(out, 0)
            writeU30(out, 0)
            writeU30(out, 0)
            out.write(0)
        }
        repeat(4) { writeU30(out, 0) }
    }.toByteArray()

    private fun writeU30(out: ByteArrayOutputStream, value: Int) {
        var rest = value
        while (rest > 0x7F) {
            out.write((rest and 0x7F) or 0x80)
            rest = rest ushr 7
        }
        out.write(rest)
    }

    private fun writeU30Bytes(value: Int): ByteArray = ByteArrayOutputStream().also {
        writeU30(it, value)
    }.toByteArray()
}
