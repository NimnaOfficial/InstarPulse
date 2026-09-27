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

            val followerPks = followers.mapTo(HashSet()) { it.pk.trim() }
            val followerNames = followers.mapTo(HashSet()) { it.username.trim().lowercase() }
            val followingPks = following.mapTo(HashSet()) { it.pk.trim() }
            val followingNames = following.mapTo(HashSet()) { it.username.trim().lowercase() }
            val whitelistPks = whitelist.mapTo(HashSet()) { it.trim().lowercase() }

            val notFollowingBack = following.filter { !followerPks.contains(it.pk.trim()) && !followerNames.contains(it.username.trim().lowercase()) && !whitelistPks.contains(it.pk.trim()) }
            val loyalFollowers = followers.filter { !followingPks.contains(it.pk.trim()) && !followingNames.contains(it.username.trim().lowercase()) }
            val mutualConnections = following.filter { followerPks.contains(it.pk.trim()) || followerNames.contains(it.username.trim().lowercase()) }

            val whitelisted = (following + followers).distinctBy { it.pk.trim() }.filter {
                whitelist.contains(it.pk.trim()) || whitelistPks.contains(it.username.trim().lowercase())
            }

            val totalFol = followers.size
            val totalFing = following.size

            val reciprocity = if (following.isNotEmpty()) (mutualConnections.size.toDouble() / following.size.toDouble()) * 100.0 else 0.0
            val reciprocityPercent = String.format(Locale.US, "%.1f", reciprocity)

            val followerRatio = if (following.isNotEmpty()) followers.size.toDouble() / following.size.toDouble() else 0.0
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
                followingPkSet = followingPks,
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

    fun startLiveSync() {
        if (isSyncing) return
        val dsUserId = prefs.getDsUserId()
        if (dsUserId.isNullOrEmpty()) {
            openLoginModal()
            return
        }

        isSyncing = true
        syncPhaseTitle = "Initializing sync..."
        syncCounterText = "Waiting for Javascript bridge..."
        syncProgressFraction = 0.0f
        jsCommand = getSyncJavascript() + "\nwindow.runRealInstagramSync();"
        triggerStateCalculation()
    }
    
    private fun getSyncJavascript(): String {
        return """
            (function() {
              if (window.__INSTAPULSE_BRIDGE_ACTIVE) return;
              window.__INSTAPULSE_BRIDGE_ACTIVE = true;
            
              const IG_APP_ID = '936619743392459';
            
              function getCookie(name) {
                const match = document.cookie.match(new RegExp('(^| )' + name + '=([^;]+)'));
                return match ? decodeURIComponent(match[2]) : null;
              }
            
              const sleep = (ms) => new Promise(r => setTimeout(r, ms));
            
              function sendToNative(payload) {
                if (window.InstaNativeBridge && window.InstaNativeBridge.postMessage) {
                  window.InstaNativeBridge.postMessage(JSON.stringify(payload));
                }
              }
            
              function checkAuthStatus() {
                const dsUserId = getCookie('ds_user_id');
                const csrfToken = getCookie('csrftoken');
                sendToNative({
                  type: 'AUTH_STATUS',
                  isLoggedIn: Boolean(dsUserId && csrfToken),
                  dsUserId: dsUserId || null
                });
              }
            
              checkAuthStatus();
              setInterval(checkAuthStatus, 2000);
            
              async function fetchGraphEdge(userId, edgeType) {
                let allUsers = [];
                let nextMaxId = '';
                let hasNext = true;
            
                while (hasNext) {
                  // CRITICAL: Must be ?count=50 (Instagram rejects count=100 on followers!)
                  const url = 'https://www.instagram.com/api/v1/friendships/' + userId + '/' + edgeType + '/?count=50' + (nextMaxId ? '&max_id=' + encodeURIComponent(nextMaxId) : '');
                  
                  let res = null;
                  let data = null;
                  let success = false;
            
                  const backoffs = [1500, 3000, 5000];
                  for (let attempt = 0; attempt < 3; attempt++) {
                    try {
                      res = await fetch(url, {
                        method: 'GET',
                        credentials: 'include',
                        headers: {
                          'x-ig-app-id': IG_APP_ID,
                          'x-csrftoken': getCookie('csrftoken') || '',
                          'x-requested-with': 'XMLHttpRequest'
                        }
                      });
            
                      if (res.ok) {
                        data = await res.json();
                        success = true;
                        break;
                      } else {
                        if (attempt < 2) await sleep(backoffs[attempt]);
                      }
                    } catch (err) {
                      if (attempt < 2) await sleep(backoffs[attempt]);
                    }
                  }
            
                  if (!success) {
                    throw new Error('Instagram HTTP error while fetching ' + edgeType + ' (fetched ' + allUsers.length + ' so far)');
                  }
            
                  const batch = (data.users || []).map(u => ({
                    pk: String(u.pk || u.id).trim(),
                    username: String(u.username || '').toLowerCase().trim(),
                    fullName: String(u.full_name || ''),
                    profilePicUrl: String(u.profile_pic_url || ''),
                    isVerified: Boolean(u.is_verified),
                    isPrivate: Boolean(u.is_private)
                  }));
            
                  allUsers = allUsers.concat(batch);
            
                  sendToNative({
                    type: 'SYNC_PROGRESS',
                    edgeType: edgeType,
                    count: allUsers.length
                  });
            
                  if (data.next_max_id && data.big_list !== false) {
                    nextMaxId = data.next_max_id;
                    await sleep(600 + Math.random() * 300);
                  } else {
                    hasNext = false;
                  }
                }
            
                const seen = new Set();
                const deduped = [];
                for (let i = 0; i < allUsers.length; i++) {
                  const key = allUsers[i].pk;
                  if (!seen.has(key)) {
                    seen.add(key);
                    deduped.push(allUsers[i]);
                  }
                }
                return deduped;
              }
            
              window.runRealInstagramSync = async function() {
                try {
                  const myId = getCookie('ds_user_id');
                  if (!myId) {
                    sendToNative({ type: 'AUTH_REQUIRED' });
                    return;
                  }
            
                  const followers = await fetchGraphEdge(myId, 'followers');
                  const following = await fetchGraphEdge(myId, 'following');
            
                  sendToNative({
                    type: 'SYNC_SUCCESS',
                    followers: followers,
                    following: following
                  });
                } catch (err) {
                  sendToNative({
                    type: 'SYNC_ERROR',
                    message: err.message || 'Failed to sync graph'
                  });
                }
              };
            })();
            true;
        """.trimIndent()
    }

    private var syncFollowersCount = 0
    private var syncFollowingCount = 0

    fun handleSyncProgress(edgeType: String, count: Int) {
        viewModelScope.launch(Dispatchers.Main) {
            if (edgeType == "followers") syncFollowersCount = count
            else if (edgeType == "following") syncFollowingCount = count
            
            syncPhaseTitle = "Syncing Live Graph..."
            syncCounterText = "Syncing Followers: $syncFollowersCount • Syncing Following: $syncFollowingCount"
            syncProgressFraction = if (edgeType.startsWith("follower")) 0.3f else 0.7f
            jsCommand = null // clear so we don't re-trigger JS loop
            triggerStateCalculation()
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
                        pk = obj.getString("pk"),
                        username = obj.getString("username"),
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
                        pk = obj.getString("pk"),
                        username = obj.getString("username"),
                        fullName = obj.optString("fullName", ""),
                        profilePicUrl = obj.optString("profilePicUrl", ""),
                        isVerified = obj.optBoolean("isVerified", false),
                        isPrivate = obj.optBoolean("isPrivate", false)
                    ))
                }
                
                // Truncation Guard (from App.tsx)
                val prevFollowers = prefs.getFollowers()
                val newFollowers = if (prevFollowers.isNotEmpty() && parsedFollowers.size < prevFollowers.size * 0.8) {
                    prevFollowers
                } else {
                    parsedFollowers
                }

                rawFollowers = newFollowers
                rawFollowing = parsedFollowing
                originalFollowersCount = newFollowers.size
                originalFollowingCount = parsedFollowing.size

                prefs.saveFollowersImmediate(newFollowers)
                prefs.saveFollowingImmediate(parsedFollowing)
                
                val now = System.currentTimeMillis()
                prefs.setLastSyncTime(now)
                prefs.savePreviousFollowersCount(originalFollowersCount)
                
                withContext(Dispatchers.Main) {
                    isSyncing = false
                    syncPhaseTitle = "Sync Complete!"
                    syncCounterText = "100% Graph Reconciled"
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
        val u = (rawFollowers + rawFollowing).find { it.pk == item.pk } ?: return
        val timestamp = System.currentTimeMillis()
        val updatedUser = u.copy(lastAction = if (item.action == ActionType.UNFOLLOW) "unfollowed" else "followed", actionTimestamp = timestamp)
        
        val mutRecent = rawRecent.toMutableList()
        mutRecent.removeAll { it.pk == item.pk }
        mutRecent.add(0, updatedUser)
        rawRecent = mutRecent
        prefs.saveRecentActivity(mutRecent)

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
        val actionType = if (isFollowing) "unfollow" else "follow"
        busyPk = user.pk
        
        // Use the same Executor HUD for single actions
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
                
                withContext(Dispatchers.Main) {
                    busyPk = item.pk
                    _executorState.value = _executorState.value.copy(
                        statusMessage = "Executing ${item.action.name.lowercase()} for @${item.username}..."
                    )
                    triggerStateCalculation()
                }

                // 12-second Watchdog Timer to wait for WebView to finish executing 3-step action
                val success = kotlinx.coroutines.withTimeoutOrNull(12000L) {
                    actionResultDeferred?.await()
                }

                if (success == null) {
                    withContext(Dispatchers.Main) {
                        _executorState.value = _executorState.value.copy(
                            statusMessage = "❌ Timeout waiting for DOM"
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
