package com.instapulse.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instapulse.data.model.IGUser
import com.instapulse.data.model.SortOrder
import com.instapulse.data.model.SubFilter
import com.instapulse.data.model.TabCategory
import com.instapulse.ui.DashboardUiState
import com.instapulse.ui.theme.CanvasBg
import com.instapulse.ui.theme.CardBg
import com.instapulse.ui.theme.CardBorder
import com.instapulse.ui.theme.Crimson
import com.instapulse.ui.theme.Cyan
import com.instapulse.ui.theme.Emerald
import com.instapulse.ui.theme.IgPink
import com.instapulse.ui.theme.Purple
import com.instapulse.ui.theme.TextMuted
import com.instapulse.ui.theme.TextPrimary
import com.instapulse.ui.theme.TextSecondary

@Composable
fun InstaPulseDashboardScreen(
    state: DashboardUiState,
    onTabSelected: (TabCategory) -> Unit,
    onSearchChanged: (String) -> Unit,
    onSubFilterSelected: (SubFilter) -> Unit,
    onCycleSort: () -> Unit,
    onToggleCheck: (String) -> Unit,
    onSelectAllVisible: (List<String>) -> Unit,
    onClearChecked: () -> Unit,
    onToggleWhitelist: (String) -> Unit,
    onExecuteSingle: (IGUser) -> Unit,
    onStartBatchQueue: () -> Unit,
    onStartLiveSync: () -> Unit,
    onOpenLoginModal: () -> Unit,
    onLogout: () -> Unit,
    onLoadSampleData: () -> Unit
) {
    val syncSubtitle = if (state.isSyncing) {
        "⚡ Syncing real-time graph..."
    } else if (state.lastSyncTime > 0) {
        val minsAgo = ((System.currentTimeMillis() - state.lastSyncTime) / 60000).coerceAtLeast(0)
        "🟢 Live Synced • ${minsAgo}m ago"
    } else {
        "🟢 Ready to Sync"
    }

    Scaffold(
        containerColor = CanvasBg,
        bottomBar = {
            AnimatedVisibility(
                visible = state.checkedPks.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .background(Color(0xF0131724))
                        .border(1.dp, Color(0x20FFFFFF), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${state.checkedPks.size} Accounts Queued",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "Fast Sequential Queue Active",
                                color = Cyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Cyan)
                                .clickable { onStartBatchQueue() }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Execute All (${state.checkedPks.size})",
                                color = Color.Black,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 90.dp)
        ) {
            // Header Bar
            item(key = "header_bar", contentType = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (state.isSyncing) Cyan else Emerald)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (state.myUsername.isNotEmpty()) "@${state.myUsername}" else "InstaPulse",
                                color = TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.2.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0x33E1306C))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "LIVE",
                                    color = IgPink,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                        Text(
                            text = "${state.totalFollowersCount} Followers • ${state.totalFollowingCount} Following  |  $syncSubtitle",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Sync Button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(CardBg)
                                .border(1.dp, IgPink, RoundedCornerShape(10.dp))
                                .clickable(enabled = !state.isSyncing) {
                                    if (state.isLoggedIn) onStartLiveSync() else onOpenLoginModal()
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (state.isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = IgPink,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = if (state.isLoggedIn) "↻ Sync" else "🔑 Login",
                                    color = IgPink,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        if (state.isLoggedIn) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x1AFF3B5C))
                                    .border(1.dp, Color(0x33FF3B5C), RoundedCornerShape(10.dp))
                                    .clickable { onLogout() }
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = "Logout",
                                    color = Crimson,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x15FFFFFF))
                                    .border(1.dp, Color(0x25FFFFFF), RoundedCornerShape(10.dp))
                                    .clickable { onLoadSampleData() }
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = "Sample Data",
                                    color = Cyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Multi-Stage Animated Sync Progress Banner
            if (state.isSyncing) {
                item(key = "sync_progress_banner", contentType = "progress_banner") {
                    SyncProgressBanner(
                        phaseTitle = state.syncPhaseTitle,
                        counterText = state.syncCounterText,
                        progressFraction = state.syncProgressFraction
                    )
                }
            }

            // Dedicated 2-Card Hero Summary Row (Current Followers & Current Following)
            item(key = "hero_summary_row", contentType = "hero_summary") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HeroStatCard(
                        modifier = Modifier.weight(1f),
                        title = "CURRENT FOLLOWERS",
                        count = state.totalFollowersCount,
                        badgeColor = Emerald,
                        icon = Icons.Default.People
                    )
                    HeroStatCard(
                        modifier = Modifier.weight(1f),
                        title = "CURRENT FOLLOWING",
                        count = state.totalFollowingCount,
                        badgeColor = Cyan,
                        icon = Icons.Default.PersonAdd
                    )
                }
            }

            // Telemetry Strip
            item(key = "telemetry_strip", contentType = "telemetry") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardBg)
                        .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                        .padding(vertical = 12.dp, horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "RECIPROCITY",
                                fontSize = 10.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${state.reciprocityPercent}%",
                                fontSize = 16.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color(0x1AFFFFFF)))

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "FOLLOWER RATIO",
                                fontSize = 10.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${state.followerRatioStr}x",
                                fontSize = 16.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color(0x1AFFFFFF)))

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "NET DELTA",
                                fontSize = 10.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (state.netDelta > 0) "+${state.netDelta}" else "${state.netDelta}",
                                fontSize = 16.sp,
                                color = if (state.netDelta > 0) Emerald else if (state.netDelta < 0) Crimson else TextPrimary,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }

            // Bento Grid Cards
            item(key = "bento_grid", contentType = "bento_grid") {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    // Bento Row 1
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BentoCard(
                            modifier = Modifier.weight(1f),
                            count = state.notFollowingBackCount,
                            badgeText = "TRAITORS",
                            badgeColor = Crimson,
                            title = "Not Following Back",
                            progress = (state.notFollowingBackCount.toFloat() / state.totalFollowingCount.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f),
                            isSelected = state.activeTab == TabCategory.DONT_FOLLOW_BACK,
                            onClick = { onTabSelected(TabCategory.DONT_FOLLOW_BACK) }
                        )

                        BentoCard(
                            modifier = Modifier.weight(1f),
                            count = state.fansCount,
                            badgeText = "FANS",
                            badgeColor = Emerald,
                            title = "Loyal Followers",
                            progress = (state.fansCount.toFloat() / state.totalFollowersCount.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f),
                            isSelected = state.activeTab == TabCategory.FANS,
                            onClick = { onTabSelected(TabCategory.FANS) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Bento Row 2
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BentoCard(
                            modifier = Modifier.weight(1f),
                            count = state.recentsCount,
                            badgeText = "RECENTS",
                            badgeColor = Cyan,
                            title = "Action History",
                            progress = 1f,
                            isSelected = state.activeTab == TabCategory.RECENTS,
                            onClick = { onTabSelected(TabCategory.RECENTS) }
                        )

                        BentoCard(
                            modifier = Modifier.weight(1f),
                            count = state.mutualsCount,
                            badgeText = "MUTUALS",
                            badgeColor = Purple,
                            title = "Mutual Connections",
                            progress = (state.mutualsCount.toFloat() / state.totalFollowingCount.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f),
                            isSelected = state.activeTab == TabCategory.MUTUALS,
                            onClick = { onTabSelected(TabCategory.MUTUALS) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Protected Whitelist Strip
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(CardBg)
                            .border(
                                1.5.dp,
                                if (state.activeTab == TabCategory.WHITELISTED) Cyan else Color(0x10FFFFFF),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable { onTabSelected(TabCategory.WHITELISTED) }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = "Shield",
                                    tint = Cyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Protected Whitelist",
                                    color = TextSecondary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Text(
                                text = "${state.whitelistedCount}",
                                color = TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Search Bar & Filter Chips
            item(key = "search_and_filters", contentType = "filters") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x0AFFFFFF))
                            .border(1.dp, Color(0x10FFFFFF), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            BasicTextField(
                                value = state.searchQuery,
                                onValueChange = onSearchChanged,
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                ),
                                cursorBrush = SolidColor(Cyan),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { innerTextField ->
                                    if (state.searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search @username or name...",
                                            color = TextMuted,
                                            fontSize = 14.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                SubFilter.ALL to "All",
                                SubFilter.VERIFIED to "Verified ✓",
                                SubFilter.PRIVATE to "Private 🔒",
                                SubFilter.PUBLIC to "Public 🌐"
                            ).forEach { (f, label) ->
                                val active = state.subFilter == f
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (active) Color(0x2638BDF8) else Color(0x0DFFFFFF))
                                        .border(1.dp, if (active) Cyan else Color.Transparent, RoundedCornerShape(12.dp))
                                        .clickable { onSubFilterSelected(f) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (active) Cyan else TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        val sortLabel = when (state.sortOrder) {
                            SortOrder.DEFAULT -> "Sort ↕"
                            SortOrder.AZ -> "A-Z ↓"
                            SortOrder.ZA -> "Z-A ↑"
                            SortOrder.AGE_NEW -> "Newest ★"
                            SortOrder.AGE_OLD -> "Oldest ⏳"
                        }
                        Box(
                            modifier = Modifier
                                .clickable { onCycleSort() }
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = sortLabel,
                                color = Cyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x0AFFFFFF))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "⚡ Uncapped Engine (${state.displayedUsers.size} listed)",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        val hasSelection = state.checkedPks.isNotEmpty()
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(15.dp))
                                .background(if (hasSelection) Cyan else Color(0x15FFFFFF))
                                .clickable {
                                    if (hasSelection) onClearChecked() else onSelectAllVisible(state.displayedUsers.map { it.pk })
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (hasSelection) "Clear Selection (${state.checkedPks.size})" else "Select All",
                                color = if (hasSelection) Color.Black else TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // User Accounts List - O(1) instantaneous render
            if (state.displayedUsers.isEmpty()) {
                item(key = "empty_placeholder", contentType = "empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 50.dp, horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No Accounts in this Category",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (state.isLoggedIn) "Adjust filters or run a live sync." else "Log in to sync your live Instagram graph, or load sample data.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                items(
                    items = state.displayedUsers,
                    key = { it.pk },
                    contentType = { "user_card" }
                ) { user ->
                    val isChecked = state.checkedPks.contains(user.pk)
                    val isWhitelisted = state.whitelistPks.contains(user.pk)
                    val isLoading = state.busyPk == user.pk
                    val isFollowing = state.followingPkSet.contains(user.pk)

                    val onCheck = remember(user.pk) { { onToggleCheck(user.pk) } }
                    val onWhitelist = remember(user.pk) { { onToggleWhitelist(user.pk) } }
                    val onAction = remember(user.pk) { { onExecuteSingle(user) } }

                    UserCardRow(
                        user = user,
                        isChecked = isChecked,
                        isWhitelisted = isWhitelisted,
                        isLoadingAction = isLoading,
                        isFollowing = isFollowing,
                        onToggleCheck = { onCheck() },
                        onToggleWhitelist = { onWhitelist() },
                        onExecuteSingle = { onAction() },
                        onInspect = { /* Inspect view */ }
                    )
                }
            }
        }
    }
}

// Multi-Stage Animated Sync Progress Banner
@Composable
fun SyncProgressBanner(
    phaseTitle: String,
    counterText: String,
    progressFraction: Float
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction.coerceIn(0.05f, 1f),
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "syncProgress"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -150f,
        targetValue = 400f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF131724))
            .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFFF58529), Color(0xFFDD2A7B), Color(0xFF8134AF))), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = phaseTitle,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Color(0xFFDD2A7B),
                    strokeWidth = 2.dp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = counterText,
                color = Cyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Smooth GPU-Animated Gradient Progress Bar with shimmer highlight
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0x20FFFFFF))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFFF58529),
                                    Color(0xFFDD2A7B),
                                    Color(0xFF8134AF)
                                )
                            )
                        )
                )

                // Shimmer Highlight
                Box(
                    modifier = Modifier
                        .offset(x = shimmerOffset.dp)
                        .width(40.dp)
                        .height(6.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color(0x80FFFFFF),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }
        }
    }
}

// Hero Summary Card
@Composable
fun HeroStatCard(
    modifier: Modifier = Modifier,
    title: String,
    count: Int,
    badgeColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 10.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = badgeColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "$count",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = TextPrimary
            )
        }
    }
}

@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    count: Int,
    badgeText: String,
    badgeColor: Color,
    title: String,
    progress: Float,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(
                width = 1.5.dp,
                color = if (isSelected) badgeColor else Color(0x10FFFFFF),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$count",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = title,
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x15FFFFFF))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(badgeColor)
                )
            }
        }
    }
}
