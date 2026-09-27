package com.instapulse.ui

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.instapulse.data.bridge.InstaBridgeCallback
import com.instapulse.data.bridge.InstaWebViewBridge
import com.instapulse.data.local.InstaPulsePreferences
import com.instapulse.data.model.ActionQueueItem
import com.instapulse.data.model.ActionType
import com.instapulse.data.model.IGUser
import com.instapulse.data.model.SortOrder
import com.instapulse.data.model.SubFilter
import com.instapulse.data.model.TabCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.random.Random

data class FriendshipStatus(
    val following: Boolean,
    val followedBy: Boolean,
    val outgoingRequest: Boolean = false
)

@Immutable
data class DashboardUiState(
    val displayedUsers: List<IGUser> = emptyList(),
    val originalFollowersCount: Int = 0,
    val originalFollowingCount: Int = 0,
    val notFollowingBackCount: Int = 0,
    val fansCount: Int = 0,
    val mutualsCount: Int = 0,
    val recentsCount: Int = 0,
    val whitelistedCount: Int = 0,
    val reciprocityPercent: String = "0.0",
    val followerRatioStr: String = "0.00",
    val netDelta: Int = 0,
    val activeTab: TabCategory = TabCategory.DONT_FOLLOW_BACK,
    val searchQuery: String = "",
    val subFilter: SubFilter = SubFilter.ALL,
    val sortOrder: SortOrder = SortOrder.DEFAULT,
    val checkedPks: Set<String> = emptySet(),
    val whitelistPks: Set<String> = emptySet(),
    val followingPkSet: Set<String> = emptySet(),
    val busyPk: String? = null,
    val isLoggedIn: Boolean = false,
    val isSyncing: Boolean = false,
    val syncPhaseTitle: String = "",
    val syncCounterText: String = "",
    val syncProgressFraction: Float = 0f,
    val lastSyncTime: Long = 0L,
    val myUsername: String = "",
    val myAvatarUrl: String = ""
)

data class ExecutorUiState(
    val visible: Boolean = false,
    val queue: List<ActionQueueItem> = emptyList(),
    val currentIndex: Int = 0,
    val isPaused: Boolean = false,
    val countdown: Int = 0,
    val statusMessage: String = "Initializing execution engine..."
)

class InstaPulseViewModel(application: Application) : AndroidViewModel(application), InstaBridgeCallback {
    private val prefs = InstaPulsePreferences(application)

    val webViewBridge = InstaWebViewBridge(this)

    // In-memory data store for zero-lag calculations
    private var rawFollowers: List<IGUser> = emptyList()
    private var rawFollowing: List<IGUser> = emptyList()
    private var rawRecent: List<IGUser> = emptyList()
    private var rawWhitelist: Set<String> = emptySet()
    private var rawFriendshipStatuses: Map<String, FriendshipStatus> = emptyMap()
    private var originalFollowersCount: Int = 0
    private var originalFollowingCount: Int = 0
    private var checkedPks: Set<String> = emptySet()
    private var activeTab: TabCategory = TabCategory.DONT_FOLLOW_BACK
    private var searchQuery: String = ""
    private var subFilter: SubFilter = SubFilter.ALL
    private var sortOrder: SortOrder = SortOrder.DEFAULT
    private var busyPk: String? = null
    private var isSyncing: Boolean = false
    private var syncPhaseTitle: String = ""
    private var syncCounterText: String = ""
    private var syncProgressFraction: Float = 0f

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _executorState = MutableStateFlow(ExecutorUiState())
    val executorState: StateFlow<ExecutorUiState> = _executorState.asStateFlow()

    private val _showLoginModal = MutableStateFlow(false)
    val showLoginModal: StateFlow<Boolean> = _showLoginModal.asStateFlow()

    private val _showSplash = MutableStateFlow(true)
    val showSplash: StateFlow<Boolean> = _showSplash.asStateFlow()

    private var executorJob: Job? = null
    private var syncTimeoutJob: Job? = null
    private var calculationJob: Job? = null

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        var followers = prefs.getFollowers()
        var following = prefs.getFollowing()
        rawRecent = prefs.getRecentActivity()
        rawWhitelist = prefs.getWhitelist()
        originalFollowersCount = prefs.getExpectedFollowersCount()
        originalFollowingCount = prefs.getExpectedFollowingCount()

