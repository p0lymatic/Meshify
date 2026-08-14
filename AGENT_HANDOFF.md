# Handoff: Meshify2 — MeshCore Android Client

## Project Overview

**Meshify2** — native Android client for MeshCore LoRa mesh networking devices, built with Jetpack Compose and Material 3 Expressive design system.

**Transport:** BLE via Nordic UART Service (NUS)  
**Protocol:** MeshCore wire protocol (compatible with meshcore-open Flutter client)  
**Reference project:** `REFERENCES/meshcore-open/` — full-featured Flutter implementation

---

## ✅ What's Fully Implemented

### **1. BLE Transport (`BleMeshClient.kt`)**
- ✅ Unfiltered BLE scan (compatible with Heltec firmware that doesn't advertise NUS)
- ✅ Device identification: NUS UUID + known name prefixes (MeshCore-, Whisper-, HT-, etc.)
- ✅ Connect via MAC address (manual entry + recent connections list)
- ✅ GATT client: MTU negotiation, service discovery, CCCD setup
- ✅ TX/RX via NUS characteristics
- ✅ In-app debug log (300 entries) + Logcat tag `MeshifyBle`
- ✅ Auto-stop scan after 12s, handles `SCAN_FAILED_ALREADY_STARTED` gracefully

### **2. Protocol Implementation (`MeshProtocol.kt`)**

#### Commands (App → Device):
- ✅ `CMD_APP_START` (1) — announce connection
- ✅ `CMD_SEND_TXT_MSG` (2) — send direct message
- ✅ `CMD_SEND_CHANNEL_TXT_MSG` (3) — send channel message
- ✅ `CMD_GET_CONTACTS` (4) — request contact list
- ✅ `CMD_SET_DEVICE_TIME` (6) — sync device clock
- ✅ `CMD_GET_BATT_AND_STORAGE` (20) — battery query
- ✅ `CMD_DEVICE_QUERY` (22) — device info
- ✅ `CMD_GET_CHANNEL` (31) — get channel config
- ✅ `CMD_SET_CHANNEL` (32) — set channel name/PSK

#### Responses (Device → App):
- ✅ `RESP_CODE_CONTACTS_START/END` (2, 4) — contact sync boundaries
- ✅ `RESP_CODE_CONTACT` (3) — 148-byte contact record
- ✅ `RESP_CODE_SELF_INFO` (5) — device identity
- ✅ `RESP_CODE_CONTACT_MSG_RECV` (7) + V3 (16) — incoming direct messages
- ✅ `RESP_CODE_CHANNEL_MSG_RECV` (8) + V3 (17) — incoming channel messages
- ✅ `RESP_CODE_BATT_AND_STORAGE` (12) — battery millivolts
- ✅ `RESP_CODE_DEVICE_INFO` (13) — firmware info
- ✅ `RESP_CODE_CHANNEL_INFO` (18) — channel definition

### **3. Direct Messaging (1:1 Chat)**
- ✅ Full-screen chat UI with Material 3 Expressive bubble design
- ✅ Message status icons: pending (⏰) → sent (✓) → delivered (✓✓) → failed (⚠)
- ✅ Smart timestamps: "Now" / "N min" / "HH:mm" / "MMM d, HH:mm"
- ✅ Auto-scroll to newest message
- ✅ BackHandler for system back button
- ✅ Messages tab: list of chat contacts with last message preview
- ✅ In-memory storage (not persisted)

**Files:**
- `ui/screens/ChatScreen.kt` — 1:1 chat UI
- `ui/screens/TabScreens.kt` — MessagesTab component
- `ui/MeshifyViewModel.kt:183-196` — sendMessage(), message handling

### **4. Channel Messaging (Group Chat)**
- ✅ Channel chat screen with sender name display
- ✅ Send/receive channel messages (CMD_SEND_CHANNEL_TXT_MSG)
- ✅ Channels 0-7 supported (MeshCore protocol limit)
- ✅ **Public channel** auto-configured: index=0, name="Public", PSK=`8b3387e9c5cdea6ac9e5edbaa115cd72`
- ✅ Channel list with last message preview
- ✅ FAB (+) button to add channels
- ✅ Add channel dialog: manual entry (index, name, PSK)
- ✅ BackHandler on channel chat screen
- ✅ In-memory storage (not persisted)

**Files:**
- `ui/screens/ChannelChatScreen.kt` — channel group chat UI
- `ui/screens/TabScreens.kt:28-78` — ChannelsTab with FAB
- `ui/screens/AddChannelDialog.kt` — add channel dialog
- `ui/MeshifyViewModel.kt:198-209` — sendChannelMessage(), channel handling

### **5. Contacts & Node Management**
- ✅ Contact parsing: 148-byte firmware records
- ✅ Contact types: Chat, Repeater, Room, Sensor
- ✅ Favorites, location (lat/lon), last-seen timestamp, routing hops
- ✅ Node info: name, model, firmware, public key, battery
- ✅ Contact cards: clickable (opens chat for Chat type)
- ✅ Sorting: favorites first, then by name

**Files:**
- `mesh/MeshProtocol.kt:44-56` — Contact parsing
- `ui/MeshifyApp.kt:88-123` — ContactsTab UI

### **6. UI & Navigation**
- ✅ Material 3 dark theme: primary #9CCAFF, secondary #9AD0B1, tertiary #FFB77D
- ✅ Scanner screen → Connected shell (5 tabs) → Chat/Channel screens
- ✅ Tabs: Contacts, Channels, Messages, Map (stub), Settings
- ✅ BackHandler on all chat screens
- ✅ Empty states for channels, messages, contacts
- ✅ Debug log dialog (bug icon in scanner app bar)

**Navigation flow:**
```
Scanner → [Connect] → Contacts/Channels/Messages tabs
                           ↓                    ↓
                      [tap contact]      [tap channel]
                           ↓                    ↓
                      ChatScreen      ChannelChatScreen
                           ↓                    ↓
                      [back button] ← returns to tab
```

---

## ⚠️ Known Issues

### **CRITICAL: QR Scanner Not Working**

**Symptoms:**
- QR scanner launches but "barely scans" (slow/unreliable)
- When it does scan, **nothing gets added** to channel list
- No error message shown to user

**Current Implementation:**
- Library: ZXing Android Embedded 4.3.0
- File: `ui/screens/AddChannelDialog.kt:67-103` — `QrChannelScanner` composable
- Format expected: `channel:0:General:8b3387e9c5cdea6ac9e5edbaa115cd72`
- Flow:
  1. Request CAMERA permission via `rememberLauncherForActivityResult`
  2. Launch `ScanContract()` with QR-only options
  3. Parse result: `val parts = result.contents.split(":")`
  4. Validate: `parts[0] == "channel"`, index in 0-7, PSK length == 32
  5. Call `onScanned(index, name, psk)` → `ViewModel.setChannel()`

**Likely Causes:**
1. **QR format mismatch** — Flutter app might use different format (JSON? different separator?)
2. **Permission denied** — camera permission dialog dismissed, but no feedback shown
3. **Silent validation failure** — PSK parsing or channel index validation fails quietly
4. **Callback not firing** — `onScanned` doesn't reach ViewModel, or ViewModel rejects it

**Debug Steps:**

1. **Check actual QR format from Flutter app:**
   - Generate channel QR in meshcore-open
   - Scan with generic QR reader to see raw text
   - Compare with our expected format

2. **Add logging to scanner:**
```kotlin
// In AddChannelDialog.kt:67
val scanLauncher = rememberLauncherForActivityResult(
    contract = ScanContract(),
    onResult = { result ->
        android.util.Log.d("QrScanner", "Raw result: ${result.contents}")
        if (result.contents != null) {
            val parts = result.contents.split(":")
            android.util.Log.d("QrScanner", "Parts: $parts (size=${parts.size})")
            if (parts.size >= 4 && parts[0] == "channel") {
                val index = parts[1].toIntOrNull()
                android.util.Log.d("QrScanner", "Parsed: idx=$index name=${parts[2]} psk=${parts[3]}")
                if (index != null && index in 0..7 && parts[3].length == 32) {
                    android.util.Log.d("QrScanner", "✓ Valid, calling onScanned")
                    onScanned(index, parts[2], parts[3])
                } else {
                    android.util.Log.e("QrScanner", "✗ Validation failed")
                }
            } else {
                android.util.Log.e("QrScanner", "✗ Format invalid")
            }
        }
        onDismiss()
    }
)
```

3. **Add logging to setChannel:**
```kotlin
// In MeshifyViewModel.kt:211
fun setChannel(channelIndex: Int, name: String, pskHex: String) {
    android.util.Log.d("MeshifyVM", "setChannel: idx=$channelIndex name='$name' psk='$pskHex'")
    try {
        val channel = Channel.fromHex(channelIndex, name, pskHex)
        android.util.Log.d("MeshifyVM", "✓ Channel created")
        channels[channelIndex] = channel
        publishChannels()
        android.util.Log.d("MeshifyVM", "✓ Published, sending to BLE")
        client.write(MeshProtocol.setChannel(channelIndex, name, channel.psk))
    } catch (e: Exception) {
        android.util.Log.e("MeshifyVM", "✗ Failed: ${e.message}", e)
        BleDebugLog.add("Failed to set channel: ${e.message}")
    }
}
```

4. **Test manual entry first:**
   - Open channel dialog, enter manually: index=1, name="Test", PSK=`8b3387e9c5cdea6ac9e5edbaa115cd72`
   - If this works but QR doesn't → issue is in QR parsing/scanning
   - If this also fails → issue is in `setChannel()` or `publishChannels()`

5. **Check reference implementation:**
   - `REFERENCES/meshcore-open/lib/screens/community_qr_scanner_screen.dart`
   - Look for QR encoding format (might be JSON, not colon-separated)
   - Check if there's a prefix/suffix we're missing

6. **Add user feedback:**
   - Show Toast/Snackbar when channel added successfully
   - Show error dialog when QR format invalid
   - Show error when camera permission denied

**Reference Code Locations:**
- QR scanning: `REFERENCES/meshcore-open/lib/screens/community_qr_scanner_screen.dart`
- Channel model: `REFERENCES/meshcore-open/lib/models/channel.dart:152` (publicChannelPsk constant)
- QR generation: `REFERENCES/meshcore-open/lib/widgets/...` (search for qr_flutter usage)

---

## ❌ Not Implemented (High Priority)

### **1. Message & Channel Persistence**
**Problem:** All messages and channels lost on disconnect/app restart.

**What's needed:**
- SharedPreferences storage (same pattern as Flutter client)
- Scope keys by device public key (first 10 hex chars)
- Store: `messages_<deviceKey><contactKey>`, `channel_messages_<deviceKey><channelIndex>`, `channels<deviceKey>`

**Files to create:**
- `storage/MessageStore.kt` — save/load messages per contact
- `storage/ChannelMessageStore.kt` — save/load channel messages per index
- `storage/ChannelStore.kt` — save/load channel configs

**Reference:** `REFERENCES/meshcore-open/lib/storage/` — all stores use PrefsManager pattern

### **2. Delivery Confirmation**
**Problem:** Messages stay in "pending" status forever. No delivery ACKs processed.

**What's missing:**
- Parse `RESP_CODE_SENT` (6) — device accepted message for transmission
  - Frame: `[6][is_flood][ack_hash(4)][estimated_timeout_ms(4)]`
  - Update message status to `Sent`, store ackHash for matching later
- Parse `PUSH_CODE_SEND_CONFIRMED` (0x82) — remote device ACKed
  - Frame: `[0x82][ack_hash(4)][trip_time_ms(4)]`
  - Update message status to `Delivered`, record trip time

**Files to modify:**
- `mesh/MeshProtocol.kt:30-44` — add parse handlers for codes 6 and 0x82
- `ui/MeshifyViewModel.kt:168-182` — handle new events, update message status

**Reference:**
- `REFERENCES/meshcore-open/lib/connector/meshcore_connector.dart:1200-1250` — ACK handling
- `documentation/ble-protocol.md:141-142, 156` — protocol spec

### **3. Retry Logic**
**Problem:** Failed messages are not retried.

**What's needed:**
- Retry queue in ViewModel
- Exponential backoff (1s, 2s, 4s, 8s, max 3 retries)
- Retry on status `Failed` or timeout (from `estimated_timeout_ms`)

**Reference:** `REFERENCES/meshcore-open/lib/services/message_retry_service.dart` — full retry implementation

### **4. Channel Sync on Connect**
**Problem:** Device might have channels configured, but we don't load them.

**What's needed:**
- In `synchronize()` (after `appStart`, before `getContacts`), send:
  ```kotlin
  for (i in 0..7) {
      client.write(MeshProtocol.getChannel(i))
  }
  ```
- Handle `RESP_CODE_CHANNEL_INFO` (18) to populate channels

**File:** `ui/MeshifyViewModel.kt:126-134` — `synchronize()` function

### **5. Unread Counters**
**Problem:** No unread message tracking for contacts or channels.

**What's needed:**
- Per-contact unread count (increment on incoming message, reset when chat opened)
- Per-channel unread count
- Display badge on contact/channel cards, navigation bar

**Reference:** `REFERENCES/meshcore-open/lib/storage/unread_store.dart`

### **6. QR Code Generation**
**Problem:** Can add channels from QR, but can't share own channels.

**What's needed:**
- Generate QR from channel (use same format as scanner expects)
- Display QR in channel settings/share dialog
- Library: already have ZXing, can generate with `BarcodeEncoder`

**Where to add:** Channel long-press → Share → show QR dialog

---

## 🔧 Technical Details

### **Build & Dependencies**

**Gradle:**
- Kotlin 2.1.0 (Compose compiler plugin)
- AGP 8.8.0
- compileSdk 37, minSdk 28, targetSdk 37

**Key Dependencies:**
```kotlin
implementation("androidx.compose.material3:material3")
implementation("com.journeyapps:zxing-android-embedded:4.3.0")
implementation("com.google.zxing:core:3.5.3")
```

**Build command:**
```bash
JAVA_HOME=/home/poly/.local/share/JetBrains/Toolbox/apps/android-studio/jbr \
./gradlew :app:assembleDebug --console=plain
```

**Output:** `app/build/outputs/apk/debug/app-debug.apk` (~58MB)

### **Architecture**

**State Management:** Single `MeshifyViewModel` (AndroidViewModel) with StateFlow  
**In-Memory Collections:**
- `contacts: LinkedHashMap<String, Contact>` — keyed by publicKey (64 hex)
- `messages: Map<String, MutableList<Message>>` — keyed by contact publicKey
- `channels: Map<Int, Channel>` — keyed by channel index (0-7)
- `channelMessages: Map<Int, MutableList<ChannelMessage>>` — keyed by channel index

**Frame Handling:**
1. BLE → `BleMeshClient.handleFrame(ByteArray)`
2. Parse → `MeshProtocol.parse(frame)` returns `MeshEvent?`
3. Dispatch → `ViewModel.handleFrame()` updates collections
4. Publish → `publishMessages()` / `publishChannels()` → StateFlow → UI

### **Protocol Frame Format**

All frames: max 172 bytes.

**Send text message (direct):**
```
[2][recipientKey(32)][flags][text...][0]
```

**Send channel message:**
```
[3][channelIndex][flags][text...][0]
```

**Set channel:**
```
[32][channelIndex][name(32, null-padded)][psk(16)]
```

**Contact record (incoming):**
```
[3][pubKey(32)][type][flags][pathLen][path(64)][name(32)][timestamp(4)][lat(4)][lon(4)][modified(4)]
```

### **Known MeshCore Constants**

- Public channel PSK: `8b3387e9c5cdea6ac9e5edbaa115cd72`
- Max frame size: 172 bytes
- Max text payload: 160 bytes
- Contact record size: 148 bytes
- Channels: 0-7 (8 total)
- Public key size: 32 bytes (64 hex chars)
- PSK size: 16 bytes (32 hex chars)

### **Device Name Prefixes (for scan heuristics):**
```kotlin
"MeshCore-", "Whisper-", "WisCore-", "Seeed", "Lilygo", "HT-", "LowMesh_MC_", "NRF52"
```

---

## 📂 Project Structure

```
app/src/main/java/com/polymatic/meshify/
├── MainActivity.kt                      # Entry point, permission handling
├── mesh/
│   ├── BleMeshClient.kt                # BLE transport (scan, connect, GATT, NUS)
│   └── MeshProtocol.kt                 # Protocol commands, parsers, models
├── ui/
│   ├── MeshifyApp.kt                   # Root UI, navigation logic, tabs
│   ├── MeshifyViewModel.kt             # State management, BLE → UI
│   └── screens/
│       ├── ChatScreen.kt               # 1:1 direct messaging
│       ├── ChannelChatScreen.kt        # Group channel messaging
│       ├── AddChannelDialog.kt         # Add channel dialog + QR scanner
│       └── TabScreens.kt               # Channels/Messages/Settings tabs
└── debug/
    └── BleDebugLog.kt                  # In-app debug log (300 entries)

REFERENCES/meshcore-open/               # Flutter reference client
├── lib/connector/meshcore_connector.dart   # Full protocol implementation
├── lib/models/                             # Data models (Channel, Message, etc.)
├── lib/storage/                            # SharedPreferences stores
└── documentation/ble-protocol.md           # Protocol specification
```

---

## 🚀 Next Steps (Priority Order)

1. **Fix QR scanner** (CRITICAL) — follow debug steps above, check Flutter QR format
2. **Add message persistence** — SharedPreferences stores for messages/channels
3. **Implement delivery confirmation** — parse codes 6 and 0x82, update message status
4. **Add channel sync on connect** — CMD_GET_CHANNEL loop in synchronize()
5. **Implement retry logic** — exponential backoff for failed messages
6. **Add unread counters** — per-contact/channel unread tracking
7. **Generate QR codes** — share channels via QR

---

## 🐛 Debugging Tips

**BLE Traffic:**
- In-app: scanner screen → bug icon (top right)
- Logcat: `adb logcat -v time -s MeshifyBle`

**Add logging anywhere:**
```kotlin
import com.polymatic.meshify.debug.BleDebugLog
BleDebugLog.add("Your debug message here")
```

**Test without device:**
- Modify `MeshProtocol.parse()` to inject fake events
- Call `ViewModel.handleFrame()` with synthetic data

**Common issues:**
- Messages not appearing: check `publishMessages()` is called after collection update
- Channels empty: check `publishChannels()` after `channels[i] = ...`
- BLE timeout: Heltec firmware might not advertise NUS (scan is unfiltered, this is OK)
- Connection drops: MTU negotiation might fail (logged in debug log)

---

## 📚 Reference Documentation

**In this repo:**
- `HANDOFF.md` — original handoff (scanner implementation)
- `REFERENCES/meshcore-open/CLAUDE.md` — Flutter client overview
- `REFERENCES/meshcore-open/documentation/ble-protocol.md` — full protocol spec

**Key Flutter files to reference:**
- `lib/connector/meshcore_connector.dart` — full protocol implementation
- `lib/models/channel.dart` — channel model, PSK derivation
- `lib/models/message.dart` — message model with retry fields
- `lib/storage/*.dart` — all persistence stores
- `lib/screens/chat_screen.dart` — 1:1 chat with full features
- `lib/screens/channel_chat_screen.dart` — channel chat with images/reactions

---

## ✅ Testing Checklist

Before claiming "done":

- [ ] Public channel appears in channel list on connect
- [ ] Manual channel entry works (add channel dialog, enter index/name/PSK)
- [ ] QR scanner launches and scans (test with known-good QR)
- [ ] Scanned channel appears in list immediately
- [ ] Can open channel chat and send message
- [ ] Incoming channel messages display sender name
- [ ] System back button works (returns to channel list)
- [ ] Messages persist across app restart
- [ ] Channels persist across app restart
- [ ] Message status updates: pending → sent → delivered
- [ ] Failed messages show error icon
- [ ] Unread counters appear on channel/contact cards

---

**Last successful build:** 2026-08-13 20:38  
**APK size:** 58MB  
**Known working:** Scanner, Contacts, Direct Messages, Channel Messages (send/receive), BackHandler  
**Known broken:** QR scanner (scans but doesn't add), Persistence, Delivery confirmation, Retry

Good luck! 🚀
