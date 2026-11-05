package dev.airtouch64.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Assumptions.assumeTrue

class PatchEngineSwfTest {
    @Test
    fun rejectsWrongSwfInputHash() {
        val source = byteArrayOf(1, 2, 3)
        assertFailsWith<IllegalStateException> {
            applySwfOperation(source, 3, SwfPatch(
                sourceSha256 = "wrong",
                resultSha256 = "unused",
                operation = "local-transform-v1",
            ), emptyMap())
        }
    }

    @Test
    fun rejectsUnknownSwfOperation() {
        val source = byteArrayOf(1, 2, 3)
        assertFailsWith<IllegalArgumentException> {
            applySwfOperation(source, 3, SwfPatch(
                sourceSha256 = PatchEngine.sha256(source),
                resultSha256 = "unused",
                operation = "local-transform-v2",
            ), emptyMap())
        }
    }

    @Test
    fun rejectsWrongTransformedSwfHashWhenFixtureIsProvided() {
        val path = System.getenv("AT64_SOURCE_SWF")
        assumeTrue(!path.isNullOrBlank())
        val source = File(path!!).readBytes()
        assertFailsWith<IllegalStateException> {
            applySwfOperation(source, 3, SwfPatch(
                sourceSha256 = PatchEngine.sha256(source),
                resultSha256 = "wrong",
                operation = "local-transform-v1",
            ), emptyMap())
        }
    }
}
