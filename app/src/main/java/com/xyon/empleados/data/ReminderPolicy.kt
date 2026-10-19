package com.xyon.empleados.data

/**
 * When a scheduled reminder should actually become a notification.
 *
 * Kept free of Android classes so the tricky overnight rules can be unit-tested.
 * Times are epoch milliseconds in the device's current timezone.
 */
object ReminderPolicy {
    /** An entry nudge is only useful while the employee is not working yet. */
    fun shouldNotifyEntry(clockedIn: Boolean): Boolean = !clockedIn

    /**
     * An exit nudge only makes sense while the entry is still open, and it must
     * belong to the shift being closed. A night shift starts on the previous day,
     * so a plain "is today" check would wrongly discard it.
     */
    fun shouldNotifyExit(
        clockedIn: Boolean,
        entryAt: Long,
        shiftStart: Long,
    ): Boolean {
        if (!clockedIn) return false
        if (entryAt <= 0L || shiftStart <= 0L) return true
        return entryAt >= shiftStart - MAX_ENTRY_LEAD_MS
    }

    /** How early an entry can legitimately be registered for the shift. */
    private const val MAX_ENTRY_LEAD_MS = 12L * 60 * 60 * 1000
}
