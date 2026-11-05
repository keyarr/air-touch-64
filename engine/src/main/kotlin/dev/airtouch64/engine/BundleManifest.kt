package dev.airtouch64.engine

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
    val sourceCopies: List<SourceCopy> = emptyList(),
    val resourceTransforms: List<ResourceTransform> = emptyList(),
    val resourceOverrides: List<BundleEntry> = emptyList(),
    val steps: List<PatchStepInfo> = emptyList(),
)

@Serializable
data class BundleHeader(
    val formatVersion: Int,
    val name: String,
    val version: String,
    val source: SourceSpec,
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
    val patch: String? = null,
    val sourceSha256: String,
    val resultSha256: String,
    val operation: String? = null,
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
data class SourceCopy(
    val sourceEntry: String,
    val entry: String,
    val sha256: String,
)

@Serializable
data class ResourceTransform(
    val entry: String,
    val operation: String,
    val inputSha256: String,
    val outputSha256: String,
)

@Serializable
data class PatchStepInfo(
    val id: String,
    val name: String,
    val description: String,
)
