package sefirah.communication.bluetooth

import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import sefirah.common.notifications.AppNotifications
import sefirah.common.notifications.NotificationCenter
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.model.BluetoothPairingResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothPairingHandler @Inject constructor(
    private val context: Context,
    private val networkManager: NetworkManager,
    private val notificationCenter: NotificationCenter,
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    fun handleBluetoothRequest(sourceDeviceId: String) {
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            Log.w(TAG, "Bluetooth unavailable on this device; cannot open discoverable flow")
            networkManager.sendMessage(sourceDeviceId, BluetoothPairingResult(false))
            return
        }

        val intent = BluetoothDiscoverableActivity.createIntent(context, sourceDeviceId).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        showDiscoverableRequestNotification(sourceDeviceId)

        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Bluetooth discoverable activity start failed", it) }
    }

    private fun showDiscoverableRequestNotification(sourceDeviceId: String) {
        val intent = BluetoothDiscoverableActivity.createIntent(context, sourceDeviceId).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val requestCode = sourceDeviceId.hashCode()
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        notificationCenter.showNotification(
            channelId = AppNotifications.BLUETOOTH_DISCOVERABLE_CHANNEL,
            notificationId = AppNotifications.BLUETOOTH_DISCOVERABLE_REQUEST_ID + requestCode,
        ) {
            setContentTitle("Allow Bluetooth pairing")
            val body = "Tap to make this phone discoverable for your desktop."
            setContentText(body)
            setStyle(NotificationCompat.BigTextStyle().bigText(body))
            setContentIntent(contentPendingIntent)
            setAutoCancel(true)
            setPriority(NotificationCompat.PRIORITY_HIGH)
            setCategory(NotificationCompat.CATEGORY_STATUS)
        }
    }

    private companion object {
        const val TAG = "BluetoothPairingHandler"
    }
}
