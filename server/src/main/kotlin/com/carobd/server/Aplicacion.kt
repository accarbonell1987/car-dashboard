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
 * Servidor CAROBD: un solo endpoint de sincronización más /health.
 * Configuración por variables de entorno (ver deploy/vps/compose.vps.yml).
 */
fun main() {
    val puerto = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val db = BaseDeDatos.desdeEntorno()
    embeddedServer(Netty, port = puerto) { modulo(Almacen(db), Tokens.desdeEntorno()) }.start(wait = true)
}

fun Application.modulo(almacen: Almacen, tokens: Tokens) {
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
        }
    }
}
