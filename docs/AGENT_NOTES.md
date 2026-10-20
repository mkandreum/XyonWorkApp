# Agent notes — XyonWorkApp (Android)

Notas de arquitectura, decisiones y cambios. Añadir una entrada por sesión.

## 2026-10-19 — Recordatorios locales 5 minutos antes del turno

### Objetivo
Avisar al empleado **5 minutos antes del inicio real de su turno**, sin Firebase (fase 1),
respetando permisos, ausencias y vacaciones.

### Diseño
```
API (turnos + asignaciones + excepciones + vacaciones)
        │
ReminderScheduler.refresh()  ← login, pull-to-refresh, fichaje, worker cada hora
        │
SchedulePlanner.plan()       ← lógica pura y testeada
        │
Reminders.applyPlan()        ← AlarmManager exacto + plan persistido
        │
ReminderAlarmReceiver        ← notificación (salvo que ya haya fichado)
```

### Reglas aplicadas
- Turno asignado del día (`user-shift-assignments`) manda sobre el turno genérico del
  departamento.
- Sin asignación: se usa el turno del departamento **solo si hay uno activo** ese día.
  Si hay varios, no se avisa (no se puede adivinar).
- Se aplican excepciones de usuario y de departamento (`schedule-overrides`), incluido
  `dayOff` y horas puntuales.
- Se descartan vacaciones/ausencias con estado `APPROVED`.
- Turnos partidos: aviso para `horaEntrada` y `horaEntradaTarde`.
- Se conserva el aviso de salida existente: 15 min después del fin previsto, y solo si
  el usuario sigue fichado.
- No se programa un aviso cuya hora ya pasó.
- No se notifica la entrada si el dispositivo sabe que el usuario ya fichó hoy.
- Si falla la red, se conserva el plan anterior; nunca se sustituye por uno vacío.
- El turno sintético `default` (09:00-18:00) que devuelve la API cuando el departamento
  no tiene horarios configurados no genera avisos.

### Archivos
| Archivo | Cambio |
|---------|--------|
| `data/ScheduleModels.kt` | DTOs de turnos, asignaciones y excepciones. |
| `data/SchedulePlanner.kt` | Resolución pura del horario + `PlannedReminder`. |
| `ui/ReminderScheduler.kt` | Descarga de datos con mutex y aplicación del plan. |
| `ui/Reminders.kt` | AlarmManager, persistencia, canal, receivers y worker. |
| `ui/ViewModels.kt` | Sync al entrar/refrescar/fichar; cancelación al cerrar sesión. |
| `ui/PermissionsScreen.kt` | Tarjeta de permiso especial de alarmas exactas en el onboarding. |
| `ui/ContentScreens.kt` | Acceso “Permitir avisos exactos” en Perfil para usuarios ya existentes. |
| `AndroidManifest.xml` | `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, receivers. |
| `app/proguard-rules.pro` | Keep del worker de WorkManager en release. |
| `ui/FichajeButton.kt` | Sustituidos `toMinutesPart()`/`toSecondsPart()` (API 31) por aritmética compatible con minSdk 26. |
| `ui/admin/AdminTools.kt` | BOM literal dentro de `removePrefix` sustituido por `"\uFEFF"` (error de lint). |
| `app/src/test/.../SchedulePlannerTest.kt` | 14 tests JVM de la lógica. |

### Correcciones tras revisión independiente (2026-10-19)
- **Turnos nocturnos (F1):** el plan ahora se calcula desde `today - 1 día` y el planner
  ya no descarta fechas anteriores a hoy. Así un aviso de salida a las 06:15 del día
  siguiente sobrevive a la sincronización de medianoche.
- **Salida nocturna (F2):** se eliminó la comparación “fichado hoy”. Se guarda el
  timestamp de la entrada abierta y `ReminderPolicy` decide si pertenece al turno.
  Una entrada olvidada de hace más de 12 h antes del turno no genera aviso.
- **Carrera al desactivar (F3):** `Reminders` tiene un contador de generación y un lock
  que hace atómico “aplicar plan” con “cancelar todo”; una sincronización en vuelo no
  puede volver a programar alarmas si el usuario desactivó los recordatorios o cerró
  sesión. Se revalida `enabled`, el token y la generación justo antes de aplicar.
- **Departamento cacheado (F4):** el scheduler fuerza `GET /users/profile` antes de leer
  los turnos y descarta asignaciones de un departamento anterior.
- **Doble PDF (F5):** el modal de firma bloquea el botón mientras genera y `Calendar`
  usa una guarda `useRef` para evitar envíos duplicados.

### Verificación
- `.\gradlew.bat assembleDebug testDebugUnitTest` — **BUILD SUCCESSFUL**.
- `.\gradlew.bat lintDebug` — **BUILD SUCCESSFUL** (0 errores).
- `.\gradlew.bat assembleRelease` — **BUILD SUCCESSFUL** (R8 + lintVital).
- `SchedulePlannerTest` + `ReminderPolicyTest` — 0 fallos.
- APK debug generado en `app/build/outputs/apk/debug/app-debug.apk`.
- Versión de publicación: **versionCode 7 / versionName 7.0**.
- AAB firmado con `keystore/xyon-release.jks` (alias `xyon`), copia local de la firma
  original de `XyonEmpleados_Publicar`. `keystore.properties` no se versiona.
- AAB generado: `Escritorio\XyonEmpleados_Publicar\XyonEmpleados-7.0-release.aab`
  (6.385.952 bytes, SHA-256 `4D60A4BB7F4C58E3EA8B369B7E17A0B5643435138E5B9F8A91EDDCC1E41C3602`).
- Firma verificada con `jarsigner` (`jar verified`); huella SHA-256 del certificado
  `7A:E7:30:CE:F9:EE:3E:AB:12:52:78:2C:87:B2:9B:E8:C6:81:B1:97:FB:16:D9:98:66:A3:58:74:4B:BF:34:88`.

### Limitaciones / siguiente fase
- Sin Firebase: los avisos son locales. Si el fabricante mata el proceso o el usuario
  deniega alarmas exactas, el aviso puede retrasarse unos minutos.
- FCM queda pendiente de credenciales (`google-services.json` + cuenta de servicio).
- Si hay varios turnos activos y el empleado no tiene asignación para ese día, no se
  avisa: es preferible no avisar antes que avisar a una hora equivocada.
