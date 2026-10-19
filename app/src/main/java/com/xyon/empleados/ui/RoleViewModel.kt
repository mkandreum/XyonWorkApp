package com.xyon.empleados.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xyon.empleados.data.Api
import com.xyon.empleados.data.User
import com.xyon.empleados.data.safeCall
import com.xyon.empleados.data.admin.AdminApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What a manager/admin has waiting for them. Null counts mean "not loaded / not available". */
data class RolePending(val requests: Int? = null, val adjustments: Int? = null) {
    val total get() = (requests ?: 0) + (adjustments ?: 0)
}

/**
 * Pending work for the current role, shared by Home, Profile and the tools hub:
 * managers see their department's requests awaiting them; admins see every request awaiting final approval.
 */
@HiltViewModel
class RoleViewModel @Inject constructor(private val api: Api, private val adminApi: AdminApi) : ViewModel() {
    private val _pending = MutableStateFlow(RolePending())
    val pending = _pending.asStateFlow()

    fun load(user: User) {
        if (!user.isManager) return
        viewModelScope.launch {
            val req = async {
                safeCall {
                    if (user.isAdmin) adminApi.stats().let { it.pendingRequests + it.pendingAssignments }
                    else api.teamVacations().count { it.status == "PENDING_MANAGER" || it.status == "PENDING" }
                }.getOrNull()
            }
            val adj = async { safeCall { api.pendingAdjustments().size }.getOrNull() }
            _pending.value = RolePending(req.await(), adj.await())
        }
    }
}
