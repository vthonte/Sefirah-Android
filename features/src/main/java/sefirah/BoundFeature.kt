package sefirah

import sefirah.domain.interfaces.DeviceManager

/**
 * Feature that resource bound by active devices
 */
abstract class BoundFeature(
    deviceManager: DeviceManager,
) : Feature(deviceManager) {
    protected abstract suspend fun onStart()

    protected abstract suspend fun onStop()

    final override suspend fun enable(deviceId: String) {
        if (enabledDevices.isEmpty()) {
            onStart()
        }
        enabledDevices.add(deviceId)
        onStart(deviceId)
    }

    final override suspend fun disable(deviceId: String) {
        if (deviceId !in enabledDevices) return
        enabledDevices.remove(deviceId)
        onStop(deviceId)
        if (enabledDevices.isEmpty()) {
            onStop()
        }
    }
}
