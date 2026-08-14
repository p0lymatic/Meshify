package com.polymatic.meshify.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.polymatic.meshify.map.GeoPoint
import com.polymatic.meshify.map.LineOfSightService
import com.polymatic.meshify.map.LosResult
import com.polymatic.meshify.mesh.Contact
import com.polymatic.meshify.mesh.ContactType
import com.polymatic.meshify.ui.MeshUiState
import com.polymatic.meshify.ui.uiText
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private const val PREF_MAP_PIN_SCALE = "map_pin_scale"
private const val PREF_MAP_LABEL_SCALE = "map_label_scale"
private const val PREF_MAP_SHOW_LABELS = "map_show_labels"

@Composable
fun NodeMapScreen(
    state: MeshUiState,
    onOpenChat: (Contact) -> Unit,
    onRequestLocation: (() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { LineOfSightService() }
    var selected by remember { mutableStateOf<Contact?>(null) }
    var result by remember { mutableStateOf<LosResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showRoutes by rememberSaveable { mutableStateOf(true) }
    var showDisplayOptions by rememberSaveable { mutableStateOf(false) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    val mapPreferences = remember { context.getSharedPreferences("meshify", Context.MODE_PRIVATE) }
    var pinScale by remember { mutableStateOf(mapPreferences.getFloat(PREF_MAP_PIN_SCALE, 1.25f)) }
    var labelScale by remember { mutableStateOf(mapPreferences.getFloat(PREF_MAP_LABEL_SCALE, 1.2f)) }
    var showLabels by remember { mutableStateOf(mapPreferences.getBoolean(PREF_MAP_SHOW_LABELS, true)) }
    val locatedContacts = state.contacts.filter { it.hasCoordinates() }
    val visibleContacts = locatedContacts.filter { contact ->
        searchQuery.isBlank() || contact.name.contains(searchQuery, ignoreCase = true)
    }
    var phonePoint by remember { mutableStateOf(context.lastKnownLocation()?.toGeoPoint()) }
    val ownPoint = state.node.toGeoPoint() ?: phonePoint

    Scaffold(
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExtendedFloatingActionButton(
                    onClick = {
                        onRequestLocation {
                            phonePoint = context.lastKnownLocation()?.toGeoPoint()
                            context.requestCurrentLocation { point -> phonePoint = point }
                        }
                    },
                    icon = { Icon(Icons.Rounded.MyLocation, null) },
                    text = { Text("GPS") },
                )
                if (selected != null && ownPoint != null) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            val target = selected?.toGeoPoint() ?: return@ExtendedFloatingActionButton
                            checking = true
                            result = null
                            scope.launch {
                                result = service.analyze(ownPoint, target, (state.node.frequencyHz ?: 915_000_000) / 1_000_000.0)
                                checking = false
                            }
                        },
                        icon = { if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Terrain, null) },
                        text = { Text("LOS") },
                    )
                }
                SmallFloatingActionButton(onClick = { showDisplayOptions = true }) {
                    Icon(Icons.Rounded.Tune, contentDescription = uiText("Отображение карты", "Map display"))
                }
            }
        },
    ) { padding ->
        if (locatedContacts.isEmpty() && ownPoint == null) {
            EmptyMapState(Modifier.padding(padding))
        } else {
            Box(Modifier.padding(padding).fillMaxSize()) {
                OsmMap(
                    modifier = Modifier.fillMaxSize(),
                    contacts = visibleContacts,
                    self = ownPoint,
                    initialCenter = ownPoint ?: locatedContacts.first().toGeoPoint()!!,
                    darkTiles = state.theme.darkMode,
                    showRoutes = showRoutes,
                    routingContacts = locatedContacts,
                    selectedContactKey = selected?.publicKey,
                    pinScale = pinScale,
                    labelScale = labelScale,
                    showLabels = showLabels,
                    los = result,
                    losTarget = selected?.toGeoPoint(),
                    onContactSelected = { selected = it },
                    onMapReady = { mapView = it },
                )
                MapSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                )
                if (searchQuery.isNotBlank() && visibleContacts.isEmpty()) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 78.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .96f),
                        tonalElevation = 4.dp,
                    ) {
                        Text(
                            uiText("Ноды не найдены", "No nodes found"),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
    if (showDisplayOptions) {
        MapDisplayOptionsSheet(
            pinScale = pinScale,
            labelScale = labelScale,
            showLabels = showLabels,
            onPinScaleChange = {
                pinScale = it
                mapPreferences.edit().putFloat(PREF_MAP_PIN_SCALE, it).apply()
            },
            onLabelScaleChange = {
                labelScale = it
                mapPreferences.edit().putFloat(PREF_MAP_LABEL_SCALE, it).apply()
            },
            onLabelsChange = {
                showLabels = it
                mapPreferences.edit().putBoolean(PREF_MAP_SHOW_LABELS, it).apply()
            },
            routesShown = showRoutes,
            onZoomIn = { mapView?.controller?.zoomIn() },
            onZoomOut = { mapView?.controller?.zoomOut() },
            onCenter = {
                val focus = ownPoint ?: visibleContacts.firstOrNull()?.toGeoPoint()
                if (focus != null) {
                    mapView?.controller?.apply {
                        setCenter(focus.toOsm())
                        setZoom(12.0)
                    }
                }
            },
            onToggleRoutes = { showRoutes = !showRoutes },
            onDismiss = { showDisplayOptions = false },
        )
    }
    selected?.let { contact ->
        ContactDetailsSheet(
            contact = contact,
            onDismiss = { selected = null },
            onOpenChat = { selected = null; onOpenChat(contact) },
            onRunLos = if (ownPoint == null || contact.toGeoPoint() == null) null else ({
                checking = true
                result = null
                scope.launch {
                    result = service.analyze(ownPoint, contact.toGeoPoint()!!, (state.node.frequencyHz ?: 915_000_000) / 1_000_000.0)
                    checking = false
                }
            }),
            los = result,
            checkingLos = checking,
        )
    }
}

@Composable
private fun OsmMap(
    modifier: Modifier,
    contacts: List<Contact>,
    self: GeoPoint?,
    initialCenter: GeoPoint,
    darkTiles: Boolean,
    showRoutes: Boolean,
    routingContacts: List<Contact>,
    selectedContactKey: String?,
    pinScale: Float,
    labelScale: Float,
    showLabels: Boolean,
    los: LosResult?,
    losTarget: GeoPoint?,
    onContactSelected: (Contact) -> Unit,
    onMapReady: (MapView) -> Unit,
) {
    val context = LocalContext.current
    val accentColor = MaterialTheme.colorScheme.primary.toArgb()
    val secondaryColor = MaterialTheme.colorScheme.secondary.toArgb()
    val errorColor = MaterialTheme.colorScheme.error.toArgb()
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary.toArgb()
    val onSecondaryColor = MaterialTheme.colorScheme.onSecondary.toArgb()
    val onErrorColor = MaterialTheme.colorScheme.onError.toArgb()
    val selfLabel = uiText("Моя нода", "My node")
    AndroidView(
        modifier = modifier,
        factory = {
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                overlayManager.tilesOverlay.setColorFilter(mapTileFilter(darkTiles))
                setMultiTouchControls(true)
                controller.setZoom(11.0)
                controller.setCenter(initialCenter.toOsm())
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                onMapReady(this)
            }
        },
        update = { map ->
            map.overlayManager.tilesOverlay.setColorFilter(mapTileFilter(darkTiles))
            map.overlays.clear()
            if (showRoutes) {
                val routeNodes = (contacts + routingContacts.filter { it.type == ContactType.Repeater })
                    .distinctBy(Contact::publicKey)
                knownRouteLines(self, routeNodes).forEach { route ->
                    val selectedRoute = route.destinationKey == selectedContactKey
                    Polyline().apply {
                        setPoints(route.points.map(GeoPoint::toOsm))
                        color = if (selectedRoute) accentColor else secondaryColor
                        width = if (selectedRoute) 9f else 5f
                    }.also(map.overlays::add)
                }
            }
            self?.let { point ->
                Marker(map).apply {
                    position = point.toOsm(); title = "Моя нода"
                    val artwork = mapPinDrawable(context, PinKind.Self, accentColor, onPrimaryColor, selfLabel, pinScale, labelScale, showLabels)
                    icon = artwork.drawable; setAnchor(Marker.ANCHOR_CENTER, artwork.anchorY)
                }.also(map.overlays::add)
            }
            contacts.forEach { contact ->
                Marker(map).apply {
                    position = contact.toGeoPoint()!!.toOsm(); title = contact.name; subDescription = contact.type.label
                    val kind = when (contact.type) {
                        ContactType.Repeater -> PinKind.Repeater
                        ContactType.Room -> PinKind.Room
                        ContactType.Sensor -> PinKind.Sensor
                        ContactType.Chat -> PinKind.Contact
                    }
                    val background = if (kind == PinKind.Repeater) secondaryColor else accentColor
                    val foreground = if (kind == PinKind.Repeater) onSecondaryColor else onPrimaryColor
                    val artwork = mapPinDrawable(context, kind, background, foreground, contact.mapLabel(), pinScale, labelScale, showLabels)
                    icon = artwork.drawable; setAnchor(Marker.ANCHOR_CENTER, artwork.anchorY)
                    setOnMarkerClickListener { _, _ -> onContactSelected(contact); true }
                }.also(map.overlays::add)
            }
            if (los?.hasData == true && self != null && losTarget != null) {
                Polyline().apply {
                    setPoints(los.samples.map { sample ->
                        val fraction = sample.distanceMeters / los.distanceMeters
                        GeoPoint(self.latitude + (losTarget.latitude - self.latitude) * fraction, self.longitude + (losTarget.longitude - self.longitude) * fraction).toOsm()
                    })
                    color = if (los.clear) accentColor else errorColor
                    width = 8f
                }.also(map.overlays::add)
            }
            map.invalidate()
        },
    )
}

