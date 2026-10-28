package com.omarchyconnect.app.core

import org.json.JSONObject

/**
 * Thème Omarchy synchronisé depuis le PC (le PC lit ~/.config omarchy/wm et
 * les couleurs du terminal pour les renvoyer au téléphone).
 *
 * Toutes les couleurs sont des Int ARGB, écrites en hexadécimal (#rrggbb) sur le fil.
 */
data class OcTheme(
    val name: String = "omarchy",
    val background: Int = 0xFF0E1116.toInt(),
    val surface: Int = 0xFF10151C.toInt(),
    val surfaceAlt: Int = 0xFF16222B.toInt(),
    val border: Int = 0xFF2A3A46.toInt(),
    val borderAccent: Int = 0xFF3E6E74.toInt(),
    val accent: Int = 0xFFA8E6CF.toInt(),
    val primary: Int = 0xFFBFE3FF.toInt(),
    val text: Int = 0xFFDDE6EE.toInt(),
    val muted: Int = 0xFF7C8A99.toInt(),
    val danger: Int = 0xFFE88C8C.toInt()
) {
    companion object {
        val Default = OcTheme()

        /** Palette par défaut, dans l'esprit Omarchy (sombre, contours colorés, accent menthe). */
        fun fromJson(raw: String?): OcTheme {
            if (raw.isNullOrBlank()) return Default
            return try {
                val root = JSONObject(raw)
                val c = if (root.has("c")) root.getJSONObject("c") else root
                fun col(key: String, def: Int): Int {
                    val v = c.optString(key, "")
                    return if (v.length in 6..7) parseColor(v) else def
                }
                Default.copy(
                    name = root.optString("name", Default.name),
                    background = col("bg", Default.background),
                    surface = col("surface", Default.surface),
                    surfaceAlt = col("surfaceAlt", Default.surfaceAlt),
                    border = col("border", Default.border),
                    borderAccent = col("borderAccent", Default.borderAccent),
                    accent = col("accent", Default.accent),
                    primary = col("primary", Default.primary),
                    text = col("text", Default.text),
                    muted = col("muted", Default.muted),
                    danger = col("danger", Default.danger)
                )
            } catch (_: Throwable) {
                Default
            }
        }

        /** Accepte "abc" / "#abc" / "#aabbcc". */
        fun parseColor(hex: String): Int {
            var h = hex.trim().removePrefix("#")
            if (h.length == 3) h = h + h
            val v = h.toLong(16) or 0xFF000000
            return v.toInt()
        }
    }
}
