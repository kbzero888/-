package com.lxl.moderntachometer.protocol

import kotlin.math.PI

data class TachometerFrame(
    val rawRpm: Int,
    val pulseCount: Long
)

object TachometerProtocol {
    const val DEFAULT_BAUD_RATE = 115200
    const val DATA_BITS = 8
    const val STOP_BITS = 1
    const val PARITY = 0 // None

    fun parseFrame(data: ByteArray): TachometerFrame? {
        if (data.size < 11) return null

        val b1 = data[1].toInt() and 0xFF
        val b2 = data[2].toInt() and 0xFF
        val pulseBase = (b1 shl 8) or b2

        val b3 = data[3].toLong() and 0xFF
        val b4 = data[4].toLong() and 0xFF
        val b5 = data[5].toLong() and 0xFF
        val b6 = data[6].toLong() and 0xFF
        val timeTicks = (b3 shl 24) or (b4 shl 16) or (b5 shl 8) or b6

        val rawRpm = if (pulseBase > 0 && timeTicks > 0L) {
            val periodSec = (timeTicks.toDouble() / 100000.0) / pulseBase.toDouble()
            val rpmDouble = (1.0 / periodSec) * 60.0
            (rpmDouble + 0.5).toInt()
        } else {
            0
        }

        val b7 = data[7].toLong() and 0xFF
        val b8 = data[8].toLong() and 0xFF
        val b9 = data[9].toLong() and 0xFF
        val b10 = data[10].toLong() and 0xFF
        val totalCount = (b7 shl 24) or (b8 shl 16) or (b9 shl 8) or b10

        return TachometerFrame(
            rawRpm = rawRpm,
            pulseCount = totalCount
        )
    }

    fun buildCountDirectionCommand(isCountUp: Boolean): ByteArray {
        return byteArrayOf(
            0x02,
            0x00,
            if (isCountUp) 0x00 else 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    fun buildResetCountCommand(isCountUp: Boolean): ByteArray {
        return byteArrayOf(
            0x02,
            0xA5.toByte(),
            if (isCountUp) 0x00 else 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    fun calculateLineSpeed(rpm: Int, diameterMm: Double, isMetersPerSecond: Boolean): Double {
        if (rpm <= 0 || diameterMm <= 0.0) return 0.0
        val mmPerSec = (rpm / 60.0) * PI * diameterMm
        return if (isMetersPerSecond) {
            mmPerSec / 1000.0
        } else {
            mmPerSec * 0.0036
        }
    }
}
