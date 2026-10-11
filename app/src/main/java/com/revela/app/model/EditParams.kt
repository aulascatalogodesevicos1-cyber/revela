package com.revela.app.model

import org.json.JSONArray
import org.json.JSONObject

/** As 8 faixas de cor do painel HSL / Cor (como no Lightroom). */
enum class HslColor(val label: String, val hue: Float, val swatch: Long) {
    RED("Vermelho", 0f, 0xFFE5484D),
    ORANGE("Laranja", 30f, 0xFFF76B15),
    YELLOW("Amarelo", 60f, 0xFFFFD60A),
    GREEN("Verde", 120f, 0xFF30A46C),
    AQUA("Água", 180f, 0xFF12A594),
    BLUE("Azul", 225f, 0xFF3E63DD),
    PURPLE("Roxo", 270f, 0xFF8E4EC6),
    MAGENTA("Magenta", 315f, 0xFFD6409F),
}

data class HslAdjust(val hue: Float = 0f, val sat: Float = 0f, val lum: Float = 0f) {
    val isZero: Boolean get() = hue == 0f && sat == 0f && lum == 0f
}

data class CurvePoint(val x: Float, val y: Float)

val IDENTITY_CURVE: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))

enum class CurveChannel(val label: String) { RGB("RGB"), RED("R"), GREEN("G"), BLUE("B") }

/**
 * Todos os ajustes não destrutivos de uma foto. Faixas seguem o Lightroom:
 * exposição em EV (-5..+5), quase todo o resto em -100..+100.
 */
data class EditParams(
    // Luz
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    // Cor
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val hsl: List<HslAdjust> = List(HslColor.entries.size) { HslAdjust() },
    val blackAndWhite: Boolean = false,
    // Gradação de cor (sombras / realces)
    val shadowHue: Float = 220f,
    val shadowSat: Float = 0f,
    val highlightHue: Float = 40f,
    val highlightSat: Float = 0f,
    // Curvas
    val curveRgb: List<CurvePoint> = IDENTITY_CURVE,
    val curveR: List<CurvePoint> = IDENTITY_CURVE,
    val curveG: List<CurvePoint> = IDENTITY_CURVE,
    val curveB: List<CurvePoint> = IDENTITY_CURVE,
    // Efeitos
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    // Detalhe
    val sharpen: Float = 0f,
    val noiseReduction: Float = 0f,
    // Geometria
    val rotation: Int = 0,          // 0, 90, 180, 270
    val flipH: Boolean = false,
    val straighten: Float = 0f,     // graus, -45..45
    val cropAspect: Float = 0f,     // largura/altura; 0 = original
) {
    fun curve(ch: CurveChannel): List<CurvePoint> = when (ch) {
        CurveChannel.RGB -> curveRgb
        CurveChannel.RED -> curveR
        CurveChannel.GREEN -> curveG
        CurveChannel.BLUE -> curveB
    }

    fun withCurve(ch: CurveChannel, pts: List<CurvePoint>): EditParams = when (ch) {
        CurveChannel.RGB -> copy(curveRgb = pts)
        CurveChannel.RED -> copy(curveR = pts)
        CurveChannel.GREEN -> copy(curveG = pts)
        CurveChannel.BLUE -> copy(curveB = pts)
    }

    fun withHsl(index: Int, adj: HslAdjust): EditParams =
        copy(hsl = hsl.toMutableList().also { it[index] = adj })

    /** Mantém a geometria desta foto e aplica o "visual" de outra configuração (preset/colar). */
    fun withLookOf(look: EditParams): EditParams =
        look.copy(rotation = rotation, flipH = flipH, straighten = straighten, cropAspect = cropAspect)

    /** Remove a geometria — usado ao salvar presets. */
    fun lookOnly(): EditParams = copy(rotation = 0, flipH = false, straighten = 0f, cropAspect = 0f)

    val hasGeometry: Boolean
        get() = rotation != 0 || flipH || straighten != 0f || cropAspect != 0f

    fun toJson(): JSONObject = JSONObject().apply {
        put("exposure", exposure.toDouble())
        put("contrast", contrast.toDouble())
        put("highlights", highlights.toDouble())
        put("shadows", shadows.toDouble())
        put("whites", whites.toDouble())
        put("blacks", blacks.toDouble())
        put("temperature", temperature.toDouble())
        put("tint", tint.toDouble())
        put("vibrance", vibrance.toDouble())
        put("saturation", saturation.toDouble())
        put("hsl", JSONArray().apply {
            hsl.forEach { put(JSONArray().put(it.hue.toDouble()).put(it.sat.toDouble()).put(it.lum.toDouble())) }
        })
        put("blackAndWhite", blackAndWhite)
        put("shadowHue", shadowHue.toDouble())
        put("shadowSat", shadowSat.toDouble())
        put("highlightHue", highlightHue.toDouble())
        put("highlightSat", highlightSat.toDouble())
        put("curveRgb", curveToJson(curveRgb))
        put("curveR", curveToJson(curveR))
        put("curveG", curveToJson(curveG))
        put("curveB", curveToJson(curveB))
        put("clarity", clarity.toDouble())
        put("dehaze", dehaze.toDouble())
        put("vignette", vignette.toDouble())
        put("grain", grain.toDouble())
        put("sharpen", sharpen.toDouble())
        put("noiseReduction", noiseReduction.toDouble())
        put("rotation", rotation)
        put("flipH", flipH)
        put("straighten", straighten.toDouble())
        put("cropAspect", cropAspect.toDouble())
    }

    companion object {
        private fun curveToJson(c: List<CurvePoint>) = JSONArray().apply {
            c.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
        }

        private fun curveFromJson(a: JSONArray?): List<CurvePoint> {
            if (a == null || a.length() < 2) return IDENTITY_CURVE
            return (0 until a.length()).map {
                val p = a.getJSONArray(it)
                CurvePoint(p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
            }
        }

        fun fromJson(o: JSONObject): EditParams {
            fun f(k: String, d: Float = 0f) = o.optDouble(k, d.toDouble()).toFloat()
            val hslArr = o.optJSONArray("hsl")
            val hsl = List(HslColor.entries.size) { i ->
                val e = hslArr?.optJSONArray(i)
                if (e == null) HslAdjust()
                else HslAdjust(e.getDouble(0).toFloat(), e.getDouble(1).toFloat(), e.getDouble(2).toFloat())
            }
            return EditParams(
                exposure = f("exposure"), contrast = f("contrast"), highlights = f("highlights"),
                shadows = f("shadows"), whites = f("whites"), blacks = f("blacks"),
                temperature = f("temperature"), tint = f("tint"), vibrance = f("vibrance"),
                saturation = f("saturation"), hsl = hsl,
                blackAndWhite = o.optBoolean("blackAndWhite", false),
                shadowHue = f("shadowHue", 220f), shadowSat = f("shadowSat"),
                highlightHue = f("highlightHue", 40f), highlightSat = f("highlightSat"),
                curveRgb = curveFromJson(o.optJSONArray("curveRgb")),
                curveR = curveFromJson(o.optJSONArray("curveR")),
                curveG = curveFromJson(o.optJSONArray("curveG")),
                curveB = curveFromJson(o.optJSONArray("curveB")),
                clarity = f("clarity"), dehaze = f("dehaze"), vignette = f("vignette"), grain = f("grain"),
                sharpen = f("sharpen"), noiseReduction = f("noiseReduction"),
                rotation = o.optInt("rotation", 0), flipH = o.optBoolean("flipH", false),
                straighten = f("straighten"), cropAspect = f("cropAspect"),
            )
        }
    }
}
