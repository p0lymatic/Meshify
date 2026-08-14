package com.polymatic.meshify.media

data class BuiltInSticker(val id: String, val name: String, val fileName: String, val keywords: List<String>) {
    val token get() = ";steam/$id;"
    val wireText get() = "$token Alt: Steam $name"
    val assetPath get() = "stickers/steam/$fileName"
}

object BuiltInStickers {
    const val packId = "steam"
    const val packName = "Steam"
    val items = listOf(
        BuiltInSticker("1", "Confused", "confused.webp", listOf("confused")), BuiltInSticker("2", "Facepalm", "facepalm.webp", listOf("facepalm")),
        BuiltInSticker("3", "Happy", "happy.webp", listOf("happy", "smile")), BuiltInSticker("4", "Hello", "hello.webp", listOf("hello", "wave")),
        BuiltInSticker("5", "LOL", "lol.webp", listOf("lol", "laugh")), BuiltInSticker("6", "Love", "love.webp", listOf("love", "heart")),
        BuiltInSticker("7", "Mocking", "mocking.webp", listOf("mocking")), BuiltInSticker("8", "Sad", "sad.webp", listOf("sad")),
        BuiltInSticker("9", "Sadder", "sadder.webp", listOf("sadder", "cry")), BuiltInSticker("10", "Sleepy", "sleepy.webp", listOf("sleepy", "sleep")),
        BuiltInSticker("11", "Sunglasses", "sunglasses.webp", listOf("cool", "sunglasses")), BuiltInSticker("12", "Tomato", "tomato.webp", listOf("tomato", "angry")),
    )
    fun find(packId: String, itemId: String) = if (packId == this.packId) items.firstOrNull { it.id == itemId } else null
}
