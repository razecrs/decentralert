package app.decentralert.dashboard

import app.decentralert.shared.CreateIncidentRequest
import app.decentralert.shared.Incident
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.window
import kotlinx.serialization.json.Json

object ApiConfig {
    val baseUrl: String
        get() = if (window.location.hostname == "localhost" || window.location.hostname == "127.0.0.1") {
            "http://localhost:8080"
        } else {
            "${window.location.origin}/api"
        }
}

class DispatchApi(private val baseUrl: String = ApiConfig.baseUrl) {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
    }

    suspend fun incidents(): List<Incident> = client.get("$baseUrl/v1/incidents").body()

    suspend fun create(request: CreateIncidentRequest): Incident = client.post("$baseUrl/v1/incidents") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()
}

