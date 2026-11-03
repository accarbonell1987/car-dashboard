package com.carobd.server

import com.carobd.core.model.OrigenViaje
import com.carobd.core.model.Viaje
import com.carobd.core.sync.AlmacenEnMemoria
import com.carobd.core.sync.MotorSync
import com.carobd.core.sync.ResultadoSync
import com.carobd.core.sync.TransporteHttp
import io.ktor.server.testing.testApplication
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pantalla y móvil del mismo usuario sincronizando por HTTP con el servidor real (sobre H2). */
class SincronizacionEntreDispositivosTest {

    @Test
    fun `un viaje grabado sin conexion llega al movil aunque el movil haya sincronizado despues`() = testApplication {
        val db = BaseDeDatos.conectar("jdbc:h2:mem:e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "", "org.h2.Driver")
        application { modulo(Almacen(db), Tokens.desdeTexto("tok-pantalla:alberto,tok-movil:alberto")) }
        val http = createClient { }

        val pantalla = AlmacenEnMemoria()
        val movil = AlmacenEnMemoria()
        val syncPantalla = MotorSync(pantalla, TransporteHttp("http://localhost", "tok-pantalla", http), "pantalla")
        val syncMovil = MotorSync(movil, TransporteHttp("http://localhost", "tok-movil", http), "movil")

        // El móvil sincroniza primero: queda "al día" con la hora actual del servidor.
        assertTrue(syncMovil.sincronizar() is ResultadoSync.Ok)

        // La pantalla grabó un viaje hace días, sin internet, y recién ahora encuentra red.
        val haceDias = Instant.parse("2026-10-30T07:15:00Z")
        pantalla.guardar(
            Viaje("v-offline", "veh", haceDias, haceDias, km = 23.4, duracionSeg = 1800, origen = OrigenViaje.OBD, updatedAt = haceDias),
        )
        val subida = syncPantalla.sincronizar()
        assertTrue(subida is ResultadoSync.Ok)
        assertEquals(0, pantalla.cantidadPendientes)

        // El móvil vuelve a sincronizar y debe recibirlo, aunque su updatedAt sea anterior a su último sync.
        val bajada = syncMovil.sincronizar()
        assertTrue(bajada is ResultadoSync.Ok)
        assertEquals(23.4, movil.viajes["v-offline"]?.km)

        // Un token inválido es un fallo recuperable: no se pierde nada.
        movil.guardar(Viaje("v2", "veh", haceDias, haceDias, km = 1.0, duracionSeg = 60, origen = OrigenViaje.OBD, updatedAt = haceDias))
        val malo = MotorSync(movil, TransporteHttp("http://localhost", "tok-falso", http), "movil").sincronizar()
        assertTrue(malo is ResultadoSync.Fallo)
        assertEquals(1, movil.cantidadPendientes)
    }
}
