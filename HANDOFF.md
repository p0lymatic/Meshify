# Handoff: Meshify BLE / Heltec V4

## What is implemented

### Core functionality
- **BLE transport**: Nordic UART Service (NUS) client with scan, connect, GATT discovery
- **MeshCore protocol**: device query, app start, battery, contacts sync, time sync, text messaging
- **Scanner UI**: unfiltered BLE scan with NUS/name heuristics, manual MAC entry, recent connections
- **Contacts**: 148-byte contact record parsing, favorites, location, last-seen, routing hops
- **Direct messaging**: send/receive text messages, message status (pending/sent/delivered/failed)
- **Channels**: placeholder UI with channel 0-7 list (protocol support pending)
- **Settings**: node info, connection details, debug log viewer

### UI Structure (Material 3 Expressive)
- **Scanner screen**: device list, connection status, manual connect, recent MACs
- **Connected shell**: bottom navigation with 5 tabs
  - **Contacts**: node summary, contact cards (clickable for chat)
  - **Messages**: direct message list with last message preview
  - **Channels**: channel list (placeholder, no messaging yet)
  - **Map**: stub (coming soon)
  - **Settings**: node info, connection controls, debug tools
- **Chat screen**: full-screen 1:1 messaging with bubble UI, auto-scroll, status icons

### Protocol Implementation
Commands:
- `CMD_APP_START` (1) — announce app connection
- `CMD_SEND_TXT_MSG` (2) — send direct message
- `CMD_GET_CONTACTS` (4) — request contact list
- `CMD_SET_DEVICE_TIME` (6) — sync device clock
- `CMD_GET_BATT_AND_STORAGE` (20) — battery query
- `CMD_DEVICE_QUERY` (22) — device info query

Responses:
- `RESP_CODE_CONTACTS_START/END` (2, 4) — contact sync
- `RESP_CODE_CONTACT` (3) — 148-byte contact record
- `RESP_CODE_SELF_INFO` (5) — device self-info
- `RESP_CODE_CONTACT_MSG_RECV` (7) — incoming direct message
- `RESP_CODE_CONTACT_MSG_RECV_V3` (16) — incoming message v3
- `RESP_CODE_BATT_AND_STORAGE` (12) — battery millivolts
- `RESP_CODE_DEVICE_INFO` (13) — firmware info

### Data Models
- `Contact`: publicKey (64 hex), name, type (Chat/Repeater/Room/Sensor), favorite, hops, lastSeenEpoch, lat/lon
- `Message`: messageId, text, timestamp, isOutgoing, status, senderKey
- `NodeInfo`: name, model, firmware, publicKey, batteryMv
- `BleDevice`: name, address, rssi, hasNordicUart, hasKnownName, isMeshCore

### State Management
- `MeshifyViewModel` (AndroidViewModel)
- StateFlow-based reactive UI
- In-memory storage: contacts (LinkedHashMap), messages (Map<String, List<Message>>)
- SharedPreferences: recent MACs (JSON, last 8 entries)
- BLE debug log: in-memory tail (300 entries) + Logcat tag `MeshifyBle`

## Recent additions (chat implementation)

### What was added
1. **Chat screen** (`ChatScreen.kt`)
   - Full-screen chat UI with AppBar (contact name, hops, back button)
   - Bubble messages: outgoing (right, primary container), incoming (left, surface container high)
   - Rounded corners: 20dp top, 4dp bottom on sender side (Material 3 Expressive)
   - Status icons: pending (schedule), sent (check), delivered (double-check), failed (error)
   - Timestamp formatting: "Now", "N min", "HH:mm", "MMM d, HH:mm"
   - Auto-scroll to newest message
   - TextField with 28dp rounded corners, send button

2. **Messages tab** (`TabScreens.kt`)
   - Direct message list with contact cards
   - Last message preview
   - Empty state when no chats
   - Clickable cards open chat screen

3. **Protocol extensions** (`MeshProtocol.kt`)
   - `sendTextMessage(recipientKey: String, text: String)` — builds CMD_SEND_TXT_MSG frame
   - `parseIncomingMessage()` — parses RESP_CODE_CONTACT_MSG_RECV (7) and V3 (16)
   - `Message` model with messageId, status tracking
   - `MeshEvent.MessageReceived`, `MeshEvent.MessageStatusUpdated`

4. **ViewModel additions** (`MeshifyViewModel.kt`)
   - `messages: Map<String, MutableList<Message>>` — in-memory message storage per contact
   - `activeContact: Contact?` — tracks open chat screen
   - `openChat(contact)`, `closeChat()` — navigation
   - `sendMessage(recipientKey, text)` — creates outgoing message, sends to BLE
   - Message event handling: incoming messages added to list, UI updates reactively

5. **Navigation flow**
   - Scanner → Contacts → Chat (tap contact card)
   - Messages tab → Chat (tap message card)
   - Chat back button → returns to previous screen

## Current limitations

