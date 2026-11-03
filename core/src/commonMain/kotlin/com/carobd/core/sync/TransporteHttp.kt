package com.carobd.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json

/**
 * Transporte por HTTPS al servidor CAROBD (POST /sync con token Bearer).
 * Cada app pasa su motor de Ktor (OkHttp en Android, Darwin en iOS); en pruebas, el del servidor de prueba.
 */
class TransporteHttp(
    private val urlBase: String,
    private val token: String,
    cliente: HttpClient,
) : TransporteSync {
    private val http = cliente.config { install(ContentNegotiation) { json(SyncJson.json) } }

    override suspend fun sincronizar(req: SyncRequest): SyncResponse {
        val resp = http.post(urlBase.trimEnd('/') + "/sync") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        if (!resp.status.isSuccess()) error("El servidor respondió ${resp.status.value}: ${resp.bodyAsText().take(200)}")
        return resp.body()
    }
}
