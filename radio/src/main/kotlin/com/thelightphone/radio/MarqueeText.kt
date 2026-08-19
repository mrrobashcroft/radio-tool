package com.thelightphone.radio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import kotlinx.coroutines.delay

/**
 * LightText that clamps to [maxLines] when it fits, and switches to a
 * horizontal pause-then-scroll marquee when the text overflows — so long
 * track titles (and radio names) stay readable instead of being clipped.
 *
 * Pause at each end keeps the marquee calm; motion only appears when the
 * text genuinely does not fit.
 */
@Composable
fun MarqueeText(
    text: String,
    variant: LightTextVariant,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    lighten: Boolean = false,
    align: TextAlign? = null,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val overflow = layout?.hasVisualOverflow == true
    val offset = remember { Animatable(0f) }

    BoxWithConstraints(modifier = modifier) {
        val containerPx = with(LocalDensity.current) { maxWidth.toPx() }
        val textWidth = layout?.size?.width
        LaunchedEffect(text, overflow, containerPx, textWidth) {
            offset.snapTo(0f)
            if (overflow && textWidth != null) {
                val maxScroll = textWidth.toFloat() - containerPx
                if (maxScroll > 0f) {
                    while (true) {
                        delay(PAUSE_MS)
                        offset.animateTo(-maxScroll, animationSpec = tween(SCROLL_MS, easing = LinearEasing))
                        delay(PAUSE_MS)
                        offset.animateTo(0f, animationSpec = tween(SCROLL_MS, easing = LinearEasing))
                    }
                }
            }
        }

        LightText(
            text = text,
            variant = variant,
            lighten = lighten,
            align = if (overflow) TextAlign.Start else align,
            maxLines = if (overflow) 1 else maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterStart)
                .graphicsLayer { translationX = offset.value },
        )
    }
}

private const val PAUSE_MS = 1_500L
private const val SCROLL_MS = 4_000
