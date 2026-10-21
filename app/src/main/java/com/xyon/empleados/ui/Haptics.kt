package com.xyon.empleados.ui

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import kotlinx.coroutines.flow.filterIsInstance
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
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

/** Every Compose haptic goes through this gate, so the preference covers nav bar, buttons, switches… */
private class GatedHaptic(private val base: HapticFeedback) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (HapticsPrefs.enabled) base.performHapticFeedback(hapticFeedbackType)
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

private class HapticNode(
    private val source: InteractionSource,
    private val haptic: HapticFeedback,
    inner: DelegatableNode,
) : DelegatingNode() {
    init { delegate(inner) }

    override fun onAttach() {
        // Release = the tap went through (a Cancel means it turned into a scroll or was dragged off)
        coroutineScope.launch {
            source.interactions.filterIsInstance<PressInteraction.Release>().collect {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
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
fun View.hapticConfirm(): Boolean = HapticsPrefs.enabled && performHapticFeedback(
    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
)

/** Failure pattern (clock-in rejected): the system "reject" on Android 11+, a long press before that. */
fun View.hapticReject(): Boolean = HapticsPrefs.enabled && performHapticFeedback(
    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
)
