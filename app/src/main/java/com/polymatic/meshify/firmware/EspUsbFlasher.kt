package com.polymatic.meshify.firmware

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val APP_IMAGE_OFFSET = 0x10_000

data class EspUsbDevice(val device: UsbDevice, val displayName: String)
data class FlashProgress(val writtenBytes: Long, val totalBytes: Long, val label: String)

/**
 * ESP ROM bootloader implementation for a single merged image at address 0x0.
 * The protocol is shared by ESP32-family boards; device-specific boot reset wiring
 * is intentionally limited to the conventional USB-UART DTR/RTS sequence.
 */
class EspUsbFlasher(private val context: Context) {
    private val usbManager = context.getSystemService(UsbManager::class.java)

    fun devices(): List<EspUsbDevice> = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).map { driver ->
        val device = driver.device
        EspUsbDevice(device, buildString {
            append(device.productName ?: "USB serial")
            append(" · ")
            append("%04X:%04X".format(device.vendorId, device.productId))
        })
    }

    fun hasPermission(device: EspUsbDevice): Boolean = usbManager.hasPermission(device.device)

    suspend fun flash(
        device: EspUsbDevice,
        image: File,
        mergedImage: Boolean,
        eraseBeforeFlash: Boolean,
        onProgress: (FlashProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(image.isFile && image.length() > 0) { "Прошивка не найдена" }
        require(!eraseBeforeFlash || mergedImage) { "Очистка перед записью требует merged-образ" }
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceId == device.device.deviceId }
            ?: error("USB serial-устройство не найдено")
        val connection = usbManager.openDevice(driver.device) ?: error("Нет разрешения на USB-устройство")
        val port = driver.ports.firstOrNull() ?: error("У устройства нет serial-порта")
        try {
            port.open(connection)
            port.setParameters(115_200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            enterBootloader(port)
            val protocol = EspRomProtocol(port)
            protocol.sync()
            if (eraseBeforeFlash) {
                onProgress(FlashProgress(0, 0, "Очистка flash-памяти"))
                protocol.eraseFlash()
            }
            val imageBytes = image.readBytes()
            protocol.flashImage(imageBytes, if (mergedImage) 0 else APP_IMAGE_OFFSET) { written, total ->
                onProgress(FlashProgress(written.toLong(), total.toLong(), "Запись прошивки"))
            }
            protocol.reboot()
            onProgress(FlashProgress(image.length(), image.length(), "Готово"))
        } finally {
            runCatching { port.close() }
            connection.close()
        }
    }

    private suspend fun enterBootloader(port: UsbSerialPort) {
        runCatching {
            port.dtr = false
            port.rts = true
            delay(100)
            port.dtr = true
            port.rts = false
            delay(100)
            port.dtr = false
            delay(100)
        }
    }
}

private class EspRomProtocol(private val port: UsbSerialPort) {
    fun sync() {
        val payload = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32)
        repeat(4) {
            runCatching { command(OP_SYNC, payload) }.getOrNull()?.let { return }
        }
        error("ESP bootloader не отвечает. Удерживайте BOOT и переподключите плату.")
    }

    fun flashImage(image: ByteArray, offset: Int, onProgress: (Int, Int) -> Unit) {
        val blockSize = 1024
        val blockCount = (image.size + blockSize - 1) / blockSize
        val eraseSize = ((image.size + 4095) / 4096) * 4096
        command(OP_FLASH_BEGIN, le(eraseSize, blockCount, blockSize, offset))
        repeat(blockCount) { sequence ->
            val start = sequence * blockSize
            val chunk = image.copyOfRange(start, minOf(start + blockSize, image.size))
            val padded = if (chunk.size == blockSize) chunk else chunk + ByteArray(blockSize - chunk.size) { 0xFF.toByte() }
            val data = le(padded.size, sequence, 0, 0) + padded
            command(OP_FLASH_DATA, data, checksum(padded))
            onProgress(minOf(start + chunk.size, image.size), image.size)
        }
    }

    fun eraseFlash() {
        command(OP_ERASE_FLASH, ByteArray(0))
    }

    fun reboot() {
        command(OP_FLASH_END, le(0))
    }

    private fun command(opcode: Int, data: ByteArray, checksum: Int = 0) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(0)
            .put(opcode.toByte())
            .putShort(data.size.toShort())
            .putInt(checksum)
            .array()
        port.write(slipEncode(header + data), WRITE_TIMEOUT_MS)
        val response = readSlipFrame()
        require(response.size >= 8 && response[0].toInt() == 1 && (response[1].toInt() and 0xFF) == opcode) {
            "Некорректный ответ ESP bootloader"
        }
        val status = response.drop(8).takeLast(2).firstOrNull()?.toInt()?.and(0xFF) ?: 0
        require(status == 0) { "ESP bootloader error $status" }
    }

    private fun readSlipFrame(): ByteArray {
        val received = ArrayList<Byte>()
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        var started = false
        while (System.currentTimeMillis() < deadline) {
            val count = port.read(buffer, 250)
            for (index in 0 until count) {
                val value = buffer[index]
                if (value == SLIP_END) {
                    if (started && received.isNotEmpty()) return slipDecode(received.toByteArray())
                    started = true
                    received.clear()
                } else if (started) {
                    received += value
                }
            }
        }
        error("Таймаут ESP bootloader")
    }

    private fun checksum(bytes: ByteArray): Int = bytes.fold(0xEF) { current, byte -> current xor (byte.toInt() and 0xFF) }

    private fun le(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach(::putInt) }
        .array()

    private fun slipEncode(bytes: ByteArray): ByteArray = buildList<Byte> {
        add(SLIP_END)
        bytes.forEach { value ->
            when (value) {
                SLIP_END -> { add(SLIP_ESC); add(SLIP_ESC_END) }
                SLIP_ESC -> { add(SLIP_ESC); add(SLIP_ESC_ESC) }
                else -> add(value)
            }
        }
        add(SLIP_END)
    }.toByteArray()

    private fun slipDecode(bytes: ByteArray): ByteArray = buildList<Byte> {
        var escaped = false
        bytes.forEach { value ->
            if (escaped) {
                add(if (value == SLIP_ESC_END) SLIP_END else if (value == SLIP_ESC_ESC) SLIP_ESC else value)
                escaped = false
            } else if (value == SLIP_ESC) {
                escaped = true
            } else add(value)
        }
    }.toByteArray()

    private companion object {
        const val OP_FLASH_BEGIN = 0x02
        const val OP_FLASH_DATA = 0x03
        const val OP_FLASH_END = 0x04
        const val OP_SYNC = 0x08
        const val OP_ERASE_FLASH = 0xD0
        const val WRITE_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 5_000L
        const val SLIP_END: Byte = 0xC0.toByte()
        const val SLIP_ESC: Byte = 0xDB.toByte()
        const val SLIP_ESC_END: Byte = 0xDC.toByte()
        const val SLIP_ESC_ESC: Byte = 0xDD.toByte()
    }
}
