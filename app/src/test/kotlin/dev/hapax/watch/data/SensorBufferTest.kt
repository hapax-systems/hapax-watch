package dev.hapax.watch.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SensorBufferTest {

    private fun reading(bpm: Double) = SensorReading(
        type = "heart_rate",
        ts = "2025-01-01T00:00:00Z",
        bpm = bpm,
    )

    @Test
    fun `add and drain returns readings in order`() {
        val buffer = SensorBuffer()
        buffer.add(reading(60.0))
        buffer.add(reading(70.0))
        buffer.add(reading(80.0))

        val drained = buffer.drain()
        assertEquals(3, drained.size)
        assertEquals(60.0, drained[0].bpm)
        assertEquals(70.0, drained[1].bpm)
        assertEquals(80.0, drained[2].bpm)
    }

    @Test
    fun `drain empties the buffer`() {
        val buffer = SensorBuffer()
        buffer.add(reading(60.0))
        buffer.drain()
        assertEquals(0, buffer.size)
        assertTrue(buffer.drain().isEmpty())
    }

    @Test
    fun `overflow drops oldest entries`() {
        val buffer = SensorBuffer(maxSize = 3)
        buffer.add(reading(1.0))
        buffer.add(reading(2.0))
        buffer.add(reading(3.0))
        buffer.add(reading(4.0))
        buffer.add(reading(5.0))

        assertEquals(3, buffer.size)
        val drained = buffer.drain()
        assertEquals(3.0, drained[0].bpm)
        assertEquals(4.0, drained[1].bpm)
        assertEquals(5.0, drained[2].bpm)
    }

    @Test
    fun `size reflects current count`() {
        val buffer = SensorBuffer()
        assertEquals(0, buffer.size)
        buffer.add(reading(60.0))
        assertEquals(1, buffer.size)
        buffer.add(reading(70.0))
        assertEquals(2, buffer.size)
    }
}
