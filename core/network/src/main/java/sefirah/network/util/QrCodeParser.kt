package sefirah.network.util

import android.net.Uri
import android.util.Log
import kotlinx.serialization.json.Json
import sefirah.domain.model.ConnectionDetails
import sefirah.domain.model.QrCodeConnectionData
import androidx.core.net.toUri

object QrCodeParser {
    private const val TAG = "QrCodeParser"
    const val SCHEME = "sefirah"
    const val PAIR_HOST = "pair"
    const val DATA_QUERY_KEY = "data"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseQrCode(qrCodeData: String): QrCodeConnectionData? =
        parsePairDeepLink(qrCodeData.trim().toUri())

    fun parsePairDeepLink(uri: Uri): QrCodeConnectionData? {
        val validSchemes = setOf(SCHEME, "sefirah-ai", "aikyam")
        if (uri.scheme !in validSchemes || uri.host != PAIR_HOST) {
            return null
        }

        val payload = uri.getQueryParameter(DATA_QUERY_KEY)
        if (payload.isNullOrBlank()) {
            Log.e(TAG, "Missing data query parameter: $uri")
            return null
        }

        return try {
            json.decodeFromString<QrCodeConnectionData>(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pair deep link payload: $payload", e)
            null
        }
    }

    fun toConnectionDetails(qrData: QrCodeConnectionData, selectedIp: String? = null): ConnectionDetails {
        return ConnectionDetails(
            deviceId = qrData.deviceId,
            prefAddress = selectedIp,
            addresses = if (!selectedIp.isNullOrEmpty() && qrData.addresses.contains(selectedIp)) {
                qrData.addresses - selectedIp
            } else {
                qrData.addresses
            },
            port = qrData.port
        )
    }
}
