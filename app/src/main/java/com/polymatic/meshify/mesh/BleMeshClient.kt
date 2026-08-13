package com.polymatic.meshify.mesh

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.polymatic.meshify.debug.BleDebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val hasNordicUart: Boolean,
    val hasKnownName: Boolean,
    val advertisedServices: String,
    val device: BluetoothDevice,
) {
    /** Device is likely MeshCore-compatible (advertises NUS or has a known firmware name prefix). */
    val isMeshCore: Boolean get() = hasNordicUart || hasKnownName
}

sealed interface BleState {
    data object Idle : BleState
    data object Scanning : BleState
    data class Connecting(val name: String) : BleState
    data class Connected(val name: String) : BleState
    data class Failed(val reason: String) : BleState
}

class BleMeshClient(private val context: Context, private val onFrame: (ByteArray) -> Unit) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private val _state = MutableStateFlow<BleState>(BleState.Idle)
    val state: StateFlow<BleState> = _state
    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices
    private val found = linkedMapOf<String, BleDevice>()
    private val handler = Handler(Looper.getMainLooper())
    private var scanActive = false
    private var scanTimeoutMs = 10_000L
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var serviceDiscoveryStarted = false
    private var mtuFallback: Runnable? = null
    private val stopScanTask = Runnable { stopScan("${scanTimeoutMs / 1000}s timeout"); _state.value = BleState.Idle }

    // ── Scan callbacks ─────────────────────────────────────────────────────

    /** Callback shared by both the NUS-filtered scan and the unfiltered fallback. */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val record = result.scanRecord
            val services = record?.serviceUuids?.joinToString { it.uuid.toString() } ?: "none"
            val nus = record?.serviceUuids?.any { it.uuid == NUS_SERVICE } == true
            val deviceName = (if (hasConnectPermission()) {
                try { device.name } catch (_: SecurityException) { null }
            } else null) ?: record?.deviceName ?: ""
            val displayName = deviceName.ifBlank { "Unknown device" }
            val knownName = isKnownMeshCoreName(deviceName)
            val item = BleDevice(displayName, device.address, result.rssi, nus, knownName, services, device)
            val old = found[item.address]
            if (old == null || old.rssi != item.rssi || old.name != item.name || old.hasNordicUart != nus) {
                BleDebugLog.add("ADV ${item.address} name='${item.name}' RSSI=${item.rssi} NUS=$nus knownName=$knownName services=$services")
            }
            found[item.address] = item
            publishDeviceList()
        }
        override fun onScanFailed(errorCode: Int) {
            BleDebugLog.add("SCAN FAILED code=$errorCode (${scanError(errorCode)})")
            // Error 1 (ALREADY_STARTED) is benign — just log and keep going.
            if (errorCode != ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
                _state.value = BleState.Failed("BLE scan error $errorCode: ${scanError(errorCode)}")
            }
        }
    }

    /**
     * Sorts discovered devices: MeshCore-identified first (NUS or known name prefix), then by RSSI.
     * Matches meshcore-open's approach where NUS-advertising devices are shown prominently.
     */
    private fun publishDeviceList() {
        _devices.value = found.values.sortedWith(
            compareByDescending<BleDevice> { it.isMeshCore }
                .thenByDescending { it.rssi }
        )
    }

    // ── GATT callback ──────────────────────────────────────────────────────

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            BleDebugLog.add("GATT state address=${gatt.device.address} status=$status newState=$newState")
            if (!hasConnectPermission()) {
                _state.value = BleState.Failed("Bluetooth permission was revoked")
                return
            }
            try {
                if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                    _state.value = BleState.Failed("Connection failed ($status)")
                    gatt.close()
                    return
                }
                this@BleMeshClient.gatt = gatt
                serviceDiscoveryStarted = false
                if (!gatt.requestMtu(185)) {
                    BleDebugLog.add("MTU request was rejected; using the default MTU")
                    discoverServices(gatt)
                } else {
                    val fallback = Runnable {
                        BleDebugLog.add("MTU callback timed out; continuing with the default MTU")
                        discoverServices(gatt)
                    }
                    mtuFallback = fallback
                    handler.postDelayed(fallback, MTU_NEGOTIATION_TIMEOUT_MS)
                }
            } catch (_: SecurityException) {
                _state.value = BleState.Failed("Bluetooth permission was revoked")
            }
        }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtuFallback?.let(handler::removeCallbacks)
            mtuFallback = null
            BleDebugLog.add("MTU negotiated=$mtu status=$status")
            discoverServices(gatt)
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            BleDebugLog.add("Services discovered status=$status: ${gatt.services.joinToString { it.uuid.toString() }}")
            val service = gatt.getService(NUS_SERVICE)
            rx = service?.getCharacteristic(NUS_RX)
            val tx = service?.getCharacteristic(NUS_TX)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null || tx == null) { _state.value = BleState.Failed("Nordic UART service not found"); BleDebugLog.add("NUS missing. Expected service=$NUS_SERVICE"); return }
            if (!hasConnectPermission()) {
                _state.value = BleState.Failed("Bluetooth permission was revoked")
                return
            }
            try {
                gatt.setCharacteristicNotification(tx, true)
                tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                tx.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val cccd = tx.getDescriptor(CCCD)
                if (cccd == null) { _state.value = BleState.Failed("Notifications unavailable"); return }
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            } catch (_: SecurityException) {
                _state.value = BleState.Failed("Bluetooth permission was revoked")
            }
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            BleDebugLog.add("Descriptor write ${descriptor.uuid} status=$status")
            if (descriptor.uuid == CCCD && status == BluetoothGatt.GATT_SUCCESS) {
                val displayName = if (hasConnectPermission()) {
                    try { gatt.device.name } catch (_: SecurityException) { null }
                } else null
                _state.value = BleState.Connected(displayName ?: gatt.device.address)
            }
        }
        @Deprecated("Deprecated in Java") override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { characteristic.value?.also { receive(it) } }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = receive(value)
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(connection: BluetoothGatt) {
        if (serviceDiscoveryStarted) return
        serviceDiscoveryStarted = true
        mtuFallback?.let(handler::removeCallbacks)
        mtuFallback = null
        try {
            if (!connection.discoverServices()) {
                _state.value = BleState.Failed("Service discovery could not be started")
                BleDebugLog.add("Service discovery request was rejected")
            }
        } catch (_: SecurityException) {
            _state.value = BleState.Failed("Bluetooth permission was revoked")
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Toggle scan: starts if idle, stops if already scanning.
     * Mirrors meshcore-open's _toggleScan() pattern.
     */
    @SuppressLint("MissingPermission") fun toggleScan() {
        if (scanActive || _state.value is BleState.Scanning) stopScanAndIdle("user toggled off")
        else scan()
    }

    /**
     * Start BLE scan. Uses the same strategy as meshcore-open:
     *
     * The scan runs **unfiltered** so that Heltec and other boards whose firmware does not advertise
     * the NUS service UUID still appear. Each advertisement is checked for:
     * 1. NUS service UUID in the advertisement data → [BleDevice.hasNordicUart]
     * 2. Known MeshCore firmware name prefix → [BleDevice.hasKnownName]
     *
     * Devices matching either heuristic are sorted to the top of the list.
     */
    @SuppressLint("MissingPermission") fun scan() {
        if (adapter?.isEnabled != true) {
            _state.value = BleState.Failed("Turn on Bluetooth to scan")
            BleDebugLog.add("Scan refused: Bluetooth adapter disabled")
            return
        }
        // Stop any prior scan cleanly before starting a new one.
        stopScan("restart")
        found.clear()
        _devices.value = emptyList()
        _state.value = BleState.Scanning
        BleDebugLog.add("Starting BLE scan (unfiltered, NUS + name heuristics applied to results)")
        // Small delay before starting (avoids SCAN_FAILED_ALREADY_STARTED race).
        handler.postDelayed({
            try {
                scanner?.startScan(
                    null, // No service filter — same reasoning as meshcore-open for Heltec compatibility.
                    ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // matches meshcore-open's androidScanMode.lowLatency
                        .build(),
                    scanCallback,
                )
                scanActive = true
                handler.postDelayed(stopScanTask, scanTimeoutMs)
            } catch (error: SecurityException) {
                _state.value = BleState.Failed("Bluetooth permission denied")
                BleDebugLog.add("Scan security error: ${error.message}")
            }
        }, 250)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(reason: String) {
        handler.removeCallbacks(stopScanTask)
        if (scanActive) {
            try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
            scanActive = false
            BleDebugLog.add("Stopped BLE scan: $reason")
        }
    }

    private fun stopScanAndIdle(reason: String) {
        stopScan(reason)
        if (_state.value is BleState.Scanning) _state.value = BleState.Idle
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BleDevice) {
        stopScan("connecting")
        _state.value = BleState.Connecting(device.name)
        BleDebugLog.add("Connecting to ${device.address} '${device.name}', NUS=${device.hasNordicUart} knownName=${device.hasKnownName}")
        gatt = device.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun connectByAddress(address: String) {
        val mac = address.trim().uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) { _state.value = BleState.Failed("Invalid MAC address: $mac"); BleDebugLog.add("Connect refused: invalid MAC '$mac'"); return }
        if (adapter?.isEnabled != true) { _state.value = BleState.Failed("Turn on Bluetooth"); BleDebugLog.add("Connect refused: Bluetooth adapter disabled"); return }
        stopScan("manual connect")
        val device = adapter!!.getRemoteDevice(mac)
        _state.value = BleState.Connecting(device.name ?: mac)
        BleDebugLog.add("Manual connect to $mac (name=${device.name ?: "unknown"})")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun write(frame: ByteArray) {
        handler.post {
            writeQueue.addLast(frame.copyOf())
            drainWriteQueue()
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeInFlight) return
        val characteristic = rx ?: run {
            if (writeQueue.isNotEmpty()) BleDebugLog.add("TX skipped: NUS RX is unavailable")
            writeQueue.clear()
            return
        }
        val frame = writeQueue.removeFirstOrNull() ?: return
        writeInFlight = true
        try {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            characteristic.value = frame
            val written = gatt?.writeCharacteristic(characteristic)
            BleDebugLog.add("TX ${frame.hex()} accepted=$written queue=${writeQueue.size}")
        } catch (_: SecurityException) {
            BleDebugLog.add("TX failed: Bluetooth permission was revoked")
        }
        // MeshCore NUS firmware and Android's GATT stack need a small gap even
        // for WRITE_TYPE_NO_RESPONSE; without it a contact request is dropped.
        handler.postDelayed({
            writeInFlight = false
            drainWriteQueue()
        }, 65L)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan("disconnect")
        handler.removeCallbacksAndMessages(null)
        writeQueue.clear()
        writeInFlight = false
        serviceDiscoveryStarted = false
        mtuFallback = null
        gatt?.disconnect(); gatt?.close(); gatt = null; rx = null
        _state.value = BleState.Idle
        BleDebugLog.add("Disconnected")
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private fun receive(value: ByteArray) { BleDebugLog.add("RX ${value.hex()}"); onFrame(value) }
    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }
    private fun scanError(code: Int) = when (code) { 1 -> "already started"; 2 -> "application registration failed"; 3 -> "internal error"; 4 -> "feature unsupported"; 5 -> "out of hardware resources"; 6 -> "too frequent"; else -> "unknown" }

    companion object {
        private val NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MTU_NEGOTIATION_TIMEOUT_MS = 1_500L

        /**
         * Known advertised-name prefixes used by stock MeshCore firmware builds.
         * Sourced from meshcore-open's [MeshCoreUuids.deviceNamePrefixes].
         */
        private val KNOWN_NAME_PREFIXES = listOf(
            "MeshCore-",
            "Whisper-",
            "WisCore-",
            "Seeed",
            "Lilygo",
            "HT-",
            "LowMesh_MC_",
            "NRF52",
        )

        /** Returns true if the BLE device name starts with any known MeshCore firmware prefix. */
        fun isKnownMeshCoreName(name: String): Boolean =
            name.isNotBlank() && KNOWN_NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
    }
}