        if (followers.isEmpty() && following.isEmpty()) {
            val (sampleFollowers, sampleFollowing) = prefs.loadSampleData()
            followers = sampleFollowers
            following = sampleFollowing
            rawWhitelist = setOf("1006")
            originalFollowersCount = 1641
            originalFollowingCount = 1859
        } else {
            if (originalFollowersCount == 0) originalFollowersCount = followers.size
            if (originalFollowingCount == 0) originalFollowingCount = following.size
        }

        rawFollowers = followers
        rawFollowing = following

        triggerStateCalculation()
    }

    // ====================================================================
    // O(1) SERVER-TRUTH RELATIONSHIP MATRIX ENGINE (Dispatched on Default)
    // ====================================================================
    private fun triggerStateCalculation() {
        calculationJob?.cancel()
        calculationJob = viewModelScope.launch(Dispatchers.Default) {
            val followers = rawFollowers
            val following = rawFollowing
            val recent = rawRecent
            val whitelist = rawWhitelist
            val statuses = rawFriendshipStatuses

            // Normalized lookup key sets (PK + lowercase username) as solid fallback
            val followerKeySet = HashSet<String>(followers.size * 2).apply {
                followers.forEach {
                    if (it.pk.isNotBlank()) add("pk:${it.pk.trim()}")
                    if (it.username.isNotBlank()) add("un:${it.username.trim().lowercase()}")
                }
            }

            val followingKeySet = HashSet<String>(following.size * 2).apply {
                following.forEach {
                    if (it.pk.isNotBlank()) add("pk:${it.pk.trim()}")
                    if (it.username.isNotBlank()) add("un:${it.username.trim().lowercase()}")
                }
            }

            val whitelistSet = whitelist.map { it.trim().lowercase() }.toHashSet()

            fun isUserInSet(u: IGUser, set: HashSet<String>): Boolean {
                return (u.pk.isNotBlank() && set.contains("pk:${u.pk.trim()}")) ||
                        (u.username.isNotBlank() && set.contains("un:${u.username.trim().lowercase()}"))
            }

            // 1. MUTUAL CONNECTIONS (mutuals)
            // Primary ground truth: status.following && status.followed_by
            // Fallback: user in followerKeySet
            val mutualConnections = following.filter { user ->
                val st = statuses[user.pk]
                if (st != null) {
                    st.following && st.followedBy
                } else {
                    isUserInSet(user, followerKeySet)
                }
            }

            // 2. NOT FOLLOWING BACK / TRAITORS (notFollowingBack)
            // Primary ground truth: status.following && !status.followed_by
            // Fallback: !isUserInSet(user, followerKeySet)
            val notFollowingBack = following.filter { user ->
                val isWhitelisted = whitelist.contains(user.pk) || whitelistSet.contains(user.username.trim().lowercase())
                if (isWhitelisted) return@filter false
                val st = statuses[user.pk]
                if (st != null) {
                    st.following && !st.followedBy
                } else {
                    !isUserInSet(user, followerKeySet)
                }
            }

            // 3. LOYAL FOLLOWERS / FANS TO FOLLOW BACK (loyalFollowers)
            // Primary ground truth: status.followed_by && !status.following && !status.outgoing_request
            // Fallback: !isUserInSet(user, followingKeySet)
            val loyalFollowers = followers.filter { user ->
                val isWhitelisted = whitelist.contains(user.pk) || whitelistSet.contains(user.username.trim().lowercase())
                if (isWhitelisted) return@filter false
                val st = statuses[user.pk]
                if (st != null) {
                    st.followedBy && !st.following && !st.outgoingRequest
                } else {
                    !isUserInSet(user, followingKeySet)
                }
            }

            // 4. Whitelisted
            val whitelisted = (following + followers).distinctBy { it.pk.trim() }.filter {
                whitelist.contains(it.pk.trim()) || whitelistSet.contains(it.username.trim().lowercase())
            }

            // Telemetry Bar Math with Double division (No integer truncation!)
            val totalFol = if (originalFollowersCount > 0) originalFollowersCount else followers.size
            val totalFing = if (originalFollowingCount > 0) originalFollowingCount else following.size

            val reciprocity = if (totalFing > 0) {
                (mutualConnections.size.toDouble() / totalFing.toDouble()) * 100.0
            } else 0.0
            val reciprocityPercent = String.format(Locale.US, "%.1f", reciprocity)

            val followerRatio = if (totalFing > 0) {
                totalFol.toDouble() / totalFing.toDouble()
            } else 0.0
            val followerRatioStr = String.format(Locale.US, "%.2f", followerRatio)

            val prevFol = prefs.getPreviousFollowersCount()
            val netDelta = if (prevFol > 0) totalFol - prevFol else 0

            // Active Tab Selection
            val targetList = when (activeTab) {
                TabCategory.DONT_FOLLOW_BACK -> notFollowingBack
                TabCategory.FANS -> loyalFollowers
                TabCategory.RECENTS -> recent
                TabCategory.MUTUALS -> mutualConnections
                TabCategory.WHITELISTED -> whitelisted
            }

            // Search Filter
            var filtered = targetList
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim().lowercase()
                filtered = filtered.filter {
                    it.username.lowercase().contains(q) || it.fullName.lowercase().contains(q)
                }
            }

            // Sub-filters
            when (subFilter) {
                SubFilter.ALL -> {}
                SubFilter.VERIFIED -> filtered = filtered.filter { it.isVerified }
                SubFilter.PRIVATE -> filtered = filtered.filter { it.isPrivate }
                SubFilter.PUBLIC -> filtered = filtered.filter { !it.isPrivate }
            }

            // Sort
            val sorted = when (sortOrder) {
                SortOrder.DEFAULT -> filtered
                SortOrder.AZ -> filtered.sortedBy { it.username.lowercase() }
                SortOrder.ZA -> filtered.sortedByDescending { it.username.lowercase() }
                SortOrder.AGE_NEW -> filtered.sortedByDescending { it.pk.toLongOrNull() ?: 0L }
                SortOrder.AGE_OLD -> filtered.sortedBy { it.pk.toLongOrNull() ?: 0L }
            }

            val followingPks = following.mapTo(HashSet(following.size * 2)) { it.pk.trim() }

            val newState = DashboardUiState(
                displayedUsers = sorted,
                originalFollowersCount = totalFol,
                originalFollowingCount = totalFing,
                notFollowingBackCount = notFollowingBack.size,
                fansCount = loyalFollowers.size,
                mutualsCount = mutualConnections.size,
                recentsCount = recent.size,
                whitelistedCount = whitelisted.size,
                reciprocityPercent = reciprocityPercent,
                followerRatioStr = followerRatioStr,
                netDelta = netDelta,
                activeTab = activeTab,
                searchQuery = searchQuery,
                subFilter = subFilter,
                sortOrder = sortOrder,
                checkedPks = checkedPks,
                whitelistPks = whitelist,
                followingPkSet = followingPks,
                busyPk = busyPk,
                isLoggedIn = prefs.isLoggedIn(),
                isSyncing = isSyncing,
                syncPhaseTitle = syncPhaseTitle,
                syncCounterText = syncCounterText,
                syncProgressFraction = syncProgressFraction,
                lastSyncTime = prefs.getLastSyncTime(),
                myUsername = prefs.getMyUsername(),
                myAvatarUrl = prefs.getMyAvatar()
            )

            withContext(Dispatchers.Main) {
                _uiState.value = newState
            }
        }
    }

    // ====================================================================
    // INSTA WEBVIEW BRIDGE CALLBACKS
    // ====================================================================
    override fun onProfileTotals(followersCount: Int, followingCount: Int, username: String, avatarUrl: String) {
        if (followersCount > 0) originalFollowersCount = followersCount
        if (followingCount > 0) originalFollowingCount = followingCount
        prefs.saveProfileInfo(username, avatarUrl, originalFollowersCount, originalFollowingCount)
        triggerStateCalculation()
    }

    override fun onSyncProgress(phase: String, loadedFollowers: Int, expectedFollowers: Int, loadedFollowing: Int, expectedFollowing: Int, progressFraction: Float) {
        syncPhaseTitle = phase
        syncCounterText = phase
        syncProgressFraction = progressFraction
        triggerStateCalculation()
    }

    override fun onSyncCompleted(followersJson: String, followingJson: String, friendshipStatusesJson: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val parsedFollowers = parseUsersJson(followersJson)
            val parsedFollowing = parseUsersJson(followingJson)
            val parsedStatuses = parseFriendshipStatuses(friendshipStatusesJson)

            if (parsedFollowers.isNotEmpty()) {
                rawFollowers = parsedFollowers
                prefs.saveFollowersImmediate(parsedFollowers)
            }
            if (parsedFollowing.isNotEmpty()) {
                rawFollowing = parsedFollowing
                prefs.saveFollowingImmediate(parsedFollowing)
            }
            if (parsedStatuses.isNotEmpty()) {
                rawFriendshipStatuses = parsedStatuses
            }

            val now = System.currentTimeMillis()
            prefs.setLastSyncTime(now)
            prefs.savePreviousFollowersCount(originalFollowersCount)

            syncProgressFraction = 1f
            syncPhaseTitle = "Sync Complete!"
            syncCounterText = "100% Graph & Server Truth Reconciled (${rawFollowers.size} followers, ${rawFollowing.size} following)"
            delay(350)

            isSyncing = false
            triggerStateCalculation()
        }
    }

    override fun onSyncError(errorMsg: String) {
        isSyncing = false
        triggerStateCalculation()
    }

    override fun onActionResult(pk: String, actionType: String, success: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            val action = if (actionType == "UNFOLLOW") ActionType.UNFOLLOW else ActionType.FOLLOW
            val item = ActionQueueItem(pk = pk, username = pk, action = action)
            applyActionResultInMemory(item)
            busyPk = null
            triggerStateCalculation()
        }
    }

    private fun parseUsersJson(jsonStr: String): List<IGUser> {
        val list = mutableListOf<IGUser>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pk = obj.optString("pk", "").trim()
                if (pk.isNotEmpty()) {
                    list.add(
                        IGUser(
                            pk = pk,
                            username = obj.optString("username", "").trim().lowercase(),
                            fullName = obj.optString("fullName", ""),
                            profilePicUrl = obj.optString("profilePicUrl", ""),
                            isVerified = obj.optBoolean("isVerified", false),
                            isPrivate = obj.optBoolean("isPrivate", false)
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun parseFriendshipStatuses(jsonStr: String): Map<String, FriendshipStatus> {
        val map = mutableMapOf<String, FriendshipStatus>()
        try {
            val obj = JSONObject(jsonStr)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val pk = keys.next()
                val st = obj.optJSONObject(pk)
                if (st != null) {
                    map[pk] = FriendshipStatus(
                        following = st.optBoolean("following", false) || st.optBoolean("outgoing_request", false),
                        followedBy = st.optBoolean("followed_by", false),
                        outgoingRequest = st.optBoolean("outgoing_request", false)
                    )
                }
            }
        } catch (_: Exception) {}
        return map
    }

    // ====================================================================
    // SYNC TRIGGER & ACTIONS
    // ====================================================================
    fun startLiveSync() {
        if (isSyncing) return
        val dsUserId = prefs.getDsUserId()
        val csrfToken = prefs.getCsrfToken()

        isSyncing = true
        syncPhaseTitle = "Stage 1/3: Scanning Followers Graph (0 / $originalFollowersCount)..."
        syncCounterText = "Stage 1/3: Scanning Followers Graph (0 / $originalFollowersCount)..."
        syncProgressFraction = 0.05f
        triggerStateCalculation()

        if (!dsUserId.isNullOrEmpty() && !csrfToken.isNullOrEmpty()) {
            webViewBridge.startSync(dsUserId, csrfToken)

            // Safety guard timeout: 120s
            syncTimeoutJob?.cancel()
            syncTimeoutJob = viewModelScope.launch {
                delay(120000)
                if (isSyncing) {
                    isSyncing = false
                    triggerStateCalculation()
                }
            }
        } else {
            // Realistic offline demo simulation with Server Truth
            viewModelScope.launch {
                delay(400)
                syncPhaseTitle = "Stage 1/3: Scanning Followers Graph (820 / $originalFollowersCount)..."
                syncCounterText = syncPhaseTitle
                syncProgressFraction = 0.20f
                triggerStateCalculation()
                delay(400)

                syncPhaseTitle = "Stage 1/3: Scanning Followers Graph ($originalFollowersCount / $originalFollowersCount)..."
                syncCounterText = syncPhaseTitle
                syncProgressFraction = 0.40f
                triggerStateCalculation()
                delay(400)

                syncPhaseTitle = "Stage 2/3: Scanning Following Graph (920 / $originalFollowingCount)..."
                syncCounterText = syncPhaseTitle
                syncProgressFraction = 0.60f
                triggerStateCalculation()
                delay(400)

                syncPhaseTitle = "Stage 2/3: Scanning Following Graph ($originalFollowingCount / $originalFollowingCount)..."
                syncCounterText = syncPhaseTitle
                syncProgressFraction = 0.80f
                triggerStateCalculation()
                delay(400)

                syncPhaseTitle = "Stage 3/3: Verifying Mutuals & Fans via Server Matrix (50%)..."
                syncCounterText = syncPhaseTitle
                syncProgressFraction = 0.90f
                triggerStateCalculation()
                delay(400)

                syncPhaseTitle = "Stage 3/3: Verifying Mutuals & Fans via Server Matrix (100%)..."
                syncCounterText = syncPhaseTitle
                syncProgressFraction = 1f
                triggerStateCalculation()
                delay(300)

                // Build simulated server truth statuses for sample data
                val simulatedStatuses = mutableMapOf<String, FriendshipStatus>()
                val mutualPks = setOf("1004", "1005", "1006", "1007", "1009", "1010", "1011", "1012", "1013", "1014", "1015")
                val fanPks = setOf("2001", "2002")
                val traitorPks = setOf("1001", "1002", "1003", "1008")

                mutualPks.forEach { simulatedStatuses[it] = FriendshipStatus(following = true, followedBy = true) }
                fanPks.forEach { simulatedStatuses[it] = FriendshipStatus(following = false, followedBy = true) }
                traitorPks.forEach { simulatedStatuses[it] = FriendshipStatus(following = true, followedBy = false) }

                rawFriendshipStatuses = simulatedStatuses
                isSyncing = false
                prefs.setLastSyncTime(System.currentTimeMillis())
                triggerStateCalculation()
            }
        }
    }

    fun startSingleAction(user: IGUser) {
        if (busyPk != null) return

        val isFollowing = rawFollowing.any { it.pk == user.pk }
        val action = if (isFollowing) ActionType.UNFOLLOW else ActionType.FOLLOW
        val item = ActionQueueItem(pk = user.pk, username = user.username, action = action)

        busyPk = user.pk
        triggerStateCalculation()

        val csrfToken = prefs.getCsrfToken() ?: ""
        if (csrfToken.isNotEmpty()) {
            webViewBridge.executeAction(user.pk, action.name, csrfToken)

            // Safety timeout: 8s
            viewModelScope.launch {
                delay(8000)
                if (busyPk == user.pk) {
                    applyActionResultInMemory(item)
                    busyPk = null
                    triggerStateCalculation()
                }
            }
        } else {
            viewModelScope.launch {
                delay(350)
                applyActionResultInMemory(item)
                busyPk = null
                triggerStateCalculation()
            }
        }
    }

    fun startBatchQueueFromSelection() {
        val selectedPks = checkedPks
        if (selectedPks.isEmpty()) return

        val allUsers = (rawFollowers + rawFollowing).associateBy { it.pk }
        val followingSet = rawFollowing.map { it.pk }.toSet()

        val queue = selectedPks.map { pk ->
            val u = allUsers[pk]
            val action = if (followingSet.contains(pk)) ActionType.UNFOLLOW else ActionType.FOLLOW
            ActionQueueItem(
                pk = pk,
                username = u?.username ?: pk,
                action = action
            )
        }

        clearChecked()
        startQueue(queue)
    }

    fun startQueue(queue: List<ActionQueueItem>) {
        if (queue.isEmpty()) return
        executorJob?.cancel()

        _executorState.value = ExecutorUiState(
            visible = true,
            queue = queue,
            currentIndex = 0,
            isPaused = false,
            countdown = 0,
            statusMessage = "Initializing execution engine..."
        )

        runExecutorLoop()
    }

    fun toggleExecutorPause() {
        val currentlyPaused = _executorState.value.isPaused
        _executorState.value = _executorState.value.copy(
            isPaused = !currentlyPaused,
            statusMessage = if (!currentlyPaused) "Queue Paused — Tap Play to Resume" else "Resuming queue execution..."
        )
        if (currentlyPaused) {
            runExecutorLoop()
        }
    }

    fun cancelExecutor() {
        executorJob?.cancel()
        _executorState.value = _executorState.value.copy(visible = false)
        busyPk = null
        triggerStateCalculation()
    }

    private fun runExecutorLoop() {
        executorJob?.cancel()
        executorJob = viewModelScope.launch {
            val csrfToken = prefs.getCsrfToken() ?: ""

            while (_executorState.value.currentIndex < _executorState.value.queue.size) {
                if (_executorState.value.isPaused) break

                val currentIndex = _executorState.value.currentIndex
                val item = _executorState.value.queue[currentIndex]

                busyPk = item.pk
                triggerStateCalculation()
                _executorState.value = _executorState.value.copy(
                    statusMessage = "Executing ${item.action.name.lowercase()} for @${item.username}..."
                )

                if (csrfToken.isNotEmpty() && item.pk != null) {
                    webViewBridge.executeAction(item.pk, item.action.name, csrfToken)
                    delay(800)
                } else {
                    delay(350)
                }

                if (_executorState.value.isPaused) break

                withContext(Dispatchers.Default) {
                    applyActionResultInMemory(item)
                    triggerStateCalculation()
                }

                val actionWord = if (item.action == ActionType.UNFOLLOW) "unfollowed" else "followed"
                _executorState.value = _executorState.value.copy(
                    statusMessage = "✅ Successfully $actionWord @${item.username}"
                )

                // Sequential queue pacing: 2.0s - 3.5s delay
                val isBatch = _executorState.value.queue.size > 1
                if (isBatch && currentIndex + 1 < _executorState.value.queue.size) {
                    val pacingTotalMs = 2000L + Random.nextLong(1500L)
                    val intervals = (pacingTotalMs / 1000L).toInt().coerceAtLeast(2)

                    for (sec in intervals downTo 1) {
                        if (_executorState.value.isPaused) break
                        _executorState.value = _executorState.value.copy(countdown = sec)
                        delay(pacingTotalMs / intervals)
                    }
                    _executorState.value = _executorState.value.copy(countdown = 0)
                }

                if (_executorState.value.isPaused) break

                if (currentIndex + 1 < _executorState.value.queue.size) {
                    _executorState.value = _executorState.value.copy(
                        currentIndex = currentIndex + 1
                    )
                } else {
                    delay(300)
                    _executorState.value = _executorState.value.copy(visible = false)
                    busyPk = null
                    triggerStateCalculation()
                    break
                }
            }
        }
    }

    private fun applyActionResultInMemory(item: ActionQueueItem) {
        val now = System.currentTimeMillis()
        val actionType = if (item.action == ActionType.UNFOLLOW) "unfollowed" else "followed"

        val existingUser = (rawFollowers + rawFollowing)
            .find { it.pk == item.pk || it.username == item.username }
            ?: IGUser(
                pk = item.pk ?: "gen_${System.currentTimeMillis()}",
                username = item.username,
                fullName = item.username
            )

        val updatedUser = existingUser.copy(
            lastAction = actionType,
            actionTimestamp = now
        )

        // Update recent activity
        rawRecent = listOf(updatedUser) + rawRecent.filter {
            it.pk != item.pk && it.username != item.username
        }
        prefs.saveRecentActivity(rawRecent)

        // Update friendship status cache
        val resolvedPk = item.pk ?: existingUser.pk
        val currentSt = rawFriendshipStatuses[resolvedPk]
        if (currentSt != null) {
            val updatedSt = currentSt.copy(
                following = item.action == ActionType.FOLLOW
            )
            rawFriendshipStatuses = rawFriendshipStatuses + (resolvedPk to updatedSt)
        }

        // Update following list & originalFollowingCount
        val currentFollowing = rawFollowing.toMutableList()
        if (item.action == ActionType.UNFOLLOW) {
            currentFollowing.removeAll { it.pk == item.pk || it.username == item.username }
            originalFollowingCount = (originalFollowingCount - 1).coerceAtLeast(0)
            prefs.updateFollowingCount(-1)
        } else {
            val exists = currentFollowing.any { it.pk == item.pk || it.username == item.username }
            if (!exists) {
                currentFollowing.add(0, updatedUser)
                originalFollowingCount += 1
                prefs.updateFollowingCount(1)
            }
        }
        rawFollowing = currentFollowing
        prefs.saveFollowing(currentFollowing)
    }

    fun finishSplash() {
        _showSplash.value = false
    }

    fun openLoginModal() {
        _showLoginModal.value = true
    }

    fun closeLoginModal() {
        _showLoginModal.value = false
    }

    private fun extractCookieValue(cookies: String, key: String): String? {
        val prefix = "$key="
        for (part in cookies.split(";")) {
            val trimmed = part.trim()
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length).trim()
            }
        }
        return null
    }

    fun onLoginSuccess(cookieHeader: String, dsUserId: String = "", csrfToken: String = "") {
        val resolvedDsUserId = dsUserId.ifEmpty { extractCookieValue(cookieHeader, "ds_user_id") ?: "" }
        val resolvedCsrfToken = csrfToken.ifEmpty { extractCookieValue(cookieHeader, "csrftoken") ?: "" }

        prefs.saveAuth(cookieHeader, resolvedDsUserId, resolvedCsrfToken)
        _showLoginModal.value = false
        triggerStateCalculation()

        startLiveSync()
    }

    fun logout() {
        executorJob?.cancel()
        prefs.clearAll()
        rawFollowers = emptyList()
        rawFollowing = emptyList()
        rawRecent = emptyList()
        rawWhitelist = emptySet()
        rawFriendshipStatuses = emptyMap()
        checkedPks = emptySet()
        originalFollowersCount = 0
        originalFollowingCount = 0
        triggerStateCalculation()
        _executorState.value = ExecutorUiState()
    }

    fun loadSampleData() {
        val (sampleFollowers, sampleFollowing) = prefs.loadSampleData()
        rawFollowers = sampleFollowers
        rawFollowing = sampleFollowing
        rawRecent = emptyList()
        rawWhitelist = setOf("1006")
        checkedPks = emptySet()
        originalFollowersCount = 1641
        originalFollowingCount = 1859

        // Realistic server truth statuses
        val simulatedStatuses = mutableMapOf<String, FriendshipStatus>()
        val mutualPks = setOf("1004", "1005", "1006", "1007", "1009", "1010", "1011", "1012", "1013", "1014", "1015")
        val fanPks = setOf("2001", "2002")
        val traitorPks = setOf("1001", "1002", "1003", "1008")

        mutualPks.forEach { simulatedStatuses[it] = FriendshipStatus(following = true, followedBy = true) }
        fanPks.forEach { simulatedStatuses[it] = FriendshipStatus(following = false, followedBy = true) }
        traitorPks.forEach { simulatedStatuses[it] = FriendshipStatus(following = true, followedBy = false) }

        rawFriendshipStatuses = simulatedStatuses
        triggerStateCalculation()
    }

    fun setActiveTab(tab: TabCategory) {
        activeTab = tab
        triggerStateCalculation()
    }

    fun setSearchQuery(query: String) {
        searchQuery = query
        triggerStateCalculation()
    }

    fun setSubFilter(filter: SubFilter) {
        subFilter = filter
        triggerStateCalculation()
    }

    fun cycleSortOrder() {
        val orders = SortOrder.values()
        val currentIdx = orders.indexOf(sortOrder)
        sortOrder = orders[(currentIdx + 1) % orders.size]
        triggerStateCalculation()
    }

    fun toggleCheck(pk: String) {
        val current = checkedPks.toMutableSet()
        if (current.contains(pk)) {
            current.remove(pk)
        } else {
            current.add(pk)
        }
        checkedPks = current
        triggerStateCalculation()
    }

    fun selectAllVisible(pks: List<String>) {
        checkedPks = pks.toSet()
        triggerStateCalculation()
    }

    fun clearChecked() {
        checkedPks = emptySet()
        triggerStateCalculation()
    }

    fun toggleWhitelist(pk: String) {
        val current = rawWhitelist.toMutableSet()
        if (current.contains(pk)) {
            current.remove(pk)
        } else {
            current.add(pk)
        }
        rawWhitelist = current
        prefs.saveWhitelist(current)
        triggerStateCalculation()
    }

    fun onTrimMemory(level: Int) {
        webViewBridge.clearCache()
        com.instapulse.data.image.AvatarMemoryCache.trimToSize(4 * 1024 * 1024)
    }
}
