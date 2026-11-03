package com.carobd.server

import com.carobd.core.model.OrigenViaje
import com.carobd.core.model.Viaje
import com.carobd.core.sync.PaqueteCambios
import com.carobd.core.sync.SyncJson
import com.carobd.core.sync.SyncRequest
import com.carobd.core.sync.SyncResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SyncTest {
    private val t0 = Instant.parse("2026-11-02T20:00:00Z")
    private val t1 = Instant.parse("2026-11-02T21:00:00Z")

    private fun viaje(id: String, updated: Instant, km: Double) = Viaje(
        id = id, vehiculoId = "veh", inicio = t0, fin = t1, km = km, duracionSeg = 3600,
        origen = OrigenViaje.OBD, updatedAt = updated,
    )

    @Test
    fun `sube, no duplica y reparte entre dispositivos del mismo usuario`() = testApplication {
        val db = BaseDeDatos.conectar("jdbc:h2:mem:sync;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "", "org.h2.Driver")
        application { modulo(Almacen(db), Tokens.desdeTexto("tok-pantalla:alberto,tok-movil:alberto,tok-otro:otra")) }
        val cliente = createClient { install(ContentNegotiation) { json(SyncJson.json) } }

        assertEquals(HttpStatusCode.OK, cliente.get("/salud").status)
        assertEquals(HttpStatusCode.Unauthorized, cliente.post("/sync") {
            contentType(ContentType.Application.Json); setBody(SyncRequest("x", null, PaqueteCambios()))
        }.status)

        // La pantalla sube un viaje dos veces (red cortada a mitad): una sola fila.
        val req = SyncRequest("pantalla", null, PaqueteCambios(viajes = listOf(viaje("v1", t0, 10.0))))
        repeat(2) {
            val r: SyncResponse = cliente.post("/sync") {
                bearerAuth("tok-pantalla"); contentType(ContentType.Application.Json); setBody(req)
            }.body()
            assertEquals(listOf("v1"), r.aceptados)
        }

        // El móvil del mismo usuario lo recibe; otro usuario no.
        val movil: SyncResponse = cliente.post("/sync") {
            bearerAuth("tok-movil"); contentType(ContentType.Application.Json); setBody(SyncRequest("movil", null, PaqueteCambios()))
        }.body()
        assertEquals(1, movil.cambios.viajes.size)
        val otro: SyncResponse = cliente.post("/sync") {
            bearerAuth("tok-otro"); contentType(ContentType.Application.Json); setBody(SyncRequest("otro", null, PaqueteCambios()))
        }.body()
        assertEquals(0, otro.cambios.viajes.size)

        // Una edición más nueva reemplaza; una más vieja no.
        cliente.post("/sync") {
            bearerAuth("tok-pantalla"); contentType(ContentType.Application.Json)
            setBody(SyncRequest("pantalla", null, PaqueteCambios(viajes = listOf(viaje("v1", t1, 12.0)))))
        }
        cliente.post("/sync") {
            bearerAuth("tok-pantalla"); contentType(ContentType.Application.Json)
            setBody(SyncRequest("pantalla", null, PaqueteCambios(viajes = listOf(viaje("v1", t0, 99.0)))))
        }
        val final: SyncResponse = cliente.post("/sync") {
            bearerAuth("tok-movil"); contentType(ContentType.Application.Json); setBody(SyncRequest("movil", t0, PaqueteCambios()))
        }.body()
        assertEquals(12.0, final.cambios.viajes.single().km)
    }
}
