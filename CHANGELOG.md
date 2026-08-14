# Changelog

All notable user-facing changes are documented here.

## [Unreleased]

### Added

- Material 3 Expressive visual refresh across the application: dynamic Monet colors, dark mode, updated controls, animated transitions, and clearer loading states.
- Russian and English interface languages, with a persistent language selector in Settings.
- Expanded node settings: node name, self-advertising, LoRa frequency/bandwidth/spreading factor/coding rate/TX power, node identity, battery, and firmware information.
- Contact and chat sorting, contact details, richer direct-message details, retry handling, and route metadata with relay names.
- Channel and contact QR workflows, including safer scanner permission handling and camera/location permission support.
- Node map with OpenStreetMap tiles, dark map mode, GPS centering, node search, line-of-sight calculation, route rendering through known relays, contact details, and configurable marker/label sizes.
- Larger map markers and persistent node labels for contacts, repeaters, rooms, sensors, and the local node.
- Map controls for route visibility, centering, zoom, and display preferences; all map display controls are available from the lower settings button.
- GitHub Actions CI configuration for automated Android builds and tests.
- ESP USB-OTG firmware flasher in Settings:
  - Downloads the public MeshCore firmware catalogue from `https://flasher.meshcore.io/releases/`.
  - Searches boards, roles, and versions; lists releases from newest to oldest and can show only images newer than the firmware reported by the connected node.
  - Uses a regular app image for updates by default, preserving the rest of the flash layout where supported.
  - Provides an explicit full-reflash switch for `*-merged.bin` images; the destructive confirmation button is locked for three seconds.
  - Supports Companion, Repeater, and Room Server image selection.
  - Detects USB serial devices, requests Android USB access, downloads the selected image, enters the ESP serial bootloader through DTR/RTS, flashes a merged image from `0x0`, reports progress, and reboots the board.
  - Requires explicit confirmation before flashing because merged images can replace bootloader data, partitions, node settings, keys, and contacts.

### Changed

- Message bubbles now open their details consistently from the bottom, with more routing and relay information.
- Sent-message bubbles use the active accent color; incoming messages retain a distinct neutral surface for faster scanning.
- System Back dismisses the keyboard before leaving a chat or channel.
- Contact and channel spacing, list hierarchy, empty states, navigation, and iconography were polished for smaller screens and an open keyboard.
- Relay hop display now uses decoded hop counts instead of raw hexadecimal route bytes.

### Fixed

- Fixed direct-message reception and relay-route enrichment after contact synchronization.
- Fixed contact synchronization regressions and keyboard-induced chat layout problems.
- Fixed channel-message status: after the connected radio accepts a channel packet, the UI no longer remains indefinitely on the pending clock state.
- Channel packet echoes are merged into the original message and now display `Heard N relays` / `Услышано реле: N` when relays repeat the packet.
- Fixed route paths on the map to include resolvable repeater hops and direct routes to repeaters.
- Fixed location permission flow needed by Bluetooth scanning and map GPS actions.

### Verification

- Added unit coverage for line-of-sight calculations, channel/route protocol parsing, QR channel parsing, and MeshCore firmware catalogue parsing/filtering.
- The debug APK and unit-test suite build successfully with `:app:testDebugUnitTest :app:assembleDebug`.

### Notes

- The ESP flasher accepts ESP app `.bin` files only when a matching `*-merged.bin` confirms that the board is ESP-flashable. Non-ESP `.uf2` and `.zip` files are not shown in this flow.
- The public catalogue does not currently provide SHA-256 hashes. Downloads use HTTPS and the flasher shows the exact selected image, but physical-board validation remains necessary for each supported USB-UART/ESP combination.
