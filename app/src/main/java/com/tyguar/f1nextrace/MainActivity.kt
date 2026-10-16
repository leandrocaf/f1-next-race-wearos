package com.tyguar.f1nextrace

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.SwipeToDismissValue
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.rememberSwipeToDismissBoxState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WHITE = Color(0xFFFFFFFF)
private val RED = Color(0xFFF00000)
private val GRAY = Color(0xFFAAAAAA)
private val DIM = Color(0xFF8A8A8A) // sessões que já passaram
private val NEXT_BG = Color(0xFF4A0E0C) // fundo da próxima sessão
private val SOFT_RED = Color(0xFFFFB4AB) // texto sobre o fundo vinho do destaque

// Horários no fuso do relógio (a versão Zepp fixava Brasília, UTC-3)
// (dia da semana vem de R.array.weekdays, traduzido)
private val SESSION_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.US)
private val DAY_FMT = DateTimeFormatter.ofPattern("dd/MM", Locale.US)

private fun Instant.local() = atZone(ZoneId.systemDefault())

class MainActivity : ComponentActivity() {
    // tela pedida por uma notificação (ex.: resultados); consumida pelo App
    private var pendingDestination by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDestination = intent.getStringExtra(SessionResults.EXTRA_DESTINATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDestination = intent.getStringExtra(SessionResults.EXTRA_DESTINATION)
        SessionReminder.schedule(applicationContext)
        setContent {
            MaterialTheme {
                AskNotificationPermission()
                App(pendingDestination = pendingDestination, onDestinationHandled = { pendingDestination = null })
            }
        }
    }
}

// Android 13+: pede a permissão de notificação (necessária para lembretes e resultados)
@Composable
private fun AskNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (!SessionReminder.canNotify(context)) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private enum class Screen { Home, Calendar, Standings, Results, Settings, Drivers, Constructors }

// Tela principal (próxima corrida) + telas secundárias (calendário e classificações).
// Deslizar para a direita volta (SwipeToDismissBox mantém a tela principal viva
// por baixo durante o gesto).
@Composable
private fun App(
    pendingDestination: String?,
    onDestinationHandled: () -> Unit,
    vm: MainViewModel = viewModel(),
) {
    var screenName by rememberSaveable { mutableStateOf(Screen.Home.name) }
    val screen = Screen.valueOf(screenName)

    // vindo de uma notificação: abre a tela pedida (resultados sempre recarregados)
    LaunchedEffect(pendingDestination) {
        val target = Screen.entries.firstOrNull { it.name == pendingDestination } ?: return@LaunchedEffect
        if (target == Screen.Results) vm.loadResult()
        screenName = target.name
        onDestinationHandled()
    }
    val holder = rememberSaveableStateHolder()
    val swipe = rememberSwipeToDismissBoxState()

    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue == SwipeToDismissValue.Dismissed) {
            swipe.snapTo(SwipeToDismissValue.Default)
            screenName = Screen.Home.name
        }
    }
    BackHandler(enabled = screen != Screen.Home) { screenName = Screen.Home.name }

    SwipeToDismissBox(
        state = swipe,
        hasBackground = screen != Screen.Home,
        backgroundKey = if (screen != Screen.Home) "next" else "none",
        contentKey = screen.name,
    ) { isBackground ->
        if (isBackground || screen == Screen.Home) {
            holder.SaveableStateProvider("next") {
                NextRaceScreen(vm, onOpen = {
                    when (it) {
                        Screen.Calendar -> vm.ensureSeason()
                        Screen.Drivers -> vm.ensureDrivers()
                        Screen.Constructors -> vm.ensureConstructors()
                        Screen.Results -> vm.ensureResult()
                        Screen.Home, Screen.Standings, Screen.Settings -> Unit
                    }
                    screenName = it.name
                })
            }
        } else when (screen) {
            Screen.Calendar -> CalendarScreen(vm)
            Screen.Standings -> StandingsMenuScreen(onOpen = {
                if (it == Screen.Drivers) vm.ensureDrivers() else vm.ensureConstructors()
                screenName = it.name
            })
            Screen.Results -> ResultScreen(vm)
            Screen.Settings -> SettingsScreen()
            Screen.Drivers -> StandingsScreen(R.string.drivers, vm.drivers, vm::loadDrivers)
            Screen.Constructors -> StandingsScreen(R.string.constructors, vm.constructors, vm::loadConstructors)
            Screen.Home -> Unit
        }
    }
}

