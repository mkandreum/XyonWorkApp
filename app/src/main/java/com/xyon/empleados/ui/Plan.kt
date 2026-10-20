package com.xyon.empleados.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xyon.empleados.data.Api
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Contracted plan of the company server (XYON_PLAN). Shared at activity scope so tabs and the
 * admin menu hide the modules the plan does not include. Until it loads (or offline) everything
 * is shown: the server is the one that enforces the plan.
 */
@HiltViewModel
class PlanViewModel @Inject constructor(private val api: Api) : ViewModel() {
    private val _features = MutableStateFlow<Set<String>?>(null)
    val features: StateFlow<Set<String>?> = _features.asStateFlow()

    fun load() {
        if (_features.value != null) return
        viewModelScope.launch { runCatching { api.plan() }.onSuccess { _features.value = it.features.toSet() } }
    }
}

fun Set<String>?.allows(feature: String?) = feature == null || this == null || feature in this
