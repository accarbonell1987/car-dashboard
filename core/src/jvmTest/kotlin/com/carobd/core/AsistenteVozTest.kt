package com.carobd.core

import com.carobd.core.tablero.Alertas
import com.carobd.core.voz.AccionAsistente
import com.carobd.core.voz.ContextoAuto
import com.carobd.core.voz.EventoRealtime
import com.carobd.core.voz.FuncionesAuto
import com.carobd.core.voz.Realtime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsistenteVozTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `la sesion pide audio pcm a 24 kHz, voz y las funciones del auto`() {
        val s = obj(Realtime.configurarSesion("hola", FuncionesAuto.lista))["session"]!!.jsonObject
        assertEquals("realtime", s["type"]!!.jsonPrimitive.content)
        val audio = s["audio"]!!.jsonObject
        val entrada = audio["input"]!!.jsonObject
        assertEquals("audio/pcm", entrada["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(24000, entrada["format"]!!.jsonObject["rate"]!!.jsonPrimitive.int)
        assertEquals("false", entrada["turn_detection"]!!.jsonObject["interrupt_response"]!!.jsonPrimitive.content)
        assertEquals(Realtime.VOZ, audio["output"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        val nombres = s["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("leer_datos_auto", "consultar_mantenimiento", "navegar_a", "terminar_conversacion"), nombres)
    }

    @Test
    fun `el audio va y vuelve en base64`() {
        val pcm = byteArrayOf(1, 2, 3, 4, -5)
        val enviado = obj(Realtime.audio(pcm))
        assertEquals("input_audio_buffer.append", enviado["type"]!!.jsonPrimitive.content)
        val b64 = enviado["audio"]!!.jsonPrimitive.content
        val ev = Realtime.leer("""{"type":"response.output_audio.delta","delta":"$b64"}""").single() as EventoRealtime.Audio
        assertArrayEquals(pcm, ev.pcm)
    }

    @Test
    fun `las funciones llegan en response done`() {
        val ev = Realtime.leer(
            """{"type":"response.done","response":{"status":"completed","output":[
              {"type":"message","content":[]},
              {"type":"function_call","status":"completed","name":"navegar_a","call_id":"c1","arguments":"{\"destino\":\"Shopping del Sol\"}"}]}}""",
        )
        assertEquals(EventoRealtime.LlamaFuncion("c1", "navegar_a", """{"destino":"Shopping del Sol"}"""), ev[0])
        assertEquals(EventoRealtime.RespuestaTerminada("completed", true), ev[1])
    }

    @Test
    fun `transcripciones, errores y mensajes desconocidos`() {
        assertEquals(listOf(EventoRealtime.DijoElUsuario("qué temperatura tiene")),
            Realtime.leer("""{"type":"conversation.item.input_audio_transcription.completed","transcript":" qué temperatura tiene "}"""))
        assertEquals(listOf(EventoRealtime.DiceElAsistente("90 grados")),
            Realtime.leer("""{"type":"response.output_audio_transcript.done","transcript":"90 grados"}"""))
        assertEquals(listOf(EventoRealtime.Error("Incorrect API key")),
            Realtime.leer("""{"type":"error","error":{"type":"invalid_request_error","message":"Incorrect API key"}}"""))
        assertTrue(Realtime.leer("""{"type":"rate_limits.updated"}""").isEmpty())
        assertTrue(Realtime.leer("no es json").isEmpty())
    }

    private val contexto = ContextoAuto(
        fuente = "auto",
        valores = mapOf("temp_refrigerante" to 114.04, "rpm" to 850.0, "estado_interno" to 3.0),
        alertas = Alertas.evaluar(mapOf("temp_refrigerante" to 114.04)),
        odometroKm = 91234.6,
        viajeKm = 12.34, viajeSeg = 1500,
    )

    @Test
    fun `leer datos trae lecturas con nombre, alertas y viaje`() {
        val r = obj(FuncionesAuto.ejecutar(FuncionesAuto.LEER_DATOS, "{}", contexto).salida)
        val lecturas = r["lecturas"]!!.jsonArray.map { it.jsonObject["nombre"]!!.jsonPrimitive.content }
        assertEquals(listOf("RPM", "Temp. refrigerante"), lecturas)
        val alerta = r["alertas"]!!.jsonArray.single().jsonObject
        assertEquals("critico", alerta["nivel"]!!.jsonPrimitive.content)
        assertEquals("114.0", alerta["valor"]!!.jsonPrimitive.content)
        assertEquals(91235, r["odometro_km"]!!.jsonPrimitive.int)
        assertEquals(25, r["viaje_en_curso"]!!.jsonObject["minutos"]!!.jsonPrimitive.int)
    }

    @Test
    fun `navegar pide abrir el mapa y sin destino no hace nada`() {
        val r = FuncionesAuto.ejecutar(FuncionesAuto.NAVEGAR, """{"destino":"Aeropuerto Silvio Pettirossi","app":"waze"}""", contexto)
        assertEquals(AccionAsistente.Navegar("Aeropuerto Silvio Pettirossi", "waze"), r.accion)
        assertEquals(null, FuncionesAuto.ejecutar(FuncionesAuto.NAVEGAR, """{"destino":"  "}""", contexto).accion)
        assertEquals(AccionAsistente.Terminar, FuncionesAuto.ejecutar(FuncionesAuto.TERMINAR, "{}", contexto).accion)
    }

    @Test
    fun `sin plan de mantenimiento lo dice`() {
        val r = obj(FuncionesAuto.ejecutar(FuncionesAuto.MANTENIMIENTO, "{}", contexto).salida)
        assertTrue(r.containsKey("sin_datos"))
    }
}

class PcmTest {
    @Test
    fun `bytes little endian`() {
        assertArrayEquals(byteArrayOf(0x34, 0x12, -1, -1), com.carobd.core.voz.Pcm.aBytes(shortArrayOf(0x1234, -1)))
    }

    @Test
    fun `de 48 kHz a 24 kHz queda la mitad y de 16 kHz a 24 kHz interpola`() {
        val Pcm = com.carobd.core.voz.Pcm
        assertEquals(240, Pcm.convertir(ShortArray(480), 480, 48_000, 24_000).size)
        val subida = Pcm.convertir(shortArrayOf(0, 300), 2, 16_000, 24_000)
        assertEquals(listOf<Short>(0, 200, 300), subida.toList())
    }
}
