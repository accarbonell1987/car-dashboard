package com.carobd.core

import com.carobd.core.model.OrigenViaje
import com.carobd.core.model.Viaje
import com.carobd.core.sync.AlmacenPersistente
import com.carobd.core.sync.PaqueteCambios
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AlmacenPersistenteTest {
    private val t0 = Instant.parse("2026-11-03T08:00:00Z")
    private fun viaje(id: String, km: Double) =
        Viaje(id, "veh", t0, t0, km, 600, origen = OrigenViaje.OBD, updatedAt = t0)

    @Test
    fun loGuardadoSobreviveAUnReinicioConSusPendientes() = runTest {
        var disco: String? = null
        val a = AlmacenPersistente(leer = { disco }, escribir = { disco = it })
        a.guardar(viaje("v1", 12.5), viaje("v2", 3.0))
        a.marcarEnviados(listOf("v1"))
        a.guardarUltimoSync(t0)

        val b = AlmacenPersistente(leer = { disco }, escribir = { disco = it })
        assertEquals(2, b.leer { it.viajes.size })
        assertEquals(listOf("v2"), b.pendientes().viajes.map { it.id })
        assertEquals(t0, b.ultimoSync())
    }

    @Test
    fun unArchivoDanadoNoRompeLaApp() = runTest {
        val a = AlmacenPersistente(leer = { "{basura" }, escribir = {})
        assertEquals(0, a.cantidadPendientes())
        assertNull(a.ultimoSync())
        a.aplicarRemotos(PaqueteCambios(viajes = listOf(viaje("r1", 1.0))))
        assertEquals(1, a.leer { it.viajes.size })
    }
}

class NubeTest {
    private val t0 = Instant.parse("2026-11-03T08:00:00Z")

    @Test
    fun sinConfiguracionNoIntentaYConFalloAvisaYGuardaLosPendientes() = runTest {
        val almacen = AlmacenPersistente({ null }, {})
        almacen.guardar(Viaje("v1", "veh", t0, t0, 1.0, 600, origen = OrigenViaje.OBD, updatedAt = t0))
        var falla = true
        val nube = com.carobd.core.sync.Nube(almacen, "pantalla", com.carobd.core.sync.ConfigNube()) {
            com.carobd.core.sync.TransporteSync { req ->
                if (falla) error("El servidor respondió 401: no") else com.carobd.core.sync.SyncResponse(req.cambios.viajes.map { it.id }, PaqueteCambios(), t0)
            }
        }
        assertNull(nube.sincronizar())
        assertEquals(1, nube.estado.value.pendientes)

        nube.configurar(com.carobd.core.sync.ConfigNube("https://carobd.ejemplo", "abc"))
        nube.sincronizar()
        assertEquals("El token no es válido", nube.estado.value.error)
        assertEquals(1, nube.estado.value.pendientes)

        falla = false
        nube.sincronizar()
        assertNull(nube.estado.value.error)
        assertEquals(0, nube.estado.value.pendientes)
        assertEquals(t0, nube.estado.value.ultimoSync)
    }

    @Test
    fun unErrorDeRedSeMuestraEnEspanol() = runTest {
        val almacen = AlmacenPersistente({ null }, {})
        val nube = com.carobd.core.sync.Nube(almacen, "pantalla", com.carobd.core.sync.ConfigNube("http://10.0.2.2:8080", "local")) {
            com.carobd.core.sync.TransporteSync { throw java.net.ConnectException("Failed to connect to /10.0.2.2:8080") }
        }
        nube.sincronizar()
        assertEquals("No se pudo conectar con el servidor", nube.estado.value.error)
    }
}
