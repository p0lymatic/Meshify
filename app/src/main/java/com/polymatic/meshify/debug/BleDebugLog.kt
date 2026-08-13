package com.polymatic.meshify.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DebugEntry(val timestamp: String, val message: String)

/** In-app tail of the BLE log. The same events are emitted to Logcat as MeshifyBle. */
object BleDebugLog {
    private const val tag = "MeshifyBle"
    private const val maxEntries = 300
    private val _entries = MutableStateFlow<List<DebugEntry>>(emptyList())
    val entries: StateFlow<List<DebugEntry>> = _entries

    fun add(message: String) {
        Log.d(tag, message)
        val entry = DebugEntry(SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()), message)
        _entries.value = (_entries.value + entry).takeLast(maxEntries)
    }
    fun clear() { _entries.value = emptyList(); Log.d(tag, "--- log cleared ---") }
}
