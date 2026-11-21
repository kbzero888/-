package com.lxl.moderntachometer.protocol

import kotlin.math.PI
import kotlin.math.roundToInt

data class TachometerFrame(
    val rawRpm: Int,
    val pulseCount: Long
)

object TachometerProtocol {
    const val DEFAULT_BAUD_RATE = 115200
    const val DATA_BITS = 8
    const val STOP_BITS = 1
    const val PARITY = 0 // None

    /**
     * 解析下位机上传的 11 字节数据帧 (还原自 s0/a.smali)
     * b[1..2]: 大端 uint16 脉冲基数 N
     * b[3..6]: 大端 uint32 时间差 T (单位 10us)
     * b[7..10]: 大端 uint32 累计脉冲数
     */
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

    /**
     * 构建计数方向控制帧 (还原自 s0/c.smali)
     * isCountUp = true  -> [0x02, 0x00, 0x00, 0, 0, 0, 0, 0]
     * isCountUp = false -> [0x02, 0x00, 0x01, 0, 0, 0, 0, 0]
     */
    fun buildCountDirectionCommand(isCountUp: Boolean): ByteArray {
        return byteArrayOf(
            0x02,
            0x00,
            if (isCountUp) 0x00 else 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    /**
     * 构建硬件累计计数清零指令 (还原自 s0/c.smali)
     * [0x02, 0xA5, mode, 0, 0, 0, 0, 0]
     */
    fun buildResetCountCommand(isCountUp: Boolean): ByteArray {
        return byteArrayOf(
            0x02,
            0xA5.toByte(),
            if (isCountUp) 0x00 else 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    /**
     * 计算线速度 (还原自 s0/a.smali)
     * @param rpm 校准后的转速 (转/分)
     * @param diameterMm 旋转体直径 (mm)
     * @param isMetersPerSecond true = m/s, false = km/h
     */
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
