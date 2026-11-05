package dev.airtouch64.engine

import java.io.ByteArrayOutputStream

private fun encodeU30(value: Int, width: Int): ByteArray {
    require(value in 0..0x3FFFFFFF && width in 1..5) { "Invalid U30 encoding" }
    val result = ByteArray(width)
    var remaining = value
    for (index in 0 until width) {
        result[index] = ((remaining and 0x7F) or if (index < width - 1) 0x80 else 0).toByte()
        remaining = remaining ushr 7
    }
    require(remaining == 0) { "U30 does not fit original width" }
    return result
}

private fun encodeU30(value: Int): ByteArray {
    require(value in 0..0x3FFFFFFF) { "Invalid U30 value" }
    val bytes = ByteArrayOutputStream(5)
    var remaining = value
    while (remaining > 0x7F) {
        bytes.write((remaining and 0x7F) or 0x80)
        remaining = remaining ushr 7
    }
    bytes.write(remaining)
    return bytes.toByteArray()
}

/** Lossless reader for the ABC structures used by the supported SWF. */
class AbcFile private constructor(
    val minorVersion: Int,
    val majorVersion: Int,
    val poolCounts: PoolCounts,
    val methods: List<MethodInfo>,
    val metadataCount: Int,
    val classes: List<ClassInfo>,
    val scriptCount: Int,
    val bodies: List<MethodBody>,
    private val namespaceSets: List<List<Int>>,
    private val source: ByteArray,
    private val sections: List<Section>,
    private val poolLayout: PoolLayout,
    private val methodLayout: CountedSectionLayout,
    private val bodyLayout: CountedSectionLayout,
    private val strings: List<String>,
    private val namespaces: List<Namespace?>,
    private val multinames: List<Multiname?>,
) {
    data class PoolCounts(
        val ints: Int,
        val uints: Int,
        val doubles: Int,
        val strings: Int,
        val namespaces: Int,
        val namespaceSets: Int,
        val multinames: Int,
    )

    data class MethodInfo(
        val returnType: Int,
        val parameterTypes: List<Int>,
        val nameIndex: Int,
        val flags: Int,
    )

    data class NamespaceInfo(val kind: Int, val name: String)

    data class BytePatch(val offset: Int, val expected: ByteArray, val replacement: ByteArray)

    data class SectionEdit(
        val sectionIndex: Int,
        val offset: Int,
        val expected: ByteArray,
        val replacement: ByteArray,
    )

    data class TraitRef(
        val owner: String,
        val name: String,
        val isStatic: Boolean,
        val trait: Trait,
    )

    data class ClassInfo(
        val nameIndex: Int,
        val superNameIndex: Int,
        val flags: Int,
        val instanceInitializer: Int,
        val instanceTraits: List<Trait>,
        val staticInitializer: Int,
        val staticTraits: List<Trait>,
        val instanceTraitCountOffset: Int,
        val instanceTraitCountWidth: Int,
        val instanceTraitEnd: Int,
        val staticTraitCountOffset: Int,
        val staticTraitCountWidth: Int,
        val staticTraitEnd: Int,
    )

    data class Trait(
        val nameIndex: Int,
        val kind: Int,
        val attributes: Int,
        val methodIndex: Int = -1,
        val slotId: Int = 0,
        val typeNameIndex: Int = 0,
        val valueIndex: Int = 0,
        val valueKind: Int = 0,
        val dispatchId: Int = 0,
        val classIndex: Int = -1,
        val functionIndex: Int = -1,
        val metadataIndices: List<Int> = emptyList(),
    )

    data class MethodBody(
        val methodIndex: Int,
        val maxStack: Int,
        val localCount: Int,
        val initialScopeDepth: Int,
        val maxScopeDepth: Int,
        val codeOffset: Int,
        val codeLength: Int,
        val exceptions: List<ExceptionInfo>,
        val traits: List<Trait>,
    )

    data class ExceptionInfo(
        val from: Int,
        val to: Int,
        val target: Int,
        val typeNameIndex: Int,
        val variableNameIndex: Int,
    )

    data class MethodRef(
        val owner: String,
        val name: String,
        val isStatic: Boolean,
        val traitKind: Int,
        val methodIndex: Int,
    )

    fun serialize(): ByteArray = serialize(emptyList())

    fun serialize(patches: List<BytePatch>): ByteArray {
        val result = source.copyOf()
        val sorted = patches.sortedBy { it.offset }
        var previousEnd = -1
        for (patch in sorted) {
            require(patch.expected.size == patch.replacement.size) { "ABC patch changes encoded width" }
            require(patch.offset >= 0 && patch.offset <= source.size - patch.expected.size) {
                "ABC patch range is invalid"
            }
            require(patch.offset >= previousEnd) { "Overlapping ABC patches" }
            require(source.copyOfRange(patch.offset, patch.offset + patch.expected.size)
                .contentEquals(patch.expected)) { "ABC patch precondition failed" }
            patch.replacement.copyInto(result, patch.offset)
            previousEnd = patch.offset + patch.expected.size
        }

        val out = ByteArrayOutputStream(result.size)
        var offset = 0
        for (section in sections) {
            require(section.start == offset) { "ABC section gap" }
            out.write(result, section.start, section.end - section.start)
            offset = section.end
        }
        require(offset == result.size) { "Unparsed ABC bytes" }
        return out.toByteArray()
    }

    fun rewriteSections(edits: List<SectionEdit>): ByteArray {
        val grouped = edits.groupBy { it.sectionIndex }
        require(grouped.keys.all { it in sections.indices }) { "Invalid ABC section index" }
        val out = ByteArrayOutputStream(source.size + edits.sumOf { it.replacement.size - it.expected.size })
        for ((sectionIndex, section) in sections.withIndex()) {
            val sectionBytes = source.copyOfRange(section.start, section.end)
            val sectionEdits = grouped[sectionIndex].orEmpty().sortedBy { it.offset }
            var cursor = 0
            for (edit in sectionEdits) {
                require(edit.offset >= cursor && edit.offset <= sectionBytes.size - edit.expected.size) {
                    "Overlapping or out-of-range ABC section edits"
                }
                require(sectionBytes.copyOfRange(edit.offset, edit.offset + edit.expected.size)
                    .contentEquals(edit.expected)) { "ABC section edit precondition failed" }
                out.write(sectionBytes, cursor, edit.offset - cursor)
                out.write(edit.replacement)
                cursor = edit.offset + edit.expected.size
            }
            out.write(sectionBytes, cursor, sectionBytes.size - cursor)
        }
        return out.toByteArray()
    }

    fun appendQName(namespaceIndex: Int, localName: String): Pair<AbcFile, Int> {
        require(namespaceIndex in 1 until namespaces.size) { "Invalid ABC namespace index" }
        val nameIndex = strings.indexOf(localName).takeIf { it > 0 } ?: strings.size
        val existing = multinames.indices.firstOrNull { index ->
            val name = multinames[index]
            name != null && (name.kind == 0x07 || name.kind == 0x0D) &&
                name.namespaceIndex == namespaceIndex && name.nameIndex == nameIndex
        }
        if (existing != null) return this to existing

        val edits = mutableListOf<SectionEdit>()
        val poolSection = sections[1]
        if (nameIndex == strings.size) {
            val entry = localName.toByteArray(Charsets.UTF_8)
            val encodedEntry = encodeU30(entry.size) + entry
            edits += SectionEdit(
                1,
                poolLayout.stringCountOffset - poolSection.start,
                poolLayout.stringCountBytes,
                encodeU30(strings.size + 1),
            )
            edits += SectionEdit(
                1,
                poolLayout.stringEnd - poolSection.start,
                byteArrayOf(),
                encodedEntry,
            )
        }

        val multinameIndex = multinames.size
        val qname = byteArrayOf(0x07) + encodeU30(namespaceIndex) + encodeU30(nameIndex)
        edits += SectionEdit(
            1,
            poolLayout.multinameCountOffset - poolSection.start,
            poolLayout.multinameCountBytes,
            encodeU30(multinames.size + 1),
        )
        edits += SectionEdit(
            1,
            poolLayout.multinameEnd - poolSection.start,
            byteArrayOf(),
            qname,
        )
        return parse(rewriteSections(edits)) to multinameIndex
    }

    fun appendMethod(method: MethodInfo): Pair<AbcFile, Int> {
        require(method.flags and 0x88 == 0) { "Optional values and parameter names are unsupported for appended methods" }
        require(method.returnType in 0 until multinames.size && method.parameterTypes.all { it in 0 until multinames.size }) {
            "Invalid appended method type index"
        }
        require(method.nameIndex in strings.indices) { "Invalid appended method name index" }
        val encoded = ByteArrayOutputStream()
        encoded.write(encodeU30(method.parameterTypes.size))
        encoded.write(encodeU30(method.returnType))
        method.parameterTypes.forEach { encoded.write(encodeU30(it)) }
        encoded.write(encodeU30(method.nameIndex))
        encoded.write(method.flags)

        val index = methods.size
        val section = sections[2]
        val abc = rewriteSections(listOf(
            SectionEdit(2, methodLayout.countOffset - section.start, methodLayout.countBytes,
                encodeU30(methods.size + 1)),
            SectionEdit(2, methodLayout.end - section.start, byteArrayOf(), encoded.toByteArray()),
        ))
        return parse(abc) to index
    }

    fun stringIndex(value: String): Int? = strings.indexOf(value).takeIf { it > 0 }

    fun appendMethodBody(
        methodIndex: Int,
        maxStack: Int,
        localCount: Int,
        initialScopeDepth: Int,
        maxScopeDepth: Int,
        code: ByteArray,
    ): AbcFile {
        require(methodIndex in methods.indices && methodBody(methodIndex) == null) { "Invalid or duplicate method body" }
        require(maxStack >= 0 && localCount >= methods[methodIndex].parameterTypes.size + 1) { "Invalid method limits" }
        require(initialScopeDepth >= 0 && maxScopeDepth >= initialScopeDepth) { "Invalid method scope depth" }
        val encoded = ByteArrayOutputStream()
        encoded.write(encodeU30(methodIndex))
        encoded.write(encodeU30(maxStack))
        encoded.write(encodeU30(localCount))
        encoded.write(encodeU30(initialScopeDepth))
        encoded.write(encodeU30(maxScopeDepth))
        encoded.write(encodeU30(code.size))
        encoded.write(code)
        encoded.write(encodeU30(0))
        encoded.write(encodeU30(0))

        val section = sections[6]
        val abc = rewriteSections(listOf(
            SectionEdit(6, bodyLayout.countOffset - section.start, bodyLayout.countBytes,
                encodeU30(bodies.size + 1)),
            SectionEdit(6, bodyLayout.end - section.start, byteArrayOf(), encoded.toByteArray()),
        ))
        return parse(abc)
    }

    fun appendMethodTrait(
        owner: String,
        nameIndex: Int,
        methodIndex: Int,
        isStatic: Boolean = false,
        isOverride: Boolean = false,
    ): AbcFile {
        require(nameIndex in multinames.indices && methodIndex in methods.indices) { "Invalid method trait index" }
        require(qualifiedName(nameIndex) != null) { "Method trait name must be a QName" }
        require(bodies.any { it.methodIndex == methodIndex }) { "Method trait target has no body" }
        val classInfo = classes.singleOrNull { qualifiedName(it.nameIndex) == owner }
            ?: throw IllegalArgumentException("Expected one class $owner")
        val traits = if (isStatic) classInfo.staticTraits else classInfo.instanceTraits
        require(traits.none { it.nameIndex == nameIndex }) { "Method trait already exists" }

        val count = traits.size
        val countOffset = if (isStatic) classInfo.staticTraitCountOffset else classInfo.instanceTraitCountOffset
        val countWidth = if (isStatic) classInfo.staticTraitCountWidth else classInfo.instanceTraitCountWidth
        val end = if (isStatic) classInfo.staticTraitEnd else classInfo.instanceTraitEnd
        val section = sections[4]
        val trait = ByteArrayOutputStream()
        trait.write(encodeU30(nameIndex))
        trait.write(1 or (if (isOverride) 0x20 else 0))
        trait.write(encodeU30(0))
        trait.write(encodeU30(methodIndex))
        val countBytes = source.copyOfRange(countOffset, countOffset + countWidth)
        return parse(rewriteSections(listOf(
            SectionEdit(4, countOffset - section.start, countBytes, encodeU30(count + 1)),
            SectionEdit(4, end - section.start, byteArrayOf(), trait.toByteArray()),
        )))
    }

    fun qualifiedName(index: Int): String? {
        if (index == 0) return null
        val name = multinames.getOrNull(index) ?: return null
        if (name.kind != 0x07 && name.kind != 0x0D) return null
        val namespace = namespaces.getOrNull(name.namespaceIndex) ?: return null
        val local = strings.getOrNull(name.nameIndex) ?: return null
        if (namespace.name.isEmpty()) return local
        return if (namespace.kind == 0x16) {
            "${namespace.name}::$local"
        } else {
            "${namespace.kind}:${namespace.name}::$local"
        }
    }

    fun multinameIndices(name: String): List<Int> = multinames.indices.filter {
        it != 0 && localName(it) == name
    }

    private fun localName(index: Int): String? {
        if (index == 0) return null
        val name = multinames.getOrNull(index) ?: return null
        if (name.kind !in setOf(0x07, 0x0D, 0x09, 0x0E, 0x0F, 0x10)) return null
        return strings.getOrNull(name.nameIndex)
    }

    fun namespaceInfo(index: Int): NamespaceInfo? {
        val namespace = namespaces.getOrNull(index) ?: return null
        return NamespaceInfo(namespace.kind, namespace.name)
    }

    fun traitNamespaceIndex(owner: String, name: String, isStatic: Boolean): Int? {
        val trait = findTraits(owner, name, isStatic).singleOrNull()?.trait ?: return null
        return namespaceIndex(trait.nameIndex)
    }

    fun findTraits(owner: String, name: String, isStatic: Boolean): List<TraitRef> {
        val result = mutableListOf<TraitRef>()
        for (classInfo in classes) {
            if (qualifiedName(classInfo.nameIndex) != owner) continue
            val traits = if (isStatic) classInfo.staticTraits else classInfo.instanceTraits
            for (trait in traits) {
                if (localName(trait.nameIndex) == name) {
                    result += TraitRef(owner, qualifiedName(trait.nameIndex) ?: name, isStatic, trait)
                }
            }
        }
        return result
    }

    fun methodCode(methodIndex: Int): ByteArray? {
        val body = methodBody(methodIndex) ?: return null
        return source.copyOfRange(body.codeOffset, body.codeOffset + body.codeLength)
    }

    fun methodCodePatch(
        methodIndex: Int,
        codeOffset: Int,
        expected: ByteArray,
        replacement: ByteArray,
    ): BytePatch {
        require(expected.size == replacement.size) { "Method edit changes code length" }
        val body = methodBody(methodIndex) ?: throw IllegalArgumentException("Method has no body")
        require(codeOffset >= 0 && codeOffset <= body.codeLength - expected.size) {
            "Method code patch is out of range"
        }
        return BytePatch(body.codeOffset + codeOffset, expected, replacement)
    }

    fun namespaceIndexPatch(multinameIndex: Int, replacementIndex: Int): BytePatch {
        val multiname = multinames.getOrNull(multinameIndex)
            ?: throw IllegalArgumentException("Invalid ABC multiname index")
        require(multiname.kind == 0x07 || multiname.kind == 0x0D) {
            "Multiname is not a QName"
        }
        val width = multiname.namespaceIndexWidth
        val replacement = encodeU30(replacementIndex, width)
        val original = source.copyOfRange(multiname.namespaceIndexOffset, multiname.namespaceIndexOffset + width)
        return BytePatch(multiname.namespaceIndexOffset, original, replacement)
    }

    fun namespaceIndex(multinameIndex: Int): Int? {
        val multiname = multinames.getOrNull(multinameIndex) ?: return null
        if (multiname.kind != 0x07 && multiname.kind != 0x0D) return null
        return multiname.namespaceIndex
    }

    fun findMethods(owner: String, name: String): List<MethodRef> {
        val result = mutableListOf<MethodRef>()
        for (classInfo in classes) {
            val className = qualifiedName(classInfo.nameIndex) ?: continue
            if (className != owner) continue
            for ((isStatic, traits) in listOf(false to classInfo.instanceTraits, true to classInfo.staticTraits)) {
                for (trait in traits) {
                    if (trait.kind !in 1..3 || localName(trait.nameIndex) != name) continue
                    result += MethodRef(owner, qualifiedName(trait.nameIndex) ?: name,
                        isStatic, trait.kind, trait.methodIndex)
                }
            }
        }
        return result
    }

    fun methodBody(methodIndex: Int): MethodBody? = bodies.firstOrNull { it.methodIndex == methodIndex }

    private data class Section(val start: Int, val end: Int)
    private data class PoolLayout(
        val stringCountOffset: Int,
        val stringCountBytes: ByteArray,
        val stringEnd: Int,
        val multinameCountOffset: Int,
        val multinameCountBytes: ByteArray,
        val multinameEnd: Int,
    )
    private data class CountedSectionLayout(
        val countOffset: Int,
        val countBytes: ByteArray,
        val end: Int,
    )
    private data class Namespace(val kind: Int, val name: String)
    private data class Multiname(
        val kind: Int,
        val namespaceIndex: Int = 0,
        val nameIndex: Int = 0,
        val namespaceIndexOffset: Int = -1,
        val namespaceIndexWidth: Int = 0,
        val namespaceSetIndex: Int = 0,
    )

    companion object {
        fun parse(bytes: ByteArray): AbcFile {
            val reader = Reader(bytes)
            val headerStart = reader.position
            val minor = reader.u16()
            val major = reader.u16()
            val sections = mutableListOf(Section(headerStart, reader.position))

            val poolStart = reader.position
            val ints = reader.u32Pool()
            val uints = reader.u32Pool()
            val doubles = reader.fixedPool(8)
            val strings = reader.stringPool()
            val namespaceEntries = reader.namespacePool(strings)
            val namespaceSets = reader.namespaceSetPool(namespaceEntries.size)
            val multinames = reader.multinamePool(namespaceEntries.size, namespaceSets.size)
            val poolLayout = reader.poolLayout()
            sections += Section(poolStart, reader.position)

            val methodsStart = reader.position
            val methods = reader.methodInfos()
            sections += Section(methodsStart, reader.position)

            val metadataStart = reader.position
            val metadataCount = reader.metadata()
            sections += Section(metadataStart, reader.position)

            val classStart = reader.position
            val instances = reader.instances()
            val classInfos = reader.classes(instances.size)
            sections += Section(classStart, reader.position)

            val scriptStart = reader.position
            val scriptCount = reader.scripts()
            sections += Section(scriptStart, reader.position)

            val bodyStart = reader.position
            val bodies = reader.methodBodies()
            sections += Section(bodyStart, reader.position)
            require(reader.position == bytes.size) { "Unparsed ABC bytes" }

            val classes = instances.indices.map { index ->
                val instance = instances[index]
                val classInfo = classInfos[index]
                ClassInfo(
                    instance.nameIndex,
                    instance.superNameIndex,
                    instance.flags,
                    instance.initializer,
                    instance.traits,
                    classInfo.first,
                    classInfo.second,
                    instance.traitLayout.countOffset,
                    instance.traitLayout.countWidth,
                    instance.traitLayout.end,
                    classInfo.third.countOffset,
                    classInfo.third.countWidth,
                    classInfo.third.end,
                )
            }
            return AbcFile(
                minor,
                major,
                PoolCounts(
                    ints,
                    uints,
                    doubles,
                    strings.size - 1,
                    namespaceEntries.size - 1,
                    namespaceSets.size - 1,
                    multinames.size - 1,
                ),
                methods,
                metadataCount,
                classes,
                scriptCount,
                bodies,
                namespaceSets,
                bytes.copyOf(),
                sections,
                poolLayout,
                reader.methodLayout(),
                reader.bodyLayout(),
                strings,
                namespaceEntries,
                multinames,
            )
        }
    }

    private data class InstanceInfo(
        val nameIndex: Int,
        val superNameIndex: Int,
        val flags: Int,
        val initializer: Int,
        val traits: List<Trait>,
        val traitLayout: TraitGroupLayout,
    )
    private data class TraitGroupLayout(val countOffset: Int, val countWidth: Int, val end: Int)

    private class Reader(private val data: ByteArray) {
        var position: Int = 0
            private set
        private var stringCountOffset = -1
        private var stringCountBytes = byteArrayOf()
        private var stringEnd = -1
        private var multinameCountOffset = -1
        private var multinameCountBytes = byteArrayOf()
        private var multinameEnd = -1
        private var methodCountOffset = -1
        private var methodCountBytes = byteArrayOf()
        private var methodEnd = -1
        private var bodyCountOffset = -1
        private var bodyCountBytes = byteArrayOf()
        private var bodyEnd = -1
        private val traitLayouts = mutableListOf<TraitGroupLayout>()

        fun poolLayout() = PoolLayout(
            stringCountOffset, stringCountBytes, stringEnd,
            multinameCountOffset, multinameCountBytes, multinameEnd,
        )
        fun methodLayout() = CountedSectionLayout(methodCountOffset, methodCountBytes, methodEnd)
        fun bodyLayout() = CountedSectionLayout(bodyCountOffset, bodyCountBytes, bodyEnd)

        fun u8(): Int = take(1)[0].toInt() and 0xFF

        fun u16(): Int {
            val value = take(2)
            return (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
        }

        fun u30(): Int = variableInt(0x3FFFFFFF).toInt()

        private fun u32(): Long = variableInt(0xFFFFFFFFL)

        private fun variableInt(maximum: Long): Long {
            var value = 0L
            for (shift in 0..28 step 7) {
                val byte = u8()
                value = value or ((byte and 0x7F).toLong() shl shift)
                if (byte < 0x80) {
                    require(value <= maximum) { "Invalid ABC variable integer" }
                    return value
                }
            }
            throw IllegalArgumentException("Invalid ABC variable integer")
        }

        private fun take(length: Int): ByteArray {
            require(length >= 0 && length <= data.size - position) { "Truncated ABC" }
            val start = position
            position += length
            return data.copyOfRange(start, position)
        }

        private fun skip(length: Int) {
            take(length)
        }

        private fun checkedCount(count: Int, minimumSize: Int = 1): Int {
            require(count >= 0 && count.toLong() * minimumSize <= data.size - position) {
                "Invalid ABC item count"
            }
            return count
        }

        fun u32Pool(): Int {
            val size = u30()
            repeat(checkedCount((size - 1).coerceAtLeast(0))) { u32() }
            return (size - 1).coerceAtLeast(0)
        }

        fun fixedPool(width: Int): Int {
            val size = u30()
            val count = (size - 1).coerceAtLeast(0)
            require(count.toLong() * width <= data.size - position) { "Invalid ABC pool size" }
            repeat(count) { skip(width) }
            return count
        }

        fun stringPool(): List<String> {
            stringCountOffset = position
            val countStart = position
            val size = u30()
            stringCountBytes = data.copyOfRange(countStart, position)
            val count = checkedCount((size - 1).coerceAtLeast(0))
            val result = ArrayList<String>(count + 1)
            result += ""
            repeat(count) {
                val length = u30()
                result += String(take(length), Charsets.UTF_8)
            }
            stringEnd = position
            return result
        }

        fun namespacePool(strings: List<String>): List<Namespace?> {
            val size = u30()
            val count = checkedCount((size - 1).coerceAtLeast(0), 2)
            val result = ArrayList<Namespace?>(count + 1)
            result += null
            repeat(count) {
                val kind = u8()
                val nameIndex = u30()
                require(nameIndex < strings.size) { "Invalid ABC namespace string index" }
                result += Namespace(kind, strings[nameIndex])
            }
            return result
        }

        fun namespaceSetPool(namespaceCount: Int): List<List<Int>> {
            val size = u30()
            val count = (size - 1).coerceAtLeast(0)
            checkedCount(count)
            val result = ArrayList<List<Int>>(count + 1)
            result.add(emptyList())
            repeat(count) {
                val members = checkedCount(u30())
                result.add(List(members) {
                    u30().also { require(it in 1 until namespaceCount) { "Invalid ABC namespace set index" } }
                })
            }
            return result
        }

        fun multinamePool(namespaceCount: Int, namespaceSetCount: Int): List<Multiname?> {
            multinameCountOffset = position
            val countStart = position
            val size = u30()
            multinameCountBytes = data.copyOfRange(countStart, position)
            val count = (size - 1).coerceAtLeast(0)
            checkedCount(count)
            val result = ArrayList<Multiname?>(count + 1)
            result += null
            repeat(count) {
                val kind = u8()
                val value = when (kind) {
                    0x07, 0x0D -> {
                        val offset = position
                        val namespaceIndex = u30()
                        val width = position - offset
                        require(namespaceIndex in 1 until namespaceCount) { "Invalid ABC namespace index" }
                        Multiname(kind, namespaceIndex = namespaceIndex, nameIndex = u30(),
                            namespaceIndexOffset = offset, namespaceIndexWidth = width)
                    }
                    0x0F, 0x10 -> Multiname(kind, nameIndex = u30())
                    0x11, 0x12 -> Multiname(kind)
                    0x09, 0x0E -> {
                        val nameIndex = u30()
                        val setIndex = u30()
                        require(setIndex in 1 until namespaceSetCount) { "Invalid ABC namespace set index" }
                        Multiname(kind, nameIndex = nameIndex, namespaceSetIndex = setIndex)
                    }
                    0x1B, 0x1C -> {
                        val setIndex = u30()
                        require(setIndex in 1 until namespaceSetCount) { "Invalid ABC namespace set index" }
                        Multiname(kind, namespaceSetIndex = setIndex)
                    }
                    0x1D -> {
                        val base = u30()
                        val params = checkedCount(u30())
                        repeat(params) { u30() }
                        Multiname(kind, namespaceIndex = base)
                    }
                    else -> throw IllegalArgumentException("Unsupported ABC multiname kind: $kind")
                }
                result += value
            }
            multinameEnd = position
            return result
        }

        fun methodInfos(): List<MethodInfo> {
            methodCountOffset = position
            val countOffset = position
            val count = checkedCount(u30())
            methodCountBytes = data.copyOfRange(countOffset, position)
            val methods = List(count) {
                val parameterCount = checkedCount(u30())
                val returnType = u30()
                val parameters = List(parameterCount) { u30() }
                val name = u30()
                val flags = u8()
                if (flags and 0x08 != 0) {
                    val optionalCount = checkedCount(u30(), 2)
                    repeat(optionalCount) { u30(); u8() }
                }
                if (flags and 0x80 != 0) repeat(parameterCount) { u30() }
                MethodInfo(returnType, parameters, name, flags)
            }
            methodEnd = position
            return methods
        }

        fun metadata(): Int {
            val count = checkedCount(u30())
            repeat(count) {
                u30()
                val itemCount = checkedCount(u30(), 2)
                repeat(itemCount) { u30(); u30() }
            }
            return count
        }

        fun instances(): List<InstanceInfo> {
            val count = checkedCount(u30())
            return List(count) {
                val name = u30()
                val superName = u30()
                val flags = u8()
                if (flags and 0x08 != 0) u30()
                val interfaceCount = checkedCount(u30())
                repeat(interfaceCount) { u30() }
                val initializer = u30()
                val traits = traits()
                InstanceInfo(name, superName, flags, initializer, traits, traitLayouts.last())
            }
        }

        fun classes(count: Int): List<Triple<Int, List<Trait>, TraitGroupLayout>> = List(count) {
            val initializer = u30()
            val traits = traits()
            Triple(initializer, traits, traitLayouts.last())
        }

        fun scripts(): Int {
            val count = checkedCount(u30())
            repeat(count) { u30(); traits() }
            return count
        }

        fun methodBodies(): List<MethodBody> {
            bodyCountOffset = position
            val countOffset = position
            val count = checkedCount(u30())
            bodyCountBytes = data.copyOfRange(countOffset, position)
            val bodies = List(count) {
                val methodIndex = u30()
                val maxStack = u30()
                val localCount = u30()
                val initialScopeDepth = u30()
                val maxScopeDepth = u30()
                val codeLength = u30()
                val codeOffset = position
                skip(codeLength)
                val exceptionCount = checkedCount(u30(), 5)
                val exceptions = List(exceptionCount) {
                    ExceptionInfo(u30(), u30(), u30(), u30(), u30())
                }
                MethodBody(
                    methodIndex, maxStack, localCount, initialScopeDepth, maxScopeDepth,
                    codeOffset, codeLength, exceptions, traits(),
                )
            }
            bodyEnd = position
            return bodies
        }

        private fun traits(): List<Trait> {
            val countOffset = position
            val countStart = position
            val count = checkedCount(u30())
            val countWidth = position - countStart
            val result = List(count) {
                val name = u30()
                val tag = u8()
                val kind = tag and 0x0F
                val attributes = tag ushr 4
                val trait = when (kind) {
                    0, 6 -> {
                        val slot = u30()
                        val typeName = u30()
                        val value = u30()
                        val valueKind = if (value == 0) 0 else u8()
                        Trait(name, kind, attributes, slotId = slot, typeNameIndex = typeName,
                            valueIndex = value, valueKind = valueKind)
                    }
                    1, 2, 3 -> {
                        val dispatchId = u30()
                        val methodIndex = u30()
                        Trait(name, kind, attributes, methodIndex = methodIndex, dispatchId = dispatchId)
                    }
                    4 -> Trait(name, kind, attributes, slotId = u30(), classIndex = u30())
                    5 -> Trait(name, kind, attributes, slotId = u30(), functionIndex = u30())
                    else -> throw IllegalArgumentException("Unsupported ABC trait kind: $kind")
                }
                if (attributes and 0x04 != 0) {
                    val metadataCount = checkedCount(u30())
                    val metadata = List(metadataCount) { u30() }
                    trait.copy(metadataIndices = metadata)
                } else {
                    trait
                }
            }
            traitLayouts += TraitGroupLayout(countOffset, countWidth, position)
            return result
        }
    }
}
