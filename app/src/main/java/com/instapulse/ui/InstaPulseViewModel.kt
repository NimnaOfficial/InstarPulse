package com.instapulse.ui

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.instapulse.data.local.InstaPulsePreferences
import com.instapulse.data.model.ActionQueueItem
import com.instapulse.data.model.ActionType
import com.instapulse.data.model.FriendshipStatus
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
import android.webkit.CookieManager
import com.instapulse.data.bridge.InstaWebViewBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.random.Random

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
    val myAvatarUrl: String = "",
    val jsCommand: String? = null
)

data class ExecutorUiState(
    val visible: Boolean = false,
    val queue: List<ActionQueueItem> = emptyList(),
    val currentIndex: Int = 0,
    val isPaused: Boolean = false,
    val countdown: Int = 0,
    val statusMessage: String = "Initializing execution engine..."
)

class InstaPulseViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = InstaPulsePreferences(application)

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
    private var jsCommand: String? = null

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _executorState = MutableStateFlow(ExecutorUiState())
    val executorState: StateFlow<ExecutorUiState> = _executorState.asStateFlow()

    private val _showLoginModal = MutableStateFlow(false)
    val showLoginModal: StateFlow<Boolean> = _showLoginModal.asStateFlow()

    private val _showSplash = MutableStateFlow(true)
    val showSplash: StateFlow<Boolean> = _showSplash.asStateFlow()

    private var executorJob: Job? = null
    private var calculationJob: Job? = null

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        val followers = prefs.getFollowers()
        val following = prefs.getFollowing()
        rawRecent = prefs.getRecentActivity()
        rawWhitelist = prefs.getWhitelist()
        rawFriendshipStatuses = prefs.getFriendshipStatuses()
        originalFollowersCount = prefs.getExpectedFollowersCount()
        originalFollowingCount = prefs.getExpectedFollowingCount()

        if (originalFollowersCount == 0) originalFollowersCount = followers.size
        if (originalFollowingCount == 0) originalFollowingCount = following.size

        rawFollowers = followers
        rawFollowing = following

        triggerStateCalculation()
    }

    private fun updateState() {
        _uiState.value = _uiState.value.copy(
            isSyncing = isSyncing,
            syncPhaseTitle = syncPhaseTitle,
            syncCounterText = syncCounterText,
            syncProgressFraction = syncProgressFraction,
            jsCommand = jsCommand
        )
    }

    private fun triggerStateCalculation() {
        calculationJob?.cancel()
        calculationJob = viewModelScope.launch(Dispatchers.Default) {
            val followers = rawFollowers
            val following = rawFollowing
            val recent = rawRecent
            val whitelist = rawWhitelist

            // STEP 3: Deduplicate both lists first
            val dedupedFollowers = followers.distinctBy { it.pk.trim() }
            val dedupedFollowing = following.distinctBy { it.pk.trim() }

            // Build O(1) Lookup Sets matching both pk.trim() AND username.toLowerCase().trim()
            val followerPkSet = HashSet<String>(dedupedFollowers.size)
            val followerUsernameSet = HashSet<String>(dedupedFollowers.size)
            dedupedFollowers.forEach { u ->
                val p = u.pk.trim()
                val n = u.username.lowercase().trim()
                if (p.isNotEmpty()) followerPkSet.add(p)
                if (n.isNotEmpty()) followerUsernameSet.add(n)
            }

            val followingPkSet = HashSet<String>(dedupedFollowing.size)
            val followingUsernameSet = HashSet<String>(dedupedFollowing.size)
            dedupedFollowing.forEach { u ->
                val p = u.pk.trim()
                val n = u.username.lowercase().trim()
                if (p.isNotEmpty()) followingPkSet.add(p)
                if (n.isNotEmpty()) followingUsernameSet.add(n)
            }

            val whitelistPks = whitelist.mapTo(HashSet()) { it.trim().lowercase() }

            fun isInFollowers(u: IGUser): Boolean =
                followerPkSet.contains(u.pk.trim()) || followerUsernameSet.contains(u.username.lowercase().trim())

            fun isInFollowing(u: IGUser): Boolean =
                followingPkSet.contains(u.pk.trim()) || followingUsernameSet.contains(u.username.lowercase().trim())

            fun isWhitelisted(u: IGUser): Boolean =
                whitelistPks.contains(u.pk.trim()) || whitelistPks.contains(u.username.lowercase().trim())

            // List 1: notFollowingBack (Traitors = Following - Followers)
            val notFollowingBack = dedupedFollowing.filter { u -> !isInFollowers(u) && !isWhitelisted(u) }

            // List 2: loyalFollowers (Fans = Followers - Following)
            val loyalFollowers = dedupedFollowers.filter { u -> !isInFollowing(u) && !isWhitelisted(u) }

            // List 3: mutuals (Mutual Friends = Following ∩ Followers)
            val mutualConnections = dedupedFollowing.filter { u -> isInFollowers(u) && !isWhitelisted(u) }

            val whitelisted = (dedupedFollowing + dedupedFollowers).distinctBy { it.pk.trim() }.filter { u -> isWhitelisted(u) }

            val totalFol = dedupedFollowers.size
            val totalFing = dedupedFollowing.size

            val reciprocity = if (totalFing > 0) (mutualConnections.size.toDouble() / totalFing.toDouble()) * 100.0 else 0.0
            val reciprocityPercent = String.format(Locale.US, "%.1f", reciprocity)

            val followerRatio = if (totalFing > 0) totalFol.toDouble() / totalFing.toDouble() else 0.0
            val followerRatioStr = String.format(Locale.US, "%.2f", followerRatio)

            val prevFol = prefs.getPreviousFollowersCount()
            val netDelta = if (prevFol > 0) totalFol - prevFol else 0

            val targetList = when (activeTab) {
                TabCategory.DONT_FOLLOW_BACK -> notFollowingBack
                TabCategory.FANS -> loyalFollowers
                TabCategory.RECENTS -> recent
                TabCategory.MUTUALS -> mutualConnections
                TabCategory.WHITELISTED -> whitelisted
            }

            var filtered = targetList
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim().lowercase()
                filtered = filtered.filter {
                    it.username.lowercase().contains(q) || it.fullName.lowercase().contains(q)
                }
            }

            when (subFilter) {
                SubFilter.ALL -> {}
                SubFilter.VERIFIED -> filtered = filtered.filter { it.isVerified }
                SubFilter.PRIVATE -> filtered = filtered.filter { it.isPrivate }
                SubFilter.PUBLIC -> filtered = filtered.filter { !it.isPrivate }
            }

            val sorted = when (sortOrder) {
                SortOrder.DEFAULT -> filtered
                SortOrder.AZ -> filtered.sortedBy { it.username.lowercase() }
                SortOrder.ZA -> filtered.sortedByDescending { it.username.lowercase() }
                SortOrder.AGE_NEW -> filtered.sortedByDescending { it.pk.toLongOrNull() ?: 0L }
                SortOrder.AGE_OLD -> filtered.sortedBy { it.pk.toLongOrNull() ?: 0L }
            }

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
                followingPkSet = followingPkSet,
                busyPk = busyPk,
                isLoggedIn = prefs.isLoggedIn(),
                isSyncing = isSyncing,
                syncPhaseTitle = syncPhaseTitle,
                syncCounterText = syncCounterText,
                syncProgressFraction = syncProgressFraction,
                lastSyncTime = prefs.getLastSyncTime(),
                myUsername = prefs.getMyUsername(),
                myAvatarUrl = prefs.getMyAvatar(),
                jsCommand = jsCommand
            )

            withContext(Dispatchers.Main) {
                _uiState.value = newState
            }
        }
    }

    fun extractSessionCookies(): Pair<String?, String?> {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        val raw = cookieManager.getCookie("https://www.instagram.com") ?: ""
        var dsUserId: String? = null
        var csrfToken: String? = null
        raw.split(";").forEach { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2) {
                when (kv[0].trim()) {
                    "ds_user_id" -> dsUserId = kv[1].trim()
                    "csrftoken" -> csrfToken = kv[1].trim()
                }
            }
        }
        if (dsUserId.isNullOrEmpty()) dsUserId = prefs.getDsUserId()
        if (csrfToken.isNullOrEmpty()) csrfToken = prefs.getCsrfToken()
        return Pair(dsUserId, csrfToken)
    }

    fun startLiveSync() {
        if (isSyncing) return
        val (dsUserId, csrfToken) = extractSessionCookies()
        if (dsUserId.isNullOrEmpty()) {
            openLoginModal()
            return
        }

        if (!csrfToken.isNullOrEmpty()) {
            prefs.saveAuth(prefs.getCookieHeader() ?: "", dsUserId, csrfToken)
        }

        isSyncing = true
        syncPhaseTitle = "Scanning Followers"
        syncCounterText = "Connecting via REST API..."
        syncProgressFraction = 0.05f
        jsCommand = getSyncJavascript(dsUserId, csrfToken ?: "")
        triggerStateCalculation()
    }
    
    private fun getSyncJavascript(dsUserId: String, csrfToken: String): String {
        return "${InstaWebViewBridge.ENGINE_JS}; window.runRealInstagramSync('$dsUserId', '$csrfToken');"
    }

    fun handleStreamBatch(edgeType: String, totalCount: Int, batchUsersJson: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val batchArray = JSONArray(batchUsersJson)
                val newBatch = ArrayList<IGUser>(batchArray.length())
                for (i in 0 until batchArray.length()) {
                    val obj = batchArray.getJSONObject(i)
                    val pk = obj.getString("pk").trim()
                    val username = obj.getString("username").trim().lowercase()
                    if (pk.isNotEmpty() && username.isNotEmpty()) {
                        newBatch.add(IGUser(
                            pk = pk,
                            username = username,
                            fullName = obj.optString("fullName", ""),
                            profilePicUrl = obj.optString("profilePicUrl", ""),
                            isVerified = obj.optBoolean("isVerified", false),
                            isPrivate = obj.optBoolean("isPrivate", false)
                        ))
                    }
                }

                if (edgeType == "followers") {
                    val existingPks = rawFollowers.map { it.pk.trim() }.toHashSet()
                    val added = newBatch.filter { !existingPks.contains(it.pk.trim()) }
                    if (added.isNotEmpty()) {
                        rawFollowers = rawFollowers + added
                        if (originalFollowersCount < rawFollowers.size) {
                            originalFollowersCount = rawFollowers.size
                        }
                    }
                    syncFollowersCount = rawFollowers.size
                    syncPhaseTitle = "Streaming Followers"
                    syncCounterText = "Loaded ${rawFollowers.size} followers live..."
                    syncProgressFraction = 0.45f
                } else if (edgeType == "following") {
                    val existingPks = rawFollowing.map { it.pk.trim() }.toHashSet()
                    val added = newBatch.filter { !existingPks.contains(it.pk.trim()) }
                    if (added.isNotEmpty()) {
                        rawFollowing = rawFollowing + added
                        if (originalFollowingCount < rawFollowing.size) {
                            originalFollowingCount = rawFollowing.size
                        }
                    }
                    syncFollowingCount = rawFollowing.size
                    syncPhaseTitle = "Streaming Following"
                    syncCounterText = "Loaded ${rawFollowing.size} following live..."
                    syncProgressFraction = 0.85f
                }

                withContext(Dispatchers.Main) {
                    triggerStateCalculation()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var syncFollowersCount = 0
    private var syncFollowingCount = 0

    fun handleProfileInfo(username: String, avatarUrl: String, followerCount: Int, followingCount: Int) {
        viewModelScope.launch(Dispatchers.Main) {
            if (followerCount > 0) originalFollowersCount = followerCount
            if (followingCount > 0) originalFollowingCount = followingCount
            prefs.saveProfileInfo(username, avatarUrl, followerCount, followingCount)
            triggerStateCalculation()
        }
    }

    fun handleSyncProgress(edgeType: String, count: Int, phase: String = "") {
        viewModelScope.launch(Dispatchers.Main) {
            if (edgeType == "followers") {
                syncFollowersCount = count
                syncPhaseTitle = if (phase.isNotBlank()) phase else "Scanning Followers"
                syncCounterText = "Scanned $count followers via REST API..."
                syncProgressFraction = 0.40f
            } else if (edgeType == "following") {
                syncFollowingCount = count
                syncPhaseTitle = if (phase.isNotBlank()) phase else "Scanning Following"
                syncCounterText = "Scanned $count following via REST API..."
                syncProgressFraction = 0.85f
            } else {
                if (phase.isNotBlank()) {
                    syncPhaseTitle = phase
                    syncCounterText = phase
                }
            }
            jsCommand = null
            triggerStateCalculation()
        }
    }

    fun handleFollowersFetched(jsonPayload: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val parsedFollowers = mutableListOf<IGUser>()
                val followersArray = if (jsonPayload.trim().startsWith("[")) {
                    JSONArray(jsonPayload)
                } else {
                    val json = JSONObject(jsonPayload)
                    json.optJSONArray("followers") ?: JSONArray()
                }
                for (i in 0 until followersArray.length()) {
                    val obj = followersArray.getJSONObject(i)
                    parsedFollowers.add(IGUser(
                        pk = obj.getString("pk").trim(),
                        username = obj.getString("username").trim().lowercase(),
                        fullName = obj.optString("fullName", ""),
                        profilePicUrl = obj.optString("profilePicUrl", ""),
                        isVerified = obj.optBoolean("isVerified", false),
                        isPrivate = obj.optBoolean("isPrivate", false)
                    ))
                }

                // CRITICAL: Only overwrite if parsedFollowers is not empty (never overwrite with empty list!)
                if (parsedFollowers.isNotEmpty()) {
                    val prevFollowers = prefs.getFollowers()
                    val newFollowers = if (prevFollowers.isNotEmpty() && parsedFollowers.size < prevFollowers.size * 0.8) {
                        prevFollowers
                    } else {
                        parsedFollowers
                    }

                    rawFollowers = newFollowers
                    if (originalFollowersCount == 0 || newFollowers.size > originalFollowersCount) {
                        originalFollowersCount = newFollowers.size
                    }
                    prefs.saveFollowersImmediate(newFollowers)

                    withContext(Dispatchers.Main) {
                        syncFollowersCount = newFollowers.size
                        syncPhaseTitle = "Followers Loaded (${newFollowers.size})"
                        syncCounterText = "Loaded ${newFollowers.size} followers. Now fetching Following..."
                        syncProgressFraction = 0.50f
                        triggerStateCalculation()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun handleFollowingFetched(jsonPayload: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val parsedFollowing = mutableListOf<IGUser>()
                val followingArray = if (jsonPayload.trim().startsWith("[")) {
                    JSONArray(jsonPayload)
                } else {
                    val json = JSONObject(jsonPayload)
                    json.optJSONArray("following") ?: JSONArray()
                }
                for (i in 0 until followingArray.length()) {
                    val obj = followingArray.getJSONObject(i)
                    parsedFollowing.add(IGUser(
                        pk = obj.getString("pk").trim(),
                        username = obj.getString("username").trim().lowercase(),
                        fullName = obj.optString("fullName", ""),
                        profilePicUrl = obj.optString("profilePicUrl", ""),
                        isVerified = obj.optBoolean("isVerified", false),
                        isPrivate = obj.optBoolean("isPrivate", false)
                    ))
                }

                // CRITICAL: Only overwrite if parsedFollowing is not empty
                if (parsedFollowing.isNotEmpty()) {
                    val prevFollowing = prefs.getFollowing()
                    val newFollowing = if (prevFollowing.isNotEmpty() && parsedFollowing.size < prevFollowing.size * 0.8) {
                        prevFollowing
                    } else {
                        parsedFollowing
                    }

                    rawFollowing = newFollowing
                    if (originalFollowingCount == 0 || newFollowing.size > originalFollowingCount) {
                        originalFollowingCount = newFollowing.size
                    }
                    prefs.saveFollowingImmediate(newFollowing)

                    withContext(Dispatchers.Main) {
                        syncFollowingCount = newFollowing.size
                        syncPhaseTitle = "Following Loaded (${newFollowing.size})"
                        syncCounterText = "Loaded ${newFollowing.size} following. Calculating sets..."
                        syncProgressFraction = 0.90f
                        triggerStateCalculation()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun handleSyncComplete(jsonPayload: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val json = JSONObject(jsonPayload)
                val followersArray = json.optJSONArray("followers") ?: JSONArray()
                val followingArray = json.optJSONArray("following") ?: JSONArray()

                val parsedFollowers = mutableListOf<IGUser>()
                for (i in 0 until followersArray.length()) {
                    val obj = followersArray.getJSONObject(i)
                    parsedFollowers.add(IGUser(
                        pk = obj.getString("pk").trim(),
                        username = obj.getString("username").trim().lowercase(),
                        fullName = obj.optString("fullName", ""),
                        profilePicUrl = obj.optString("profilePicUrl", ""),
                        isVerified = obj.optBoolean("isVerified", false),
                        isPrivate = obj.optBoolean("isPrivate", false)
                    ))
                }

                val parsedFollowing = mutableListOf<IGUser>()
                for (i in 0 until followingArray.length()) {
                    val obj = followingArray.getJSONObject(i)
                    parsedFollowing.add(IGUser(
                        pk = obj.getString("pk").trim(),
                        username = obj.getString("username").trim().lowercase(),
                        fullName = obj.optString("fullName", ""),
                        profilePicUrl = obj.optString("profilePicUrl", ""),
                        isVerified = obj.optBoolean("isVerified", false),
                        isPrivate = obj.optBoolean("isPrivate", false)
                    ))
                }

                // Truncation Guard
                val prevFollowers = prefs.getFollowers()
                val newFollowers = if (parsedFollowers.isEmpty() && prevFollowers.isNotEmpty()) {
                    prevFollowers
                } else if (prevFollowers.isNotEmpty() && parsedFollowers.size < prevFollowers.size * 0.8) {
                    prevFollowers
                } else if (parsedFollowers.isNotEmpty()) {
                    parsedFollowers
                } else {
                    rawFollowers
                }

                val prevFollowing = prefs.getFollowing()
                val newFollowing = if (parsedFollowing.isEmpty() && prevFollowing.isNotEmpty()) {
                    prevFollowing
                } else if (prevFollowing.isNotEmpty() && parsedFollowing.size < prevFollowing.size * 0.8) {
                    prevFollowing
                } else if (parsedFollowing.isNotEmpty()) {
                    parsedFollowing
                } else {
                    rawFollowing
                }

                rawFollowers = newFollowers
                rawFollowing = newFollowing
                if (originalFollowersCount == 0 || newFollowers.size > originalFollowersCount) {
                    originalFollowersCount = newFollowers.size
                }
                if (originalFollowingCount == 0 || newFollowing.size > originalFollowingCount) {
                    originalFollowingCount = newFollowing.size
                }

                prefs.saveFollowersImmediate(newFollowers)
                prefs.saveFollowingImmediate(newFollowing)

                val now = System.currentTimeMillis()
                prefs.setLastSyncTime(now)
                prefs.savePreviousFollowersCount(originalFollowersCount)

                withContext(Dispatchers.Main) {
                    isSyncing = false
                    syncPhaseTitle = "Sync Complete!"
                    syncCounterText = "${newFollowers.size} Followers • ${newFollowing.size} Following"
                    syncProgressFraction = 1f
                    jsCommand = null
                    triggerStateCalculation()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    handleSyncError(e.message ?: "JSON parse error")
                }
            }
        }
    }

    fun handleSyncError(errorMsg: String) {
        viewModelScope.launch(Dispatchers.Main) {
            isSyncing = false
            syncPhaseTitle = "Sync Error"
            syncCounterText = errorMsg
            jsCommand = null
            triggerStateCalculation()
        }
    }
    
    private var actionResultDeferred: CompletableDeferred<Boolean>? = null

    fun handleActionResult(targetPk: String, actionType: String, success: Boolean, status: String = "") {
        viewModelScope.launch(Dispatchers.Main) {
            val confirmed = success || status == "SUCCESS"
            actionResultDeferred?.complete(confirmed)
        }
    }

    private fun extractNativeCsrfToken(): String {
        return try {
            val rawCookies = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: ""
            var csrf = ""
            rawCookies.split(";").forEach { part ->
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0].trim() == "csrftoken") {
                    csrf = kv[1].trim()
                }
            }
            csrf.ifEmpty { prefs.getCsrfToken() ?: "" }
        } catch (e: Exception) {
            prefs.getCsrfToken() ?: ""
        }
    }

    fun startSingleAction(user: IGUser) {
        if (busyPk != null) return
        val targetPk = user.pk.trim()
        val targetUsername = user.username.lowercase().trim()

        val isFollowing = rawFollowing.any { it.pk.trim() == targetPk || it.username.lowercase().trim() == targetUsername }
        val action = if (isFollowing) ActionType.UNFOLLOW else ActionType.FOLLOW

        busyPk = targetPk

        // 0ms OPTIMISTIC REAL-TIME UI MUTATION
        val backupFollowing = rawFollowing.toList()
        val backupRecent = rawRecent.toList()
        val timestamp = System.currentTimeMillis()

        if (action == ActionType.UNFOLLOW) {
            rawFollowing = rawFollowing.filter {
                it.pk.trim() != targetPk && it.username.lowercase().trim() != targetUsername
            }
            val recentsItem = user.copy(lastAction = "unfollowed", actionTimestamp = timestamp)
            rawRecent = listOf(recentsItem) + rawRecent.filter {
                it.pk.trim() != targetPk && it.username.lowercase().trim() != targetUsername
            }
        } else {
            val followedUser = user.copy(lastAction = "followed", actionTimestamp = timestamp)
            if (!rawFollowing.any { it.pk.trim() == targetPk || it.username.lowercase().trim() == targetUsername }) {
                rawFollowing = rawFollowing + followedUser
            }
            rawRecent = listOf(followedUser) + rawRecent.filter {
                it.pk.trim() != targetPk && it.username.lowercase().trim() != targetUsername
            }
        }

        prefs.saveFollowing(rawFollowing)
        prefs.saveRecentActivity(rawRecent)
        triggerStateCalculation() // 0ms UI update!

        val queueItem = ActionQueueItem(pk = targetPk, username = user.username, action = action)
        _executorState.value = ExecutorUiState(
            visible = true,
            queue = listOf(queueItem),
            currentIndex = 0,
            isPaused = false,
            countdown = 0,
            statusMessage = "Executing ${action.name.lowercase()} for @${user.username}..."
        )

        val csrfToken = extractNativeCsrfToken()
        val taskKey = "single_${targetPk}_${System.currentTimeMillis()}"

        executorJob?.cancel()
        executorJob = viewModelScope.launch(Dispatchers.IO) {
            actionResultDeferred = CompletableDeferred()

            withContext(Dispatchers.Main) {
                jsCommand = "${InstaWebViewBridge.ENGINE_JS}; window.executeRealtimeInstaAction('$taskKey', '$targetPk', '${user.username}', '${action.name}', '$csrfToken');"
            }

            val confirmed = withTimeoutOrNull(15000L) {
                actionResultDeferred?.await()
            }

            withContext(Dispatchers.Main) {
                busyPk = null
                jsCommand = null
                if (confirmed == true) {
                    _executorState.value = _executorState.value.copy(
                        statusMessage = "✓ Verified ${action.name.lowercase()} for @${user.username}"
                    )
                    delay(1200L)
                    _executorState.value = _executorState.value.copy(visible = false)
                } else {
                    // Rollback on server error
                    rawFollowing = backupFollowing
                    rawRecent = backupRecent
                    prefs.saveFollowing(rawFollowing)
                    prefs.saveRecentActivity(rawRecent)
                    triggerStateCalculation()
                    _executorState.value = _executorState.value.copy(
                        statusMessage = "❌ Action failed or rate limited. Rolled back."
                    )
                    delay(2500L)
                    _executorState.value = _executorState.value.copy(visible = false)
                }
            }
        }
    }

    fun startBatchQueueFromSelection() {
        val selectedPks = checkedPks
        if (selectedPks.isEmpty()) return
        val allUsers = (rawFollowers + rawFollowing + rawRecent).distinctBy { it.pk.trim() }.associateBy { it.pk.trim() }
        val followingSet = rawFollowing.map { it.pk.trim() }.toSet()
        val queue = selectedPks.map { pk ->
            val u = allUsers[pk.trim()]
            val action = if (followingSet.contains(pk.trim())) ActionType.UNFOLLOW else ActionType.FOLLOW
            ActionQueueItem(pk = pk.trim(), username = u?.username ?: pk.trim(), action = action)
        }
        clearChecked()

        _executorState.value = ExecutorUiState(
            visible = true,
            queue = queue,
            currentIndex = 0,
            isPaused = false,
            countdown = 0,
            statusMessage = "Starting batch queue (${queue.size} items)..."
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
        jsCommand = null
        actionResultDeferred?.cancel()
        triggerStateCalculation()
    }

    private fun runExecutorLoop() {
        executorJob?.cancel()
        executorJob = viewModelScope.launch(Dispatchers.IO) {
            while (_executorState.value.currentIndex < _executorState.value.queue.size) {
                if (_executorState.value.isPaused) break
                val currentIndex = _executorState.value.currentIndex
                val item = _executorState.value.queue[currentIndex]
                val targetPk = item.pk?.trim() ?: ""
                val targetUsername = item.username.lowercase().trim()

                actionResultDeferred = CompletableDeferred()

                val backupFollowing = rawFollowing.toList()
                val backupRecent = rawRecent.toList()
                val timestamp = System.currentTimeMillis()

                withContext(Dispatchers.Main) {
                    busyPk = targetPk
                    // 0ms Optimistic UI mutation per item in batch
                    if (item.action == ActionType.UNFOLLOW) {
                        rawFollowing = rawFollowing.filter {
                            it.pk.trim() != targetPk && it.username.lowercase().trim() != targetUsername
                        }
                        val userObj = (backupFollowing + backupRecent + rawFollowers).firstOrNull {
                            it.pk.trim() == targetPk || it.username.lowercase().trim() == targetUsername
                        } ?: IGUser(pk = targetPk, username = item.username)
                        val recentsItem = userObj.copy(lastAction = "unfollowed", actionTimestamp = timestamp)
                        rawRecent = listOf(recentsItem) + rawRecent.filter {
                            it.pk.trim() != targetPk && it.username.lowercase().trim() != targetUsername
                        }
                    } else {
                        val userObj = (rawFollowers + backupRecent).firstOrNull {
                            it.pk.trim() == targetPk || it.username.lowercase().trim() == targetUsername
                        } ?: IGUser(pk = targetPk, username = item.username)
                        val followedUser = userObj.copy(lastAction = "followed", actionTimestamp = timestamp)
                        if (!rawFollowing.any { it.pk.trim() == targetPk || it.username.lowercase().trim() == targetUsername }) {
                            rawFollowing = rawFollowing + followedUser
                        }
                        rawRecent = listOf(followedUser) + rawRecent.filter {
                            it.pk.trim() != targetPk && it.username.lowercase().trim() != targetUsername
                        }
                    }
                    prefs.saveFollowing(rawFollowing)
                    prefs.saveRecentActivity(rawRecent)
                    triggerStateCalculation()

                    _executorState.value = _executorState.value.copy(
                        statusMessage = "Executing ${item.action.name.lowercase()} for @${item.username} (${currentIndex + 1}/${_executorState.value.queue.size})..."
                    )

                    val csrfToken = extractNativeCsrfToken()
                    val taskKey = "batch_${targetPk}_${System.currentTimeMillis()}"
                    jsCommand = "${InstaWebViewBridge.ENGINE_JS}; window.executeRealtimeInstaAction('$taskKey', '$targetPk', '${item.username}', '${item.action.name}', '$csrfToken');"
                }

                // 15-second Watchdog Timer for execution
                val confirmed = withTimeoutOrNull(15000L) {
                    actionResultDeferred?.await()
                }

                withContext(Dispatchers.Main) {
                    jsCommand = null
                    if (confirmed != true) {
                        // Rollback this specific item
                        rawFollowing = backupFollowing
                        rawRecent = backupRecent
                        prefs.saveFollowing(rawFollowing)
                        prefs.saveRecentActivity(rawRecent)
                        triggerStateCalculation()
                        _executorState.value = _executorState.value.copy(
                            statusMessage = "⚠️ @${item.username} failed — rolling back and continuing..."
                        )
                    } else {
                        _executorState.value = _executorState.value.copy(
                            statusMessage = "✓ Verified @${item.username}"
                        )
                    }
                }

                if (_executorState.value.isPaused) break

                val isBatch = _executorState.value.queue.size > 1
                if (isBatch && currentIndex + 1 < _executorState.value.queue.size) {
                    val pacingTotalMs = 2000L + Random.nextLong(1500L)
                    val intervals = (pacingTotalMs / 1000L).toInt().coerceAtLeast(2)
                    for (sec in intervals downTo 1) {
                        if (_executorState.value.isPaused) break
                        withContext(Dispatchers.Main) {
                            _executorState.value = _executorState.value.copy(countdown = sec)
                        }
                        delay(pacingTotalMs / intervals)
                    }
                    withContext(Dispatchers.Main) {
                        _executorState.value = _executorState.value.copy(countdown = 0)
                    }
                }

                if (_executorState.value.isPaused) break

                withContext(Dispatchers.Main) {
                    if (currentIndex + 1 < _executorState.value.queue.size) {
                        _executorState.value = _executorState.value.copy(currentIndex = currentIndex + 1)
                    } else {
                        _executorState.value = _executorState.value.copy(
                            statusMessage = "🎉 Batch queue completed successfully!"
                        )
                        delay(1200L)
                        _executorState.value = _executorState.value.copy(visible = false)
                        busyPk = null
                        triggerStateCalculation()
                    }
                }
                if (currentIndex + 1 >= _executorState.value.queue.size) break
            }
        }
    }

    fun onTrimMemory(level: Int) {
        if (level >= 15) {
            System.gc()
        }
    }
    
    fun onLoginSuccess(cookieHeader: String, dsUserId: String, csrfToken: String) {
        prefs.saveAuth(cookieHeader, dsUserId, csrfToken)
        _showLoginModal.value = false
        triggerStateCalculation()
        startLiveSync()
    }

    fun openLoginModal() { _showLoginModal.value = true }
    fun closeLoginModal() { _showLoginModal.value = false }
    fun finishSplash() { _showSplash.value = false }

    fun setActiveTab(tab: TabCategory) { activeTab = tab; triggerStateCalculation() }
    fun setSearchQuery(query: String) { searchQuery = query; triggerStateCalculation() }
    fun setSubFilter(filter: SubFilter) { subFilter = filter; triggerStateCalculation() }
    fun cycleSortOrder() {
        sortOrder = when (sortOrder) {
            SortOrder.DEFAULT -> SortOrder.AZ
            SortOrder.AZ -> SortOrder.ZA
            SortOrder.ZA -> SortOrder.AGE_NEW
            SortOrder.AGE_NEW -> SortOrder.AGE_OLD
            SortOrder.AGE_OLD -> SortOrder.DEFAULT
        }
        triggerStateCalculation()
    }
    fun toggleCheck(pk: String) {
        val mut = checkedPks.toMutableSet()
        if (mut.contains(pk)) mut.remove(pk) else mut.add(pk)
        checkedPks = mut
        triggerStateCalculation()
    }
    fun selectAllVisible(pks: List<String>) {
        checkedPks = pks.toSet()
        triggerStateCalculation()
    }
    fun clearChecked() { checkedPks = emptySet(); triggerStateCalculation() }
    fun toggleWhitelist(pk: String) {
        val mut = rawWhitelist.toMutableSet()
        if (mut.contains(pk)) mut.remove(pk) else mut.add(pk)
        rawWhitelist = mut
        prefs.saveWhitelist(mut)
        triggerStateCalculation()
    }
    fun logout() {
        prefs.clearAll()
        rawFollowers = emptyList()
        rawFollowing = emptyList()
        rawRecent = emptyList()
        rawWhitelist = emptySet()
        originalFollowersCount = 0
        originalFollowingCount = 0
        triggerStateCalculation()
    }
}
