package com.instapulse.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instapulse.data.image.AvatarImage
import com.instapulse.data.model.IGUser
import com.instapulse.ui.theme.Amber
import com.instapulse.ui.theme.CardRowBg
import com.instapulse.ui.theme.Crimson
import com.instapulse.ui.theme.Emerald
import com.instapulse.ui.theme.IgPulseGradient
import com.instapulse.ui.theme.RecentsCyan
import com.instapulse.ui.theme.TextMuted
import com.instapulse.ui.theme.TextPrimary
import com.instapulse.ui.theme.TextSecondary
import com.instapulse.ui.theme.hyperGlassCard

@Composable
fun UserCardRow(
    user: IGUser,
    isChecked: Boolean,
    isWhitelisted: Boolean,
    isLoadingAction: Boolean,
    isFollowing: Boolean,
    onToggleCheck: (pk: String) -> Unit,
    onToggleWhitelist: (pk: String) -> Unit,
    onExecuteSingle: (user: IGUser) -> Unit,
    onInspect: (username: String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val isRecents = user.lastAction != null
    val actionTitle = if (isFollowing) "Unfollow" else "Follow Back"
    val actionColor = if (isFollowing) Crimson else Emerald
    val leftAccentColor = when {
        isRecents -> RecentsCyan
        isFollowing -> Crimson
        else -> Emerald
    }

    // Touch & Press Physics via graphicsLayer (zero recomposition overhead)
    val cardInteractionSource = remember { MutableInteractionSource() }
    val isCardPressed by cardInteractionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (isCardPressed) 0.975f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "cardPressScale"
    )

    // Shield spring-bounce physics
    val shieldScale by animateFloatAsState(
        targetValue = if (isWhitelisted) 1.15f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "shieldScale"
    )

    // Morphing action button width & color
    val actionBtnWidth by animateDpAsState(
        targetValue = if (isLoadingAction) 42.dp else if (isFollowing) 84.dp else 96.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "actionBtnWidth"
    )

    val actionBtnColor by animateColorAsState(
        targetValue = if (isLoadingAction) RecentsCyan else actionColor,
        animationSpec = tween(durationMillis = 250),
        label = "actionBtnColor"
    )

    // Action completion haptic pulse
    LaunchedEffect(isLoadingAction) {
        if (!isLoadingAction) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
            .hyperGlassCard(
                accentColor = if (isLoadingAction) RecentsCyan else if (isChecked) RecentsCyan else null,
                isSelected = isChecked,
                cornerRadius = 18.dp
            )
            .padding(vertical = 11.dp, horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left colored status indicator stripe
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(leftAccentColor)
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Checkbox with tactile scale
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isChecked) RecentsCyan else Color(0x12FFFFFF))
                    .border(1.dp, if (isChecked) RecentsCyan else Color(0x35FFFFFF), RoundedCornerShape(7.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleCheck(user.pk)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isChecked) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.Black,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Lightweight 88x88px Downsampled Avatar with double-ring for verified/active accounts
            AvatarImage(
                avatarUrl = user.profilePicUrl,
                username = user.username,
                size = 46.dp,
                hasDoubleRing = user.isVerified || isRecents,
                borderColor = leftAccentColor,
                modifier = Modifier.clickable { onInspect(user.username) }
            )

            Spacer(modifier = Modifier.width(12.dp))

            // User Info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onInspect(user.username) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "@${user.username}",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (user.isVerified) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "✓",
                            color = RecentsCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (user.isPrivate) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Private",
                            tint = TextMuted,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }

                Text(
                    text = user.fullName.ifEmpty { "Instagram Account" },
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (isRecents) {
                    val statusLabel = when (user.lastAction) {
                        "unfollowed" -> "Unfollowed just now"
                        "skipped_unavailable" -> "Unavailable / Skipped"
                        else -> "Followed just now"
                    }
                    val statusColor = when (user.lastAction) {
                        "unfollowed" -> Amber
                        "skipped_unavailable" -> TextMuted
                        else -> Emerald
                    }
                    Text(
                        text = statusLabel,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Shield (Whitelist) Button with Spring-bounce scale
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .graphicsLayer {
                        scaleX = shieldScale
                        scaleY = shieldScale
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isWhitelisted) Color(0x3300E5FF) else Color(0x10FFFFFF))
                    .border(
                        1.dp,
                        if (isWhitelisted) RecentsCyan else Color(0x1AFFFFFF),
                        RoundedCornerShape(12.dp)
                    )
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleWhitelist(user.pk)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Whitelist Shield",
                    tint = if (isWhitelisted) RecentsCyan else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Morphing Action Pill Button (Unfollow / Follow Back / Morphing Loading Pill)
            Box(
                modifier = Modifier
                    .width(actionBtnWidth)
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(actionBtnColor)
                    .clickable(enabled = !isLoadingAction) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onExecuteSingle(user)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isLoadingAction) {
                    val infiniteTransition = rememberInfiniteTransition(label = "btnSpin")
                    val spinAngle by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "spinAngle"
                    )
                    Canvas(modifier = Modifier.size(16.dp)) {
                        drawArc(
                            color = Color.Black,
                            startAngle = spinAngle,
                            sweepAngle = 260f,
                            useCenter = false,
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                    }
                } else {
                    AnimatedContent(
                        targetState = actionTitle,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "actionText"
                    ) { title ->
                        Text(
                            text = title,
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
