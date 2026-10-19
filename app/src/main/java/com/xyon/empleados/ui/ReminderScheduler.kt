package com.xyon.empleados.ui

import android.content.Context
import com.xyon.empleados.data.Api
import com.xyon.empleados.data.SchedulePlanner
import com.xyon.empleados.data.SessionStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches everything needed to know the real start of each upcoming shift and
 * hands the resulting plan to [Reminders].
 *
 * If any request fails, the previous plan is left untouched: a stale-but-correct
 * reminder is better than a wrong one, and we never guess around vacations.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val api: Api,
    private val session: SessionStore,
    private val me: MeProvider,
) {
    private val mutex = Mutex()

    suspend fun refresh(): Result<Int> {
        if (!Reminders.enabled(ctx)) return Result.success(0)

        return mutex.withLock {
            if (session.token == null && !session.load()) return@withLock Result.success(0)
            Reminders.ensurePeriodicSync(ctx)
            val generation = Reminders.generation()

            try {
                // The worker may run for days without opening the app: refresh the
                // department instead of trusting a stale cached profile.
                val user = me.user(forceRefresh = true)
                if (user.department.isBlank()) return@withLock Result.success(0)

                val today = LocalDate.now()
                // Start one day earlier to keep a night-shift exit that is still
                // pending after midnight. Past entries/exits are filtered by time.
                val from = today.minusDays(1)
                val lastDay = today.plusDays(HORIZON_DAYS.toLong())
                val months = monthsBetween(from, lastDay)

                // `default` is a synthetic 09:00-18:00 shift the API returns when the
                // department has no configured schedule. It is not an assigned shift,
                // so it must not generate reminders.
                val shifts = api.departmentShifts(user.department).filterNot { it.id == "default" }
                val assignments = months
                    .flatMap { ym -> api.shiftAssignments(user.id, ym.monthValue, ym.year) }
                    // Ignore leftovers from a previous department.
                    .filter { it.shift == null || it.shift.department == user.department }
                val overrides = api.scheduleOverrides(user.id, from.toString(), lastDay.toString())
                val vacations = api.vacations()

                val plans = SchedulePlanner.plan(
                    from = from,
                    shifts = shifts,
                    assignments = assignments,
                    overrides = overrides,
                    vacations = vacations,
                    now = LocalDateTime.now(),
                    days = HORIZON_DAYS + 1,
                )
                // The user may have logged out or switched reminders off while the
                // requests were in flight. Never re-arm alarms after that.
                if (!Reminders.enabled(ctx) ||
                    session.token == null ||
                    Reminders.generation() != generation
                ) {
                    return@withLock Result.success(0)
                }
                Reminders.applyPlan(ctx, plans, generation)
                Result.success(plans.size)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
    }

    fun cancel() {
        Reminders.cancelAll(ctx)
        Reminders.stopSync(ctx)
    }

    private fun monthsBetween(from: LocalDate, to: LocalDate): List<YearMonth> = buildList {
        var current = YearMonth.from(from)
        val last = YearMonth.from(to)
        while (!current.isAfter(last)) {
            add(current)
            current = current.plusMonths(1)
        }
    }

    private companion object {
        const val HORIZON_DAYS = 14
    }
}

/** Hilt bridge for WorkManager, which instantiates workers without constructor injection. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderWorkerEntryPoint {
    fun scheduler(): ReminderScheduler
}
