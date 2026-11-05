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

        val doAbc = Swf.tags(raw).single { it.type == 82 }.payload
        val abcStart = abcStart(doAbc)
        val abc = AbcFile.parse(doAbc.copyOfRange(abcStart, doAbc.size))
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
        val replacementAbc = abc.serialize(patches)
        val replacementDoAbc = doAbc.copyOfRange(0, abcStart) + replacementAbc
        return Swf.replaceDoAbc(raw, replacementDoAbc)
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
