package dev.airtouch64.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.airtouch64.engine.BundleManifest
import dev.airtouch64.engine.PatchEngine
import dev.airtouch64.engine.PatchEvent
import dev.airtouch64.engine.PatchResult
import dev.airtouch64.engine.SigningIdentity
import dev.airtouch64.engine.SigningStore
import dev.airtouch64.engine.SourceInspection
import java.io.File

class PatchRepository(private val context: Context) {
    private val engine = PatchEngine(File(context.cacheDir, "engine"))

    fun manifest(): BundleManifest = engine.readManifest(bundleFile())

    fun bundleFile(): File {
        val target = File(context.filesDir, "bundle/bundle.zip")
        val assetSize = context.assets.openFd(BUNDLE_ASSET).use { it.length }
        if (target.isFile && target.length() == assetSize) return target
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "bundle.zip.tmp")
        context.assets.open(BUNDLE_ASSET).use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        temp.renameTo(target)
        return target
    }

    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val value = cursor.getString(0)
                    if (!value.isNullOrBlank()) return value
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "source.apk"
    }

    fun importSource(uri: Uri, name: String): File {
        val dir = File(context.cacheDir, "source").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "source.apk" })
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Não foi possível abrir o APK selecionado")
        return target
    }

    fun inspect(source: File, manifest: BundleManifest): SourceInspection = engine.inspect(source, manifest)

    fun identity(): SigningIdentity = SigningStore.loadOrCreate(File(context.filesDir, "signing"))

    fun patch(
        source: File,
        manifest: BundleManifest,
        identity: SigningIdentity,
        onEvent: (PatchEvent) -> Unit,
    ): PatchResult {
        val dir = File(context.filesDir, "patched").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val output = File(dir, "ps-touch-revived-$stamp.apk")
        return engine.patch(source, bundleFile(), output, identity, onEvent)
    }

    companion object {
        private const val BUNDLE_ASSET = "bundle.zip"
    }
}
