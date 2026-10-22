package com.xyon.empleados.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** Text whose characters roll up one by one when they change (clock digits, counters). */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Row(modifier) {
        text.forEach { ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(200))) togetherWith
                        (slideOutVertically(tween(260)) { -it / 2 } + fadeOut(tween(160)))
                },
                label = "rollingChar",
            ) { c -> Text(c.toString(), style = style, color = color) }
        }
    }
}
