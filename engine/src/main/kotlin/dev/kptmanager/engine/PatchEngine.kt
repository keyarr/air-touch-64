package dev.kptmanager.engine

import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

class SourceInspection(
    val sha256: String,
    val size: Long,
    val supported: Boolean,
    val missingEntries: List<String>,
    val entryCount: Int,
)

class PatchResult(
    val output: File,
    val sha256: String,
    val size: Long,
    val signerFingerprint: String,
    val entries: Int,
    val steps: List<String>,
)

sealed interface PatchEvent {
    data class Step(val id: String, val state: State) : PatchEvent {
        enum class State { RUNNING, DONE, FAILED }
    }
    data class Message(val text: String) : PatchEvent
    data class Progress(val done: Int, val total: Int) : PatchEvent
}

/**
 * Applies a prebuilt bundle to the user supplied source APK. Everything that runs here is a
 * verified byte level transformation: hashes are checked before and after each patch.
 */
class PatchEngine(private val workspace: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun readManifest(bundle: File): BundleManifest =
        ZipFile(bundle).use { zip ->
            val entry = zip.getEntry(MANIFEST) ?: error("Bundle has no $MANIFEST")
            zip.getInputStream(entry).use { json.decodeFromString(it.readBytes().decodeToString()) }
        }

    fun inspect(source: File, manifest: BundleManifest): SourceInspection {
        val sha = sha256(source)
        val missing = mutableListOf<String>()
        var count = 0
        ZipFile(source).use { zip ->
            count = zip.size()
            for (entry in manifest.source.requiredEntries) {
                if (zip.getEntry(entry) == null) missing += entry
            }
        }
        return SourceInspection(
            sha256 = sha,
            size = source.length(),
            supported = sha in manifest.source.sha256 && missing.isEmpty(),
            missingEntries = missing,
            entryCount = count,
        )
    }

    fun patch(
        source: File,
        bundle: File,
        output: File,
        identity: SigningIdentity,
        onEvent: (PatchEvent) -> Unit,
    ): PatchResult {
        workspace.mkdirs()
        val steps = mutableListOf<String>()
        var entryCount = 0
        val manifest = readManifest(bundle)

        fun step(id: String) {
            steps += id
            onEvent(PatchEvent.Step(id, PatchEvent.Step.State.RUNNING))
        }

        fun done(id: String) = onEvent(PatchEvent.Step(id, PatchEvent.Step.State.DONE))

        step("validate")
        val inspection = inspect(source, manifest)
        check(inspection.supported) {
            if (inspection.missingEntries.isNotEmpty()) {
                "APK sem os arquivos esperados: ${inspection.missingEntries.joinToString(", ")}"
            } else {
                "APK não suportado (SHA-256 ${inspection.sha256.take(16)}…)"
            }
        }
        onEvent(PatchEvent.Message("Origem verificada: ${inspection.sha256}"))
        done("validate")

        ZipFile(bundle).use { bundleZip ->
            step("swf")
            val swfEntry = manifest.source.swfEntry
            val swfRaw = readEntry(source, swfEntry)
            check(sha256(swfRaw) == manifest.swf.sourceSha256) {
                "SWF de origem não confere com o bundle"
            }
            val patchBytes = readBundle(bundleZip, manifest.swf.patch)
            val swfOut = BsPatch.patch(swfRaw, patchBytes)
            check(sha256(swfOut) == manifest.swf.resultSha256) {
                "SWF patch produziu um arquivo inesperado"
            }
            onEvent(PatchEvent.Message("SWF: ${patchBytes.size / 1024} KiB de patch aplicados"))
            done("swf")

            step("dex")
            val dexOut = mutableMapOf<String, ByteArray>()
            for (dex in manifest.dexPatches) {
                val input = readBundle(bundleZip, "shell/${dex.entry}")
                check(sha256(input) == dex.inputSha256) { "DEX ${dex.entry} inesperado no bundle" }
                val patched = BsPatch.patch(input, readBundle(bundleZip, dex.patch))
                check(sha256(patched) == dex.outputSha256) { "DEX ${dex.entry} não confere após o patch" }
                dexOut[dex.entry] = patched
            }
            onEvent(PatchEvent.Message("DEX: ${dexOut.size} arquivo(s) corrigido(s)"))
            done("dex")

            step("resources")
            val resources = mutableMapOf<String, ByteArray>()
            ZipFile(source).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory || !entry.name.startsWith("assets/resource/")) continue
                    resources[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
                }
            }
            for (override in manifest.resourceOverrides) {
                resources[override.entry] = readBundle(bundleZip, override.bundlePath)
            }
            onEvent(PatchEvent.Message("Recursos: ${resources.size} arquivos"))
            done("resources")

            step("assemble")
            val unsigned = File(workspace, "unsigned.apk")
            unsigned.delete()
            val entries = linkedMapOf<String, ByteArray>()
            for (entry in bundleZip.entries()) {
                if (entry.isDirectory || !entry.name.startsWith("shell/")) continue
                val name = entry.name.removePrefix("shell/")
                if (isSignatureFile(name)) continue
                entries[name] = dexOut[name] ?: bundleZip.getInputStream(entry).use { it.readBytes() }
            }
            entries.putAll(resources)
            entries[swfEntry] = swfOut
            for (lib in manifest.nativeLibs) {
                entries[lib.entry] = readBundle(bundleZip, lib.bundlePath)
            }

            entryCount = entries.size
            writeApk(unsigned, entries, onEvent)
            onEvent(PatchEvent.Message("APK montado: ${unsigned.length() / 1024 / 1024} MiB, ${entries.size} entradas"))
            done("assemble")

            step("sign")
            output.parentFile?.mkdirs()
            output.delete()
            ApkSigning.sign(unsigned, output, identity)
            assertAlignment(output)
            val certs = ApkSigning.verify(output)
            onEvent(PatchEvent.Message("Assinado e verificado (${certs.size} certificado(s))"))
            done("sign")
        }

        unsignedCleanup()
        return PatchResult(
            output = output,
            sha256 = sha256(output),
            size = output.length(),
            signerFingerprint = identity.sha256Fingerprint,
            entries = entryCount,
            steps = steps,
        )
    }

    private fun assertAlignment(apk: File) {
        java.io.RandomAccessFile(apk, "r").use { raf ->
            val len = raf.length()
            val searchLen = minOf(len, 65557L).toInt()
            val buffer = ByteArray(searchLen)
            raf.seek(len - searchLen)
            raf.readFully(buffer)

            var eocdOffset = -1L
            for (i in searchLen - 22 downTo 0) {
                if (buffer[i] == 0x50.toByte() && buffer[i + 1] == 0x4b.toByte() &&
                    buffer[i + 2] == 0x05.toByte() && buffer[i + 3] == 0x06.toByte()
                ) {
                    eocdOffset = len - searchLen + i
                    break
                }
            }
            check(eocdOffset >= 0) { "EOCD record not found in ${apk.name}" }

            raf.seek(eocdOffset + 12)
            val cdSize = Integer.toUnsignedLong(Integer.reverseBytes(raf.readInt()))
            val cdOffset = Integer.toUnsignedLong(Integer.reverseBytes(raf.readInt()))

            var pos = cdOffset
            val cdEnd = cdOffset + cdSize
            while (pos < cdEnd) {
                raf.seek(pos)
                val sig = Integer.reverseBytes(raf.readInt())
                if (sig != 0x02014b50) break
                raf.seek(pos + 10)
                val method = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                raf.seek(pos + 28)
                val nameLen = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                val extraLen = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                val commentLen = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                raf.seek(pos + 42)
                val localOffset = Integer.toUnsignedLong(Integer.reverseBytes(raf.readInt()))
                val nameBytes = ByteArray(nameLen)
                raf.seek(pos + 46)
                raf.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                pos += 46 + nameLen + extraLen + commentLen

                raf.seek(localOffset + 26)
                val locNameLen = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                val locExtraLen = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                val dataOffset = localOffset + 30 + locNameLen + locExtraLen

                if (name.endsWith(".so")) {
                    check(dataOffset % 16384L == 0L) {
                        "Native library $name is not 16KB aligned (offset $dataOffset)"
                    }
                } else if (name == "resources.arsc" || method == 0) {
                    check(dataOffset % 4L == 0L) {
                        "Stored entry $name is not 4-byte aligned (offset $dataOffset)"
                    }
                }
            }
        }
    }

    private fun writeApk(file: File, entries: Map<String, ByteArray>, onEvent: (PatchEvent) -> Unit) {
        AlignedZipWriter(file).use { writer ->
            var index = 0
            for ((name, data) in entries) {
                val alignment = when {
                    name.endsWith(".so") -> 16 * 1024
                    name == "resources.arsc" -> 4
                    else -> 4
                }
                val store = name.endsWith(".so") ||
                    name == "resources.arsc" ||
                    name.endsWith(".png") ||
                    name.endsWith(".jpg") ||
                    name.endsWith(".jpeg") ||
                    name.endsWith(".gif") ||
                    name == "res/b8" ||
                    name == "res/iv" ||
                    name == "androidsupportmultidexversion.txt"
                writer.add(name, data, store = store, alignment = alignment)
                index++
                if (index % 25 == 0) onEvent(PatchEvent.Progress(index, entries.size))
            }
            writer.finish()
        }
    }

    private fun readEntry(apk: File, name: String): ByteArray =
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(name) ?: error("Entrada ausente no APK: $name")
            zip.getInputStream(entry).use { it.readBytes() }
        }

    private fun readBundle(zip: ZipFile, path: String): ByteArray {
        val entry = zip.getEntry(path) ?: error("Entrada ausente no bundle: $path")
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun isSignatureFile(name: String): Boolean {
        if (name == "META-INF/MANIFEST.MF") return true
        if (!name.startsWith("META-INF/")) return false
        return name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") ||
            name.endsWith(".EC") || name.endsWith(".MF")
    }

    private fun unsignedCleanup() {
        File(workspace, "unsigned.apk").delete()
    }

    companion object {
        private const val MANIFEST = "manifest.json"

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
