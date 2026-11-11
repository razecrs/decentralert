package app.decentralert.mobile

import android.os.Build
import app.decentralert.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

class VaultApi(private val baseUrl: String = "https://decentralert.razecrs.com/api") {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun register(): DeviceCredentials {
        val response = request("/v1/devices/register", "POST", "{\"deviceName\":${json.encodeToString(Build.MODEL)}}", null)
        val registration = json.decodeFromString<DeviceRegistration>(response)
        return DeviceCredentials(registration.deviceId, registration.deviceToken)
    }

    suspend fun saveVault(credentials: DeviceCredentials, vault: EmergencyVault): EmergencyVault =
        json.decodeFromString(request("/v1/vault", "PUT", json.encodeToString(vault), credentials))

    suspend fun createSession(credentials: DeviceCredentials, request: CreateSosSessionRequest): SosSession =
        json.decodeFromString(this.request("/v1/sos/sessions", "POST", json.encodeToString(request), credentials))

    suspend fun heartbeat(credentials: DeviceCredentials, sessionId: String, heartbeat: HeartbeatRequest): SosSession =
        json.decodeFromString(request("/v1/sos/sessions/$sessionId/heartbeat", "POST", json.encodeToString(heartbeat), credentials))

    suspend fun confirm(credentials: DeviceCredentials, sessionId: String): SosSession =
        json.decodeFromString(request("/v1/sos/sessions/$sessionId/confirm", "POST", "{}", credentials))

    suspend fun cancel(credentials: DeviceCredentials, sessionId: String): SosSession =
        json.decodeFromString(request("/v1/sos/sessions/$sessionId/cancel", "POST", "{}", credentials))

    private suspend fun request(path: String, method: String, body: String?, credentials: DeviceCredentials?): String = withContext(Dispatchers.IO) {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            credentials?.let {
                connection.setRequestProperty("X-Device-Id", it.deviceId)
                connection.setRequestProperty("Authorization", "Bearer ${it.deviceToken}")
            }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.bufferedWriter().use { writer -> writer.write(body) }
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("Server returned $status: $response")
            response
        } finally {
            connection.disconnect()
        }
    }
}
