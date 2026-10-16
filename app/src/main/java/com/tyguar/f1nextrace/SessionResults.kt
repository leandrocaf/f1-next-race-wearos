package com.tyguar.f1nextrace

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

// Avisa quando uma sessão termina e os resultados saem; tocar na notificação abre a aba
// de resultados. A API não informa o fim das sessões, então usamos uma duração estimada
// e consultamos os resultados (com novas tentativas) até eles serem publicados.
// Só classificação, sprint e corrida têm resultado na API.
object SessionResults {
    const val TAG = "session-results"
    const val EXTRA_DESTINATION = "destination"
    const val DEST_RESULTS = "Results"

    private const val CHANNEL_ID = "session-results"
    private const val KEY_LAST = "last-results-notified"
    private const val DATA_ROUND = "round"
    private const val DATA_LABEL = "label"
    private const val DATA_START = "start"

    // desiste de procurar resultados tanto tempo depois do fim estimado
    internal val GIVE_UP: Duration = Duration.ofHours(8)

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    // duração estimada; null = sessão sem resultado na API
    internal fun duration(label: Int): Duration? = when (label) {
        R.string.session_quali -> Duration.ofMinutes(60)
        R.string.session_sprint -> Duration.ofMinutes(60)
        R.string.session_race -> Duration.ofMinutes(120)
        else -> null
    }

    // Agenda a busca de resultados da próxima sessão que ainda vai terminar (ou terminou
    // há pouco) e ainda não foi avisada
    fun plan(context: Context, season: List<Race>) {
        if (!SessionReminder.isEnabled(context)) return
        val now = Instant.now()
        val last = SessionReminder.prefs(context).getString(KEY_LAST, null)
        val next = season.asSequence()
            .flatMap { race -> race.sessions.asSequence().map { race to it } }
            .mapNotNull { (race, session) ->
                val start = session.start ?: return@mapNotNull null
                val end = start.plus(duration(session.label) ?: return@mapNotNull null)
                Triple(race, session, end).takeIf { end.isAfter(now.minus(GIVE_UP)) && start.toString() != last }
            }
            .firstOrNull() ?: return
        val (race, session, end) = next
        val start = session.start ?: return

        val delay = Duration.between(now, end).coerceAtLeast(Duration.ZERO)
        val data = Data.Builder()
            .putString(DATA_ROUND, race.round)
            .putString(DATA_LABEL, context.resources.getResourceEntryName(session.label))
            .putLong(DATA_START, start.toEpochMilli())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "results-${start.toEpochMilli()}",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ResultsWorker>()
                .setConstraints(network)
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .setInputData(data)
                .addTag(TAG)
                .build(),
        )
    }

    internal fun notify(context: Context, result: SessionResult, start: Instant) {
        if (!SessionReminder.isEnabled(context) || !SessionReminder.canNotify(context)) return
        val prefs = SessionReminder.prefs(context)
        if (prefs.getString(KEY_LAST, null) == start.toString()) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.results_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val locale = context.resources.configuration.locales[0]
        val podium = result.rows.take(3).joinToString(", ") { "${it.position}. ${it.name}" }
        // abre direto na aba de resultados (código de requisição distinto do lembrete)
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_DESTINATION, DEST_RESULTS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_results)
            .setContentTitle(localizedRaceName(result.raceName, locale))
            .setContentText(context.getString(R.string.results_text, context.getString(result.label), podium))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(("results" + start).hashCode(), notification)
        prefs.edit().putString(KEY_LAST, start.toString()).apply()
    }

    internal const val ROUND_KEY = DATA_ROUND
    internal const val LABEL_KEY = DATA_LABEL
    internal const val START_KEY = DATA_START
}

class ResultsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val round = inputData.getString(SessionResults.ROUND_KEY) ?: return Result.failure()
        val label = inputData.getString(SessionResults.LABEL_KEY)
            ?.let { SessionReminder.labelFromName(applicationContext, it) }
            ?.takeIf { it != 0 } ?: return Result.failure()
        val start = Instant.ofEpochMilli(inputData.getLong(SessionResults.START_KEY, 0))
        val end = start.plus(SessionResults.duration(label) ?: return Result.failure())

        if (!SessionReminder.isEnabled(applicationContext)) return Result.success()
        // muito depois do fim e nada publicado: desiste
        if (Instant.now().isAfter(end.plus(SessionResults.GIVE_UP))) return Result.success()

        val result = try {
            F1Api.resultFor(round, label)
        } catch (e: Exception) {
            return Result.retry()
        } ?: return Result.retry() // resultados ainda não publicados: tenta de novo

        SessionResults.notify(applicationContext, result, start)
        return Result.success()
    }
}
