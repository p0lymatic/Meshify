# Meshify media packs

Media references are ordinary MeshCore text: `:pack/item:` for emoji and `;pack/item;` for stickers. They never carry an asset over the mesh network. Pack IDs use lowercase ASCII `a-z`, digits, `_`, `-` and are at most 32 characters. Item IDs are decimal or `0x` hexadecimal.

Store packs in `emojis/<pack-id>/` or `stickers/<pack-id>/`. Every directory needs `manifest.json` with `type`, `id`, `name`, integer `version`, `author`, `license`, `description`, optional `cover`, and `items`. Each item has `id`, `name`, `keywords`, relative `path`, `mime`, `size`, and `sha256`.

Emoji assets are WebP up to 256 KiB. Sticker assets are WebP up to 512 KiB or muted MP4 up to 2 MiB; MP4 content must be no longer than three seconds. GIF, TGS and vector assets are rejected. Run `python3 tools/pack_catalog.py` after modifying a pack; it validates paths, IDs, MIME, byte size and checksum, then recreates root `catalog.json`.

Example:

```json
{
  "type": "emoji",
  "id": "weather",
  "name": "Weather",
  "version": 1,
  "author": "Example",
  "license": "CC-BY-4.0",
  "description": "Weather symbols",
  "items": [{
    "id": "1", "name": "Sun", "keywords": ["sun"],
    "path": "sun.webp", "mime": "image/webp", "size": 1234,
    "sha256": "..."
  }]
}
```
