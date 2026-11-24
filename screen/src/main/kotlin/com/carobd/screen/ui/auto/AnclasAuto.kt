package com.carobd.screen.ui.auto

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.min

/**
 * Where each lamp is on the car photo, as fractions of the image (0,0 top left; 1,1 bottom right).
 *
 * This is the ONLY place that knows the picture. Changing the photo or its angle means: swap the drawable,
 * update [proporcion], and move these points (open the image in any editor, divide the pixel by the width
 * or height). The animation layers never hard-code a position.
 *
 * "Izq"/"Der" are the car's own left and right (left = driver's side). Lamps that the angle hides are null.
 */
data class AnclasFoto(
    /** Width / height of the image in pixels. */
    val proporcion: Float,
    /** Share of the free space the photo takes (the rest is margin for beams, rain and wind). */
    val escala: Float = 0.94f,
    /** Projector of each headlight (low and high beam). */
    val faroIzq: Offset?,
    val faroDer: Offset?,
    /** The position/DRL strip along the bottom of each headlight lens, from inner to outer end. */
    val posicionIzq: Pair<Offset, Offset>?,
    val posicionDer: Pair<Offset, Offset>?,
    /** Amber turn signal inside each headlight unit. */
    val giroDelanteroIzq: Offset?,
    val giroDelanteroDer: Offset?,
    /** Tail lamps (position red, brake red, and their turn section). */
    val traseraIzq: Offset?,
    val traseraDer: Offset?,
    val giroTraseroIzq: Offset?,
    val giroTraseroDer: Offset?,
    /** Fog lamps in the bumper; only lit as a faint fill with the low beam. */
    val antinieblaIzq: Offset?,
    val antinieblaDer: Offset?,
    /** Unit vector, in image pixels, pointing where the car's nose points (the beams go this way). */
    val haciaAdelante: Offset,
    /** Point on the ground right behind the rear bumper, for the red brake reflection. */
    val pisoTrasero: Offset,
    /** Lines (pairs of points) on the body and ground where raindrops splash: roof, bonnet, boot, ground. */
    val superficiesLluvia: List<Pair<Offset, Offset>>,
) {
    val faros = listOfNotNull(faroIzq, faroDer)
    val traseras = listOfNotNull(traseraIzq, traseraDer)

    /** The photo's rectangle inside a box of [size]: fitted, scaled by [escala] and centred, as FotoAuto draws it. */
    fun rectFoto(size: Size): Rect {
        val ancho = min(size.width, size.height * proporcion) * escala
        val alto = ancho / proporcion
        return Rect(Offset((size.width - ancho) / 2f, (size.height - alto) / 2f), Size(ancho, alto))
    }
}

/** A fraction of the photo to a point in the box. */
internal fun Rect.en(f: Offset) = Offset(left + f.x * width, top + f.y * height)

/**
 * drawable-nodpi/accent.webp (900x429): front three-quarter from the car's LEFT side, nose to the left.
 * The near headlight is the car's left one; the right one is the sliver at the far left edge. Only the left tail
 * lamp's wrap-around is visible, at the right edge. Positions checked against the owner's photos of the car
 * (headlight with the LED strip along its lower edge, amber signal in the upper outer corner, fog lamp low in
 * the bumper, tail lamps wrapping onto the rear quarter).
 */
val ANCLAS_ACCENT = AnclasFoto(
    proporcion = 900f / 429f,
    faroIzq = Offset(0.333f, 0.556f),
    faroDer = Offset(0.036f, 0.541f),
    posicionIzq = Offset(0.247f, 0.622f) to Offset(0.392f, 0.505f),
    posicionDer = Offset(0.022f, 0.590f) to Offset(0.050f, 0.505f),
    giroDelanteroIzq = Offset(0.381f, 0.496f),
    giroDelanteroDer = Offset(0.044f, 0.494f),
    traseraIzq = Offset(0.982f, 0.431f),
    traseraDer = null,
    giroTraseroIzq = Offset(0.986f, 0.452f),
    giroTraseroDer = null,
    antinieblaIzq = Offset(0.300f, 0.793f),
    antinieblaDer = null,
    // From the rear wheel hub (815,255) to the front one (410,330), turned a little towards the viewer.
    haciaAdelante = Offset(-0.94f, 0.34f),
    pisoTrasero = Offset(0.975f, 0.690f),
    superficiesLluvia = listOf(
        Offset(0.43f, 0.045f) to Offset(0.80f, 0.035f), // roof
        Offset(0.10f, 0.47f) to Offset(0.40f, 0.36f), // bonnet
        Offset(0.86f, 0.24f) to Offset(0.97f, 0.30f), // boot lid
        Offset(0.02f, 0.96f) to Offset(0.98f, 0.82f), // ground along the car
    ),
)
