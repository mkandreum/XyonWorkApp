# AGENTS.md — XyonEmpleadosApp (Android nativo)

## Priority

**Quality over speed.** The user explicitly asked for well-made changes. Read the code
around the change, keep the existing patterns, add a test when logic is involved, and
verify with a build before proposing a commit. Never push without showing the diff and
getting approval.

## What this repo is

Native Android client for XyonEmpleados: Kotlin 2.0, Jetpack Compose Material 3, Hilt,
Retrofit + kotlinx.serialization, DataStore, WorkManager. Single `:app` module, package
`com.xyon.empleados`. The web + backend repo is `XyonEmpleados`.

## Build and test

JDK 17 is required. On this machine:

```powershell
$env:JAVA_HOME="C:\Users\daniel.gonzalez\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

`local.properties` (SDK path) and `keystore.properties` are ignored by git. Do not commit
them. Default API: `https://work.xyoncloud.win/api/`; override with
`-PapiBaseUrl=https://.../api/`.

## Rules

- Spanish UI copy; English identifiers except backend field names.
- Use `core` UI primitives / `Xyon.colors`; no hard-coded colors.
- State via `StateFlow`; network via `safeCall` + `userMessage()`.
- Never log credentials, tokens or personal data.
- HTTPS only; do not weaken `network_security_config.xml`.
- New schedule/reminder logic needs a JVM test in `app/src/test/java/...`.

## Reminder architecture (5 minutes before shift)

| File | Responsibility |
|------|----------------|
| `data/SchedulePlanner.kt` | Pure resolution of shifts, assignments, overrides, vacations; tested. |
| `ui/ReminderScheduler.kt` | Fetches schedule data and applies the plan. |
| `ui/Reminders.kt` | AlarmManager, persisted plan, notification channel, boot/time-change receivers, hourly sync worker. |
| `ui/ViewModels.kt` | Syncs on login, refresh, fichaje and logout. |
| `ui/PermissionsScreen.kt` | Notification permission + exact-alarm special access. |

Sources: `department-schedules/{department}`, `user-shift-assignments`,
`schedule-overrides`, `vacations`. Skip approved vacations/absences, `dayOff`, inactive
weekdays, ambiguous multiple shifts without assignment, past times and already-clocked-in
users. Split shifts schedule both starts. If fetching fails, keep the previous plan.

## Map

- `data/Api.kt` / `data/admin/AdminApi.kt` — endpoints.
- `data/Models.kt` / `data/ScheduleModels.kt` — DTOs.
- `data/Storage.kt` — encrypted token + offline cache.
- `ui/ViewModels.kt` — session/dashboard/calendar/payroll/notifications.
- `ui/AppNav.kt` — navigation and role tabs.
- `ui/theme/` — design system.
- `ui/admin/` — admin tools.

## Current change log

See `docs/AGENT_NOTES.md`. Latest session:
- Added local exact clock-in reminders 5 minutes before each user's assigned shift.
- Added schedule resolution with vacations, absences, overrides and day off.
- Added exact-alarm permission card, boot/time-change rescheduling and hourly background sync.
- Added 14 JVM tests for `SchedulePlanner`.
- Fixed two pre-existing lint errors: API-31-only `Duration` methods in `FichajeButton`
  (crash on Android 8–11) and a literal BOM inside `AdminTools`.
- Verified with `assembleDebug`, `testDebugUnitTest` and `lintDebug`.

## Pending / next

- Firebase/FCM (deferred by the user): needs `google-services.json`, a service-account
  JSON, backend env var and a `DeviceToken` table. Keep the local plan as fallback.

## Skills

- `.claude/skills/xyon-android/SKILL.md` — this repo.
- `.claude/skills/android-development`, `.claude/skills/mobile-android-design` — imported
  reference material (architecture, Compose, Material 3).
