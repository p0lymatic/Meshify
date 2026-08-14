package com.polymatic.meshify.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CompoundBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraSettings
import com.polymatic.meshify.mesh.ChannelQrParser
import com.polymatic.meshify.mesh.Channel
import com.polymatic.meshify.ui.uiText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChannelDialog(
    onDismiss: () -> Unit,
    onAdd: (Int, String, String) -> Unit,
    onScanQr: () -> Unit,
    initialChannel: Channel? = null,
    defaultIndex: Int = 0,
) {
    var channelIndex by remember(initialChannel?.index, defaultIndex) { mutableIntStateOf(initialChannel?.index ?: defaultIndex.coerceIn(0, 7)) }
    var channelName by remember(initialChannel?.index) { mutableStateOf(initialChannel?.name.orEmpty()) }
    var channelPsk by remember(initialChannel?.index) { mutableStateOf(initialChannel?.pskHex.orEmpty()) }
    var showError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 20.dp, bottomEnd = 32.dp, bottomStart = 20.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    if (initialChannel == null) uiText("Добавить канал", "Add channel") else uiText("Изменить канал", "Edit channel"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                OutlinedTextField(
                    value = channelIndex.toString(),
                    onValueChange = {
                        val num = it.toIntOrNull()
                        if (num != null && num in 0..7) {
                            channelIndex = num
                            showError = false
                        }
                    },
                    label = { Text("Индекс канала (0-7)") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = initialChannel != null,
                    isError = showError,
                    supportingText = if (showError) {
                        { Text("Индекс должен быть от 0 до 7") }
                    } else null,
                )

                OutlinedTextField(
                    value = channelName,
                    onValueChange = { channelName = it },
                    label = { Text("Имя канала") },
                    placeholder = { Text("Общий") },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = channelPsk,
                    onValueChange = {
                        channelPsk = it.filter { c -> c in "0123456789ABCDEFabcdef" }
                    },
                    label = { Text("PSK (32 hex-символа)") },
                    placeholder = { Text("8b3387e9c5cdea6ac9e5edbaa115cd72") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    supportingText = { Text("${channelPsk.length}/32 characters") },
                )

                OutlinedButton(
                    onClick = onScanQr,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Сканировать QR")
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Отмена")
                    }
                    Button(
                        onClick = {
                            if (channelIndex in 0..7 && channelName.isNotBlank() && channelPsk.length == 32) {
                                onAdd(channelIndex, channelName, channelPsk)
                                onDismiss()
                            } else {
                                showError = true
                            }
                        },
                        enabled = channelName.isNotBlank() && channelPsk.length == 32,
                    ) {
                        Text(if (initialChannel == null) uiText("Добавить", "Add") else uiText("Сохранить", "Save"))
                    }
                }
            }
        }
    }
}

@Composable
fun QrChannelScanner(
    defaultIndex: Int,
    onDismiss: () -> Unit,
    onScanned: (Int, String, String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    var handled by remember { mutableStateOf(false) }
    val barcodeView = remember {
        CompoundBarcodeView(context).apply {
            setStatusText("")
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            cameraSettings = CameraSettings().apply {
                isAutoFocusEnabled = true
                isContinuousFocusEnabled = true
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            permissionGranted = granted
            if (!granted) errorMessage = "Для сканирования QR-кода нужен доступ к камере"
        }
    )

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    DisposableEffect(lifecycleOwner, permissionGranted) {
        val observer = LifecycleEventObserver { _, event ->
            if (!permissionGranted) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> barcodeView.resume()
                Lifecycle.Event.ON_PAUSE -> barcodeView.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (permissionGranted && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) barcodeView.resume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            barcodeView.pause()
        }
    }

    val callback = remember(defaultIndex) {
        object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                if (handled) return
                val raw = result?.text ?: return
                ChannelQrParser.parse(raw, defaultIndex)
                    .onSuccess { channel ->
                        handled = true
                        barcodeView.pause()
                        onScanned(channel.index, channel.name, channel.pskHex)
                    }
                    .onFailure { errorMessage = it.message ?: "Неподдерживаемый QR-код канала" }
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) = Unit
        }
    }
    LaunchedEffect(permissionGranted, callback) {
        if (permissionGranted) barcodeView.decodeContinuous(callback)
    }

    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            if (permissionGranted) {
                AndroidView(factory = { barcodeView }, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.Center).size(252.dp)
                        .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 28.dp, topEnd = 16.dp, bottomEnd = 28.dp, bottomStart = 16.dp)),
                )
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FilledTonalIconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
                }
                if (permissionGranted) {
                    FilledTonalIconButton(onClick = {
                        torchEnabled = !torchEnabled
                        if (torchEnabled) barcodeView.setTorchOn() else barcodeView.setTorchOff()
                    }) {
                        Icon(if (torchEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "Фонарик")
                    }
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                errorMessage?.let {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                        Text(it, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    if (permissionGranted) "Place the MeshCore channel QR inside the frame" else "Camera permission is unavailable",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!permissionGranted) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") }
                }
            }
        }
    }
}
