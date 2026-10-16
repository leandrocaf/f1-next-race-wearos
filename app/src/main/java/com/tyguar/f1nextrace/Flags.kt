package com.tyguar.f1nextrace

import androidx.annotation.DrawableRes
import java.util.Locale

// País (valor da API) -> bandeira em res/drawable-nodpi
@DrawableRes
fun flagFor(country: String?): Int? = when (country) {
    "Italy" -> R.drawable.flag_it
    "France" -> R.drawable.flag_fr
    "Netherlands" -> R.drawable.flag_nl
    "Belgium" -> R.drawable.flag_be
    "Germany" -> R.drawable.flag_de
    "Austria" -> R.drawable.flag_at
    "Hungary" -> R.drawable.flag_hu
    "Monaco" -> R.drawable.flag_mc
    "Mexico" -> R.drawable.flag_mx
    "Azerbaijan" -> R.drawable.flag_az
    "Singapore" -> R.drawable.flag_sg
    "Spain" -> R.drawable.flag_es
    "Saudi Arabia" -> R.drawable.flag_sa
    "Qatar" -> R.drawable.flag_qa
    "Bahrain" -> R.drawable.flag_bh
    "UAE", "United Arab Emirates" -> R.drawable.flag_ae
    "Japan" -> R.drawable.flag_jp
    "China" -> R.drawable.flag_cn
    "Brazil" -> R.drawable.flag_br
    "Canada" -> R.drawable.flag_ca
    "UK", "United Kingdom" -> R.drawable.flag_gb
    "USA", "United States" -> R.drawable.flag_us
    "Australia" -> R.drawable.flag_au
    else -> null
}

// Países sem PNG (ex.: Malaysia): bandeira emoji a partir do código ISO,
// descoberto pelo nome em inglês que a API devolve
private val ALIASES = mapOf("UK" to "GB", "USA" to "US", "UAE" to "AE")

private val ISO_BY_NAME: Map<String, String> by lazy {
    Locale.getISOCountries().associateBy {
        Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH).lowercase()
    }
}

private fun isoFor(country: String?): String? {
    if (country.isNullOrBlank()) return null
    return ALIASES[country] ?: ISO_BY_NAME[country.lowercase()]
}

fun flagEmoji(country: String?): String? {
    val iso = isoFor(country) ?: return null
    // Cada letra vira um "regional indicator symbol" (A = U+1F1E6)
    return iso.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

// Nome do país: traduzido só no pt-BR (único idioma além do inglês suportado,
// ver res/values-pt-rBR); nos demais idiomas fica o inglês da API
fun localizedCountry(country: String?, locale: Locale): String? {
    if (locale.language != "pt" || locale.country != "BR") return country
    val iso = isoFor(country) ?: return country
    return Locale.Builder().setRegion(iso).build().getDisplayCountry(locale).ifEmpty { country }
}

// Nomes de GP em pt-BR (a API só devolve inglês, "<Nome> GP" depois da abreviação).
// Chave = nome sem o sufixo " GP"; o que não estiver aqui fica em inglês.
private val RACE_NAMES_PT = mapOf(
    "Australian" to "GP da Austrália",
    "Chinese" to "GP da China",
    "Japanese" to "GP do Japão",
    "Bahrain" to "GP do Bahrein",
    "Saudi Arabian" to "GP da Arábia Saudita",
    "Miami" to "GP de Miami",
    "Emilia Romagna" to "GP da Emília-Romanha",
    "Monaco" to "GP de Mônaco",
    "Spanish" to "GP da Espanha",
    "Canadian" to "GP do Canadá",
    "Austrian" to "GP da Áustria",
    "British" to "GP da Grã-Bretanha",
    "Belgian" to "GP da Bélgica",
    "Hungarian" to "GP da Hungria",
    "Dutch" to "GP da Holanda",
    "Italian" to "GP da Itália",
    "Azerbaijan" to "GP do Azerbaijão",
    "Singapore" to "GP de Singapura",
    "United States" to "GP dos Estados Unidos",
    "Mexico City" to "GP da Cidade do México",
    "Mexican" to "GP do México",
    "Brazilian" to "GP do Brasil",
    "São Paulo" to "GP de São Paulo",
    "Sao Paulo" to "GP de São Paulo",
    "Las Vegas" to "GP de Las Vegas",
    "Qatar" to "GP do Catar",
    "Abu Dhabi" to "GP de Abu Dhabi",
    "Malaysian" to "GP da Malásia",
)

fun localizedRaceName(name: String, locale: Locale): String {
    if (locale.language != "pt" || locale.country != "BR") return name
    return RACE_NAMES_PT[name.removeSuffix(" GP")] ?: name
}
