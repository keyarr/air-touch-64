package dev.airtouch64.engine

import java.nio.charset.StandardCharsets

internal object ResourceTransformations {
    fun applyVerified(
        entry: String,
        operation: String,
        input: ByteArray,
        inputSha256: String,
        outputSha256: String,
    ): ByteArray {
        check(PatchEngine.sha256(input) == inputSha256) { "Resource transformation input hash mismatch: $entry" }
        val output = apply(entry, operation, input)
        check(PatchEngine.sha256(output) == outputSha256) { "Resource transformation output hash mismatch: $entry" }
        return output
    }

    fun apply(entry: String, operation: String, input: ByteArray): ByteArray {
        require(operation == "resource-v1") { "Unsupported resource operation: $operation" }
        val locale = Regex("assets/resource/application/(de|en|es|fr|it|ja|pt|ru)/application\\.zstrings").matchEntire(entry)?.groupValues?.get(1)
        if (locale != null) return catalog(input, locale)
        if (entry.startsWith("assets/resource/application/filters/")) return shader(entry, input)
        require(entry == "assets/resource/application/gallery/ttgallerysettingsdetailpanelmodule.cabmod") {
            "Unsupported resource transformation: $entry"
        }
        return about(input)
    }

    private fun catalog(input: ByteArray, locale: String): ByteArray {
        require(input.size >= 2) { "Catalog has no UTF-16 BOM" }
        val charset = when {
            input[0] == 0xff.toByte() && input[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            input[0] == 0xfe.toByte() && input[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> error("Catalog has no UTF-16 BOM")
        }
        var text = input.copyOfRange(2, input.size).toString(charset)
        val changes = linkedMapOf("$$$/TTGallery/Adobe Photoshop Touch" to "Ps Touch")
        if (locale == "ja") {
            changes["$$$/about/window/title/About"] = "About"
            changes["$$$/TTGallerySettingsPanelModule/About"] = "About"
            changes["$$$/ttgallerysettingspanelmodule/text/text/About"] = "About"
        }
        for ((key, value) in changes) {
            val regex = Regex("""(?m)^"${Regex.escape(key)}=[^\r\n]*"(?=\r?$)""")
            val matches = regex.findAll(text).toList()
            check(matches.size == 1) { "$key: expected one catalog key, found ${matches.size}" }
            text = regex.replace(text) { "\"$key=$value\"" }
        }
        val facebook = "\"\$\$\$/private/GoURL/FacebookPage=https://github.com/keyarr/air-touch-64\"\n"
        if (!text.contains("\"\$\$\$/private/GoURL/FacebookPage=")) text += facebook
        val bom = if (charset == Charsets.UTF_16LE) byteArrayOf(0xff.toByte(), 0xfe.toByte())
        else byteArrayOf(0xfe.toByte(), 0xff.toByte())
        return bom + text.toByteArray(charset)
    }

    private fun about(input: ByteArray): ByteArray {
        var text = input.toString(StandardCharsets.UTF_8)

        fun update(id: String, attributes: Map<String, String>) {
            val pattern = Regex("<(?:text|ttactiontext)\\b(?=[^>]*\\bid=\"${Regex.escape(id)}\")[^>]*/>")
            val match = pattern.find(text) ?: error("Missing About element: $id")
            var element = match.value
            for ((name, value) in attributes) {
                val escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                val attribute = Regex("\\b${Regex.escape(name)}=\"[^\"]*\"")
                val match = attribute.find(element)
                element = if (match != null) element.replaceRange(match.range, "$name=\"$escaped\"")
                else element.dropLast(2) + " $name=\"$escaped\"/>"
            }
            text = text.replaceRange(match.range, element)
        }

        fun remove(id: String) {
            val pattern = Regex("<text\\b(?=[^>]*\\bid=\"${Regex.escape(id)}\")[^>]*/>\\s*")
            val matches = pattern.findAll(text).toList()
            check(matches.size == 1) { "$id: expected one obsolete About element, found ${matches.size}" }
            text = pattern.replace(text, "")
        }

        fun rename(old: String, new: String) {
            val pattern = Regex("(<(?:text|ttactiontext)\\b(?=[^>]*\\bid=\"${Regex.escape(old)}\")[^>]*\\bid=\")[^\"]+(\"[^>]*/>)")
            val matches = pattern.findAll(text).toList()
            check(matches.size == 1) { "$old: expected one About id, found ${matches.size}" }
            text = pattern.replace(text) { match -> match.groupValues[1] + new + match.groupValues[2] }
        }

        update("text2locprivate", mapOf("text" to "$$$/private/ttgallerysettingsdetailpanelmodule/text/text/Adobe, the Adobe logo, and Photoshop are either registered trademarks or trademarks of Adobe Systems Incorporated in the United States and/or other countries.=Adobe, the Adobe logo, and Photoshop are either registered trademarks or trademarks of Adobe Systems Incorporated in the United States and/or other countries."))
        update("ttboldtext112ttlargetextlocprivate", mapOf("text" to "$$$/private/ttgallerysettingsdetailpanelmodule/text/text/Adobe#{reg} Photoshop#{reg} Touch for Phone=Ps Touch"))
        update("text22locprivate", mapOf("text" to "$$$/private/ttgallerysettingsdetailpanelmodule/text/text/Copyright #{copy} 2015 Adobe Systems Incorporated and its licensors. All rights reserved.=Copyright © 2015 Adobe Systems Incorporated and its licensors. All rights reserved."))
        update("text3locprivate", mapOf("text" to "$$$/private/ttgallerysettingsdetailpanelmodule/text/text/Notices, terms and conditions pertaining to third party software are located at =Notices, terms and conditions pertaining to third-party software are available at:"))
        update("text11", mapOf("text" to "$$$/private/ttgallerysettingsdetailpanelmodule/text/text/Third-party notices=Third-party notices", "target" to "referenceDispatcher", "selector" to "thirdParty"))
        remove("text321locprivate")
        remove("text322locprivate")
        if (Regex("<(?:text|ttactiontext)\\b(?=[^>]*\\bid=\"text31ttlargetext\")").containsMatchIn(text)) rename("text31ttlargetext", "text31")
        update("text31", mapOf("text" to "$$$/ttgallerysettingsdetailpanelmodule/text/text/Project source=Project source:"))
        update("text111", mapOf("text" to "$$$/ttgallerysettingsdetailpanelmodule/text/text/GitHub repository=GitHub repository", "selector" to "facebookPage"))
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private fun shader(entry: String, input: ByteArray): ByteArray {
        val name = entry.substringAfterLast('/')
        require(name in ShaderMainBlocks.byName) { "Unsupported shader transformation: $entry" }
        var text = input.toString(StandardCharsets.UTF_8)
        check(Regex("void main\\(\\)").findAll(text).count() == 1) { "$entry: expected one main function" }
        text = text.replace("/*$$$ \n", "/*$$$\n")
        when (name) {
            "posterize.fs" -> {
                text = text.replace("min=\"1\" max=\"32\"", "min=\"2\" max=\"32\"")
                text = text.replace("uniform vec4 color1;\nuniform vec4 color2;\n\n", "")
                val start = text.indexOf("precision highp float;")
                val end = text.indexOf("varying vec2 mainTexCoord;")
                check(start >= 0 && end > start)
                var declarations = text.substring(start, end).replace(Regex("(?<!precision highp float;)\\n\\n(?=uniform)"), "\n")
                text = text.replaceRange(start, end, declarations)
                text = text.replace("uniform float numColors;\n\nvarying", "uniform float numColors;\nvarying")
            }
            "comic.fs", "threshold.fs" -> {
                if (name == "comic.fs") text = text.replace("min=\"1\" max=\"30\"", "min=\"2\" max=\"30\"")
                val start = text.indexOf("precision highp float;")
                val end = text.indexOf("varying vec2 mainTexCoord;")
                check(start >= 0 && end > start)
                text = text.replaceRange(start, end, text.substring(start, end).replace("\n\n", "\n"))
            }
            "replacecolor.fs" -> {
                val start = text.indexOf("uniform vec3 sampleColor;")
                val end = text.indexOf("varying vec2 mainTexCoord;")
                check(start >= 0 && end > start)
                val declarations = text.substring(start, end).replace(
                    "uniform vec3 sampleColor;\n\nuniform vec4 replaceColor;\n\nuniform float threshold;\n\nuniform float intensity;",
                    "uniform vec3 sampleColor;\nuniform vec4 replaceColor;\nuniform float threshold;\nuniform float intensity;",
                )
                text = text.replaceRange(start, end, declarations)
            }
        }
        val start = text.indexOf("void main()")
        val brace = text.indexOf('{', start)
        check(start >= 0 && brace > start)
        var depth = 0
        var end = -1
        for (index in brace until text.length) {
            if (text[index] == '{') depth++
            if (text[index] == '}') {
                depth--
                if (depth == 0) { end = index + 1; break }
            }
        }
        check(end > start) { "$entry: unterminated main function" }
        text = text.replaceRange(start, end, ShaderMainBlocks.byName.getValue(name))
        return text.toByteArray(StandardCharsets.UTF_8)
    }
}
