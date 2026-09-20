package sefirah.communication.call

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sefirah.BoundFeature
import sefirah.common.util.isCallLogsPermissionGranted
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.model.DevicePreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallLogFeature @Inject constructor(
    private val context: Context,
    deviceManager: DeviceManager,
    private val networkManager: NetworkManager,
) : BoundFeature(deviceManager) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isObserverRegistered = false

    private val callLogObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            Log.d(TAG, "Call log database changed, pushing latest call logs")
            syncLatestCallLogs()
        }
    }

    override fun isPrefEnabled(prefs: DevicePreferences) = prefs.callLogSync

    override fun hasPermissions(): Boolean = isCallLogsPermissionGranted(context)

    override suspend fun onStart() {
        registerObserver()
    }

    override suspend fun onStop() {
        unregisterObserver()
    }

    override suspend fun onStart(deviceId: String) {
        syncAllCallLogs(deviceId)
    }

    private fun registerObserver() {
        if (isObserverRegistered || !hasPermissions()) return
        try {
            context.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                callLogObserver,
            )
            isObserverRegistered = true
            Log.d(TAG, "Registered CallLog ContentObserver")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register CallLog ContentObserver", e)
        }
    }

    private fun unregisterObserver() {
        if (!isObserverRegistered) return
        try {
            context.contentResolver.unregisterContentObserver(callLogObserver)
            isObserverRegistered = false
            Log.d(TAG, "Unregistered CallLog ContentObserver")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister CallLog ContentObserver", e)
        }
    }

    private fun syncAllCallLogs(deviceId: String) {
        if (!hasPermissions()) return
        scope.launch {
            try {
                val logs = CallLogHelper.getCallLogs(context)
                logs.forEach { callLog ->
                    networkManager.sendMessage(deviceId, callLog)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing call logs to $deviceId", e)
            }
        }
    }

    private fun syncLatestCallLogs() {
        if (!hasPermissions()) return
        scope.launch {
            try {
                val recentLogs = CallLogHelper.getRecentCallLogs(context, 20)
                activeDeviceIds.forEach { deviceId ->
                    recentLogs.forEach { callLog ->
                        networkManager.sendMessage(deviceId, callLog)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting call logs", e)
            }
        }
    }

    private companion object {
        const val TAG = "CallLogFeature"
    }
}
