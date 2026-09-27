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
        return """
            (function() {
              if (window.__INSTAPULSE_BRIDGE_ACTIVE) return;
              window.__INSTAPULSE_BRIDGE_ACTIVE = true;
              window.__IS_SYNCING_LOCK = false;

              const IG_APP_ID = '936619743392459';
              let lastAuthState = null;

              function getCookie(name) {
                const match = document.cookie.match(new RegExp('(^| )' + name + '=([^;]+)'));
                return match ? decodeURIComponent(match[2]) : null;
              }

              function sendMessage(payload) {
                const str = JSON.stringify(payload);
                if (window.InstaNativeBridge && window.InstaNativeBridge.postMessage) {
                  window.InstaNativeBridge.postMessage(str);
                } else if (window.ReactNativeWebView && window.ReactNativeWebView.postMessage) {
                  window.ReactNativeWebView.postMessage(str);
                } else if (window.AndroidBridge && window.AndroidBridge.postMessage) {
                  window.AndroidBridge.postMessage(str);
                }
              }

              const sleep = (ms) => new Promise(r => setTimeout(r, ms));

              // Only emit AUTH_STATUS when login state changes (prevents 2-second sync spam!)
              function checkAuthStatus() {
                const dsUserId = getCookie('ds_user_id');
                const csrfToken = getCookie('csrftoken');
                const isLoggedIn = Boolean(dsUserId && csrfToken);
                const stateKey = isLoggedIn + '_' + (dsUserId || '');
                if (stateKey !== lastAuthState) {
                  lastAuthState = stateKey;
                  sendMessage({
                    type: 'AUTH_STATUS',
                    isLoggedIn: isLoggedIn,
                    dsUserId: dsUserId || null
                  });
                }
              }

              checkAuthStatus();
              setInterval(checkAuthStatus, 2500);

              async function fetchGraphEdgeREST(userId, edgeType, passedCsrf) {
                const usersMap = new Map();
                let nextMaxId = '';
                let hasNext = true;
                let countParam = 50; // Automatically steps down to 12 if IG rejects 50

                while (hasNext) {
                  const url = 'https://www.instagram.com/api/v1/friendships/' + userId + '/' + edgeType +
                    '/?count=' + countParam + (nextMaxId ? '&max_id=' + encodeURIComponent(nextMaxId) : '');

                  let data = null;
                  let success = false;
                  const backoffs = [1500, 3000, 5000, 8000];

                  for (let attempt = 0; attempt < 4; attempt++) {
                    try {
                      const csrf = getCookie('csrftoken') || passedCsrf || '';
                      const res = await fetch(url, {
                        method: 'GET',
                        credentials: 'include',
                        headers: {
                          'x-ig-app-id': IG_APP_ID,
                          'x-csrftoken': csrf,
                          'x-requested-with': 'XMLHttpRequest'
                        }
                      });

                      if (res.ok) {
                        data = await res.json();
                        success = true;
                        break;
                      } else if (res.status === 400 && countParam === 50) {
                        // If Instagram rejects count=50 on followers, immediately switch to count=12
                        countParam = 12;
                        await sleep(800);
                      } else {
                        await sleep(backoffs[attempt]);
                      }
                    } catch (err) {
                      await sleep(backoffs[attempt]);
                    }
                  }

                  if (!success || !data) {
                    // CRITICAL FIX: Do NOT throw and wipe out users already fetched!
                    // Keep all users in usersMap and stop paginating this edge cleanly.
                    sendMessage({
                      type: 'STATUS',
                      message: 'Finished ' + edgeType + ' (' + usersMap.size + ' loaded)'
                    });
                    break;
                  }

                  const batch = data.users || [];
                  for (let i = 0; i < batch.length; i++) {
                    const u = batch[i];
                    const pk = String(u.pk || u.id || '').trim();
                    const username = String(u.username || '').toLowerCase().trim();
                    if (!pk || !username) continue;
                    usersMap.set(pk, {
                      pk: pk,
                      username: username,
                      fullName: String(u.full_name || ''),
                      profilePicUrl: String(u.profile_pic_url || ''),
                      isVerified: Boolean(u.is_verified),
                      isPrivate: Boolean(u.is_private)
                    });
                  }

                  sendMessage({
                    type: 'SYNC_PROGRESS',
                    edgeType: edgeType,
                    count: usersMap.size,
                    phase: 'Scanning ' + edgeType + ' (' + usersMap.size + ' accounts)...'
                  });

                  if (data.next_max_id !== null && data.next_max_id !== undefined && String(data.next_max_id) !== '' && data.big_list !== false) {
                    nextMaxId = String(data.next_max_id);
                    await sleep(650 + Math.random() * 350);
                  } else {
                    hasNext = false;
                  }
                }

                return Array.from(usersMap.values());
              }

              window.runRealInstagramSync = async function(passedUserId, passedCsrf) {
                if (window.__IS_SYNCING_LOCK) return;
                window.__IS_SYNCING_LOCK = true;

                try {
                  const myId = passedUserId || getCookie('ds_user_id');
                  const csrf = passedCsrf || getCookie('csrftoken') || '';
                  if (!myId) {
                    window.__IS_SYNCING_LOCK = false;
                    sendMessage({ type: 'AUTH_REQUIRED' });
                    return;
                  }

                  // Profile Info
                  try {
                    const profRes = await fetch('https://www.instagram.com/api/v1/users/' + myId + '/info/', {
                      method: 'GET',
                      credentials: 'include',
                      headers: {
                        'x-ig-app-id': IG_APP_ID,
                        'x-csrftoken': csrf,
                        'x-requested-with': 'XMLHttpRequest'
                      }
                    });
                    if (profRes.ok) {
                      const profData = await profRes.json();
                      const u = profData.user || {};
                      sendMessage({
                        type: 'PROFILE_INFO',
                        username: u.username || '',
                        avatarUrl: u.profile_pic_url || '',
                        followerCount: u.follower_count || 0,
                        followingCount: u.following_count || 0
                      });
                    }
                  } catch (eProf) {}

                  // 1. Fetch Followers Separately & Send Immediately to UI
                  sendMessage({ type: 'STATUS', message: 'Scanning Followers via REST API...' });
                  const followers = await fetchGraphEdgeREST(myId, 'followers', csrf);
                  sendMessage({
                    type: 'FOLLOWERS_LOADED',
                    followers: followers
                  });

                  // Short cooldown before scanning following
                  await sleep(1000);

                  // 2. Fetch Following Separately & Send Immediately to UI
                  sendMessage({ type: 'STATUS', message: 'Scanning Following via REST API...' });
                  const following = await fetchGraphEdgeREST(myId, 'following', csrf);
                  sendMessage({
                    type: 'FOLLOWING_LOADED',
                    following: following
                  });

                  window.__IS_SYNCING_LOCK = false;
                  sendMessage({
                    type: 'SYNC_SUCCESS',
                    followers: followers,
                    following: following
                  });
                } catch (err) {
                  window.__IS_SYNCING_LOCK = false;
                  sendMessage({
                    type: 'SYNC_ERROR',
                    message: err.message || 'Sync error'
                  });
                }
              };

              window.runRealInstagramSync('$dsUserId', '$csrfToken');
            })();
            true;
        """.trimIndent()
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
    
    private var actionResultDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    fun handleActionResult(targetPk: String, actionType: String, success: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            jsCommand = null
            if (success) {
                val action = if (actionType == "unfollow") ActionType.UNFOLLOW else ActionType.FOLLOW
                applyActionResultInMemory(ActionQueueItem(targetPk, targetPk, action))
            }
            if (_executorState.value.visible && executorJob != null) {
                _executorState.value = _executorState.value.copy(
                    statusMessage = if (success) "✅ Success" else "❌ Failed"
                )
                actionResultDeferred?.complete(success)
            } else {
                busyPk = null
                triggerStateCalculation()
            }
        }
    }

    private fun applyActionResultInMemory(item: ActionQueueItem) {
        val u = (rawFollowers + rawFollowing).find { it.pk == item.pk }
        val targetUser = u ?: IGUser(pk = item.pk ?: "", username = item.username)
        val timestamp = System.currentTimeMillis()
        val updatedUser = targetUser.copy(
            lastAction = if (item.action == ActionType.UNFOLLOW) "unfollowed" else "followed",
            actionTimestamp = timestamp
        )
        
        val mutRecent = rawRecent.toMutableList()
        mutRecent.removeAll { it.pk == item.pk }
        mutRecent.add(0, updatedUser)
        rawRecent = mutRecent
        prefs.saveRecentActivity(mutRecent)

        // Update server-truth matrix in memory & preferences
        val resolvedPk = item.pk ?: targetUser.pk
        val currentSt = rawFriendshipStatuses[resolvedPk]
        val updatedSt = (currentSt ?: FriendshipStatus()).copy(
            following = item.action == ActionType.FOLLOW
        )
        rawFriendshipStatuses = rawFriendshipStatuses + (resolvedPk to updatedSt)
        prefs.saveFriendshipStatuses(rawFriendshipStatuses)

        if (item.action == ActionType.UNFOLLOW) {
            val mutFollowing = rawFollowing.toMutableList()
            mutFollowing.removeAll { it.pk == item.pk }
            rawFollowing = mutFollowing
            prefs.saveFollowing(mutFollowing)
        } else {
            val mutFollowing = rawFollowing.toMutableList()
            if (!mutFollowing.any { it.pk == item.pk }) {
                mutFollowing.add(updatedUser)
            }
            rawFollowing = mutFollowing
            prefs.saveFollowing(mutFollowing)
        }
    }

    fun startSingleAction(user: IGUser) {
        if (busyPk != null) return
        val isFollowing = rawFollowing.any { it.pk == user.pk }
        busyPk = user.pk
        
        _executorState.value = ExecutorUiState(
            visible = true,
            queue = listOf(ActionQueueItem(pk = user.pk, username = user.username, action = if (isFollowing) ActionType.UNFOLLOW else ActionType.FOLLOW)),
            currentIndex = 0,
            isPaused = false,
            countdown = 0,
            statusMessage = "Initializing single action..."
        )
        runExecutorLoop()
    }

    fun startBatchQueueFromSelection() {
        val selectedPks = checkedPks
        if (selectedPks.isEmpty()) return
        val allUsers = (rawFollowers + rawFollowing).associateBy { it.pk }
        val followingSet = rawFollowing.map { it.pk }.toSet()
        val queue = selectedPks.map { pk ->
            val u = allUsers[pk]
            val action = if (followingSet.contains(pk)) ActionType.UNFOLLOW else ActionType.FOLLOW
            ActionQueueItem(pk = pk, username = u?.username ?: pk, action = action)
        }
        clearChecked()
        
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

                actionResultDeferred = kotlinx.coroutines.CompletableDeferred()
                
                val (_, csrfToken) = extractSessionCookies()
                val actionTypeStr = if (item.action == ActionType.UNFOLLOW) "unfollow" else "follow"
                val actionScript = """
                    (async function() {
                        const targetPk = '${item.pk}';
                        const actionType = '$actionTypeStr';
                        const csrf = '$csrfToken';
                        const IG_APP_ID = '936619743392459';
                        function getCookie(name) {
                            const match = document.cookie.match(new RegExp('(^| )' + name + '=([^;]+)'));
                            return match ? decodeURIComponent(match[2]) : null;
                        }
                        const effectiveCsrf = csrf || getCookie('csrftoken') || '';
                        try {
                            const endpoint = actionType === 'unfollow' 
                                ? 'https://www.instagram.com/api/v1/friendships/destroy/' + targetPk + '/'
                                : 'https://www.instagram.com/api/v1/friendships/create/' + targetPk + '/';
                            const res = await fetch(endpoint, {
                                method: 'POST',
                                credentials: 'include',
                                headers: {
                                    'content-type': 'application/x-www-form-urlencoded',
                                    'x-ig-app-id': IG_APP_ID,
                                    'x-csrftoken': effectiveCsrf,
                                    'x-requested-with': 'XMLHttpRequest'
                                }
                            });
                            const data = await res.json();
                            const ok = res.ok && (data.status === 'ok' || Boolean(data.friendship_status));
                            if (window.InstaNativeBridge) {
                                window.InstaNativeBridge.postMessage(JSON.stringify({
                                    type: 'ACTION_RESULT',
                                    targetPk: targetPk,
                                    actionType: actionType,
                                    success: ok
                                }));
                            }
                        } catch(e) {
                            if (window.InstaNativeBridge) {
                                window.InstaNativeBridge.postMessage(JSON.stringify({
                                    type: 'ACTION_RESULT',
                                    targetPk: targetPk,
                                    actionType: actionType,
                                    success: false
                                }));
                            }
                        }
                    })();
                    true;
                """.trimIndent()

                withContext(Dispatchers.Main) {
                    busyPk = item.pk
                    _executorState.value = _executorState.value.copy(
                        statusMessage = "Executing ${item.action.name.lowercase()} for @${item.username}..."
                    )
                    jsCommand = actionScript
                    triggerStateCalculation()
                }

                // 12-second Watchdog Timer to wait for WebView to finish executing action
                val success = kotlinx.coroutines.withTimeoutOrNull(12000L) {
                    actionResultDeferred?.await()
                }

                if (success == null) {
                    withContext(Dispatchers.Main) {
                        _executorState.value = _executorState.value.copy(
                            statusMessage = "❌ Timeout waiting for server response"
                        )
                    }
                }

                if (_executorState.value.isPaused) break

                val isBatch = _executorState.value.queue.size > 1
                if (isBatch && currentIndex + 1 < _executorState.value.queue.size) {
                    val pacingTotalMs = 2500L + Random.nextLong(1500L)
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
