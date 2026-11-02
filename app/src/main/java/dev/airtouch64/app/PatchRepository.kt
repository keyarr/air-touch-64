package dev.airtouch64.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.airtouch64.engine.BundleHeader
import dev.airtouch64.engine.PatchEngine
import dev.airtouch64.engine.PatchEvent
import dev.airtouch64.engine.PatchResult
import dev.airtouch64.engine.SigningIdentity
import dev.airtouch64.engine.SigningStore
import dev.airtouch64.engine.SourceInspection
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class PatchRepository(private val context: Context) {
    private val engine = PatchEngine(File(context.cacheDir, "engine"))
    private val bundleFile = File(context.filesDir, "bundle/bundle.at64")

    companion object {
        private const val BUNDLE_RELEASE_URL =
            "https://github.com/keyarr/airtouch64-bundle-backend/releases/latest/download/bundle.at64"
    }

    fun loadSavedBundle(): BundleSelection? {
        if (!bundleFile.isFile) return null
        return runCatching { BundleSelection(bundleFile, savedName(), engine.readHeader(bundleFile)) }
            .getOrElse { bundleFile.delete(); null }
    }

    fun importBundle(uri: Uri, name: String): BundleSelection {
        val dir = bundleFile.parentFile!!.apply { mkdirs() }
        val temp = File(dir, "bundle.at64.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use(input::copyTo) }
                ?: error(context.getString(R.string.open_apk_failed))
            val header = engine.readHeader(temp)
            bundleFile.delete()
            check(temp.renameTo(bundleFile)) { "Could not save bundle" }
            File(dir, "bundle.name").writeText(name)
            return BundleSelection(bundleFile, name, header)
        } finally { temp.delete() }
    }

    fun downloadLatestBundle(): BundleSelection {
        val dir = bundleFile.parentFile!!.apply { mkdirs() }
        val temp = File(dir, "bundle.at64.download")
        val connection = URL(BUNDLE_RELEASE_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Bundle download failed: HTTP ${connection.responseCode}"
            }
            connection.inputStream.use { input -> temp.outputStream().use(input::copyTo) }
            val header = engine.readHeader(temp)
            try {
                Files.move(temp.toPath(), bundleFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), bundleFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            File(dir, "bundle.name").writeText("bundle.at64")
            return BundleSelection(bundleFile, "bundle.at64", header)
        } finally {
            temp.delete()
            connection.disconnect()
        }
    }

    private fun savedName(): String = File(bundleFile.parentFile, "bundle.name").takeIf { it.isFile }?.readText()
        ?: bundleFile.name

    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank)?.let { name -> return name }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "source.apk"
    }

    fun importSource(uri: Uri, name: String): File {
        val dir = File(context.cacheDir, "source").apply { mkdirs() }
        val target = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "source.apk" })
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
            ?: error(context.getString(R.string.open_apk_failed))
        return target
    }

    fun inspect(source: File, header: BundleHeader): SourceInspection = engine.inspect(source, header)
    fun identity(): SigningIdentity = SigningStore.loadOrCreate(File(context.filesDir, "signing"))

    fun patch(source: File, bundle: File, identity: SigningIdentity, onEvent: (PatchEvent) -> Unit): PatchResult {
        val dir = File(context.filesDir, "patched").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        return engine.patch(source, bundle, File(dir, "ps-touch-revived-$stamp.apk"), identity, onEvent)
    }
}
