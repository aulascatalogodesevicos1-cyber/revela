package com.revela.app.model

import org.json.JSONObject

data class Preset(
    val id: String,
    val name: String,
    val params: EditParams,
    val builtIn: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("params", params.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject) = Preset(
            id = o.getString("id"),
            name = o.getString("name"),
            params = EditParams.fromJson(o.getJSONObject("params")),
        )
    }
}

/** Presets que já vêm no app. Os do usuário ficam salvos em presets.json. */
object BuiltInPresets {
    private fun hsl(vararg pairs: Pair<HslColor, HslAdjust>): List<HslAdjust> {
        val list = MutableList(HslColor.entries.size) { HslAdjust() }
        pairs.forEach { (c, a) -> list[c.ordinal] = a }
        return list
    }

    val all: List<Preset> = listOf(
        Preset(
            "builtin_natural", "Natural+",
            EditParams(
                exposure = 0.1f, contrast = 10f, highlights = -30f, shadows = 25f,
                vibrance = 15f, clarity = 10f, sharpen = 25f,
            ), builtIn = true,
        ),
        Preset(
            "builtin_vivid", "Vívido",
            EditParams(contrast = 20f, vibrance = 35f, saturation = 10f, clarity = 15f, dehaze = 10f, sharpen = 20f),
            builtIn = true,
        ),
        Preset(
            "builtin_warm_film", "Filme quente",
            EditParams(
                temperature = 18f, contrast = -10f, highlights = -20f, blacks = 10f, saturation = -8f,
                curveRgb = listOf(CurvePoint(0f, 0.06f), CurvePoint(0.5f, 0.52f), CurvePoint(1f, 0.97f)),
                shadowHue = 200f, shadowSat = 15f, highlightHue = 40f, highlightSat = 20f,
                vignette = -15f, grain = 20f,
            ), builtIn = true,
        ),
        Preset(
            "builtin_cold_urban", "Frio urbano",
            EditParams(
                temperature = -18f, tint = 5f, contrast = 20f, clarity = 20f, saturation = -15f,
                hsl = hsl(
                    HslColor.BLUE to HslAdjust(sat = 15f),
                    HslColor.ORANGE to HslAdjust(sat = -10f),
                    HslColor.AQUA to HslAdjust(hue = 10f),
                ),
                vignette = -20f,
            ), builtIn = true,
        ),
        Preset(
            "builtin_matte", "Matte",
            EditParams(
                contrast = -15f, saturation = -10f, grain = 10f,
                curveRgb = listOf(CurvePoint(0f, 0.10f), CurvePoint(0.5f, 0.52f), CurvePoint(1f, 0.95f)),
            ), builtIn = true,
        ),
        Preset(
            "builtin_portrait", "Retrato suave",
            EditParams(
                exposure = 0.15f, highlights = -20f, shadows = 15f, temperature = 5f, clarity = -10f,
                hsl = hsl(
                    HslColor.ORANGE to HslAdjust(sat = -8f, lum = 10f),
                    HslColor.RED to HslAdjust(sat = -5f),
                ),
                noiseReduction = 20f,
            ), builtIn = true,
        ),
        Preset(
            "builtin_bw_classic", "P&B clássico",
            EditParams(
                blackAndWhite = true, contrast = 25f, highlights = -20f, shadows = 15f,
                clarity = 15f, grain = 15f, vignette = -20f,
            ), builtIn = true,
        ),
        Preset(
            "builtin_bw_soft", "P&B sépia",
            EditParams(
                blackAndWhite = true, contrast = -10f, blacks = 20f,
                highlightHue = 35f, highlightSat = 25f, shadowHue = 30f, shadowSat = 15f, grain = 12f,
            ), builtIn = true,
        ),
    )
}
