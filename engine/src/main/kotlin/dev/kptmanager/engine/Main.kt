package dev.kptmanager.engine

import java.io.File

/**
 * Host runner used to validate the bundle and the engine without a device:
 *   ./gradlew :engine:run --args="source.apk bundle.zip out.apk keys"
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        error("usage: main <source.apk> <bundle.zip> <output.apk> [key-directory]")
    }
    val source = File(args[0])
    val bundle = File(args[1])
    val output = File(args[2])
    val keyDir = File(args.getOrElse(3) { "build/test-keys" })

    val engine = PatchEngine(File(System.getProperty("java.io.tmpdir"), "kptmanager-engine"))
    val manifest = engine.readManifest(bundle)
    println("bundle: ${manifest.name} ${manifest.version}")

    val inspection = engine.inspect(source, manifest)
    println("source: ${inspection.sha256} supported=${inspection.supported}")
    check(inspection.supported) { "source not supported" }

    val identity = SigningStore.loadOrCreate(keyDir)
    val result = engine.patch(source, bundle, output, identity) { event ->
        when (event) {
            is PatchEvent.Step -> println("step ${event.id}: ${event.state}")
            is PatchEvent.Message -> println("  ${event.text}")
            is PatchEvent.Progress -> Unit
        }
    }
    println("output: ${result.output}")
    println("sha256: ${result.sha256}")
    println("size: ${result.size}")
    println("entries: ${result.entries}")
    println("signer: ${result.signerFingerprint}")
}
