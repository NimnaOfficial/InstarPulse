package com.instapulse.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instapulse.ui.theme.CanvasBg
import com.instapulse.ui.theme.IgPink
import com.instapulse.ui.theme.TextMuted
import com.instapulse.ui.theme.TextPrimary
import com.instapulse.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CinematicSplashScreen(
    onFinish: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val pulseScale1 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale1"
    )

    val pulseAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha1"
    )

    val pulseScale2 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, delayMillis = 300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale2"
    )

    val pulseAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, delayMillis = 300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha2"
    )

    val progressAnim = remember { Animatable(0f) }
    val sweepAnim = remember { Animatable(-150f) }
    val exitScale = remember { Animatable(1f) }
    val exitAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch {
            progressAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1400, easing = FastOutSlowInEasing)
            )
        }
        launch {
            delay(200)
            sweepAnim.animateTo(
                targetValue = 200f,
                animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing)
            )
        }
        delay(1800)
        launch {
            exitScale.animateTo(1.25f, animationSpec = tween(350, easing = FastOutSlowInEasing))
        }
        exitAlpha.animateTo(0f, animationSpec = tween(350, easing = LinearEasing))
        onFinish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBg)
            .scale(exitScale.value)
            .alpha(exitAlpha.value),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            // Radar Rings & Emblem
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(200.dp)
            ) {
                // Pulse Ring 1
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .scale(pulseScale1)
                        .alpha(pulseAlpha1)
                        .border(1.5.dp, IgPink, CircleShape)
                )

                // Pulse Ring 2
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .scale(pulseScale2)
                        .alpha(pulseAlpha2)
                        .border(1.5.dp, Color(0xFF8134AF), CircleShape)
                )

                // Ambient glow
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .alpha(0.25f)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(IgPink, Color.Transparent)
                            ),
                            CircleShape
                        )
                )

                // Central Emblem
                Box(
                    modifier = Modifier
                        .size(86.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(IgPink, Color(0xFF8134AF))
                            )
                        )
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(21.dp))
                            .background(Color(0xFF121520)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⚡",
                            fontSize = 36.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Title with Sweep Beam
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "InstaPulse",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    letterSpacing = (-0.5).sp
                )

                Box(
                    modifier = Modifier
                        .offset(x = sweepAnim.value.dp)
                        .width(40.dp)
                        .height(44.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0x70FFFFFF),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "RELATIONSHIP INTELLIGENCE ENGINE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(60.dp))

            // Progress bar
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF1E2335))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressAnim.value)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(IgPink, Color(0xFF38BDF8))
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Initializing O(1) Offline Engine...",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextMuted
            )
        }
    }
}
