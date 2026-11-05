package dev.airtouch64.engine

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Assumptions.assumeTrue

class AbcFileTest {
    @Test
    fun roundTripsEmptyAbcAndPreservesOverlongU30Width() {
        val canonical = emptyAbc()
        assertContentEquals(canonical, AbcFile.parse(canonical).serialize())

        val overlong = emptyAbc(overlongFirstPoolCount = true)
        assertContentEquals(overlong, AbcFile.parse(overlong).serialize())
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

    private fun writeU30(out: ByteArrayOutputStream, value: Int) {
        var rest = value
        while (rest > 0x7F) {
            out.write((rest and 0x7F) or 0x80)
            rest = rest ushr 7
        }
        out.write(rest)
    }
}
