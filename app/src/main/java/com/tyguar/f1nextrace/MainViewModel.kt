package com.tyguar.f1nextrace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    // offline = sem rede/DNS/timeout, mostrado como mensagem amigável
    data class Error(val message: String, val offline: Boolean) : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
}

class MainViewModel : ViewModel() {
    private val _next = MutableStateFlow<Load<Race?>>(Load.Loading)
    val next: StateFlow<Load<Race?>> = _next.asStateFlow()

    private val _season = MutableStateFlow<Load<List<Race>>>(Load.Loading)
    val season: StateFlow<Load<List<Race>>> = _season.asStateFlow()

    private val _drivers = MutableStateFlow<Load<List<Standing>>>(Load.Loading)
    val drivers: StateFlow<Load<List<Standing>>> = _drivers.asStateFlow()

    private val _constructors = MutableStateFlow<Load<List<Standing>>>(Load.Loading)
    val constructors: StateFlow<Load<List<Standing>>> = _constructors.asStateFlow()

    private val _result = MutableStateFlow<Load<SessionResult?>>(Load.Loading)
    val result: StateFlow<Load<SessionResult?>> = _result.asStateFlow()

    private var resultRequested = false
    private var seasonRequested = false
    private var driversRequested = false
    private var constructorsRequested = false

    init {
        loadNext()
    }

    fun loadNext() = load(_next) { F1Api.nextRace() }

    // Calendário só é buscado quando a tela é aberta pela primeira vez
    fun ensureSeason() {
        if (!seasonRequested) loadSeason()
    }

    fun loadSeason() {
        seasonRequested = true
        load(_season) { F1Api.season() }
    }

    fun ensureDrivers() {
        if (!driversRequested) loadDrivers()
    }

    fun loadDrivers() {
        driversRequested = true
        load(_drivers) { F1Api.driverStandings() }
    }

    fun ensureConstructors() {
        if (!constructorsRequested) loadConstructors()
    }

    fun loadConstructors() {
        constructorsRequested = true
        load(_constructors) { F1Api.constructorStandings() }
    }

    fun ensureResult() {
        if (!resultRequested) loadResult()
    }

    fun loadResult() {
        resultRequested = true
        load(_result) { F1Api.latestResult() }
    }

    private fun <T> load(target: MutableStateFlow<Load<T>>, fetch: suspend () -> T) {
        target.value = Load.Loading
        viewModelScope.launch {
            target.value = try {
                Load.Ok(fetch())
            } catch (e: Exception) {
                Load.Error(
                    message = e.message ?: e.javaClass.simpleName,
                    offline = e is UnknownHostException || e is ConnectException ||
                        e is SocketTimeoutException,
                )
            }
        }
    }
}
