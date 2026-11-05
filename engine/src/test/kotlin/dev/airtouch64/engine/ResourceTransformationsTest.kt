package dev.airtouch64.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
