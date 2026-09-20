package sefirah.domain.model

data class DevicePreferences(
    val clipboardSync: Boolean = true,
    val messageSync: Boolean = true,
    val notificationSync: Boolean = true,
    val callStateSync: Boolean = true,
    val callLogSync: Boolean = true,
    val imageClipboard: Boolean = true,
    val mediaSession: Boolean = true,
    val mediaSessionNotification: Boolean = true,
    val remoteVolumeControl: Boolean = true,
    val mediaPlayerControl: Boolean = true,
    val remoteStorage: Boolean = true,
    val playSound: Boolean = true,
)