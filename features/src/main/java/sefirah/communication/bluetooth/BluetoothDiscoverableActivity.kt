package sefirah.communication.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import sefirah.common.notifications.AppNotifications
import sefirah.common.util.NEARBY_DEVICES_PERMISSIONS
import sefirah.common.util.nearbyDevicesPermissionGranted
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.model.BluetoothPairingResult
import javax.inject.Inject

@AndroidEntryPoint
class BluetoothDiscoverableActivity : FragmentActivity() {

    @Inject lateinit var networkManager: NetworkManager

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            sendResult(result.resultCode > 0)
            finish()
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _: ActivityResult ->
            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
            if (bluetoothManager?.adapter?.isEnabled == true) {
                launchDiscoverable()
            } else {
                sendResult(false)
                finish()
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.values.all { it }
            if (granted) {
                val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                if (adapter != null) {
                    ensureBluetoothEnabledAndLaunch(adapter)
                } else {
                    sendResult(false)
                    finish()
                }
            } else {
                sendResult(false)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return

        val sourceDeviceId = intent.getStringExtra(EXTRA_SOURCE_DEVICE_ID)
        if (sourceDeviceId.isNullOrBlank()) {
            finish()
            return
        }

        NotificationManagerCompat.from(this).cancel(AppNotifications.BLUETOOTH_DISCOVERABLE_REQUEST_ID + sourceDeviceId.hashCode())

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            sendResult(false)
            finish()
            return
        }

        if (!nearbyDevicesPermissionGranted(this)) {
            permissionLauncher.launch(NEARBY_DEVICES_PERMISSIONS)
            return
        }

        ensureBluetoothEnabledAndLaunch(adapter)
    }

    private fun ensureBluetoothEnabledAndLaunch(adapter: BluetoothAdapter) {
        if (adapter.isEnabled) {
            launchDiscoverable()
            return
        }

        // Try direct enable if permitted
        runCatching { adapter.enable() }
        if (adapter.isEnabled) {
            launchDiscoverable()
            return
        }

        // Fall back to system prompt intent
        val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        runCatching { enableBluetoothLauncher.launch(enableIntent) }
            .onFailure {
                launchDiscoverable()
            }
    }

    private fun launchDiscoverable() {
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION_SECONDS)
        }

        runCatching { discoverableLauncher.launch(discoverableIntent) }
            .onFailure { e ->
                Log.e(TAG, "Failed to launch discoverable intent", e)
                sendResult(false)
                finish()
            }
    }

    private fun sendResult(granted: Boolean) {
        val sourceDeviceId = intent.getStringExtra(EXTRA_SOURCE_DEVICE_ID) ?: return
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val deviceName = bluetoothManager?.adapter?.name
        lifecycleScope.launch {
            networkManager.sendMessage(sourceDeviceId, BluetoothPairingResult(granted, deviceName))
        }
    }

    companion object {
        private const val TAG = "BluetoothDiscoverable"
        private const val EXTRA_SOURCE_DEVICE_ID = "extra_source_device_id"
        private const val DISCOVERABLE_DURATION_SECONDS = 120

        fun createIntent(context: Context, sourceDeviceId: String): Intent =
            Intent(context, BluetoothDiscoverableActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_DEVICE_ID, sourceDeviceId)
            }
    }
}
