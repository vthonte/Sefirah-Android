# Sefirah-Android — Developer & AI Architecture Notes

This document provides essential architectural context, operational rules, and feature implementations for AI assistants and developers working on `Sefirah-Android`.

---

## 1. Project Overview & Package Identity
- **Repository**: `Sefirah-Android`
- **Application ID / Package**: `com.castle.sefirah.ai` (Debug build has `.ai` suffix)
- **Primary Branch**: `feature/ai`
- **Companion Desktop App**: `Sefirah` (Windows App SDK / WinUI 3, package `vthonte.Sefirah-AI`)
- **Build System**: Gradle with Kotlin Multiplatform-ready module structure (`app`, `features`, `core/network`, `core/database`, `core/presentation`, `domain`, `data`).

### Building & Installing
```cmd
cmd /c ".\gradlew.bat assembleDebug"
adb -s <device-serial> install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 2. Networking, Ports & USB Tunnels

### Port Architecture
| Port | Direction | Transport | Description |
|---|---|---|---|
| `5149` | Both | UDP | Device Discovery (UDP broadcast & listener) |
| `5150` | Inbound / Outbound | TCP (TLS) | Primary Sefirah communication server |
| `5151` | Inbound | SFTP | Remote storage / file browsing server on phone |
| `5152` | Outbound (Loopback) | TCP (TLS) | Phone $\rightarrow$ Desktop via ADB reverse (`127.0.0.1:5152` $\rightarrow$ Desktop `5150`) |
| `5153` | Inbound (Loopback) | TCP (TLS) | Desktop $\rightarrow$ Phone via ADB forward (Desktop `5153` $\rightarrow$ Phone `5150`) |
| `5555` | Inbound | ADB TCP | Wireless ADB daemon on phone |

### ⚠️ CRITICAL USB STABILITY RULE
- **NEVER RUN `adb tcpip 5555` AUTOMATICALLY.**
- Running `adb tcpip 5555` restarts the Android `adbd` daemon, which drops the physical USB hardware connection, kills active reverse/forward tunnels, and crashes `scrcpy`.
- Desktop handles reverse (`reverse tcp:5152 tcp:5150`) and forward (`forward tcp:5151 tcp:5151`, `forward tcp:5153 tcp:5150`) when USB connects.

---

## 3. Connection Priority & Fast Wi-Fi Detection

### Connection Priority
In `NetworkService.connectPaired(device)`:
1. **USB Loopback**: Tests `127.0.0.1:5152` (reverse tunnel) and `127.0.0.1:5150` first. If reachable, USB connection is established immediately.
2. **Wi-Fi IPs**: If USB loopback is not reachable, iterates through known Wi-Fi IP addresses in `device.addresses`.

### Fast Wi-Fi Detection
- **Network Callback Registration**: In `NetworkDiscovery.kt`, `ConnectivityManager.NetworkCallback` is ALWAYS registered even when `trustAllNetworks` is true.
- **Immediate Broadcast & Probe**: When `onAvailable` or `onCapabilitiesChanged` fires, Android immediately calls `broadcastDevice()` and `probePairedDevices()`.
- **Periodic 10s Broadcast**: In `NetworkDiscovery.startDiscovery()`, a coroutine loop broadcasts UDP packets every 10 seconds.
- **Address Preservation**: When receiving a UDP broadcast in `startDeviceListener()`, new IP addresses are recorded into the database (`appRepository.updateDeviceAddresses`) even if the device is currently connected via USB.
- **Seamless Upgrade from USB to Wi-Fi**: If connected only via loopback (`127.0.0.1`) and a Wi-Fi UDP packet is received, Android connects to the newly discovered Wi-Fi IP without delay.

---

## 4. Manual Disconnect & Reconnect Override

### Manual Disconnect Behavior
- When the user manually disconnects on either device, the device enters `ConnectionState.Disconnected(forcedDisconnect = true)`.
- Background automatic discovery and probing pause while `forcedDisconnect` is true, respecting the user's intent.

### Nominal Reconnect Override from Either Device
- **Reconnecting from Phone**: Tapping "Connect" or "Sync" in `ConnectionViewModel.kt` calls `connectPaired(device)`, which sets `ConnectionState.Connecting`, clearing `forcedDisconnect`.
- **Reconnecting from Laptop**: When Desktop connects to Phone, `authenticatePairedDevice` sets `ConnectionState.Connected`, which clears `forcedDisconnect` on the phone.
- Result: Tapping Connect on either device brings both devices to "Connected" without touching the other device.

---

## 5. 5-Second Keep-Alive Pings
- Both Desktop and Android define `Ping` and `Pong` in `SocketMessage`.
- In `MessageHandler.kt`:
  ```kotlin
  is Ping -> sendMessage(device.deviceId, Pong(message.timestamp))
  is Pong -> {} // Keep-alive response received
  ```
- Desktop sends `Ping` every 5 seconds. If Desktop misses 3 consecutive pings (15s), it drops the dead socket and reconnects.

---

## 6. Bluetooth Auto-Enable Flow
- When Desktop requests Bluetooth pairing (`BluetoothPairingRequest`):
  - In `BluetoothPairingHandler.kt`: Pairing is NOT rejected if Bluetooth is off; it launches `BluetoothDiscoverableActivity`.
  - In `BluetoothDiscoverableActivity.kt`:
    1. Checks permissions (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`).
    2. If `!adapter.isEnabled`, tries `adapter.enable()` and falls back to `Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)` to present the system enable dialog.
    3. Once enabled, calls `launchDiscoverable()` with `Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)`.

---

## 7. Android Permissions & AppOps
- **DND & Volume Control**: Requires `android.permission.ACCESS_NOTIFICATION_POLICY`. Granted via ADB on Desktop:
  `adb shell cmd appops set com.castle.sefirah.ai ACCESS_NOTIFICATIONS allow`
- **Call Logs**: Handled by `CallLogFeature` extending `BoundFeature`, with a `ContentObserver` on `CallLog.Calls.CONTENT_URI`.
- **Play Sound (Find My Phone)**: `PlaySoundFeature` plays default ringtone using `RingtoneManager` with alarm/notification fallbacks. Toggleable from Desktop or Phone.
