package com.instapulse.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.instapulse.data.model.IGUser
import com.instapulse.ui.theme.Amber
import com.instapulse.ui.theme.CardRowBg
import com.instapulse.ui.theme.Crimson
import com.instapulse.ui.theme.Cyan
import com.instapulse.ui.theme.Emerald
import com.instapulse.ui.theme.TextMuted
import com.instapulse.ui.theme.TextPrimary
import com.instapulse.ui.theme.TextSecondary

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
    val isRecents = user.lastAction != null
    val actionTitle = if (isFollowing) "Unfollow" else "Follow"
    val actionColor = if (isFollowing) Crimson else Emerald
    val leftBorderColor = when {
        isRecents -> Cyan
        isFollowing -> Crimson
        else -> Emerald
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(CardRowBg)
            .border(1.dp, Color(0x15FFFFFF), RoundedCornerShape(18.dp))
            .border(
                width = 2.dp,
                color = if (isLoadingAction) Cyan else Color.Transparent,
                shape = RoundedCornerShape(18.dp)
            )
            .padding(vertical = 12.dp, horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left colored stripe
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(leftBorderColor)
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Checkbox
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isChecked) Cyan else Color.Transparent)
                    .border(1.dp, if (isChecked) Cyan else Color(0x35FFFFFF), RoundedCornerShape(6.dp))
                    .clickable { onToggleCheck(user.pk) },
                contentAlignment = Alignment.Center
            ) {
                if (isChecked) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Avatar
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, leftBorderColor, CircleShape)
                    .clickable { onInspect(user.username) },
                contentAlignment = Alignment.Center
            ) {
                if (user.profilePicUrl.isNotEmpty()) {
                    AsyncImage(
                        model = user.profilePicUrl,
                        contentDescription = user.username,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF262C40)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = user.username.firstOrNull()?.uppercase() ?: "?",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }

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
                            color = Cyan,
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

            // Whitelist Shield Button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isWhitelisted) Color(0x3038BDF8) else Color(0x10FFFFFF))
                    .clickable { onToggleWhitelist(user.pk) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Whitelist Shield",
                    tint = if (isWhitelisted) Cyan else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action Button
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(actionColor)
                    .clickable(enabled = !isLoadingAction) { onExecuteSingle(user) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoadingAction) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "⚡ Working...",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                } else {
                    Text(
                        text = actionTitle,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
