package dev.airtouch64.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class Avm2CodeTest {
    @Test
    fun relocatesBranchTargetAcrossInsertedInstructions() {
        val code = byteArrayOf(0x10, 1, 0, 0, 0x02, 0x47)
        val result = Avm2Code.insert(code, listOf(Avm2Code.Insertion(4, byteArrayOf(0x02, 0x02))))
        assertContentEquals(byteArrayOf(0x10, 3, 0, 0, 0x02, 0x02, 0x02, 0x47), result)
    }

    @Test
    fun branchToReplacementStartExecutesReplacement() {
        val code = byteArrayOf(0x10, 0, 0, 0, 0x47)
        val result = Avm2Code.replace(code, listOf(Avm2Code.Replacement(4, 1, byteArrayOf(0x02, 0x47))))
        assertContentEquals(byteArrayOf(0x10, 0, 0, 0, 0x02, 0x47), result)
    }

    @Test
    fun canKeepExistingBranchTargetAfterInsertedBlock() {
        val code = byteArrayOf(0x10, 0, 0, 0, 0x47)
        val result = Avm2Code.insert(
            code,
            listOf(Avm2Code.Insertion(4, byteArrayOf(0x02, 0x02), branchTargetsAfter = true)),
        )
        assertContentEquals(byteArrayOf(0x10, 2, 0, 0, 0x02, 0x02, 0x47), result)
    }

    @Test
    fun rejectsInsertionInsideInstructionAndUnknownOpcodes() {
        assertFailsWith<IllegalArgumentException> {
            Avm2Code.insert(byteArrayOf(0x10, 0, 0, 0, 0x47), listOf(Avm2Code.Insertion(2, byteArrayOf(0x02))))
        }
        assertFailsWith<IllegalArgumentException> {
            Avm2Code.insert(byteArrayOf(0xFF.toByte()), listOf(Avm2Code.Insertion(0, byteArrayOf(0x02))))
        }
    }
}