/** Mirrors the reference project's OSM Dark preset without introducing another tile provider. */
private fun mapTileFilter(dark: Boolean): ColorFilter? = if (dark) {
    ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
} else null

private enum class PinKind { Self, Contact, Repeater, Room, Sensor }

private data class MapPinArtwork(val drawable: BitmapDrawable, val anchorY: Float)

private fun mapPinDrawable(
    context: Context,
    kind: PinKind,
    background: Int,
    foreground: Int,
    label: String,
    pinScale: Float,
    labelScale: Float,
    showLabel: Boolean,
): MapPinArtwork {
    val size = (76 * pinScale.coerceIn(.85f, 1.75f)).toInt()
    val center = 27f
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = foreground
        textSize = 15f * labelScale.coerceIn(.85f, 1.75f)
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val labelHeight = if (showLabel) (textPaint.fontMetrics.run { bottom - top } + 16f * labelScale).toInt() else 0
    val labelWidth = if (showLabel) (textPaint.measureText(label) + 26f * labelScale).toInt() else 0
    val bitmapWidth = maxOf(size, labelWidth)
    val bitmapHeight = size + labelHeight
    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (showLabel) {
        val left = (bitmapWidth - labelWidth) / 2f
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        canvas.drawRoundRect(left, 2f, left + labelWidth, labelHeight - 2f, labelHeight / 2f, labelHeight / 2f, labelPaint)
        val baseline = labelHeight / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, bitmapWidth / 2f, baseline, textPaint)
    }
    val scale = size / 54f
    canvas.save()
    canvas.translate((bitmapWidth - size) / 2f, labelHeight.toFloat())
    canvas.scale(scale, scale)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = 2.5f }
    canvas.drawCircle(center, center + 2, 22f, shadow)
    canvas.drawCircle(center, center, 20f, fill)
    canvas.drawCircle(center, center, 20f, stroke)
    val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = foreground
        style = Paint.Style.STROKE
        strokeWidth = 3.8f
        strokeCap = Paint.Cap.ROUND
    }
    val solidGlyph = Paint(glyph).apply { style = Paint.Style.FILL }
    when (kind) {
        PinKind.Repeater -> {
            // A router body with two antennas reads more clearly than an abstract mast at map scale.
            canvas.drawRoundRect(14f, 26f, 40f, 38f, 4f, 4f, glyph)
            canvas.drawLine(19f, 26f, 15f, 17f, glyph)
            canvas.drawLine(35f, 26f, 39f, 17f, glyph)
            canvas.drawCircle(15f, 17f, 2.2f, solidGlyph)
            canvas.drawCircle(39f, 17f, 2.2f, solidGlyph)
            canvas.drawCircle(21f, 32f, 1.8f, solidGlyph)
            canvas.drawCircle(28f, 32f, 1.8f, solidGlyph)
        }
        PinKind.Sensor -> {
            canvas.drawCircle(center, center, 3f, solidGlyph)
            canvas.drawCircle(center, center, 9f, glyph)
            canvas.drawCircle(center, center, 14f, glyph)
        }
        PinKind.Room -> {
            canvas.drawRoundRect(16f, 18f, 38f, 34f, 4f, 4f, glyph)
            canvas.drawLine(21f, 34f, 18f, 39f, glyph)
            canvas.drawLine(29f, 34f, 34f, 39f, glyph)
        }
        PinKind.Self, PinKind.Contact -> {
            canvas.drawCircle(center, 20f, 5.5f, solidGlyph)
            canvas.drawRoundRect(17f, 27f, 37f, 41f, 8f, 8f, solidGlyph)
        }
    }
    canvas.restore()
    return MapPinArtwork(
        drawable = BitmapDrawable(context.resources, bitmap),
        anchorY = (labelHeight + size / 2f) / bitmapHeight,
    )
}

