package com.polymatic.meshify

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polymatic.meshify.ui.MeshifyApp
import com.polymatic.meshify.ui.MeshifyViewModel

class MainActivity : ComponentActivity() {
    private var hasBluetoothPermission = false
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasBluetoothPermission = result.values.all { it }
        if (hasBluetoothPermission) viewModelForPermissions?.toggleScan()
    }
    private var viewModelForPermissions: MeshifyViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MeshifyViewModel = viewModel()
            viewModelForPermissions = viewModel
            val state = viewModel.state.collectAsStateWithLifecycle().value
            MeshifyApp(
                state = state,
                onRequestPermissions = ::requestBluetoothPermissions,
                onToggleScan = viewModel::toggleScan,
                onConnect = viewModel::connect,
                onConnectByAddress = viewModel::connectByAddress,
                onDisconnect = viewModel::disconnect,
                onRemoveRecentMac = viewModel::removeRecentMac,
                onClearDebugLog = viewModel::clearDebugLog,
                onOpenChat = viewModel::openChat,
                onCloseChat = viewModel::closeChat,
                onSendMessage = viewModel::sendMessage,
                onRetryMessage = viewModel::retryMessage,
                onOpenChannel = viewModel::openChannel,
                onCloseChannel = viewModel::closeChannel,
                onSendChannelMessage = viewModel::sendChannelMessage,
                onAddChannel = viewModel::setChannel,
                onMonetChanged = viewModel::setMonetEnabled,
                onDarkModeChanged = viewModel::setDarkModeEnabled,
                onSetNodeName = viewModel::setNodeName,
                onSendSelfAdvert = viewModel::sendSelfAdvert,
                onSetRadioSettings = viewModel::setRadioSettings,
            )
        }
    }

    private fun requestBluetoothPermissions() {
        permissions.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            },
        )
    }
}
