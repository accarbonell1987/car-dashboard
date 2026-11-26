package com.carobd.server

import com.carobd.core.sync.SyncJson
import com.carobd.core.sync.SyncRequest
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * Servidor CAROBD: sincronización, token del asistente de voz y /health.
 * Configuración por variables de entorno (ver deploy/vps/compose.vps.yml).
 */
fun main() {
    val puerto = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val db = BaseDeDatos.desdeEntorno()
    embeddedServer(Netty, port = puerto) { modulo(Almacen(db), Tokens.desdeEntorno(), OpenAiRealtime.desdeEntorno(), TopeDiario.desdeEntorno()) }.start(wait = true)
}

fun Application.modulo(
    almacen: Almacen,
    tokens: Tokens,
    asistente: ProveedorTokens? = null,
    tope: TopeDiario = TopeDiario(30),
) {
    install(CallLogging)
    install(ContentNegotiation) { json(SyncJson.json) }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "petición inválida")))
        }
    }
    install(Authentication) {
        bearer("dispositivo") {
            authenticate { credencial -> tokens.usuarioDe(credencial.token)?.let { UserIdPrincipal(it) } }
        }
    }
    routing {
        get("/health") { call.respondText("ok") }
        // Alias temporal: el deploy-vps.sh instalado en el VPS todavía prueba /salud. Se quita cuando se actualice.
        get("/salud") { call.respondText("ok") }
        authenticate("dispositivo") {
            post("/sync") {
                val usuario = call.principal<UserIdPrincipal>()!!.name
                val req = call.receive<SyncRequest>()
                call.respond(almacen.sincronizar(usuario, req))
            }
            // Token efímero para la Realtime API. 503 si el servidor no tiene clave, 429 si se pasó el tope del día.
            post("/asistente/token") {
                val usuario = call.principal<UserIdPrincipal>()!!.name
                when {
                    asistente == null ->
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "asistente no configurado"))
                    !tope.tomar(usuario) ->
                        call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "tope diario del asistente"))
                    else -> try {
                        call.respond(asistente.crear(usuario))
                    } catch (e: Exception) {
                        call.application.environment.log.warn("No se pudo crear el token del asistente: ${e.message}")
                        call.respond(HttpStatusCode.BadGateway, mapOf("error" to "no se pudo crear el token"))
                    }
                }
            }
        }
    }
}
