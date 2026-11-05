package dev.airtouch64.engine

/** AVM2 instruction layout and branch relocation for method-code insertions. */
object Avm2Code {
    data class Insertion(val offset: Int, val code: ByteArray, val branchTargetsAfter: Boolean = false)
    data class Replacement(
        val offset: Int,
        val removedLength: Int,
        val code: ByteArray,
        val branchTargetsAfter: Boolean = false,
    )

    private data class Branch(val operandOffset: Int, val target: Int, val base: Int)
    private data class Instruction(val offset: Int, val end: Int, val branches: List<Branch>)

    fun insert(code: ByteArray, insertions: List<Insertion>): ByteArray {
        return replace(code, insertions.map { Replacement(it.offset, 0, it.code, it.branchTargetsAfter) })
    }

    fun replace(code: ByteArray, replacements: List<Replacement>): ByteArray {
        val sorted = replacements.sortedBy { it.offset }
        require(sorted.all { it.offset >= 0 && it.removedLength >= 0 && it.offset <= code.size - it.removedLength }) {
            "AVM2 replacement is out of range"
        }
        require(sorted.zipWithNext().all { (a, b) -> a.offset + a.removedLength <= b.offset }) {
            "Overlapping AVM2 replacements"
        }
        val instructions = decode(code)
        val boundaries = instructions.mapTo(mutableSetOf(code.size)) { it.offset }
        require(sorted.all { it.offset in boundaries && it.offset + it.removedLength in boundaries }) {
            "AVM2 replacement is not aligned to instruction boundaries"
        }

        fun relocated(offset: Int): Int {
            var result = offset
            for (replacement in sorted) {
                val end = replacement.offset + replacement.removedLength
                if (replacement.removedLength == 0) {
                    if (offset > replacement.offset || (offset == replacement.offset && replacement.branchTargetsAfter)) {
                        result += replacement.code.size
                    }
                } else if (offset >= end) {
                    result += replacement.code.size - replacement.removedLength
                } else if (offset == replacement.offset && replacement.branchTargetsAfter) {
                    result += replacement.code.size - replacement.removedLength
                } else if (offset > replacement.offset) {
                    throw IllegalArgumentException("AVM2 branch target is inside replaced code")
                }
            }
            return result
        }

        val replacementByOffset = sorted.associateBy { it.offset }
        val out = ByteArray(code.size + sorted.sumOf { it.code.size - it.removedLength })
        var outputOffset = 0
        for (instruction in instructions) {
            replacementByOffset[instruction.offset]?.let {
                it.code.copyInto(out, outputOffset)
                outputOffset += it.code.size
            }
            if (sorted.any { instruction.offset >= it.offset && instruction.offset < it.offset + it.removedLength }) {
                continue
            }
            val newStart = outputOffset
            code.copyInto(out, outputOffset, instruction.offset, instruction.end)
            outputOffset += instruction.end - instruction.offset
            for (branch in instruction.branches) {
                val target = relocated(branch.target)
                val base = if (branch.base == instruction.offset) newStart else outputOffset
                val delta = target - base
                require(delta in -0x800000..0x7FFFFF) { "AVM2 branch is out of range" }
                val operand = newStart + branch.operandOffset
                out[operand] = delta.toByte()
                out[operand + 1] = (delta ushr 8).toByte()
                out[operand + 2] = (delta ushr 16).toByte()
            }
        }
        replacementByOffset[code.size]?.let {
            it.code.copyInto(out, outputOffset)
            outputOffset += it.code.size
        }
        check(outputOffset == out.size)
        return out
    }

