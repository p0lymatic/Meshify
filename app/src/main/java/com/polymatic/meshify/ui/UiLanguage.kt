package com.polymatic.meshify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalLanguageTag = compositionLocalOf { "ru" }

@Composable
fun uiText(russian: String, english: String): String =
    if (LocalLanguageTag.current == "en") english else russian

