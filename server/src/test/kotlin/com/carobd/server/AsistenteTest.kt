package com.carobd.server

import com.carobd.core.sync.SyncJson
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AsistenteTest {
    private fun db() = BaseDeDatos.conectar("jdbc:h2:mem:asistente;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "", "org.h2.Driver")
    private val tokens = Tokens.desdeTexto("tok-pantalla:alberto")

    @Test
    fun `entrega el token solo con credencial y respeta el tope del dia`() = testApplication {
        val pedidos = mutableListOf<String>()
        val falso = ProveedorTokens { usuario -> pedidos += usuario; TokenAsistente("ek_prueba", 1_800_000_000, "gpt-realtime-mini") }
        application { modulo(Almacen(db()), tokens, falso, TopeDiario(2)) }
        val cliente = createClient { install(ContentNegotiation) { json(SyncJson.json) } }

        assertEquals(HttpStatusCode.Unauthorized, cliente.post("/asistente/token").status)
        repeat(2) {
            val r = cliente.post("/asistente/token") { bearerAuth("tok-pantalla") }
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals("ek_prueba", r.body<TokenAsistente>().token)
        }
        assertEquals(HttpStatusCode.TooManyRequests, cliente.post("/asistente/token") { bearerAuth("tok-pantalla") }.status)
        assertEquals(listOf("alberto", "alberto"), pedidos)
    }

    @Test
    fun `sin clave de OpenAI el asistente esta apagado`() = testApplication {
        application { modulo(Almacen(db()), tokens) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/asistente/token") { bearerAuth("tok-pantalla") }.status)
    }

    @Test
    fun `si OpenAI falla responde 502 sin detalles`() = testApplication {
        application { modulo(Almacen(db()), tokens, { error("OpenAI respondió 401") }) }
        val r = client.post("/asistente/token") { bearerAuth("tok-pantalla") }
        assertEquals(HttpStatusCode.BadGateway, r.status)
    }

    @Test
    fun `el tope se reinicia al cambiar el dia`() {
        var ahora = Instant.parse("2026-11-26T12:00:00Z")
        val reloj = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = ahora
        }
        val tope = TopeDiario(1, reloj)
        assertTrue(tope.tomar("a"))
        assertFalse(tope.tomar("a"))
        assertTrue(tope.tomar("b"))
        ahora = Instant.parse("2026-11-27T12:00:00Z")
        assertTrue(tope.tomar("a"))
    }
}