@Composable
private fun ListScreen(
    state: ScalingLazyListState = rememberScalingLazyListState(),
    content: ScalingLazyListScope.() -> Unit,
) {
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(state),
            content = content,
        )
    }
}

@Composable
private fun NextRaceScreen(vm: MainViewModel, onOpen: (Screen) -> Unit) {
    val next by vm.next.collectAsStateWithLifecycle()

    when (val s = next) {
        is Load.Loading -> Status(stringResource(R.string.loading), loading = true)
        is Load.Error -> ErrorStatus(s, onRetry = vm::loadNext)
        is Load.Ok -> {
            val race = s.value
            if (race == null) {
                Status(stringResource(R.string.no_data), onRetry = vm::loadNext)
                return
            }
            val now by rememberNow()
            val locale = LocalConfiguration.current.locales[0]
            // próxima sessão = primeira com horário >= agora
            val nextIdx = race.sessions.indexOfFirst { it.start != null && !it.start.isBefore(now) }

            ListScreen {
                item { RaceHeader(race) }
                if (nextIdx >= 0) {
                    item {
                        val session = race.sessions[nextIdx]
                        Text(
                            text = stringResource(
                                R.string.countdown,
                                stringResource(session.label),
                                countdownText(Duration.between(now, session.start)),
                            ),
                            color = RED,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                item {
                    Text(
                        text = listOfNotNull(race.locality, localizedCountry(race.country, locale))
                            .joinToString(", "),
                        color = GRAY,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                itemsIndexed(race.sessions) { i, session ->
                    SessionRow(
                        session,
                        isNext = i == nextIdx,
                        isPast = session.start?.isBefore(now) == true,
                    )
                }
                item { MenuChip(R.string.calendar, top = 8.dp) { onOpen(Screen.Calendar) } }
                item { MenuChip(R.string.standings) { onOpen(Screen.Standings) } }
                item { MenuChip(R.string.results) { onOpen(Screen.Results) } }
                item { MenuChip(R.string.settings) { onOpen(Screen.Settings) } }
            }
        }
    }
}

@Composable
private fun MenuChip(@StringRes label: Int, top: Dp = 0.dp, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(stringResource(label)) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.padding(top = top),
    )
}

// Nome do GP no idioma do aparelho (traduzido só no pt-BR)
@Composable
private fun raceName(name: String) = localizedRaceName(name, LocalConfiguration.current.locales[0])

// "Agora" que avança no início de cada minuto, para atualizar a contagem regressiva
@Composable
private fun rememberNow() = produceState(Instant.now()) {
    while (true) {
        val ms = System.currentTimeMillis()
        delay(60_000 - ms % 60_000)
        value = Instant.now()
    }
}

// Ex.: "2d 3h 15m"; omite dias/horas zerados à esquerda
@Composable
private fun countdownText(d: Duration): String {
    val days = d.toDays().toInt()
    val hours = d.toHours().toInt() % 24
    val minutes = d.toMinutes().toInt() % 60
    return buildList {
        if (days > 0) add(stringResource(R.string.unit_day, days))
        if (days > 0 || hours > 0) add(stringResource(R.string.unit_hour, hours))
        add(stringResource(R.string.unit_minute, minutes))
    }.joinToString(" ")
}

// Cabeçalho: bandeira + nome centralizados juntos
@Composable
private fun RaceHeader(race: Race) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (Flag(race.country, width = 30, height = 20)) Spacer(Modifier.width(6.dp))
        Text(
            text = raceName(race.name),
            color = WHITE,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// PNG se existir, senão emoji; retorna false se não há bandeira para o país
@Composable
private fun Flag(country: String?, width: Int, height: Int): Boolean {
    val res = flagFor(country)
    if (res != null) {
        Image(
            painter = painterResource(res),
            contentDescription = country,
            modifier = Modifier.size(width = width.dp, height = height.dp),
        )
        return true
    }
    val emoji = flagEmoji(country) ?: return false
    Text(emoji, fontSize = (height * 0.9).sp)
    return true
}

@Composable
private fun SessionRow(session: Session, isNext: Boolean, isPast: Boolean) {
    val weekdays = stringArrayResource(R.array.weekdays)
    val dt = session.start?.local()
        ?.let { weekdays[it.dayOfWeek.value % 7] + " " + it.format(SESSION_FMT) }
        ?: fallbackDate(session.date, session.time)
    // a próxima sessão ganha fundo destacado e borda vermelha
    val highlight = if (isNext) {
        Modifier
            .background(NEXT_BG, RoundedCornerShape(12.dp))
            .border(1.dp, RED, RoundedCornerShape(12.dp))
    } else {
        Modifier
    }
    Row(
        modifier = Modifier.fillMaxWidth().then(highlight).padding(horizontal = 6.dp, vertical = if (isNext) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // seta indicando a próxima sessão
        Text(
            text = if (isNext) ">" else "",
            color = SOFT_RED,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(12.dp),
        )
        Text(
            text = stringResource(session.label),
            color = when {
                isNext -> SOFT_RED
                isPast -> DIM
                else -> RED
            },
            fontSize = 14.sp,
            fontWeight = if (isNext) FontWeight.ExtraBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = dt,
            color = if (isPast) DIM else WHITE,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun CalendarScreen(vm: MainViewModel) {
    val season by vm.season.collectAsStateWithLifecycle()

    when (val s = season) {
        is Load.Loading -> Status(stringResource(R.string.loading), loading = true)
        is Load.Error -> ErrorStatus(s, onRetry = vm::loadSeason)
        is Load.Ok -> {
            val races = s.value
            if (races.isEmpty()) {
                Status(stringResource(R.string.no_data), onRetry = vm::loadSeason)
                return
            }
            val now = remember { Instant.now() }
            val nextIdx = races.indexOfFirst { it.start != null && !it.start.isBefore(now) }
            val state = rememberScalingLazyListState()

            // já abre centralizado na próxima corrida (+1 pelo título)
            LaunchedEffect(races) {
                if (nextIdx >= 0) state.scrollToItem(nextIdx + 1)
            }

            ListScreen(state) {
                item {
                    Text(stringResource(R.string.calendar), color = RED, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                itemsIndexed(races) { i, race ->
                    CalendarRow(
                        race,
                        index = i,
                        isNext = i == nextIdx,
                        isPast = race.start?.isBefore(now) == true,
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarRow(race: Race, index: Int, isNext: Boolean, isPast: Boolean) {
    val day = race.start?.local()?.format(DAY_FMT) ?: fallbackDate(race.date, null)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // número da etapa
        Text(
            text = race.round.ifEmpty { "${index + 1}" },
            color = if (isPast) DIM else RED,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(20.dp),
        )
        Box(Modifier.size(width = 24.dp, height = 16.dp), contentAlignment = Alignment.Center) {
            Flag(race.country, width = 24, height = 16)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = raceName(race.name),
            color = when {
                isPast -> DIM
                isNext -> RED
                else -> WHITE
            },
            fontSize = 13.sp,
            fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = day,
            color = if (isPast) DIM else GRAY,
            fontSize = 13.sp,
        )
    }
}

// Escolha entre classificação de pilotos e de construtores
// Ajustes: liga/desliga as notificações e escolhe a antecedência do lembrete
@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SessionReminder.isEnabled(context)) }
    var lead by remember { mutableIntStateOf(SessionReminder.leadMinutes(context)) }
    var canNotify by remember { mutableStateOf(SessionReminder.canNotify(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        canNotify = SessionReminder.canNotify(context)
    }
    // volta das configurações do sistema: reavalia a permissão
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) canNotify = SessionReminder.canNotify(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    ListScreen {
        item { Text(stringResource(R.string.settings), color = RED, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        item {
            ToggleChip(
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    SessionReminder.setEnabled(context, on)
                    if (on && Build.VERSION.SDK_INT >= 33 && !canNotify) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                label = { Text(stringResource(R.string.settings_reminders)) },
                toggleControl = { Icon(imageVector = ToggleChipDefaults.switchIcon(enabled), contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        if (enabled) {
            item { Text(stringResource(R.string.settings_lead), color = GRAY, fontSize = 12.sp, textAlign = TextAlign.Center) }
            SessionReminder.LEAD_OPTIONS.forEach { minutes ->
                item {
                    ToggleChip(
                        checked = lead == minutes,
                        onCheckedChange = {
                            lead = minutes
                            SessionReminder.setLeadMinutes(context, minutes)
                        },
                        label = { Text(stringResource(if (minutes == 60) R.string.lead_60 else R.string.lead_15)) },
                        toggleControl = { Icon(imageVector = ToggleChipDefaults.radioIcon(lead == minutes), contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        // ligado, mas o sistema bloqueia as notificações do app
        if (enabled && !canNotify) {
            item {
                Text(
                    stringResource(R.string.settings_notifications_blocked),
                    color = RED,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Chip(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    label = { Text(stringResource(R.string.settings_open)) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

@Composable
private fun StandingsMenuScreen(onOpen: (Screen) -> Unit) {
    ListScreen {
        item {
            Text(stringResource(R.string.standings), color = RED, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        item { MenuChip(R.string.drivers, top = 8.dp) { onOpen(Screen.Drivers) } }
        item { MenuChip(R.string.constructors) { onOpen(Screen.Constructors) } }
    }
}

@Composable
private fun StandingsScreen(
    @StringRes title: Int,
    state: StateFlow<Load<List<Standing>>>,
    onRetry: () -> Unit,
) {
    val load by state.collectAsStateWithLifecycle()

    when (val s = load) {
        is Load.Loading -> Status(stringResource(R.string.loading), loading = true)
        is Load.Error -> ErrorStatus(s, onRetry = onRetry)
        is Load.Ok -> {
            if (s.value.isEmpty()) {
                Status(stringResource(R.string.no_data), onRetry = onRetry)
                return
            }
            ListScreen {
                item {
                    Text(stringResource(title), color = RED, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                itemsIndexed(s.value) { i, row -> StandingRow(row, podium = i < 3) }
            }
        }
    }
}

@Composable
private fun ResultScreen(vm: MainViewModel) {
    val load by vm.result.collectAsStateWithLifecycle()

    when (val s = load) {
        is Load.Loading -> Status(stringResource(R.string.loading), loading = true)
        is Load.Error -> ErrorStatus(s, onRetry = vm::loadResult)
        is Load.Ok -> {
            val result = s.value
            if (result == null) {
                Status(stringResource(R.string.no_data), onRetry = vm::loadResult)
                return
            }
            ListScreen {
                item {
                    Text(
                        text = raceName(result.raceName),
                        color = WHITE,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
                item {
                    Text(stringResource(result.label), color = RED, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                itemsIndexed(result.rows) { i, row -> ResultRowItem(row, podium = i < 3) }
            }
        }
    }
}

@Composable
private fun ResultRowItem(row: ResultRow, podium: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.position,
            color = RED,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = row.name,
            color = WHITE,
            fontSize = 14.sp,
            fontWeight = if (podium) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(text = row.detail, color = GRAY, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun StandingRow(row: Standing, podium: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.position,
            color = RED,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = row.name,
                color = WHITE,
                fontSize = 14.sp,
                fontWeight = if (podium) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.team != null) {
                Text(row.team, color = GRAY, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(text = row.points, color = WHITE, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ErrorStatus(error: Load.Error, onRetry: () -> Unit) = Status(
    text = if (error.offline) stringResource(R.string.no_internet)
    else stringResource(R.string.error, error.message),
    onRetry = onRetry,
)

@Composable
private fun Status(text: String, loading: Boolean = false, onRetry: (() -> Unit)? = null) {
    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            if (loading) CircularProgressIndicator(indicatorColor = RED)
            Text(text, color = GRAY, fontSize = 14.sp, textAlign = TextAlign.Center)
            if (onRetry != null) {
                Chip(
                    onClick = onRetry,
                    label = { Text(stringResource(R.string.retry)) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

// Sem hora válida: mostra a data crua da API (YYYY-MM-DD -> DD/MM)
private fun fallbackDate(date: String, time: String?): String {
    val p = date.split("-")
    val ddmm = if (p.size == 3) "${p[2]}/${p[1]}" else date
    return listOfNotNull(ddmm, time?.removeSuffix("Z")?.take(5)).joinToString(" ")
}
