package com.xyon.empleados.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch

/**
 * The person's own switch for haptics ("Vibración" in Profile). It sits on top of the system
 * "touch feedback" setting, which is always respected too. Read synchronously from anywhere.
 */
object HapticsPrefs {
    private const val FILE = "xyon_ui"
    private const val KEY = "haptics"
    @Volatile var enabled: Boolean = true
        private set

    fun init(ctx: Context) {
        enabled = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, true)
        Haptics.init(ctx)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

/**
 * Real vibration effects (the phone's tick / click / heavy-click waveforms). The system "touch feedback"
 * tick that Compose's own haptics use is barely perceptible on many phones, so everything the app
 * vibrates for plays through here. Always gated by the person's "Vibración" switch in Profile.
 */
object Haptics {
    private var vibrator: Vibrator? = null

    fun init(ctx: Context) {
        vibrator = runCatching {
            if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }.getOrNull()
    }

    private fun predefined(effect: Int, fallbackMs: Long, fallbackAmp: Int) {
        if (!HapticsPrefs.enabled) return
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            v.vibrate(
                if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(effect)
                else VibrationEffect.createOneShot(fallbackMs, fallbackAmp),
            )
        }
    }

    private fun pattern(timings: LongArray, amplitudes: IntArray) {
        if (!HapticsPrefs.enabled) return
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            v.vibrate(
                if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amplitudes, -1)
                else VibrationEffect.createWaveform(timings, -1),
            )
        }
    }

    /** Light tick: taps on rows, chips, switches. */
    fun tick() = predefined(VibrationEffect.EFFECT_TICK, 12, 90)
    /** Firm click: buttons, tab changes, pull-to-refresh. */
    fun click() = predefined(VibrationEffect.EFFECT_CLICK, 20, 150)
    /** Heavy click: the big clock-in button, destructive confirmations. */
    fun heavy() = predefined(VibrationEffect.EFFECT_HEAVY_CLICK, 35, 230)
    /** Success: two rising pulses. */
    fun success() = pattern(longArrayOf(0, 24, 70, 40), intArrayOf(0, 140, 0, 255))
    /** Failure: three short, hard pulses. */
    fun reject() = pattern(longArrayOf(0, 40, 60, 40, 60, 60), intArrayOf(0, 255, 0, 255, 0, 200))
}

/** Every Compose haptic goes through this gate, so the preference covers nav bar, buttons, switches… */
private class GatedHaptic(@Suppress("unused") private val base: HapticFeedback) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        when (hapticFeedbackType) {
            HapticFeedbackType.LongPress -> Haptics.heavy()
            HapticFeedbackType.TextHandleMove -> Haptics.tick()
            else -> Haptics.click()
        }
    }
}

/**
 * Light tick on every completed tap of anything that uses the default indication (cards, rows,
 * icon buttons, chips, dialog buttons, calendar days…). Components that already vibrate on their
 * own (nav bar, XyonButton, clock-in button) draw no indication, so they never double up.
 * The system "touch feedback" setting is respected by [HapticFeedback].
 */
@Composable
fun ProvideTapHaptics(content: @Composable () -> Unit) {
    val base = LocalIndication.current
    val system = LocalHapticFeedback.current
    val haptic = remember(system) { GatedHaptic(system) }
    val wrapped = remember(base, haptic) { if (base is IndicationNodeFactory) HapticIndication(base, haptic) else base }
    CompositionLocalProvider(LocalIndication provides wrapped, LocalHapticFeedback provides haptic, content = content)
}

private class HapticIndication(
    private val base: IndicationNodeFactory,
    private val haptic: HapticFeedback,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        HapticNode(interactionSource, haptic, base.create(interactionSource))

    override fun equals(other: Any?) = other is HapticIndication && other.base == base && other.haptic == haptic
    override fun hashCode() = 31 * base.hashCode() + haptic.hashCode()
}

/**
 * Every default-indication press: the content squishes a little under the finger and springs back,
 * and a completed tap ticks. (A Cancel means it turned into a scroll or was dragged off: no tick.)
 */
private class HapticNode(
    private val source: InteractionSource,
    private val haptic: HapticFeedback,
    inner: DelegatableNode,
) : DelegatingNode(), DrawModifierNode {
    private val squish = Animatable(1f)
    init { delegate(inner) }

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> coroutineScope.launch { squish.animateTo(.965f, spring(stiffness = Spring.StiffnessHigh)) }
                    is PressInteraction.Release -> {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        coroutineScope.launch { squish.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) }
                    }
                    is PressInteraction.Cancel -> coroutineScope.launch { squish.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val s = squish.value
        if (s == 1f) drawContent() else scale(s, center) { this@draw.drawContent() }
    }
}

/** Switch that ticks when toggled (Material's Switch draws no indication, so it needs its own). */
@Composable
fun HapticSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors()) {
    val haptic = LocalHapticFeedback.current
    Switch(
        checked,
        onCheckedChange?.let { cb -> { v: Boolean -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); cb(v) } },
        modifier, enabled = enabled, colors = colors,
    )
}

@Composable
fun HapticCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors()) {
    val haptic = LocalHapticFeedback.current
    Checkbox(
        checked,
        onCheckedChange?.let { cb -> { v: Boolean -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); cb(v) } },
        modifier, enabled = enabled, colors = colors,
    )
}

/** Success pattern (clock-in done): the system "confirm" on Android 11+, a long press before that. */
fun View.hapticConfirm(): Boolean { Haptics.success(); return HapticsPrefs.enabled }

/** Failure pattern (clock-in rejected): the system "reject" on Android 11+, a long press before that. */
fun View.hapticReject(): Boolean { Haptics.reject(); return HapticsPrefs.enabled }
