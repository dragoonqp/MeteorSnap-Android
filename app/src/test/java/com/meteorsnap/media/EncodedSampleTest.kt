package com.meteorsnap.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class EncodedSampleTest {

    @Test
    fun copyPayloadToDirectBuffer_preservesAllBytes() {
        val expected = byteArrayOf(0, 1, 2, 3, 0x7F, 0x80.toByte(), 0xFF.toByte())
        val sample = HardwareH264Encoder.EncodedSample(
            timestampNs = 1L,
            presentationTimeUs = 1L,
            flags = 0,
            generation = 1,
            data = expected
        )
        val destination = ByteBuffer.allocateDirect(sample.size)

        sample.copyPayloadTo(destination)
        assertTrue(destination.isDirect)
        assertEquals(expected.size, destination.position())

        destination.flip()
        val actual = ByteArray(destination.remaining())
        destination.get(actual)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun copyPayloadTo_rejectsInsufficientDestination() {
        val sample = HardwareH264Encoder.EncodedSample(
            timestampNs = 1L,
            presentationTimeUs = 1L,
            flags = 0,
            generation = 1,
            data = byteArrayOf(1, 2, 3)
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            sample.copyPayloadTo(ByteBuffer.allocateDirect(2))
        }
        assertTrue(error.message.orEmpty().contains("空间不足"))
    }
}
