package dev.kptmanager.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kptmanager.engine.ApkSigning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DevicePatchTest {

    @Test
    fun testFullPatchPipelineOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = PatchRepository(context)

        // 1. Read manifest from bundled asset
        val manifest = repo.manifest()
        assertEquals("PS Touch Revived ARM64", manifest.name)

        // 2. Exercise BouncyCastle certificate generation and KeyStore on Android
        val identity = repo.identity()
        assertTrue(identity.sha256Fingerprint.isNotEmpty())

        // 3. Source APK in app-specific external files dir
        val extFiles = context.getExternalFilesDir(null) ?: error("External files dir not available")
        val sourceFile = File(extFiles, "pstouch-source.apk")
        assertTrue("Source APK must exist at ${sourceFile.absolutePath}", sourceFile.isFile)

        val inspection = repo.inspect(sourceFile, manifest)
        assertTrue("Source APK must be supported", inspection.supported)

        // 4. Run patching on device
        val events = mutableListOf<String>()
        val result = repo.patch(sourceFile, manifest, identity) { event ->
            events += event.toString()
        }

        assertTrue("Output APK must exist", result.output.isFile)
        assertEquals(345, result.entries)

        // 5. Verify signature on device
        val certs = ApkSigning.verify(result.output)
        assertEquals(1, certs.size)

        // Copy out to extFiles/patched-on-device.apk so we can inspect/install it
        val exported = File(extFiles, "patched-on-device.apk")
        result.output.inputStream().use { input ->
            exported.outputStream().use { output -> input.copyTo(output) }
        }
        println("Patched APK written to ${exported.absolutePath}, size=${exported.length()}")
    }
}
