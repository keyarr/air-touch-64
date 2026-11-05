package dev.airtouch64.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ResourceTransformationsTest {
    @Test
    fun transformsUtf16CatalogWithoutChangingOtherLines() {
        val input = "\"$$$/TTGallery/Adobe Photoshop Touch=Original\"\r\n\"keep=value\"\r\n"
            .toByteArray(Charsets.UTF_16)
        val output = ResourceTransformations.apply(
            "assets/resource/application/en/application.zstrings",
            "resource-v1",
            input,
        ).toString(Charsets.UTF_16)
        assertEquals(
            "\"$$$/TTGallery/Adobe Photoshop Touch=Ps Touch\"\r\n\"keep=value\"\r\n" +
                "\"$$$/private/GoURL/FacebookPage=https://github.com/keyarr/air-touch-64\"\n",
            output,
        )
    }

    @Test
    fun rejectsCatalogWithMissingRequiredKey() {
        val input = "\"keep=value\"\n".toByteArray(Charsets.UTF_16)
        assertFailsWith<IllegalStateException> {
            ResourceTransformations.apply(
                "assets/resource/application/en/application.zstrings",
                "resource-v1",
                input,
            )
        }
    }

    @Test
    fun checksTransformationHashes() {
        val input = "\"$$$/TTGallery/Adobe Photoshop Touch=Original\"\n".toByteArray(Charsets.UTF_16)
        val entry = "assets/resource/application/en/application.zstrings"
        val output = ResourceTransformations.apply(entry, "resource-v1", input)
        assertFailsWith<IllegalStateException> {
            ResourceTransformations.applyVerified(entry, "resource-v1", input, "wrong", PatchEngine.sha256(output))
        }
        assertFailsWith<IllegalStateException> {
            ResourceTransformations.applyVerified(entry, "resource-v1", input, PatchEngine.sha256(input), "wrong")
        }
    }

    @Test
    fun copiesOnlyDeclaredSourceEntriesAndChecksHashes() {
        val source = File.createTempFile("source-copy", ".apk")
        try {
            ZipOutputStream(source.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("res/raw/startga.html"))
                zip.write("source bytes".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("ignored"))
                zip.write("ignored".toByteArray())
                zip.closeEntry()
            }
            val engine = PatchEngine(File(source.parentFile, "copy-workspace"))
            val bytes = "source bytes".toByteArray()
            val copy = SourceCopy("res/raw/startga.html", "res/fE.html", PatchEngine.sha256(bytes))
            assertEquals(bytes.toList(), engine.readSourceCopies(source, listOf(copy)).getValue("res/fE.html").toList())
            assertFailsWith<IllegalStateException> {
                engine.readSourceCopies(source, listOf(copy.copy(sha256 = "wrong")))
            }
            assertFailsWith<IllegalStateException> {
                engine.readSourceCopies(source, listOf(copy, copy.copy(sourceEntry = "ignored")))
            }
        } finally {
            source.delete()
        }
    }
}
