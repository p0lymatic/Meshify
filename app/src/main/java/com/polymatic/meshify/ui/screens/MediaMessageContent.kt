package com.polymatic.meshify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import android.graphics.BitmapFactory
import com.polymatic.meshify.media.BuiltInSticker
import com.polymatic.meshify.media.BuiltInStickers
import com.polymatic.meshify.mesh.MediaTokenParser

/** Read-only rendering never fetches a pack. Unavailable media stays recognizable and actionable. */
@Composable
internal fun MediaMessageContent(text: String, color: Color, onInstallPack: ((String) -> Unit)? = null) {
    builtInStickerFromWireText(text)?.let { sticker ->
        BuiltInStickerImage(sticker, Modifier.size(72.dp))
        return
    }
    val fragments = MediaTokenParser.fragments(text)
    if (fragments.none { it is MediaTokenParser.Fragment.Media }) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        fragments.forEach { fragment -> when (fragment) {
            is MediaTokenParser.Fragment.Text -> if (fragment.value.isNotEmpty()) Text(fragment.value, style = MaterialTheme.typography.bodyLarge, color = color)
            is MediaTokenParser.Fragment.Media -> {
                val sticker = if (fragment.token.type == MediaTokenParser.Type.Sticker) BuiltInStickers.find(fragment.token.packId, fragment.token.itemId) else null
                if (sticker != null) BuiltInStickerImage(sticker, Modifier.size(72.dp)) else AssistChip(
                    onClick = { onInstallPack?.invoke(fragment.token.packId) }, label = { Text(fragment.token.raw) },
                )
            }
        } }
    }
}

internal fun isStandaloneBuiltInSticker(text: String): Boolean {
    return builtInStickerFromWireText(text) != null
}

/** Accepts the interoperable textual fallback while keeping it out of Meshify's visual bubble. */
internal fun builtInStickerFromWireText(text: String): BuiltInSticker? = BuiltInStickers.items.firstOrNull { sticker ->
    text == sticker.token || text == sticker.wireText
}

@Composable
internal fun BuiltInStickerImage(sticker: BuiltInSticker, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image = remember(sticker.assetPath) {
        context.assets.open(sticker.assetPath).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
    }
    if (image != null) Image(image, sticker.name, modifier, contentScale = ContentScale.Fit)
}
