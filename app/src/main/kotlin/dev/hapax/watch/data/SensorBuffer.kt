package dev.hapax.watch.data

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Thread-safe ring buffer for sensor readings.
 * When capacity is exceeded, oldest entries are dropped.
 */
class SensorBuffer(private val maxSize: Int = MAX_ENTRIES) {

    private val deque = ConcurrentLinkedDeque<SensorReading>()

    val size: Int get() = deque.size

    fun add(reading: SensorReading) {
        deque.addLast(reading)
        while (deque.size > maxSize) {
            deque.pollFirst()
        }
    }

    /**
     * Atomically drains all buffered readings and returns them in order.
     */
    fun drain(): List<SensorReading> {
        val result = mutableListOf<SensorReading>()
        while (true) {
            val item = deque.pollFirst() ?: break
            result.add(item)
        }
        return result
    }

    companion object {
        const val MAX_ENTRIES = 500
    }
}
