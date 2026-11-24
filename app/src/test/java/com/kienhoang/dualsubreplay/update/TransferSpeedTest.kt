package com.kienhoang.dualsubreplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferSpeedTest {
    @Test
    fun calculatesTransferSpeedFromNewBytesOnly() {
        assertEquals(2048L, transferBytesPerSecond(1024, 500))
        assertEquals(2_000_000L, transferBytesPerSecond(1_000_000, 500))
    }

    @Test
    fun excludesIdleAndUnknownIntervals() {
        assertNull(transferBytesPerSecond(0, 400))
        assertNull(transferBytesPerSecond(2048, 0))
        assertNull(transferBytesPerSecond(-1, 10))
    }
}