    private fun decode(code: ByteArray): List<Instruction> {
        val result = mutableListOf<Instruction>()
        var offset = 0
        while (offset < code.size) {
            val start = offset
            val opcode = u8(code, offset++)
            val branches = mutableListOf<Branch>()
            when (opcode) {
                in 0x0C..0x1A -> {
                    val operand = offset
                    val delta = s24(code, offset)
                    offset += 3
                    branches += Branch(operand - start, start + 4 + delta, start + 4)
                }
                0x1B -> {
                    val defaultOperand = offset
                    val defaultDelta = s24(code, offset)
                    offset += 3
                    val (caseCount, width) = u30(code, offset)
                    offset += width
                    branches += Branch(defaultOperand - start, start + defaultDelta, start)
                    repeat(caseCount + 1) {
                        val operand = offset
                        val delta = s24(code, offset)
                        offset += 3
                        branches += Branch(operand - start, start + delta, start)
                    }
                }
                else -> offset = skipOperands(code, offset, opcode)
            }
            require(offset <= code.size) { "Truncated AVM2 instruction" }
            branches.forEach { require(it.target in 0..code.size) { "AVM2 branch target is out of range" } }
            result += Instruction(start, offset, branches)
        }
        return result
    }

    private fun skipOperands(code: ByteArray, offset: Int, opcode: Int): Int {
        return when (opcode) {
            0x04, 0x05, 0x06, 0x08, 0x25, in 0x2C..0x2F, 0x31, 0x40, 0x41, 0x42, 0x49, 0x53,
            0x55, 0x56, 0x58, 0x59, 0x5A, 0x5D, 0x5E, 0x5F, 0x60, 0x61, 0x62, 0x63,
            0x66, 0x67, 0x68, 0x6A, 0x6C, 0x6D, 0x6E, 0x6F, 0x80, 0x86, 0x92, 0x94, 0xB2, 0xC2,
            0xC3, 0xF0, 0xF1, 0xF2 -> offset + u30(code, offset).second
            0x24, 0x65 -> offset + 1
            0x32 -> offset + u30(code, offset).second + u30(code, offset + u30(code, offset).second).second
            0x43, 0x44, 0x45, 0x46, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F -> {
                val first = u30(code, offset)
                offset + first.second + u30(code, offset + first.second).second
            }
            0xEF -> {
                val afterFirst = offset + 1
                val first = u30(code, afterFirst)
                val afterSecond = afterFirst + first.second + 1
                afterSecond + u30(code, afterSecond).second
            }
            0x01, 0x02, 0x03, 0x07, 0x09, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x23,
            0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B,
            0x30, 0x47, 0x48, 0x50, 0x51, 0x52, 0x57, 0x64, 0x69, 0x6B,
            0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78,
            0x82, 0x83, 0x84, 0x85, 0x87, 0x88, 0x89, 0x90, 0x91, 0x93, 0x95,
            0x96, 0x97, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8,
            0xA9, 0xAA, 0xAB, 0xAC, 0xAD, 0xAE, 0xAF, 0xB0, 0xB1, 0xB3, 0xB4,
            0xC0, 0xC1, 0xC4, 0xC5, 0xC6, 0xC7, 0xD0, 0xD1, 0xD2, 0xD3, 0xD4,
            0xD5, 0xD6, 0xD7, 0xF3 -> offset
            else -> throw IllegalArgumentException("Unsupported AVM2 opcode 0x${opcode.toString(16)}")
        }
    }

    private fun u8(code: ByteArray, offset: Int): Int {
        require(offset in code.indices) { "Truncated AVM2 code" }
        return code[offset].toInt() and 0xFF
    }

    private fun u30(code: ByteArray, offset: Int): Pair<Int, Int> {
        var value = 0
        for (index in 0..4) {
            val byte = u8(code, offset + index)
            value = value or ((byte and 0x7F) shl (index * 7))
            if (byte < 0x80) {
                require(value <= 0x3FFFFFFF) { "Invalid AVM2 U30" }
                return value to index + 1
            }
        }
        throw IllegalArgumentException("Invalid AVM2 U30")
    }

    private fun s24(code: ByteArray, offset: Int): Int {
        val value = u8(code, offset) or (u8(code, offset + 1) shl 8) or (u8(code, offset + 2) shl 16)
        return if (value and 0x800000 != 0) value or -0x1000000 else value
    }
}
