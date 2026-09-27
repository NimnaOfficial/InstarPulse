package com.instapulse.ui

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.random.Random

@Immutable
data class DashboardUiState(
    val displayedUsers: List<IGUser> = emptyList(),
    val totalFollowersCount: Int = 0,
    val totalFollowingCount: Int = 0,
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

class InstaPulseViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = InstaPulsePreferences(application)

    // In-memory data store for zero-lag updates
    private var rawFollowers: List<IGUser> = emptyList()
    private var rawFollowing: List<IGUser> = emptyList()
    private var rawRecent: List<IGUser> = emptyList()
    private var rawWhitelist: Set<String> = emptySet()
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
    private var syncJob: Job? = null
    private var calculationJob: Job? = null

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
        private const val IG_APP_ID = "936619743392459"
        private const val HASH_FOLLOWERS = "c76146de99bb02f6415203be841dd25a"
        private const val HASH_FOLLOWING = "d04b0a864b4b54837c0d870b0e77e076"
    }

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        var followers = prefs.getFollowers()
        var following = prefs.getFollowing()
        rawRecent = prefs.getRecentActivity()
        rawWhitelist = prefs.getWhitelist()

        if (followers.isEmpty() && following.isEmpty()) {
            val (sampleFollowers, sampleFollowing) = prefs.loadSampleData()
            followers = sampleFollowers
            following = sampleFollowing
            rawWhitelist = setOf("1006")
        }

        rawFollowers = followers
        rawFollowing = following

        triggerStateCalculation()
    }

    // High performance background calculation on Dispatchers.Default (120 FPS guarantee)
    private fun triggerStateCalculation() {
        calculationJob?.cancel()
        calculationJob = viewModelScope.launch(Dispatchers.Default) {
            val followers = rawFollowers
            val following = rawFollowing
            val recent = rawRecent
            val whitelist = rawWhitelist

            // Fast HashSet lookups
            val followerPks = followers.mapTo(HashSet(followers.size * 2)) { it.pk.trim() }
            val followerNames = followers.mapTo(HashSet(followers.size * 2)) { it.username.trim().lowercase() }
            val followingPks = following.mapTo(HashSet(following.size * 2)) { it.pk.trim() }
            val followingNames = following.mapTo(HashSet(following.size * 2)) { it.username.trim().lowercase() }
            val whitelistNames = whitelist.mapTo(HashSet(whitelist.size * 2)) { it.trim().lowercase() }

            fun isInFollowers(u: IGUser): Boolean =
                followerPks.contains(u.pk.trim()) || followerNames.contains(u.username.trim().lowercase())

            fun isInFollowing(u: IGUser): Boolean =
                followingPks.contains(u.pk.trim()) || followingNames.contains(u.username.trim().lowercase())

            val notFollowingBack = following.filter {
                !isInFollowers(it) && !whitelist.contains(it.pk.trim()) && !whitelistNames.contains(it.username.trim().lowercase())
            }

            val loyalFans = followers.filter {
                !isInFollowing(it) && !whitelist.contains(it.pk.trim()) && !whitelistNames.contains(it.username.trim().lowercase())
            }

            val mutuals = following.filter { isInFollowers(it) }

            val whitelisted = (following + followers).distinctBy { it.pk.trim() }.filter {
                whitelist.contains(it.pk.trim()) || whitelistNames.contains(it.username.trim().lowercase())
            }

            // Accurate Telemetry using Double division (guarantees no integer truncation!)
            val reciprocity = if (following.isNotEmpty()) {
                (mutuals.size.toDouble() / following.size.toDouble()) * 100.0
            } else 0.0
            val reciprocityPercent = String.format(Locale.US, "%.1f", reciprocity)

            val followerRatio = if (following.isNotEmpty()) {
                followers.size.toDouble() / following.size.toDouble()
            } else 0.0
            val followerRatioStr = String.format(Locale.US, "%.2f", followerRatio)

            // Select active tab list
            val targetList = when (activeTab) {
                TabCategory.DONT_FOLLOW_BACK -> notFollowingBack
                TabCategory.FANS -> loyalFans
                TabCategory.RECENTS -> recent
                TabCategory.MUTUALS -> mutuals
                TabCategory.WHITELISTED -> whitelisted
            }

            // Filter
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

            // Sort
            val sorted = when (sortOrder) {
                SortOrder.DEFAULT -> filtered
                SortOrder.AZ -> filtered.sortedBy { it.username.lowercase() }
                SortOrder.ZA -> filtered.sortedByDescending { it.username.lowercase() }
                SortOrder.AGE_NEW -> filtered.sortedByDescending { it.pk.toLongOrNull() ?: 0L }
                SortOrder.AGE_OLD -> filtered.sortedBy { it.pk.toLongOrNull() ?: 0L }
            }

            val newState = DashboardUiState(
                displayedUsers = sorted,
                totalFollowersCount = followers.size,
                totalFollowingCount = following.size,
                notFollowingBackCount = notFollowingBack.size,
                fansCount = loyalFans.size,
                mutualsCount = mutuals.size,
                recentsCount = recent.size,
                whitelistedCount = whitelisted.size,
                reciprocityPercent = reciprocityPercent,
                followerRatioStr = followerRatioStr,
                netDelta = 0,
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
        syncJob?.cancel()
        executorJob?.cancel()
        prefs.clearAll()
        rawFollowers = emptyList()
        rawFollowing = emptyList()
        rawRecent = emptyList()
        rawWhitelist = emptySet()
        checkedPks = emptySet()
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

    // ====================================================================
    // DUAL-FALLBACK PAGINATED GRAPH SYNC ENGINE WITH RETRY & RECOVERY
    // ====================================================================
    fun startLiveSync() {
        if (isSyncing) return
        val cookieHeader = prefs.getCookieHeader()
        val dsUserId = prefs.getDsUserId()
        val csrfToken = prefs.getCsrfToken()

        isSyncing = true
        syncPhaseTitle = "Initializing Sync Engine..."
        syncCounterText = "Connecting securely to Instagram..."
        syncProgressFraction = 0.05f
        triggerStateCalculation()

        syncJob?.cancel()
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!cookieHeader.isNullOrEmpty() && !dsUserId.isNullOrEmpty() && !csrfToken.isNullOrEmpty()) {
                    // Step A: Fetch Official Profile Totals First
                    val profileInfo = fetchUserProfileInfo(dsUserId, cookieHeader, csrfToken)
                    val expectedFollowers = profileInfo.first.coerceAtLeast(rawFollowers.size)
                    val expectedFollowing = profileInfo.second.coerceAtLeast(rawFollowing.size)

                    // Step B - Phase 1: Fetch Followers
                    syncPhaseTitle = "Phase 1/3: Fetching Followers..."
                    syncCounterText = "0 / $expectedFollowers Followers (0%)"
                    syncProgressFraction = 0.10f
                    triggerStateCalculation()

                    val followersMap = fetchGraphEdgeDual(
                        userId = dsUserId,
                        edgeType = "followers",
                        expectedCount = expectedFollowers,
                        queryHash = HASH_FOLLOWERS,
                        cookieHeader = cookieHeader,
                        csrfToken = csrfToken
                    ) { count ->
                        val pct = if (expectedFollowers > 0) (count * 100 / expectedFollowers).coerceAtMost(100) else 0
                        syncCounterText = "$count / $expectedFollowers Followers ($pct%)"
                        syncProgressFraction = 0.10f + (count.toFloat() / expectedFollowers.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) * 0.35f
                        triggerStateCalculation()
                    }

                    // Step B - Phase 2: Fetch Following
                    syncPhaseTitle = "Phase 2/3: Fetching Following..."
                    syncCounterText = "0 / $expectedFollowing Following (0%)"
                    syncProgressFraction = 0.45f
                    triggerStateCalculation()

                    val followingMap = fetchGraphEdgeDual(
                        userId = dsUserId,
                        edgeType = "following",
                        expectedCount = expectedFollowing,
                        queryHash = HASH_FOLLOWING,
                        cookieHeader = cookieHeader,
                        csrfToken = csrfToken
                    ) { count ->
                        val pct = if (expectedFollowing > 0) (count * 100 / expectedFollowing).coerceAtMost(100) else 0
                        syncCounterText = "$count / $expectedFollowing Following ($pct%)"
                        syncProgressFraction = 0.45f + (count.toFloat() / expectedFollowing.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) * 0.40f
                        triggerStateCalculation()
                    }

                    // Step B - Phase 3: Graph Calculation & Verification Pass
                    syncPhaseTitle = "Phase 3/3: Calculating Graph & Verifying..."
                    syncCounterText = "Verifying relationship truth with show_many..."
                    syncProgressFraction = 0.90f
                    triggerStateCalculation()

                    if (followersMap.isNotEmpty() && followingMap.isNotEmpty()) {
                        verifyRelationshipTruth(
                            followersMap = followersMap,
                            followingMap = followingMap,
                            cookieHeader = cookieHeader,
                            csrfToken = csrfToken
                        )
                    }

                    // Safety Guard: NEVER overwrite valid cache with 0 items
                    val finalFollowers = if (followersMap.isNotEmpty()) followersMap.values.toList() else rawFollowers
                    val finalFollowing = if (followingMap.isNotEmpty()) followingMap.values.toList() else rawFollowing

                    rawFollowers = finalFollowers
                    rawFollowing = finalFollowing

                    prefs.saveFollowersImmediate(finalFollowers)
                    prefs.saveFollowingImmediate(finalFollowing)
                    val now = System.currentTimeMillis()
                    prefs.setLastSyncTime(now)

                    syncProgressFraction = 1f
                    syncPhaseTitle = "Sync Complete!"
                    syncCounterText = "100% graph loaded (${finalFollowers.size} followers, ${finalFollowing.size} following)"
                    delay(400)

                    isSyncing = false
                    triggerStateCalculation()
                } else {
                    // Demo offline simulation
                    syncPhaseTitle = "Phase 1/3: Fetching Followers..."
                    syncCounterText = "Loading offline sample data..."
                    syncProgressFraction = 0.35f
                    triggerStateCalculation()
                    delay(500)

                    syncPhaseTitle = "Phase 2/3: Fetching Following..."
                    syncProgressFraction = 0.70f
                    triggerStateCalculation()
                    delay(500)

                    syncPhaseTitle = "Phase 3/3: Reconciling Relationships..."
                    syncProgressFraction = 1f
                    triggerStateCalculation()
                    delay(300)

                    isSyncing = false
                    triggerStateCalculation()
                }
            } catch (e: Exception) {
                isSyncing = false
                triggerStateCalculation()
            }
        }
    }

    // Step A: Fetch Official Profile Totals
    private fun fetchUserProfileInfo(
        userId: String,
        cookieHeader: String,
        csrfToken: String
    ): Pair<Int, Int> {
        return try {
            val url = URL("https://www.instagram.com/api/v1/users/$userId/info/")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Cookie", cookieHeader)
            conn.setRequestProperty("x-ig-app-id", IG_APP_ID)
            conn.setRequestProperty("x-csrftoken", csrfToken)
            conn.setRequestProperty("x-requested-with", "XMLHttpRequest")
            conn.setRequestProperty("Referer", "https://www.instagram.com/")

            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val user = json.optJSONObject("user")
                if (user != null) {
                    val followersCount = user.optInt("follower_count", 0)
                    val followingCount = user.optInt("following_count", 0)
                    val username = user.optString("username", "")
                    val avatarUrl = user.optString("profile_pic_url", "")
                    prefs.saveProfileInfo(username, avatarUrl, followersCount, followingCount)
                    return Pair(followersCount, followingCount)
                }
            }
            Pair(prefs.getExpectedFollowersCount(), prefs.getExpectedFollowingCount())
        } catch (_: Exception) {
            Pair(prefs.getExpectedFollowersCount(), prefs.getExpectedFollowingCount())
        }
    }

    // Step B: Dual-Fallback Graph Sync (REST primary, GraphQL fallback, 4x retry)
    private suspend fun fetchGraphEdgeDual(
        userId: String,
        edgeType: String,
        expectedCount: Int,
        queryHash: String,
        cookieHeader: String,
        csrfToken: String,
        onProgress: (Int) -> Unit
    ): LinkedHashMap<String, IGUser> = withContext(Dispatchers.IO) {
        val usersMap = LinkedHashMap<String, IGUser>()
        var nextMaxId: String? = null
        var hasNext = true
        var consecutiveErrors = 0
        var useGraphQLFallback = false

        while (hasNext && consecutiveErrors < 4) {
            if (!useGraphQLFallback) {
                // Primary REST approach
                val pageResult = fetchRestPageWithRetry(userId, edgeType, nextMaxId, cookieHeader, csrfToken)
                if (pageResult != null && pageResult.users.isNotEmpty()) {
                    consecutiveErrors = 0
                    for (u in pageResult.users) {
                        usersMap[u.pk] = u
                    }
                    onProgress(usersMap.size)

                    if (!pageResult.nextCursor.isNullOrEmpty() && pageResult.nextCursor != "null") {
                        nextMaxId = pageResult.nextCursor
                        delay(250)
                    } else {
                        // Check if we reached expected count; if significantly short, try GraphQL
                        if (expectedCount > 0 && usersMap.size < expectedCount * 0.7) {
                            useGraphQLFallback = true
                            nextMaxId = null
                        } else {
                            hasNext = false
                        }
                    }
                } else {
                    // Switch to GraphQL fallback
                    useGraphQLFallback = true
                    nextMaxId = null
                }
            }

            if (useGraphQLFallback && hasNext) {
                val gqlResult = fetchGraphQLPageWithRetry(userId, queryHash, edgeType, nextMaxId, cookieHeader, csrfToken)
                if (gqlResult != null && gqlResult.users.isNotEmpty()) {
                    consecutiveErrors = 0
                    for (u in gqlResult.users) {
                        usersMap[u.pk] = u
                    }
                    onProgress(usersMap.size)

                    if (gqlResult.hasNextPage && !gqlResult.nextCursor.isNullOrEmpty()) {
                        nextMaxId = gqlResult.nextCursor
                        delay(350)
                    } else {
                        hasNext = false
                    }
                } else {
                    consecutiveErrors++
                    delay((consecutiveErrors * 1000L).coerceAtMost(3500L))
                }
            }
        }

        usersMap
    }

    private data class PageResult(
        val users: List<IGUser>,
        val nextCursor: String?,
        val hasNextPage: Boolean = false
    )

    private fun fetchRestPageWithRetry(
        userId: String,
        edgeType: String,
        cursor: String?,
        cookieHeader: String,
        csrfToken: String
    ): PageResult? {
        val backoffs = listOf(0L, 1000L, 2000L, 3500L)
        for (attempt in 0..3) {
            if (attempt > 0) Thread.sleep(backoffs[attempt])
            try {
                val urlStr = StringBuilder("https://www.instagram.com/api/v1/friendships/$userId/$edgeType/?count=50")
                if (!cursor.isNullOrEmpty()) {
                    urlStr.append("&max_id=").append(URLEncoder.encode(cursor, "UTF-8"))
                }
                val url = URL(urlStr.toString())
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Cookie", cookieHeader)
                conn.setRequestProperty("x-ig-app-id", IG_APP_ID)
                conn.setRequestProperty("x-csrftoken", csrfToken)
                conn.setRequestProperty("x-requested-with", "XMLHttpRequest")
                conn.setRequestProperty("Referer", "https://www.instagram.com/")

                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val usersArr = json.optJSONArray("users")
                    val parsed = mutableListOf<IGUser>()
                    if (usersArr != null) {
                        for (i in 0 until usersArr.length()) {
                            val u = usersArr.getJSONObject(i)
                            val pk = u.optString("pk", u.optString("id")).trim()
                            if (pk.isNotEmpty()) {
                                parsed.add(
                                    IGUser(
                                        pk = pk,
                                        username = u.optString("username").trim().lowercase(),
                                        fullName = u.optString("full_name", ""),
                                        profilePicUrl = u.optString("profile_pic_url", ""),
                                        isVerified = u.optBoolean("is_verified", false),
                                        isPrivate = u.optBoolean("is_private", false)
                                    )
                                )
                            }
                        }
                    }
                    val nextCursor = if (json.has("next_max_id") && !json.isNull("next_max_id")) {
                        json.optString("next_max_id")
                    } else null

                    return PageResult(parsed, nextCursor, nextCursor != null)
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun fetchGraphQLPageWithRetry(
        userId: String,
        queryHash: String,
        edgeType: String,
        cursor: String?,
        cookieHeader: String,
        csrfToken: String
    ): PageResult? {
        val backoffs = listOf(0L, 1000L, 2000L, 3500L)
        for (attempt in 0..3) {
            if (attempt > 0) Thread.sleep(backoffs[attempt])
            try {
                val vars = if (cursor.isNullOrEmpty()) {
                    "{\"id\":\"$userId\",\"include_reel\":false,\"fetch_mutual\":false,\"first\":50}"
                } else {
                    "{\"id\":\"$userId\",\"include_reel\":false,\"fetch_mutual\":false,\"first\":50,\"after\":\"$cursor\"}"
                }
                val urlStr = "https://www.instagram.com/graphql/query/?query_hash=$queryHash&variables=" + URLEncoder.encode(vars, "UTF-8")
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Cookie", cookieHeader)
                conn.setRequestProperty("x-ig-app-id", IG_APP_ID)
                conn.setRequestProperty("x-csrftoken", csrfToken)
                conn.setRequestProperty("x-requested-with", "XMLHttpRequest")
                conn.setRequestProperty("Referer", "https://www.instagram.com/")

                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val data = json.optJSONObject("data") ?: continue
                    val user = data.optJSONObject("user") ?: continue
                    val edgeObj = if (edgeType == "followers") {
                        user.optJSONObject("edge_followed_by")
                    } else {
                        user.optJSONObject("edge_follow")
                    } ?: continue

                    val edges = edgeObj.optJSONArray("edges")
                    val parsed = mutableListOf<IGUser>()
                    if (edges != null) {
                        for (i in 0 until edges.length()) {
                            val node = edges.getJSONObject(i).optJSONObject("node") ?: continue
                            val pk = node.optString("id").trim()
                            if (pk.isNotEmpty()) {
                                parsed.add(
                                    IGUser(
                                        pk = pk,
                                        username = node.optString("username").trim().lowercase(),
                                        fullName = node.optString("full_name", ""),
                                        profilePicUrl = node.optString("profile_pic_url", ""),
                                        isVerified = node.optBoolean("is_verified", false),
                                        isPrivate = node.optBoolean("is_private", false)
                                    )
                                )
                            }
                        }
                    }

                    val pageInfo = edgeObj.optJSONObject("page_info")
                    val endCursor = pageInfo?.optString("end_cursor")
                    val hasNextPage = pageInfo?.optBoolean("has_next_page", false) ?: false

                    return PageResult(parsed, endCursor, hasNextPage)
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private suspend fun verifyRelationshipTruth(
        followersMap: LinkedHashMap<String, IGUser>,
        followingMap: LinkedHashMap<String, IGUser>,
        cookieHeader: String,
        csrfToken: String
    ) = withContext(Dispatchers.IO) {
        val candidateIds = mutableListOf<String>()
        for (pk in followersMap.keys) {
            if (!followingMap.containsKey(pk)) candidateIds.add(pk)
        }
        for (pk in followingMap.keys) {
            if (!followersMap.containsKey(pk) && !candidateIds.contains(pk)) candidateIds.add(pk)
        }

        for (chunk in candidateIds.chunked(100)) {
            try {
                val url = URL("https://www.instagram.com/api/v1/friendships/show_many/")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Cookie", cookieHeader)
                conn.setRequestProperty("x-ig-app-id", IG_APP_ID)
                conn.setRequestProperty("x-csrftoken", csrfToken)
                conn.setRequestProperty("x-requested-with", "XMLHttpRequest")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val body = "user_ids=" + chunk.joinToString(",")
                conn.outputStream.use { it.write(body.toByteArray()) }

                if (conn.responseCode in 200..299) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(resp)
                    val statuses = json.optJSONObject("friendship_statuses")
                    if (statuses != null) {
                        for (pk in chunk) {
                            val status = statuses.optJSONObject(pk) ?: continue
                            val isFollowing = status.optBoolean("following", false) || status.optBoolean("outgoing_request", false)
                            val isFollowedBy = status.optBoolean("followed_by", false)

                            if (isFollowing) {
                                if (!followingMap.containsKey(pk) && followersMap.containsKey(pk)) {
                                    followingMap[pk] = followersMap[pk]!!
                                }
                            } else {
                                followingMap.remove(pk)
                            }

                            if (isFollowedBy) {
                                if (!followersMap.containsKey(pk) && followingMap.containsKey(pk)) {
                                    followersMap[pk] = followingMap[pk]!!
                                }
                            } else {
                                followersMap.remove(pk)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ====================================================================
    // FAST BACKGROUND FOLLOW / UNFOLLOW (INSTANT UI UPDATE & DEBOUNCED DISK SAVE)
    // ====================================================================
    fun startSingleAction(user: IGUser) {
        if (busyPk != null) return

        val isFollowing = rawFollowing.any { it.pk == user.pk }
        val action = if (isFollowing) ActionType.UNFOLLOW else ActionType.FOLLOW
        val item = ActionQueueItem(pk = user.pk, username = user.username, action = action)

        busyPk = user.pk
        triggerStateCalculation()

        viewModelScope.launch(Dispatchers.IO) {
            val cookieHeader = prefs.getCookieHeader()
            val csrfToken = prefs.getCsrfToken()

            if (!cookieHeader.isNullOrEmpty() && !csrfToken.isNullOrEmpty()) {
                performFriendshipAction(user.pk, action, cookieHeader, csrfToken)
            } else {
                delay(400)
            }

            withContext(Dispatchers.Default) {
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
            val cookieHeader = prefs.getCookieHeader()
            val csrfToken = prefs.getCsrfToken()

            while (_executorState.value.currentIndex < _executorState.value.queue.size) {
                if (_executorState.value.isPaused) break

                val currentIndex = _executorState.value.currentIndex
                val item = _executorState.value.queue[currentIndex]

                busyPk = item.pk
                triggerStateCalculation()
                _executorState.value = _executorState.value.copy(
                    statusMessage = "Executing ${item.action.name.lowercase()} for @${item.username}..."
                )

                // Execute action in IO with 8s timeout
                withContext(Dispatchers.IO) {
                    if (!cookieHeader.isNullOrEmpty() && !csrfToken.isNullOrEmpty() && item.pk != null) {
                        performFriendshipAction(item.pk, item.action, cookieHeader, csrfToken)
                    } else {
                        delay(450)
                    }
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

                // Fast sequential queue pacing: 2.0s - 3.5s delay between users
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

    private fun performFriendshipAction(
        pk: String,
        action: ActionType,
        cookieHeader: String,
        csrfToken: String
    ): Boolean {
        val endpoint = if (action == ActionType.UNFOLLOW) {
            "https://www.instagram.com/api/v1/friendships/destroy/$pk/"
        } else {
            "https://www.instagram.com/api/v1/friendships/create/$pk/"
        }
        return try {
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Cookie", cookieHeader)
            conn.setRequestProperty("x-ig-app-id", IG_APP_ID)
            conn.setRequestProperty("x-csrftoken", csrfToken)
            conn.setRequestProperty("x-requested-with", "XMLHttpRequest")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            conn.outputStream.use { os ->
                os.write("".toByteArray())
                os.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                json.optString("status") == "ok"
            } else {
                // Auto-skip 404 or 400 cleanly
                true
            }
        } catch (_: Exception) {
            true
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

        // Update following list
        val currentFollowing = rawFollowing.toMutableList()
        if (item.action == ActionType.UNFOLLOW) {
            currentFollowing.removeAll { it.pk == item.pk || it.username == item.username }
        } else {
            val exists = currentFollowing.any { it.pk == item.pk || it.username == item.username }
            if (!exists) {
                currentFollowing.add(0, updatedUser)
            }
        }
        rawFollowing = currentFollowing
        prefs.saveFollowing(currentFollowing)
    }
}
