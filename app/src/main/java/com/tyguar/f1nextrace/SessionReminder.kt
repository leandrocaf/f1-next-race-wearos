package com.tyguar.f1nextrace

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

// Avisa o usuário antes da próxima sessão (1 hora ou 15 minutos, configurável).
//
// - SessionCheckWorker roda de tempos em tempos (e uma vez ao abrir o app): busca a
//   próxima sessão na API e agenda o SessionNotifyWorker para (início - antecedência).
// - SessionNotifyWorker posta a notificação. Se a sessão já está dentro da antecedência quando a
//   verificação roda, a notificação sai na hora.
// A sessão já avisada fica guardada para não notificar duas vezes.
object SessionReminder {
    // opções de antecedência do aviso (minutos) e padrão
    val LEAD_OPTIONS = listOf(60, 15)
    private const val DEFAULT_LEAD_MINUTES = 15
    private const val KEY_LEAD = "lead-minutes"

    fun leadMinutes(context: Context) = prefs(context).getInt(KEY_LEAD, DEFAULT_LEAD_MINUTES)

    // Muda a antecedência: libera um novo aviso da mesma sessão e reagenda
    fun setLeadMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_LEAD, minutes).remove(KEY_LAST_NOTIFIED).apply()
        schedule(context)
    }

    private fun lead(context: Context) = Duration.ofMinutes(leadMinutes(context).toLong())

    private const val PERIODIC_CHECK = "session-check"
    private const val IMMEDIATE_CHECK = "session-check-now"
    private const val NOTIFY = "session-notify"
    private const val CHANNEL_ID = "session-reminders"
    private const val PREFS = "session-reminder"
    private const val KEY_LAST_NOTIFIED = "last-notified"
    private const val KEY_ENABLED = "enabled"

    private const val DATA_RACE = "race"
    private const val DATA_LABEL = "label"
    private const val DATA_START = "start"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    // Liga/desliga os lembretes; desligar cancela tudo que estava agendado
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) schedule(context) else cancel(context)
    }

    private fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC_CHECK)
        wm.cancelUniqueWork(IMMEDIATE_CHECK)
        wm.cancelUniqueWork(NOTIFY)
        wm.cancelAllWorkByTag(SessionResults.TAG)
    }

    // Chamado ao abrir o app: verifica agora e mantém a verificação periódica
    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(
            IMMEDIATE_CHECK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SessionCheckWorker>().setConstraints(network).build(),
        )
        wm.enqueueUniquePeriodicWork(
            PERIODIC_CHECK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SessionCheckWorker>(3, TimeUnit.HOURS)
                .setConstraints(network)
                .build(),
        )
    }

    // Agenda (ou dispara agora) o aviso da sessão
    internal fun plan(context: Context, race: Race, session: Session) {
        val start = session.start ?: return
        if (start.toString() == lastNotified(context)) return
        val delay = Duration.between(Instant.now(), start.minus(lead(context)))
        if (delay.isNegative || delay.isZero) {
            notify(context, race.name, session.label, start)
            return
        }
        val data = Data.Builder()
            .putString(DATA_RACE, race.name)
            .putString(DATA_LABEL, context.resources.getResourceEntryName(session.label))
            .putLong(DATA_START, start.toEpochMilli())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOTIFY,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SessionNotifyWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build(),
        )
    }

    internal fun notify(context: Context, raceName: String, labelRes: Int, start: Instant) {
        val remaining = Duration.between(Instant.now(), start)
        // sessão já começou (ex.: trabalho adiado pelo sistema): não avisa
        if (remaining.isNegative || remaining.isZero) return
        if (start.toString() == lastNotified(context)) return
        if (!isEnabled(context) || !canNotify(context)) return

        val res = context.resources
        val locale = res.configuration.locales[0]
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.reminder_channel_desc) },
        )

        val local = start.atZone(ZoneId.systemDefault())
        val whenText = res.getStringArray(R.array.weekdays)[local.dayOfWeek.value % 7] + " " +
            local.format(DateTimeFormatter.ofPattern("HH:mm", locale))
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_race)
            .setContentTitle(localizedRaceName(raceName, locale))
            .setContentText(
                context.getString(
                    R.string.reminder_text,
                    context.getString(labelRes),
                    remainingText(context, remaining),
                    whenText,
                ),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(start.hashCode(), notification)
        prefs(context).edit().putString(KEY_LAST_NOTIFIED, start.toString()).apply()
    }

    // "1h", "45min", "1h 5min"
    private fun remainingText(context: Context, d: Duration): String {
        val hours = d.toHours().toInt()
        val minutes = (d.toMinutes() % 60).toInt()
        return buildList {
            if (hours > 0) add(context.getString(R.string.unit_hour, hours))
            if (minutes > 0 || hours == 0) add(context.getString(R.string.unit_minute, minutes))
        }.joinToString(" ")
    }

    fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    internal fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun lastNotified(context: Context) = prefs(context).getString(KEY_LAST_NOTIFIED, null)

    internal fun labelFromName(context: Context, name: String) =
        context.resources.getIdentifier(name, "string", context.packageName)

    internal const val RACE_KEY = DATA_RACE
    internal const val LABEL_KEY = DATA_LABEL
    internal const val START_KEY = DATA_START
}

class SessionCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val race = try {
            F1Api.nextRace()
        } catch (e: Exception) {
            return Result.retry()
        }

        val now = Instant.now()
        val next = race?.sessions?.firstOrNull { it.start != null && it.start.isAfter(now) }
        if (race != null && next != null) SessionReminder.plan(applicationContext, race, next)

        // aviso de "sessão terminou" com resultados; se falhar, a próxima checagem tenta de novo
        try {
            SessionResults.plan(applicationContext, F1Api.season())
        } catch (e: Exception) {
            return Result.retry()
        }
        return Result.success()
    }
}

class SessionNotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val race = inputData.getString(SessionReminder.RACE_KEY) ?: return Result.failure()
        val label = inputData.getString(SessionReminder.LABEL_KEY)
            ?.let { SessionReminder.labelFromName(applicationContext, it) }
            ?.takeIf { it != 0 } ?: return Result.failure()
        val start = Instant.ofEpochMilli(inputData.getLong(SessionReminder.START_KEY, 0))
        SessionReminder.notify(applicationContext, race, label, start)
        return Result.success()
    }
}
