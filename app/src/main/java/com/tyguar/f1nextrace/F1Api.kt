package com.tyguar.f1nextrace

import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

// Mesma API da versão Zepp OS. No Wear OS o relógio acessa a internet
// direto (Wi-Fi/LTE ou pelo celular), sem side service.
private const val NEXT_RACE_URL = "https://api.jolpi.ca/ergast/f1/current/next.json"
private const val SEASON_URL = "https://api.jolpi.ca/ergast/f1/current.json"
private const val DRIVERS_URL = "https://api.jolpi.ca/ergast/f1/current/driverStandings.json"
private const val LAST_RESULTS_URL = "https://api.jolpi.ca/ergast/f1/current/last/results.json"
private const val CONSTRUCTORS_URL = "https://api.jolpi.ca/ergast/f1/current/constructorStandings.json"

// Sessões do fim de semana (chave da API -> rótulo)
private val SESSIONS = listOf(
    "FirstPractice" to R.string.session_fp1,
    "SecondPractice" to R.string.session_fp2,
    "ThirdPractice" to R.string.session_fp3,
    "SprintQualifying" to R.string.session_sprint_quali,
    "SprintShootout" to R.string.session_sprint_quali,
    "Sprint" to R.string.session_sprint,
    "Qualifying" to R.string.session_quali,
)

data class Session(
    @StringRes val label: Int,
    val date: String,
    val time: String?,
    // null se data/hora não puderem ser interpretadas
    val start: Instant?,
)

data class Race(
    val round: String,
    val name: String,
    val locality: String?,
    val country: String?,
    val date: String,
    val time: String?,
    val start: Instant?,
    // Sessões + corrida, em ordem cronológica
    val sessions: List<Session>,
)

// Linha da classificação (pilotos ou construtores)
data class Standing(
    val position: String,
    val name: String,
    // equipe do piloto (null para construtores)
    val team: String?,
    val points: String,
)

// Linha de resultado de uma sessão: posição, piloto e tempo/diferença/status
data class ResultRow(val position: String, val name: String, val detail: String)

data class SessionResult(
    @StringRes val label: Int,
    val raceName: String,
    val rows: List<ResultRow>,
)

object F1Api {
    suspend fun nextRace(): Race? = races(NEXT_RACE_URL).firstOrNull()

    suspend fun season(): List<Race> = races(SEASON_URL)

    suspend fun driverStandings(): List<Standing> = standings(DRIVERS_URL, "DriverStandings") { o ->
        val d = o.optJSONObject("Driver")
        Standing(
            position = o.optString("position"),
            name = d?.optStringOrNull("code") ?: d?.optString("familyName").orEmpty(),
            team = o.optJSONArray("Constructors")?.optJSONObject(0)?.optStringOrNull("name"),
            points = o.optString("points"),
        )
    }

    suspend fun constructorStandings(): List<Standing> =
        standings(CONSTRUCTORS_URL, "ConstructorStandings") { o ->
            Standing(
                position = o.optString("position"),
                name = o.optJSONObject("Constructor")?.optString("name").orEmpty(),
                team = null,
                points = o.optString("points"),
            )
        }

    // Resultado de uma sessão de um fim de semana (rodada). Só classificação, sprint e
    // corrida têm resultado na API; null se ainda não foi publicado.
    suspend fun resultFor(round: String, @StringRes label: Int): SessionResult? {
        val path = when (label) {
            R.string.session_quali -> "qualifying"
            R.string.session_sprint -> "sprint"
            else -> "results"
        }
        return sessionResult("https://api.jolpi.ca/ergast/f1/current/$round/$path.json", label)
    }

    // Resultado da sessão mais recente: classificação/sprint/corrida do fim de semana
    // atual se já aconteceram; senão, a última corrida
    suspend fun latestResult(): SessionResult? {
        val now = Instant.now()
        val race = nextRace()
        if (race != null) {
            val started = race.sessions
                .filter { it.start != null && !it.start.isAfter(now) }
                .filter {
                    it.label == R.string.session_quali || it.label == R.string.session_sprint ||
                        it.label == R.string.session_race
                }
                .sortedByDescending { it.start }
            for (session in started) resultFor(race.round, session.label)?.let { return it }
        }
        return sessionResult(LAST_RESULTS_URL, R.string.session_race)
    }

    private suspend fun sessionResult(url: String, @StringRes label: Int): SessionResult? {
        val race = JSONObject(get(url))
            .optJSONObject("MRData")
            ?.optJSONObject("RaceTable")
            ?.optJSONArray("Races")
            ?.optJSONObject(0)
            ?: return null
        val arr = race.optJSONArray("Results")
            ?: race.optJSONArray("SprintResults")
            ?: race.optJSONArray("QualifyingResults")
            ?: return null
        if (arr.length() == 0) return null
        val rows = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val d = o.optJSONObject("Driver")
            ResultRow(
                position = o.optString("position"),
                name = d?.optStringOrNull("code") ?: d?.optString("familyName").orEmpty(),
                // corrida/sprint: tempo (ou diferença); classificação: melhor Q; senão status
                detail = o.optJSONObject("Time")?.optStringOrNull("time")
                    ?: o.optStringOrNull("Q3") ?: o.optStringOrNull("Q2") ?: o.optStringOrNull("Q1")
                    ?: o.optStringOrNull("status").orEmpty(),
            )
        }
        return SessionResult(label, shortName(race.optString("raceName")), rows)
    }

    private suspend fun standings(url: String, key: String, parse: (JSONObject) -> Standing): List<Standing> {
        val arr = JSONObject(get(url))
            .optJSONObject("MRData")
            ?.optJSONObject("StandingsTable")
            ?.optJSONArray("StandingsLists")
            ?.optJSONObject(0)
            ?.optJSONArray(key)
            ?: return emptyList()
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    private suspend fun races(url: String): List<Race> {
        val body = get(url)
        val arr = JSONObject(body)
            .optJSONObject("MRData")
            ?.optJSONObject("RaceTable")
            ?.optJSONArray("Races")
            ?: return emptyList()
        return (0 until arr.length()).map { parseRace(arr.getJSONObject(it)) }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRace(o: JSONObject): Race {
        val loc = o.optJSONObject("Circuit")?.optJSONObject("Location")
        val date = o.optString("date")
        val time = o.optStringOrNull("time")

        val sessions = SESSIONS.mapNotNull { (key, label) ->
            val s = o.optJSONObject(key) ?: return@mapNotNull null
            val d = s.optStringOrNull("date") ?: return@mapNotNull null
            val t = s.optStringOrNull("time")
            Session(label, d, t, parseInstant(d, t))
        } + Session(R.string.session_race, date, time, parseInstant(date, time))

        return Race(
            round = o.optString("round"),
            name = shortName(o.optString("raceName")),
            locality = loc?.optStringOrNull("locality"),
            country = loc?.optStringOrNull("country"),
            date = date,
            time = time,
            start = parseInstant(date, time),
            sessions = sessions.sortedBy { it.start ?: Instant.EPOCH },
        )
    }

    private fun shortName(raceName: String) =
        raceName.replace(Regex("Grand Prix", RegexOption.IGNORE_CASE), "GP")

    // Data (YYYY-MM-DD) + hora UTC (HH:MM:SSZ) -> Instant
    private fun parseInstant(date: String, time: String?): Instant? =
        runCatching { Instant.parse(date + "T" + (time ?: "00:00:00Z")) }.getOrNull()

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null
}
