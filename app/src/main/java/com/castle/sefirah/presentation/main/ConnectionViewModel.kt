package com.castle.sefirah.presentation.main

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.komu.sekia.di.AppCoroutineScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sefirah.data.repository.AppUpdateChecker
import sefirah.data.repository.ReleaseRepository
import sefirah.domain.model.PairedDevice
import sefirah.domain.model.UpdateInfo
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val networkManager: NetworkManager,
    private val deviceManager: DeviceManager,
    private val appScope: AppCoroutineScope,
    private val appUpdateChecker: AppUpdateChecker
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val pairedDevices: StateFlow<List<PairedDevice>> = deviceManager.pairedDevices

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _isCheckingForUpdate = MutableStateFlow(false)
    val isCheckingForUpdate: StateFlow<Boolean> = _isCheckingForUpdate.asStateFlow()

    val hasCheckedForUpdate = mutableStateOf(false)

    val selectedDevice: StateFlow<PairedDevice?> = combine(
        pairedDevices,
        deviceManager.selectedDeviceId
    ) { devices, selectedId ->
        devices.firstOrNull { it.deviceId == selectedId }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    private val connectionJobs = mutableMapOf<String, Job>()

    init {
        appScope.launch {
            selectedDevice.collect { device ->
                // Clear refreshing when selected device's connection state changes to Connected or Disconnected
                if (device != null && _isRefreshing.value && 
                    (device.connectionState.isConnected || device.connectionState.isDisconnected)) {
                    _isRefreshing.value = false
                }
            }
        }
    }

    fun toggleSync(syncRequest: Boolean, targetDevice: PairedDevice? = null) {
        val device = targetDevice ?: selectedDevice.value ?: return
        selectDevice(device)
        _isRefreshing.value = true
        
        val currentState = device.connectionState
        when {
            syncRequest && !currentState.isConnected -> connect(device)
            syncRequest && currentState.isConnected -> {
                // Disconnect first, then reconnect
                connectionJobs.remove(device.deviceId)?.cancel()
                connectionJobs[device.deviceId] = appScope.launch(Dispatchers.IO) {
                    networkManager.disconnect(device.deviceId)
                    delay(200.milliseconds)
                    networkManager.connectPaired(device, isManualReconnect = true)
                }
            }
            !syncRequest && currentState.isConnectedOrConnecting -> {
                // Cancel any ongoing connection attempt
                connectionJobs.remove(device.deviceId)?.cancel()
                appScope.launch(Dispatchers.IO) {
                    networkManager.disconnect(device.deviceId)
                }
            }
        }
    }

    fun connect(device: PairedDevice) {
        selectDevice(device)
        // Cancel any existing connection attempt
        connectionJobs.remove(device.deviceId)?.cancel()
        connectionJobs[device.deviceId] = appScope.launch(Dispatchers.IO) {
            networkManager.connectPaired(device, isManualReconnect = true)
        }
    }

    fun selectDevice(device: PairedDevice) {
        deviceManager.selectDevice(device.deviceId)
    }

    suspend fun checkForUpdate(force: Boolean = false): ReleaseRepository.Result {
        _isCheckingForUpdate.value = true
        return try {
            val result = appUpdateChecker.checkForUpdate(force)
            when (result) {
                is ReleaseRepository.Result.NewUpdate -> {
                    _updateInfo.value = result.updateInfo
                }
                is ReleaseRepository.Result.NoNewUpdate -> {
                    result.updateInfo?.let { _updateInfo.value = it }
                }
                ReleaseRepository.Result.Error -> Unit
            }
            result
        } finally {
            _isCheckingForUpdate.value = false
        }
    }
}