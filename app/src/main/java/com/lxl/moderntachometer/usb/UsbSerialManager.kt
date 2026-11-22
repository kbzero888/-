package com.lxl.moderntachometer.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.lxl.moderntachometer.protocol.TachometerFrame
import com.lxl.moderntachometer.protocol.TachometerProtocol
import java.io.IOException
import java.util.concurrent.Executors

class UsbSerialManager(
    private val context: Context,
    private val onConnectionStateChanged: (Boolean, String) -> Unit,
    private val onFrameReceived: (TachometerFrame) -> Unit
) : SerialInputOutputManager.Listener {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.lxl.moderntachometer.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var currentBaudRate: Int = TachometerProtocol.DEFAULT_BAUD_RATE

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let { connectToDevice(it, currentBaudRate) }
                        } else {
                            onConnectionStateChanged(false, "USB 权限被拒绝")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    scanAndConnect(currentBaudRate)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    disconnect()
                    onConnectionStateChanged(false, "设备已断开")
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        disconnect()
    }

    fun scanAndConnect(baudRate: Int = currentBaudRate) {
        currentBaudRate = baudRate
        val availableDrivers: List<UsbSerialDriver> =
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            onConnectionStateChanged(false, "未检测到串口转速计设备")
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
            onConnectionStateChanged(false, "正在请求 USB 访问权限...")
        } else {
            connectToDevice(device, baudRate)
        }
    }

    private fun connectToDevice(device: UsbDevice, baudRate: Int) {
        disconnect()
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: run {
            onConnectionStateChanged(false, "不支持的 USB 串口芯片")
            return
        }

        val connection = usbManager.openDevice(driver.device) ?: run {
            onConnectionStateChanged(false, "无法打开 USB 设备连接")
            return
        }

        try {
            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(
                baudRate,
                TachometerProtocol.DATA_BITS,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            port.dtr = true
            port.rts = true

            serialPort = port
            ioManager = SerialInputOutputManager(port, this).apply {
                readTimeout = 20
                Executors.newSingleThreadExecutor().submit(this)
            }
            val chipName = driver.javaClass.simpleName.replace("SerialDriver", "")
            onConnectionStateChanged(true, "已连接 ($chipName @ ${baudRate}bps)")
        } catch (e: IOException) {
            onConnectionStateChanged(false, "串口打开失败: ${e.message}")
            disconnect()
        }
    }

    fun sendCommand(bytes: ByteArray): Boolean {
        val port = serialPort ?: return false
        return try {
            port.write(bytes, 200)
            true
        } catch (e: IOException) {
            false
        }
    }

    fun disconnect() {
        ioManager?.listener = null
        ioManager?.stop()
        ioManager = null
        try {
            serialPort?.close()
        } catch (_: IOException) {
        }
        serialPort = null
    }

    override fun onNewData(data: ByteArray) {
        var offset = 0
        while (offset + 11 <= data.size) {
            val slice = data.copyOfRange(offset, offset + 11)
            TachometerProtocol.parseFrame(slice)?.let { frame ->
                onFrameReceived(frame)
            }
            offset += 11
        }
    }

    override fun onRunError(e: Exception) {
        onConnectionStateChanged(false, "通信异常断开: ${e.message}")
    }
}
