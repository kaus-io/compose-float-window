package com.example.composefloatwindow

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FloatWindowViewModel(
    private val handle: SavedStateHandle
) : ViewModel() {

    private val _counter = MutableStateFlow(handle.get<Int>(KEY_COUNTER) ?: 0)
    val counter: StateFlow<Int> = _counter.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    init {
        appendLog("ViewModel.init  restoredCounter=${_counter.value}")
    }

    fun increment() {
        _counter.update { it + 1 }
        handle[KEY_COUNTER] = _counter.value
    }

    fun appendLog(msg: String) {
        _logs.update { (it + msg).takeLast(20) }
        viewModelScope.launch { }
    }

    companion object {
        private const val KEY_COUNTER = "counter"
    }
}
