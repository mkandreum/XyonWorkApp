---
name: xyon-android
description: Work on the XyonWork native Android app (Kotlin, Jetpack Compose, Hilt, Retrofit, WorkManager). Use for any change in this repository, especially fichajes, schedules, reminders/notifications, admin screens, builds and JVM tests.
---

# XyonWork Android

Native employee app for the XyonWork platform. Single `:app` module, Kotlin 2.0,
Jetpack Compose Material 3, Hilt, Retrofit + kotlinx.serialization, DataStore and
WorkManager. Package `com.xyon.empleados`.

## Non-negotiable rules

1. **Quality before speed.** Read the surrounding code, preserve its style, and verify
   every change with a build and (when logic is involved) a JVM test.
2. **Never push without the user reviewing the diff first.** Commit locally only when asked.
3. **Never commit secrets or machine files:** `local.properties`, `keystore.properties`,
   `*.jks`, `*.keystore`, tokens, passwords.
4. **HTTPS only.** `NetworkModule` refuses a non-HTTPS `API_BASE_URL`.
5. **No logging of credentials, tokens or personal data.**
6. UI copy is Spanish. Code identifiers are English except API/model fields that mirror
   the backend (`horaEntrada`, `horaSalida`, `tipo`…).

## Build and test

Gradle needs JDK 17. On this machine:

```powershell
$env:JAVA_HOME="C:\Users\daniel.gonzalez\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

`local.properties` is ignored by git and must point to the Android SDK. The default API
base URL is `https://work.xyoncloud.win/api/`; override with
`-PapiBaseUrl=https://.../api/`.

## Architecture map

- `MainActivity.kt` — `XyonApp` (`@HiltAndroidApp`) + single activity, deep links and shortcuts.
- `data/Api.kt` — Retrofit interface for employee endpoints.
- `data/admin/AdminApi.kt` — admin/manager endpoints.
- `data/Models.kt`, `data/ScheduleModels.kt` — serializable DTOs.
- `data/Network.kt` — Hilt network module, bearer token, error mapping.
- `data/Storage.kt` — encrypted token (`SessionStore`), offline cache (`CacheStore`).
- `data/Loader.kt` — cache-first `StateFlow` loader used by ViewModels.
- `ui/ViewModels.kt` — session, dashboard, calendar, payroll, notifications.
- `ui/AppNav.kt` — auth graph, role-based tabs and navigation.
- `ui/theme/` — the visual system (`Xyon.colors`, typography, shapes). Use it instead of
  hard-coded colors or default Material styling.

State is exposed as `StateFlow`; screens collect it with `collectAsState()`. Do not block
the UI thread. Use `safeCall { }` and `Throwable.userMessage()` for API errors.

## Clock-in reminders (current feature)

```
SchedulePlanner  (pure logic, tested)
      ▲
ReminderScheduler (fetch shifts + assignments + overrides + vacations)
      ▲
SessionViewModel / DashboardViewModel / ReminderSyncWorker
      ▼
Reminders (AlarmManager + persisted plan + notification channel)
      ▼
ReminderAlarmReceiver / ReminderBootReceiver
```

- Reminder time: **5 minutes before** every real shift start.
- Sources of truth: `department-schedules/{department}`,
  `user-shift-assignments`, `schedule-overrides`, `vacations`.
- Skip rules: approved vacations/absences, `dayOff` overrides, inactive weekdays,
  multiple active shifts without a per-day assignment (cannot know), past times, and
  users already clocked in on this device.
- Split shifts produce one reminder per start (`horaEntrada` and `horaEntradaTarde`),
  unless a one-off override defines custom hours.
- Alarms are exact when the OS allows it (`SCHEDULE_EXACT_ALARM`); otherwise the app
  falls back to an inexact alarm instead of dropping the notification.
- The plan is persisted in `SharedPreferences("reminders")` and re-armed on boot,
  package update, time change and timezone change.
- `ReminderSyncWorker` refreshes every hour; app start and pull-to-refresh also sync.
- Logout cancels alarms and stops the periodic worker.

If the schedule cannot be fetched, the previous plan is kept. Never replace a valid
plan with an empty/guessed one.

## Tests

JVM tests live in `app/src/test/java/...`. `SchedulePlannerTest` covers assigned shifts,
single/ambiguous department shifts, vacations, overrides, split shifts and past times.
Add a test for every new branch in schedule/reminder logic.

## Common pitfalls

- `DepartmentShift` maps `horaSalidaMañana` with `@SerialName`; keep the accent.
- Backend dates are ISO strings; use `take(10)` before parsing to `LocalDate`.
- `UserShiftAssignment.date` is a UTC timestamp at midnight; `take(10)` is the day key.
- Do not call `WorkManager.getInstance()` before application startup on old devices.
- `POST_NOTIFICATIONS` is requested in the first-run `PermissionsScreen`; exact-alarm
  access is a separate special permission card.
- The release build needs `keystore.properties` at the repo root; it is not committed.

## Documentation

- `AGENTS.md` — project rules and quick context.
- `docs/AGENT_NOTES.md` — architecture notes and session changelog.
