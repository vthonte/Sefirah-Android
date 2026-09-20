package sefirah.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.isClosed
import io.ktor.utils.io.core.buildPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.io.readLine
import kotlinx.io.writeString
import sefirah.common.util.checkLocationPermissions
import sefirah.database.AppRepository
import sefirah.database.model.NetworkEntity
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.interfaces.PreferencesRepository
import sefirah.domain.interfaces.SocketFactory
import sefirah.domain.model.AddressEntry
import sefirah.domain.model.ConnectionDetails
import sefirah.domain.model.DiscoveredDevice
import sefirah.domain.model.PairedDevice
import sefirah.domain.model.UdpBroadcast
import sefirah.domain.util.MessageSerializer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.properties.Delegates

@Singleton
class NetworkDiscovery @Inject constructor(
    private val nsdService: NsdService,
    private val preferencesRepository: PreferencesRepository,
    private val appRepository: AppRepository,
    private val socketFactory: SocketFactory,
    private val deviceManager: DeviceManager,
    private val networkManager: NetworkManager,
    private val context: Context,
) {
    private lateinit var connectivityManager: ConnectivityManager
    private var isNetworkCallbackRegistered = false
    private var discoveryJob: Job? = null
    private var udpSocket: BoundDatagramSocket? = null
    private var lastBroadcastTime: Long = 0
    private lateinit var udpBroadcast: UdpBroadcast
    private lateinit var udpBroadcastMessage: String
    private var tcpServerPort by Delegates.notNull<Int>()

    private val scope = CoroutineScope(Dispatchers.IO) + SupervisorJob()
    private var udpPort: Int = 5149

    private var trustAllNetworks : Boolean = true

    private val _currentWifiSsid = MutableStateFlow<String?>(null)
    val currentWifiSsid: StateFlow<String?> = _currentWifiSsid.asStateFlow()

    suspend fun initialize(tcpServerPort: Int) {
        Log.i(TAG, "Initializing discovery")
        this.tcpServerPort = tcpServerPort
        val localDevice = deviceManager.localDevice
        udpBroadcast = UdpBroadcast(
            port = tcpServerPort,
            deviceId = localDevice.deviceId,
            deviceName = localDevice.deviceName,
        )
        udpBroadcastMessage = MessageSerializer.serialize(udpBroadcast) ?: throw Exception("Failed to serialize UDP broadcast")

        preferencesRepository.readTrustAllNetworks().collectLatest { trustAllNetworks ->
            this.trustAllNetworks = trustAllNetworks
            register()
            if (this.trustAllNetworks) {
                startDiscovery()
            } else {
                stopDiscovery()
            }
        }
    }

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return

        Log.d(TAG, "Starting discovery")
        discoveryJob = scope.launch {
            try {
                nsdService.advertiseService(udpBroadcast, tcpServerPort)
                if (udpSocket == null) {
                    udpSocket = socketFactory.udpSocket(udpPort)
                }

                launch { startDeviceListener() }
                launch { startNSDDiscovery() }
                launch {
                    while (isActive) {
                        broadcastDevice()
                        kotlinx.coroutines.delay(10_000L)
                    }
                }

                broadcastDevice()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting discovery", e)
            }
        }
    }

    private fun stopDiscovery() {
        try {
            if (discoveryJob?.isActive == false) return
            Log.d(TAG, "Stopping discovery")
            discoveryJob?.cancel()
            discoveryJob = null
            udpSocket?.close()
            udpSocket = null
            nsdService.stopAdvertisingService()
            nsdService.stopDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        }
    }

    fun register() {
        if (!checkLocationPermissions(context)) {
            startDiscovery()
            return
        }
        if (isNetworkCallbackRegistered) unregister()
        Log.d(TAG, "Registering network callback")

        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // update the current WiFi ssid if android version is less than 13
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo

            if (connectionInfo.supplicantState == SupplicantState.COMPLETED) {
                updateWifiSsid(connectionInfo.ssid)
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            isNetworkCallbackRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    fun unregister() {
        if (!isNetworkCallbackRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isNetworkCallbackRegistered = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
    }

    private val networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                val wifiInfo = networkCapabilities.transportInfo as? WifiInfo
                deviceDiscoveryCallback(wifiInfo)
                broadcastDevice()
                probePairedDevices()
            }

            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network callback Received (onAvailable)")
                broadcastDevice()
                probePairedDevices()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network Lost")
                _currentWifiSsid.value = null
                if (!trustAllNetworks) stopDiscovery()
            }
        }
    } else {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network callback Received")
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                deviceDiscoveryCallback(wifiInfo)
                broadcastDevice()
                probePairedDevices()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network Lost")
                _currentWifiSsid.value = null
                if (!trustAllNetworks) stopDiscovery()
            }
        }
    }

    // Passive wifi discovery callback: proceeds to start the udp listener if we are connected to a known wifi network
    private fun deviceDiscoveryCallback(wifiInfo: WifiInfo?) {
        scope.launch {
            if (wifiInfo != null && wifiInfo.ssid != UNKNOWN_SSID && wifiInfo.networkId != -1) {
                updateWifiSsid(wifiInfo.ssid)
                _currentWifiSsid.value?.let { ssid ->
                    if (trustAllNetworks || appRepository.getNetwork(ssid) != null) {
                        startDiscovery()
                        broadcastDevice()
                    } else {
                        stopDiscovery()
                    }
                }
            } else {
                _currentWifiSsid.value = null
            }
        }
    }

    fun probePairedDevices() {
        scope.launch {
            try {
                probeUsbDevice()
                deviceManager.pairedDevices.value.forEach { device ->
                    if (device.connectionState.isForcedDisconnect) return@forEach
                    if (!device.connectionState.isConnectedOrConnecting || device.address == "127.0.0.1") {
                        val hasNonLoopback = device.addresses.any { !it.address.startsWith("127.") }
                        if (hasNonLoopback) {
                            networkManager.connectPaired(device)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "probePairedDevices: ${e.message}")
            }
        }
    }

    suspend fun startNSDDiscovery() {
        nsdService.startDiscovery()
        nsdService.services.collectLatest { serviceInfoList ->
            serviceInfoList.forEach { serviceInfo ->
                try {
                    val attributes = serviceInfo.attributes
                    if (attributes != null) {
                        val deviceId = serviceInfo.serviceName
                        val port = attributes["serverPort"]?.let { String(it, Charsets.UTF_8).toIntOrNull() }

                        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
                        ) {
                            serviceInfo.hostAddresses.map { it.hostAddress ?: "" }
                                .filter { it.isNotBlank() && !it.contains(":") }
                        } else {
                            listOfNotNull(serviceInfo.host?.hostAddress)
                                .filter { !it.contains(":") }
                        }

                        if (port == null || addresses.isEmpty()) return@forEach

                        when (val device = deviceManager.getDevice(deviceId)) {
                            is PairedDevice -> {
                                // Merge discovered IPs with existing entries
                                val existingAddresses = device.addresses.map { it.address }.toSet()
                                val newEntries = addresses
                                    .filter { it !in existingAddresses }
                                    .map { AddressEntry(it) }
                                val mergedAddresses = device.addresses + newEntries
                                
                                val updatedDevice = if (newEntries.isNotEmpty() || device.port != port) {
                                    device.copy(
                                        addresses = mergedAddresses,
                                        port = port
                                    )
                                } else {
                                    device
                                }

                                if (newEntries.isNotEmpty() || device.port != port) {
                                    deviceManager.addOrUpdatePairedDevice(updatedDevice)
                                }

                                if (device.connectionState.isConnectedOrConnecting || device.connectionState.isForcedDisconnect) return@forEach

                                networkManager.connectPaired(updatedDevice)
                            }
                            is DiscoveredDevice -> return@forEach
                            null -> {
                                val connectionDetails = ConnectionDetails(deviceId, port, addresses)
                                networkManager.connectTo(connectionDetails)
                            }
                        }
                    }
                }
                catch (e: Exception) {
                    Log.e(TAG, "Error processing service info", e)
                }
            }
        }

    }

    fun broadcastDevice() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBroadcastTime < BROADCAST_RATE_LIMIT_MS) return

        lastBroadcastTime = currentTime

        scope.launch {
            try {
                if (udpSocket == null || udpSocket?.isClosed == true) {
                    udpSocket = socketFactory.udpSocket(udpPort)
                }

                val broadcastList = (deviceManager.pairedDevices.value
                    .flatMap { device -> 
                        val addrs = device.addresses.filter { it.isEnabled }.ifEmpty { device.addresses }.map { it.address }
                        listOfNotNull(device.address) + addrs
                    }
                    .map { it.trim().removePrefix("/").substringBefore(':').trim() }
                    .filter { it.isNotBlank() && !it.contains(":") }
                    .distinct() + "255.255.255.255").distinct()

                broadcastList.forEach { hostname ->
                    try {
                        val address = InetSocketAddress(hostname, udpPort)
                        val bytePacket = buildPacket { writeString(udpBroadcastMessage) }
                        udpSocket?.send(Datagram(bytePacket, address))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to broadcast to $hostname: ${e.message?.take(30)}")
                    }
                }
                probeUsbDevice()
            } catch (e: Exception) {
                Log.e(TAG, "Broadcasting failed", e)
            }
        }
    }

    fun probeUsbDevice() {
        scope.launch {
            try {
                // Check if loopback port 5152 (adb reverse) is responsive
                val isPortOpen = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    try {
                        java.net.Socket().use { s ->
                            s.connect(java.net.InetSocketAddress("127.0.0.1", 5152), 400)
                            true
                        }
                    } catch (_: Exception) {
                        false
                    }
                }
                if (isPortOpen) {
                    Log.d(TAG, "USB reverse tunnel detected on 127.0.0.1:5152, probing Desktop...")
                    val pairedUsb = deviceManager.pairedDevices.value.firstOrNull { pd ->
                        pd.address?.startsWith("127.") == true || pd.addresses.any { it.address.startsWith("127.") }
                    } ?: deviceManager.pairedDevices.value.firstOrNull()
                    if (pairedUsb != null) {
                        if (!pairedUsb.connectionState.isConnectedOrConnecting && !pairedUsb.connectionState.isForcedDisconnect) {
                            networkManager.connectPaired(pairedUsb)
                        }
                    } else {
                        networkManager.connectTo(ConnectionDetails(deviceId = "", port = 5152, addresses = listOf("127.0.0.1"), prefAddress = "127.0.0.1"))
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "probeUsbDevice: ${e.message}")
            }
        }
    }


    private suspend fun startDeviceListener() {
        Log.d(TAG, "Device listener started")
        try {
            while (currentCoroutineContext().isActive) {
                val datagram = udpSocket!!.receive()
                val udpBroadcast = datagram.packet.readLine()?.let {
                    MessageSerializer.deserialize(it) as UdpBroadcast
                } ?: continue

                if (udpBroadcast.deviceId == deviceManager.localDevice.deviceId) continue
                Log.d(TAG, "Received UDP broadcast from ${udpBroadcast.deviceName}")

                val rawSenderIp = (datagram.address as? InetSocketAddress)?.hostname
                    ?: datagram.address.toString().substringBefore(':').removePrefix("/")
                val senderIp = rawSenderIp.trim().removePrefix("/").substringBefore(':').trim()
                if (senderIp.isBlank() || senderIp.contains(":")) continue

                when (val device = deviceManager.getDevice(udpBroadcast.deviceId)) {
                    is PairedDevice -> {
                        // Always update IP addresses if new ones are found, regardless of connection state
                        val existingAddresses = device.addresses.map { it.address }.toSet()
                        if (!existingAddresses.contains(senderIp)) {
                            try {
                                val updatedAddresses = device.addresses + AddressEntry(senderIp)
                                appRepository.updateDeviceAddresses(device.deviceId, updatedAddresses)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to update IP addresses in database", e)
                            }
                        }

                        if (device.connectionState.isForcedDisconnect) continue

                        // If already connected and not on loopback, don't re-connect
                        if (device.connectionState.isConnectedOrConnecting && device.address != "127.0.0.1") continue

                        // Update device with discovered port if it differs
                        val updatedDevice = if (device.port != udpBroadcast.port) {
                            device.copy(port = udpBroadcast.port)
                        } else {
                            device
                        }
                        networkManager.connectPaired(updatedDevice)
                    }
                    null -> {
                        // New device
                        val connectionDetails = ConnectionDetails(udpBroadcast.deviceId, udpBroadcast.port, listOf(senderIp))
                        networkManager.connectTo(connectionDetails)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in device listener on port $udpPort", e)
        }
    }

    fun updateWifiSsid(ssid: String) {
        val ssid = ssid.removeSurrounding("\"")
        when {
            ssid.equals(UNKNOWN_SSID, ignoreCase = true) -> return
            ssid.isBlank() -> return
        }

        _currentWifiSsid.value = ssid
    }

    fun saveCurrentNetworkAsTrusted() {
        scope.launch {
            _currentWifiSsid.value?.let { appRepository.addNetwork(NetworkEntity(it)) }
        }
    }

    companion object {
        private const val TAG = "NetworkDiscovery"
        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val BROADCAST_RATE_LIMIT_MS = 200L
    }
}