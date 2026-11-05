package dev.airtouch64.app

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.airtouch64.engine.ApkSigning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class DevicePatchTest {

    @Test
    fun testFullPatchPipelineOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = PatchRepository(context)

        // 1. Use the embedded audited bundle
        val bundle = requireNotNull(repo.loadSavedBundle()) { "Import a bundle before running this test" }
        assertEquals("PS Touch Revived ARM64", bundle.header.name)

        // 2. Exercise BouncyCastle certificate generation and KeyStore on Android
        val identity = repo.identity()
        assertTrue(identity.sha256Fingerprint.isNotEmpty())

        // 3. Source APK
        val localSource = File(context.cacheDir, "test-source.apk")
        if (!localSource.isFile || localSource.length() == 0L) {
            val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val pfd = uiAutomation.executeShellCommand("cat /data/local/tmp/pstouch-source.apk")
            java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                localSource.outputStream().use { output -> input.copyTo(output) }
            }
            pfd.close()
        }
        assertTrue("Source APK must exist and be non-empty", localSource.isFile && localSource.length() > 0)

        val inspection = repo.inspect(localSource, bundle.header)
        assertTrue("Source APK must be supported", inspection.supported)

        // 4. Run patching on device
        val events = mutableListOf<String>()
        val sampling = AtomicBoolean(true)
        val peakPssKb = AtomicLong(Debug.getPss())
        val sampler = Thread {
            while (sampling.get()) {
                peakPssKb.updateAndGet { maxOf(it, Debug.getPss()) }
                Thread.sleep(50)
            }
        }
        sampler.start()
        val startedAt = SystemClock.elapsedRealtime()
        val result = try {
            repo.patch(localSource, bundle.file, identity) { event -> events += event.toString() }
        } finally {
            sampling.set(false)
            sampler.join()
        }
        Log.i("DevicePatchTest", "patch_ms=${SystemClock.elapsedRealtime() - startedAt} peak_pss_kb=${peakPssKb.get()}")

        assertTrue("Output APK must exist", result.output.isFile)
        assertEquals(353, result.entries)
        ZipFile(result.output).use { apk ->
            val swfHash = apk.getInputStream(apk.getEntry("assets/TTPixel.swf")).use { input ->
                dev.airtouch64.engine.PatchEngine.sha256(input.readBytes())
            }
            assertEquals("496c3e5009b94ebabecf57beb3f5a0dfade967b8ba6e626cadd2446d0bd538c5", swfHash)
        }

        // 5. Verify signature on device
        val certs = ApkSigning.verify(result.output)
        assertEquals(1, certs.size)

        // Copy out to cache/patched-on-device.apk so we can inspect it
        val exported = File(context.cacheDir, "patched-on-device.apk")
        result.output.inputStream().use { input ->
            exported.outputStream().use { output -> input.copyTo(output) }
        }
        println("Patched APK written to ${exported.absolutePath}, size=${exported.length()}")
    }
}
