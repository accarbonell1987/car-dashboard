package com.carobd.server

/**
 * Tokens por dispositivo. En v1 se definen en la variable CAROBD_TOKENS con el formato
 * `token1:usuarioA,token2:usuarioA,token3:usuarioB`. En v2 pasan a la base con registro de usuarios.
 */
class Tokens(private val mapa: Map<String, String>) {
    fun usuarioDe(token: String): String? = mapa[token]

    companion object {
        fun desdeEntorno(): Tokens = desdeTexto(System.getenv("CAROBD_TOKENS") ?: "")

        fun desdeTexto(texto: String): Tokens = Tokens(
            texto.split(",").mapNotNull { par ->
                val (token, usuario) = par.trim().split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
                token to usuario
            }.toMap(),
        )
    }
}
