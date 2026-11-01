package dev.kptmanager.engine

import kotlinx.serialization.Serializable

/** Manifest that describes what a patch bundle carries and how to apply it. */
@Serializable
data class BundleManifest(
    val formatVersion: Int,
    val name: String,
    val version: String,
    val source: SourceSpec,
    val swf: SwfPatch,
    val dexPatches: List<DexPatch> = emptyList(),
    val nativeLibs: List<BundleEntry> = emptyList(),
    val resourceOverrides: List<BundleEntry> = emptyList(),
    val steps: List<PatchStepInfo> = emptyList(),
)

@Serializable
data class SourceSpec(
    val sha256: List<String>,
    val requiredEntries: List<String>,
    val swfEntry: String,
)

@Serializable
data class SwfPatch(
    val patch: String,
    val sourceBodySha256: String,
    val resultBodySha256: String,
)

@Serializable
data class DexPatch(
    val entry: String,
    val patch: String,
    val inputSha256: String,
    val outputSha256: String,
)

/** A file inside the bundle, identified by its path in the output APK. */
@Serializable
data class BundleEntry(
    val entry: String,
    val bundlePath: String,
)

@Serializable
data class PatchStepInfo(
    val id: String,
    val name: String,
    val description: String,
)