private fun Contact.mapLabel(): String = name.trim().ifBlank { type.label }.let {
    if (it.length > 18) it.take(17) + "..." else it
}

@Composable
private fun MapSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .96f),
        tonalElevation = 5.dp,
        shadowElevation = 2.dp,
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = { Text(uiText("Поиск ноды", "Search nodes")) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
    }
}

@Composable
private fun MapSheetToolButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, selected: Boolean = false) {
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Icon(icon, contentDescription = label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapDisplayOptionsSheet(
    pinScale: Float,
    labelScale: Float,
    showLabels: Boolean,
    onPinScaleChange: (Float) -> Unit,
    onLabelScaleChange: (Float) -> Unit,
    onLabelsChange: (Boolean) -> Unit,
    routesShown: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onCenter: () -> Unit,
    onToggleRoutes: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(uiText("Отображение карты", "Map display"), style = MaterialTheme.typography.headlineSmall)
            Text(uiText("Инструменты карты", "Map tools"), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MapSheetToolButton(uiText("Приблизить", "Zoom in"), Icons.Rounded.Add, onZoomIn)
                MapSheetToolButton(uiText("Отдалить", "Zoom out"), Icons.Rounded.Remove, onZoomOut)
                MapSheetToolButton(uiText("Центрировать", "Center map"), Icons.Rounded.CenterFocusStrong, onCenter)
                MapSheetToolButton(
                    if (routesShown) uiText("Скрыть маршруты", "Hide routes") else uiText("Показать маршруты", "Show routes"),
                    Icons.Rounded.Route,
                    onToggleRoutes,
                    selected = routesShown,
                )
            }
            HorizontalDivider()
            MapScaleSlider(uiText("Размер обозначений нод", "Node marker size"), pinScale, onPinScaleChange)
            MapScaleSlider(uiText("Размер подписей", "Label size"), labelScale, onLabelScaleChange)
            ListItem(
                headlineContent = { Text(uiText("Подписи нод", "Node labels")) },
                supportingContent = { Text(uiText("Показывать названия прямо на карте", "Show names directly on the map")) },
                trailingContent = { Switch(checked = showLabels, onCheckedChange = onLabelsChange) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
            FilledTonalButton(
                onClick = {
                    onPinScaleChange(1.25f)
                    onLabelScaleChange(1.2f)
                    onLabelsChange(true)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(uiText("Сбросить отображение", "Reset display")) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MapScaleSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text("${(value * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = .85f..1.75f, steps = 8)
    }
}

/** Builds only observed, geographically resolvable routes: self -> relays -> destination. */
private data class KnownRoute(val destinationKey: String, val points: List<GeoPoint>)

private fun knownRouteLines(self: GeoPoint?, contacts: List<Contact>): List<KnownRoute> {
    if (self == null) return emptyList()
    return contacts.asSequence()
        .filter { it.hasCoordinates() && it.hops >= 0 && it.pathHashWidth in 1..4 }
        .mapNotNull { destination ->
            if (destination.hops == 0) return@mapNotNull KnownRoute(destination.publicKey, listOf(self, destination.toGeoPoint()!!))
            if (destination.pathBytes.isEmpty()) return@mapNotNull null
            val relays = resolveRouteRelays(destination, contacts)
            val relayPoints = relays.mapNotNull(Contact::toGeoPoint)
            if (relays.size != destination.hops || relayPoints.size != relays.size) null
            else KnownRoute(destination.publicKey, listOf(self) + relayPoints + listOfNotNull(destination.toGeoPoint()))
        }
        .filter { it.points.size >= 2 }
        .toList()
}

private fun resolveRouteRelays(destination: Contact, contacts: List<Contact>): List<Contact> {
    if (destination.hops <= 0 || destination.pathBytes.size < destination.hops * destination.pathHashWidth) return emptyList()
    val available = contacts.filter { it.type == ContactType.Repeater }.toMutableList()
    return (0 until destination.hops).mapNotNull { index ->
        val prefix = destination.pathBytes.copyOfRange(index * destination.pathHashWidth, (index + 1) * destination.pathHashWidth)
            .joinToString("") { "%02X".format(it) }
        available.filter { it.publicKey.startsWith(prefix, ignoreCase = true) }
            .maxByOrNull { it.lastSeenEpoch }
            ?.also(available::remove)
    }
}

@Composable
private fun EmptyMapState(modifier: Modifier) = Box(modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
    Text(uiText("Нет нод с координатами", "No nodes have coordinates"), style = MaterialTheme.typography.titleMedium)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailsSheet(contact: Contact, onDismiss: () -> Unit, onOpenChat: () -> Unit, onRunLos: (() -> Unit)?, los: LosResult?, checkingLos: Boolean) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(contact.name, style = MaterialTheme.typography.headlineSmall)
            ContactDetailRow(uiText("Тип", "Type"), contact.type.label)
            ContactDetailRow(uiText("Маршрут", "Route"), routeCountLabel(contact.hops))
            ContactDetailRow(uiText("Последняя активность", "Last activity"), formatTimestamp(contact.lastSeenEpoch * 1_000))
            contact.toGeoPoint()?.let { ContactDetailRow(uiText("Координаты", "Coordinates"), "%.5f, %.5f".format(java.util.Locale.US, it.latitude, it.longitude)) }
            if (contact.pathBytes.isNotEmpty()) {
                ContactDetailRow(uiText("Данные маршрута", "Route data"), uiText("${contact.pathBytes.size} байт, хеш ${contact.pathHashWidth} байт", "${contact.pathBytes.size} bytes, ${contact.pathHashWidth}-byte hash"))
            }
            ContactDetailRow(uiText("Публичный ключ", "Public key"), "${contact.publicKey.take(12)}…${contact.publicKey.takeLast(8)}")
            los?.let {
                HorizontalDivider()
                ContactDetailRow("LOS", if (it.hasData) if (it.clear) uiText("Прямая видимость", "Clear line of sight") else uiText("Есть препятствие %.1f м".format(it.maxObstructionMeters), "Obstacle %.1f m".format(it.maxObstructionMeters)) else (it.error ?: uiText("Нет данных", "No data")))
                ContactDetailRow(uiText("Дистанция", "Distance"), "%.1f км".format(it.distanceMeters / 1_000))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (contact.type == ContactType.Chat) Button(onClick = onOpenChat, modifier = Modifier.weight(1f)) { Text(uiText("Открыть чат", "Open chat")) }
                if (onRunLos != null) FilledTonalButton(onClick = onRunLos, modifier = Modifier.weight(1f), enabled = !checkingLos) { Text(if (checkingLos) uiText("Проверка…", "Checking…") else uiText("Проверить LOS", "Check LOS")) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable private fun ContactDetailRow(label: String, value: String) = Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.Top) {
    Text(label, modifier = Modifier.weight(.42f), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, modifier = Modifier.weight(.58f), style = MaterialTheme.typography.bodyMedium)
}
private fun Contact.hasCoordinates() = latitude != 0.0 || longitude != 0.0
private fun Contact.toGeoPoint() = if (hasCoordinates()) GeoPoint(latitude, longitude) else null
private fun com.polymatic.meshify.mesh.NodeInfo.hasCoordinates() = (latitude ?: 0.0) != 0.0 || (longitude ?: 0.0) != 0.0
private fun com.polymatic.meshify.mesh.NodeInfo.toGeoPoint() = if (hasCoordinates()) GeoPoint(latitude!!, longitude!!) else null
private fun GeoPoint.toOsm() = OsmGeoPoint(latitude, longitude)
private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)
private fun Context.lastKnownLocation() = if (
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
) runCatching {
    val manager = getSystemService(LocationManager::class.java)
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull(Location::getTime)
}.getOrNull() else null

private fun Context.requestCurrentLocation(onLocation: (GeoPoint) -> Unit) {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) return
    val manager = getSystemService(LocationManager::class.java)
    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return
    runCatching {
        manager.requestSingleUpdate(provider, LocationListener { location -> onLocation(location.toGeoPoint()) }, Looper.getMainLooper())
    }
}
