package com.polymatic.meshify.ui.screens

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.polymatic.meshify.firmware.EspUsbDevice
import com.polymatic.meshify.firmware.EspUsbFlasher
import com.polymatic.meshify.firmware.FirmwareCatalog
import com.polymatic.meshify.firmware.FirmwareCatalogRepository
import com.polymatic.meshify.firmware.FirmwareImage
import com.polymatic.meshify.firmware.FirmwareRelease
import com.polymatic.meshify.firmware.FirmwareRole
import com.polymatic.meshify.mesh.NodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirmwareFlasherSheet(currentNode: NodeInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { FirmwareCatalogRepository() }
    val flasher = remember { EspUsbFlasher(context.applicationContext) }
    var releases by remember { mutableStateOf<List<FirmwareRelease>>(emptyList()) }
    var loadingCatalog by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var role by remember { mutableStateOf<FirmwareRole?>(null) }
    var updatesOnly by remember(currentNode.firmware) { mutableStateOf(true) }
    var mergedImage by remember { mutableStateOf(false) }
    var eraseBeforeFlash by remember { mutableStateOf(false) }
    var usbDevices by remember { mutableStateOf(flasher.devices()) }
    var selectedUsb by remember { mutableStateOf<EspUsbDevice?>(null) }
    var selectedImage by remember { mutableStateOf<FirmwareImage?>(null) }
    var showConfirm by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var progressFraction by remember { mutableStateOf<Float?>(null) }
    var isFlashing by remember { mutableStateOf(false) }
    var confirmationSeconds by remember { mutableIntStateOf(0) }

    fun refreshCatalog() {
        loadingCatalog = true
        catalogError = null
        scope.launch {
            runCatching { repository.load() }
                .onSuccess { releases = it }
                .onFailure { catalogError = it.message ?: "Не удалось загрузить каталог" }
            loadingCatalog = false
        }
    }
    LaunchedEffect(Unit) { refreshCatalog() }
    val images = remember(releases, role, query, updatesOnly, mergedImage, currentNode.firmware) {
        FirmwareCatalog.images(releases, role, query, if (updatesOnly && !mergedImage) currentNode.firmware else null, mergedImage)
    }
    LaunchedEffect(showConfirm, mergedImage, eraseBeforeFlash) {
        if (showConfirm && (mergedImage || eraseBeforeFlash)) {
            confirmationSeconds = 3
            while (confirmationSeconds > 0) {
                delay(1_000)
                confirmationSeconds -= 1
            }
        } else confirmationSeconds = 0
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(.94f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Прошивка ESP", style = MaterialTheme.typography.headlineSmall)
                    Text("USB-OTG · официальный каталог MeshCore", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { refreshCatalog() }, enabled = !loadingCatalog) { Icon(Icons.Rounded.Refresh, "Обновить каталог") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                singleLine = true,
                label = { Text("Поиск платы, роли или версии") },
            )
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = role == null, onClick = { role = null }, label = { Text("Все") })
                FirmwareRole.entries.forEach { entry ->
                    FilterChip(selected = role == entry, onClick = { role = entry }, label = { Text(entry.russianLabel) })
                }
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    ListItem(
                        headlineContent = { Text(if (mergedImage) "Merged-образ" else "Обновление приложения") },
                        supportingContent = {
                            Text(
                                if (mergedImage) "Запишет полный образ с адреса 0x0"
                                else "${currentNode.model ?: "MeshCore"} · ${currentNode.firmware ?: "версия не определена"}",
                            )
                        },
                        leadingContent = { Icon(Icons.Rounded.Memory, null) },
                        trailingContent = {
                            Switch(
                                checked = mergedImage,
                                onCheckedChange = {
                                    mergedImage = it
                                    if (!it) eraseBeforeFlash = false
                                    selectedImage = null
                                },
                            )
                        },
                    )
                    ListItem(
                        headlineContent = { Text("Очистить перед прошивкой") },
                        supportingContent = {
                            Text(
                                if (eraseBeforeFlash) "Полностью удалит загрузчик, разделы, настройки и ключи"
                                else if (mergedImage) "Запишет поверх существующей flash-памяти"
                                else "Доступно только для merged-образа",
                            )
                        },
                        leadingContent = { Icon(Icons.Rounded.DeleteSweep, null) },
                        trailingContent = {
                            Switch(
                                checked = eraseBeforeFlash,
                                enabled = mergedImage,
                                onCheckedChange = { eraseBeforeFlash = it },
                            )
                        },
                    )
                    if (!mergedImage && !currentNode.firmware.isNullOrBlank()) {
                        FilterChip(
                            selected = updatesOnly,
                            onClick = { updatesOnly = !updatesOnly },
                            label = { Text(if (updatesOnly) "Только более новые версии" else "Все версии") },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    Text("USB-устройство", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    if (usbDevices.isEmpty()) {
                        AssistChip(onClick = { usbDevices = flasher.devices() }, label = { Text("Подключите ESP32 по USB-OTG") }, leadingIcon = { Icon(Icons.Rounded.Usb, null) })
                    } else {
                        usbDevices.forEach { device ->
                            FilterChip(
                                selected = selectedUsb?.device?.deviceId == device.device.deviceId,
                                onClick = { selectedUsb = device },
                                label = { Text(device.displayName) },
                                leadingIcon = { Icon(Icons.Rounded.Usb, null) },
                            )
                        }
                    }
                }
                item {
                    Text(
                        if (mergedImage) "Merged-образы ESP" else "Образы приложения ESP",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
                if (loadingCatalog) item { Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
                catalogError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) } }
                if (!loadingCatalog && catalogError == null && images.isEmpty()) item {
                    Text(
                        if (updatesOnly) "Более новых образов не найдено" else "Подходящих образов не найдено",
                        modifier = Modifier.padding(8.dp),
                    )
                }
                items(images, key = { it.file.absoluteUrl }) { image ->
                    FirmwareImageRow(image, selected = selectedImage?.file?.absoluteUrl == image.file.absoluteUrl) { selectedImage = image }
                }
            }
            progress?.let {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                    WavyProgressIndicator(progressFraction, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            Button(
                onClick = {
                    val device = selectedUsb ?: return@Button
                    if (!flasher.hasPermission(device)) {
                        requestUsbPermission(context, device)
                        progress = "Разрешите доступ к USB и нажмите «Прошить» ещё раз"
                    } else if (selectedImage != null) showConfirm = true
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                enabled = selectedUsb != null && selectedImage != null && !isFlashing,
            ) {
                Icon(Icons.Rounded.Memory, null)
                Spacer(Modifier.width(8.dp))
                Text(if (mergedImage) "Прошить merged" else "Установить обновление")
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showConfirm) {
        val image = selectedImage ?: return
        val device = selectedUsb ?: return
        val isMergedImage = mergedImage
        val shouldErase = eraseBeforeFlash
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(if (isMergedImage || shouldErase) "Перепрошить ESP?" else "Установить обновление?") },
            text = {
                Text(
                    if (isMergedImage || shouldErase) {
                        buildString {
                            append("${image.board}\n${image.release.russianRole()} ${image.release.version}\n\n")
                            if (shouldErase) append("Flash-память будет полностью очищена. Настройки, ключи и контакты будут удалены.\n\n")
                            if (isMergedImage) append("Merged-образ будет записан с адреса 0x0 и заменит загрузчик, таблицу разделов и приложение.")
                        }
                    } else {
                        "${image.board}\n${image.release.russianRole()} ${image.release.version}\n\nБудет обновлен только раздел приложения."
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    showConfirm = false
                    isFlashing = true
                    progress = "Загрузка ${image.file.name}"
                    progressFraction = 0f
                    scope.launch {
                        runCatching {
                            val file = repository.download(image.file, context.cacheDir) { done, total ->
                                progress = if (total > 0) "Загрузка: ${(done * 100 / total)}%" else "Загрузка: $done байт"
                                progressFraction = total.takeIf { it > 0 }?.let { done.toFloat() / it }
                            }
                            flasher.flash(device, file, isMergedImage, shouldErase) { state ->
                                progress = if (state.totalBytes > 0) {
                                    "${state.label}: ${(state.writtenBytes * 100 / state.totalBytes)}%"
                                } else state.label
                                progressFraction = state.totalBytes.takeIf { it > 0 }
                                    ?.let { state.writtenBytes.toFloat() / it }
                            }
                        }.onSuccess { progress = "Прошивка завершена"; progressFraction = 1f }
                            .onFailure { progress = "Ошибка: ${it.message ?: "неизвестная ошибка"}" }
                        isFlashing = false
                    }
                }, enabled = confirmationSeconds == 0) {
                    Text(if (confirmationSeconds > 0) "Прочтите предупреждение: $confirmationSeconds" else if (isMergedImage) "Прошить merged" else "Установить")
                }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun FirmwareImageRow(image: FirmwareImage, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(image.board, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text("${image.release.russianRole()} · ${image.release.version}\n${image.file.name}", style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Icon(Icons.Rounded.Memory, null) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
    )
}

private fun FirmwareRelease.russianRole(): String = role.russianLabel

private fun requestUsbPermission(context: Context, device: EspUsbDevice) {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    val intent = Intent("com.polymatic.meshify.USB_PERMISSION").setPackage(context.packageName)
    val pendingIntent = PendingIntent.getBroadcast(context, device.device.deviceId, intent, flags)
    context.getSystemService(UsbManager::class.java).requestPermission(device.device, pendingIntent)
}

/** A compact M3 Expressive-inspired progress track with a gently animated wave. */
@Composable
private fun WavyProgressIndicator(
    fraction: Float?,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "flash wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1_100),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave phase",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val progressColor = MaterialTheme.colorScheme.primary

    Canvas(modifier.height(14.dp)) {
        if (size.width <= 0f) return@Canvas
        val centerY = size.height / 2f
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, centerY - 3f),
            size = Size(size.width, 6f),
            cornerRadius = CornerRadius(3f, 3f),
        )

        val width = size.width * (fraction?.coerceIn(0f, 1f) ?: 1f)
        if (width <= 0f) return@Canvas
        clipRect(right = width) {
            val path = Path()
            val amplitude = 2.2f
            val cycles = 2f
            val step = (size.width / 120f).coerceAtLeast(1f)
            var x = 0f
            while (x <= size.width) {
                val y = centerY + sin((x / size.width) * cycles * 2f * PI + phase) * amplitude
                if (x == 0f) path.moveTo(x, y.toFloat()) else path.lineTo(x, y.toFloat())
                x += step
            }
            drawPath(
                path = path,
                color = progressColor,
                style = Stroke(width = 5f, cap = StrokeCap.Round),
            )
        }
    }
}