### Not yet implemented
- **Message persistence**: messages only in memory (lost on disconnect/restart)
- **Delivery confirmation**: `RESP_CODE_SENT` (6) and `PUSH_CODE_SEND_CONFIRMED` (0x82) parsing
- **Retry logic**: failed messages are not retried
- **Channel messaging**: `CMD_SEND_CHANNEL_TXT_MSG` (3), channel chat screen
- **Message fragmentation**: assumes full frame arrives in one BLE notification
- **Read receipts**: no unread tracking per message
- **Reactions, translations, GIFs**: not implemented
- **Load older messages**: no pagination (all messages in memory)
- **Room support**: 4-byte author prefix parsing for room messages
- **Image messages**: no `CMD_SEND_CHANNEL_DATA` support

### Known issues from previous handoff
User reported Heltec V4 node did not appear with NUS-only scan filter. Current implementation:
- Scans **without service filter** (compatible with Heltec firmware that doesn't advertise NUS)
- Marks devices advertising NUS or with known name prefixes as MeshCore
- Stops prior scan + 250ms delay before new scan (prevents error 1)
- Treats Android error `1` / `SCAN_FAILED_ALREADY_STARTED` as logged diagnostic
- Auto-stops scans after 12 seconds

### Diagnosis steps (if connection still fails)
1. Install `app/build/outputs/apk/debug/app-debug.apk`
2. Scan, open bug icon in scanner app bar
3. Tap Heltec device card to connect
4. Check log for `Services discovered` line
   - If contains `6e400001-b5a3-f393-e0a9-e50e24dcca9e`, NUS is present
   - If not, board/firmware is not exposing MeshCore NUS

## Build verification

Last build succeeded:
```bash
JAVA_HOME=/home/poly/.local/share/JetBrains/Toolbox/apps/android-studio/jbr ./gradlew :app:assembleDebug --console=plain
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (57MB)

## Important technical caveats

- No frame reassembly: assumes each BLE notification = complete protocol frame
- `MainActivity` requests BLE permissions via Scan FAB; top refresh assumes granted
- No persistence: messages, USB, TCP, reconnect not implemented
- Contact sync is full-list only (no incremental updates)
- Protocol details from `REFERENCES/meshcore-open/docs/BLE_PROTOCOL.md` and Flutter connector

## Reference project

`REFERENCES/meshcore-open/` — Flutter MeshCore client with full feature set:
- BLE/TCP/USB transports
- Complete protocol implementation (all commands, responses, push codes)
- Channel messaging, communities, QR import/export
- Map with node tracking, path tracing, line-of-sight
- Repeater management, telemetry, translation service
- SharedPreferences persistence (scoped per device key)

Useful files:
- `lib/screens/chat_screen.dart` — full direct messaging with reactions, translations, GIF
- `lib/screens/channel_chat_screen.dart` — group messaging
- `lib/connector/meshcore_connector.dart` — BLE/TCP/USB unified transport
- `lib/connector/meshcore_protocol.dart` — all command/response builders
- `lib/models/message.dart`, `lib/models/channel_message.dart` — data models
- `documentation/ble-protocol.md` — protocol reference

## Relevant files

Core logic:
- `app/src/main/java/com/polymatic/meshify/mesh/BleMeshClient.kt` — BLE transport
- `app/src/main/java/com/polymatic/meshify/mesh/MeshProtocol.kt` — protocol commands/parsing
- `app/src/main/java/com/polymatic/meshify/ui/MeshifyViewModel.kt` — state management
- `app/src/main/java/com/polymatic/meshify/debug/BleDebugLog.kt` — debug log

UI:
- `app/src/main/java/com/polymatic/meshify/ui/MeshifyApp.kt` — root UI, scanner, connected shell
- `app/src/main/java/com/polymatic/meshify/ui/screens/ChatScreen.kt` — 1:1 messaging
- `app/src/main/java/com/polymatic/meshify/ui/screens/TabScreens.kt` — tabs (Messages, Channels, Settings)
- `app/src/main/java/com/polymatic/meshify/MainActivity.kt` — entry point

## Next steps

High priority:
1. Message persistence (SharedPreferences, scoped by device key)
2. Delivery confirmation parsing (`RESP_CODE_SENT`, `PUSH_CODE_SEND_CONFIRMED`)
3. Retry logic for failed messages
4. Channel messaging implementation (`CMD_SEND_CHANNEL_TXT_MSG`, channel chat screen)
5. Unread message tracking (per contact, per channel)

Medium priority:
6. Load older messages (pagination from SharedPreferences)
7. Frame reassembly buffer for fragmented notifications
8. Reconnect on disconnect
9. Contact detail screen (location, telemetry, routing info)
10. Map screen with contact pins

Low priority:
11. Reactions, translations, GIF support
12. Room author resolution (4-byte prefix parsing)
13. Image messages (channel data command)
14. TCP/USB transports
15. Repeater management

## Design notes

Material 3 Expressive theme:
- Dark color scheme: primary #9CCAFF, secondary #9AD0B1, tertiary #FFB77D
- Rounded corners: 26-28dp for cards, 20dp for message bubbles
- Surface elevation via `ElevatedCard`, `Surface(tonalElevation = 3.dp)`
- Icon wells: 48dp circle with colored background (alpha 0.2), centered icon
- Typography: title/body hierarchy, bold for primary labels, onSurfaceVariant for secondary
- Spacing: 10-14dp between cards, 16dp padding in cards, 12dp horizontal screen padding
