package com.lxl.moderntachometer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.lxl.moderntachometer.protocol.TachometerFrame
import com.lxl.moderntachometer.protocol.TachometerProtocol
import com.lxl.moderntachometer.usb.UsbSerialManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

data class TachometerUiState(
    val isConnected: Boolean = false,
    val statusMessage: String = "未连接设备，请插入 USB 转速计",
    val baudRate: Int = TachometerProtocol.DEFAULT_BAUD_RATE,

    // 转速统计 (已应用校准系数)
    val currentRpm: Int = 0,
    val maxRpm: Int = 0,
    val minRpm: Int = 0,
    val avgRpm: Int = 0,
    val sampleCount: Long = 0L,

    // 校准设置
    val calibrationFactorText: String = "1.0",
    val bladesPerRev: Int = 1, // 每转反光贴片数 (自动换算为 1/N 系数)
    val ignoreZeroRpm: Boolean = true,

    // 脉冲计数
    val pulseCount: Long = 0L,
    val isCountUp: Boolean = true,

    // 线速度计算
    val diameterMmText: String = "",
    val isMetersPerSecond: Boolean = true,
    val lineSpeed: Double = 0.0,

    // 最近历史记录
    val recentLogs: List<String> = emptyList()
)

class TachometerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(TachometerUiState())
    val uiState: StateFlow<TachometerUiState> = _uiState.asStateFlow()

    private var rawMaxRpm: Int = 0
    private var rawMinRpm: Int = Int.MAX_VALUE
    private var totalCalibratedRpm: Long = 0L
    private var validSamples: Long = 0L

    private val usbManager = UsbSerialManager(
        context = application.applicationContext,
        onConnectionStateChanged = { connected, msg ->
            _uiState.update { it.copy(isConnected = connected, statusMessage = msg) }
        },
        onFrameReceived = { frame ->
            handleIncomingFrame(frame)
        }
    )

    init {
        usbManager.register()
        usbManager.scanAndConnect()
    }

    override fun onCleared() {
        super.onCleared()
        usbManager.unregister()
    }

    fun connectOrScan() {
        usbManager.scanAndConnect(_uiState.value.baudRate)
    }

    fun disconnect() {
        usbManager.disconnect()
        _uiState.update { it.copy(isConnected = false, statusMessage = "已手动断开连接") }
    }

    fun updateBaudRate(newBaudRate: Int) {
        _uiState.update { it.copy(baudRate = newBaudRate) }
        if (_uiState.value.isConnected) {
            usbManager.scanAndConnect(newBaudRate)
        }
    }

    private fun handleIncomingFrame(frame: TachometerFrame) {
        val state = _uiState.value
        val customFactor = state.calibrationFactorText.toDoubleOrNull() ?: 1.0
        val bladeDivider = state.bladesPerRev.coerceAtLeast(1).toDouble()
        val effectiveFactor = customFactor / bladeDivider

        val calibratedRpm = (frame.rawRpm * effectiveFactor).roundToInt()

        var newMax = state.maxRpm
        var newMin = state.minRpm
        var newAvg = state.avgRpm

        val shouldRecordStat = if (state.ignoreZeroRpm) calibratedRpm > 0 else true
        if (shouldRecordStat) {
            if (calibratedRpm > rawMaxRpm) rawMaxRpm = calibratedRpm
            if (calibratedRpm < rawMinRpm) rawMinRpm = calibratedRpm
            totalCalibratedRpm += calibratedRpm
            validSamples++

            newMax = rawMaxRpm
            newMin = if (rawMinRpm == Int.MAX_VALUE) 0 else rawMinRpm
            newAvg = (totalCalibratedRpm / validSamples).toInt()
        }

        val diameter = state.diameterMmText.toDoubleOrNull() ?: 0.0
        val speed = TachometerProtocol.calculateLineSpeed(
            rpm = calibratedRpm,
            diameterMm = diameter,
            isMetersPerSecond = state.isMetersPerSecond
        )

        val updatedLogs = if (calibratedRpm > 0 || !state.ignoreZeroRpm) {
            val entry = "$calibratedRpm RPM  [高:$newMax 低:$newMin 均:$newAvg]"
            (listOf(entry) + state.recentLogs).take(30)
        } else {
            state.recentLogs
        }

        _uiState.update {
            it.copy(
                currentRpm = calibratedRpm,
                maxRpm = newMax,
                minRpm = newMin,
                avgRpm = newAvg,
                sampleCount = validSamples,
                pulseCount = frame.pulseCount,
                lineSpeed = speed,
                recentLogs = updatedLogs
            )
        }
    }

    fun resetRpmStats() {
        rawMaxRpm = 0
        rawMinRpm = Int.MAX_VALUE
        totalCalibratedRpm = 0L
        validSamples = 0L
        _uiState.update {
            it.copy(
                currentRpm = 0,
                maxRpm = 0,
                minRpm = 0,
                avgRpm = 0,
                sampleCount = 0L,
                lineSpeed = 0.0,
                recentLogs = emptyList()
            )
        }
    }

    fun setCountDirection(isCountUp: Boolean) {
        _uiState.update { it.copy(isCountUp = isCountUp) }
        usbManager.sendCommand(TachometerProtocol.buildCountDirectionCommand(isCountUp))
    }

    fun resetHardwarePulseCount() {
        val isUp = _uiState.value.isCountUp
        _uiState.update { it.copy(pulseCount = 0L) }
        usbManager.sendCommand(TachometerProtocol.buildResetCountCommand(isUp))
    }

    fun updateCalibrationFactor(text: String) {
        _uiState.update { it.copy(calibrationFactorText = text) }
    }

    fun updateBladesPerRev(blades: Int) {
        _uiState.update { it.copy(bladesPerRev = blades.coerceIn(1, 32)) }
    }

    fun toggleIgnoreZero(ignore: Boolean) {
        _uiState.update { it.copy(ignoreZeroRpm = ignore) }
    }

    fun updateDiameter(text: String) {
        val diameter = text.toDoubleOrNull() ?: 0.0
        val currentRpm = _uiState.value.currentRpm
        val speed = TachometerProtocol.calculateLineSpeed(
            rpm = currentRpm,
            diameterMm = diameter,
            isMetersPerSecond = _uiState.value.isMetersPerSecond
        )
        _uiState.update { it.copy(diameterMmText = text, lineSpeed = speed) }
    }

    fun setLineSpeedUnit(isMetersPerSecond: Boolean) {
        val diameter = _uiState.value.diameterMmText.toDoubleOrNull() ?: 0.0
        val currentRpm = _uiState.value.currentRpm
        val speed = TachometerProtocol.calculateLineSpeed(
            rpm = currentRpm,
            diameterMm = diameter,
            isMetersPerSecond = isMetersPerSecond
        )
        _uiState.update { it.copy(isMetersPerSecond = isMetersPerSecond, lineSpeed = speed) }
    }
}
