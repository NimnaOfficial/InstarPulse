package com.instapulse.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.instapulse.data.local.InstaPulsePreferences
import com.instapulse.data.model.ActionQueueItem
import com.instapulse.data.model.ActionType
import com.instapulse.data.model.IGUser
import com.instapulse.data.model.SortOrder
import com.instapulse.data.model.SubFilter
import com.instapulse.data.model.TabCategory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

data class DashboardUiState(
    val followers: List<IGUser> = emptyList(),
    val following: List<IGUser> = emptyList(),
    val recentActivity: List<IGUser> = emptyList(),
    val whitelistPks: Set<String> = emptySet(),
    val checkedPks: Set<String> = emptySet(),
    val activeTab: TabCategory = TabCategory.DONT_FOLLOW_BACK,
    val searchQuery: String = "",
    val subFilter: SubFilter = SubFilter.ALL,
    val sortOrder: SortOrder = SortOrder.DEFAULT,
    val isLoggedIn: Boolean = false,
    val isSyncing: Boolean = false,
    val syncFollowersCount: Int = 0,
    val syncFollowingCount: Int = 0,
    val lastSyncTime: Long = 0L,
    val busyPk: String? = null,
    val netDelta: Int = 0
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

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _executorState = MutableStateFlow(ExecutorUiState())
    val executorState: StateFlow<ExecutorUiState> = _executorState.asStateFlow()

    private val _showLoginModal = MutableStateFlow(false)
    val showLoginModal: StateFlow<Boolean> = _showLoginModal.asStateFlow()

    private val _showSplash = MutableStateFlow(true)
    val showSplash: StateFlow<Boolean> = _showSplash.asStateFlow()

    private var executorJob: Job? = null
    private var countdownJob: Job? = null

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        var followers = prefs.getFollowers()
        var following = prefs.getFollowing()
        val recent = prefs.getRecentActivity()
        val whitelist = prefs.getWhitelist()
        val loggedIn = prefs.isLoggedIn()
        val lastSync = prefs.getLastSyncTime()

        // If fresh install with no data, prepopulate with realistic sample data
        if (followers.isEmpty() && following.isEmpty()) {
            val (sampleFollowers, sampleFollowing) = prefs.loadSampleData()
            followers = sampleFollowers
            following = sampleFollowing
        }

        _uiState.value = _uiState.value.copy(
            followers = followers,
            following = following,
            recentActivity = recent,
            whitelistPks = whitelist,
            isLoggedIn = loggedIn,
            lastSyncTime = lastSync
        )
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

    fun onLoginSuccess(dsUserId: String, csrfToken: String) {
        prefs.setLoggedIn(true)
        _uiState.value = _uiState.value.copy(isLoggedIn = true)
        _showLoginModal.value = false
        startLiveSync()
    }

    fun logout() {
        prefs.clearAll()
        _uiState.value = DashboardUiState(isLoggedIn = false)
    }

    fun loadSampleData() {
        val (sampleFollowers, sampleFollowing) = prefs.loadSampleData()
        _uiState.value = _uiState.value.copy(
            followers = sampleFollowers,
            following = sampleFollowing,
            recentActivity = emptyList(),
            whitelistPks = setOf("1006"),
            lastSyncTime = System.currentTimeMillis()
        )
    }

    fun setActiveTab(tab: TabCategory) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun setSubFilter(filter: SubFilter) {
        _uiState.value = _uiState.value.copy(subFilter = filter)
    }

    fun cycleSortOrder() {
        val orders = SortOrder.values()
        val currentIdx = orders.indexOf(_uiState.value.sortOrder)
        val next = orders[(currentIdx + 1) % orders.size]
        _uiState.value = _uiState.value.copy(sortOrder = next)
    }

    fun toggleCheck(pk: String) {
        val current = _uiState.value.checkedPks.toMutableSet()
        if (current.contains(pk)) {
            current.remove(pk)
        } else {
            if (current.size < 50) current.add(pk)
        }
        _uiState.value = _uiState.value.copy(checkedPks = current)
    }

    fun selectAllVisible(pks: List<String>) {
        _uiState.value = _uiState.value.copy(checkedPks = pks.take(50).toSet())
    }

    fun clearChecked() {
        _uiState.value = _uiState.value.copy(checkedPks = emptySet())
    }

    fun toggleWhitelist(pk: String) {
        val current = _uiState.value.whitelistPks.toMutableSet()
        if (current.contains(pk)) {
            current.remove(pk)
        } else {
            current.add(pk)
        }
        prefs.saveWhitelist(current)
        _uiState.value = _uiState.value.copy(whitelistPks = current)
    }

    fun startLiveSync() {
        if (_uiState.value.isSyncing) return
        _uiState.value = _uiState.value.copy(
            isSyncing = true,
            syncFollowersCount = 0,
            syncFollowingCount = 0
        )

        viewModelScope.launch {
            // Simulated live network pagination with realistic progress
            for (i in 1..4) {
                delay(400)
                _uiState.value = _uiState.value.copy(
                    syncFollowersCount = i * 25,
                    syncFollowingCount = i * 30
                )
            }
            delay(500)
            val now = System.currentTimeMillis()
            prefs.setLastSyncTime(now)
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                lastSyncTime = now
            )
        }
    }

    fun startSingleAction(user: IGUser) {
        val isFollowing = _uiState.value.following.any { it.pk == user.pk }
        val action = if (isFollowing) ActionType.UNFOLLOW else ActionType.FOLLOW
        startQueue(listOf(ActionQueueItem(pk = user.pk, username = user.username, action = action)))
    }

    fun startBatchQueueFromSelection() {
        val selectedPks = _uiState.value.checkedPks
        if (selectedPks.isEmpty()) return

        val allUsers = (_uiState.value.followers + _uiState.value.following).associateBy { it.pk }
        val followingSet = _uiState.value.following.map { it.pk }.toSet()

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
        countdownJob?.cancel()

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
        countdownJob?.cancel()
        _executorState.value = _executorState.value.copy(visible = false)
        _uiState.value = _uiState.value.copy(busyPk = null)
    }

    private fun runExecutorLoop() {
        executorJob?.cancel()
        executorJob = viewModelScope.launch {
            while (_executorState.value.currentIndex < _executorState.value.queue.size) {
                if (_executorState.value.isPaused) break

                val currentIndex = _executorState.value.currentIndex
                val item = _executorState.value.queue[currentIndex]

                _uiState.value = _uiState.value.copy(busyPk = item.pk)
                _executorState.value = _executorState.value.copy(
                    statusMessage = "Executing ${item.action.name.lowercase()} for @${item.username}..."
                )

                // Realistic execution delay (API roundtrip)
                delay(600 + Random.nextLong(200))

                if (_executorState.value.isPaused) break

                // Apply action to state
                applyActionResult(item)

                val actionWord = if (item.action == ActionType.UNFOLLOW) "unfollowed" else "followed"
                _executorState.value = _executorState.value.copy(
                    statusMessage = "✅ Successfully $actionWord @${item.username}"
                )

                // Ultra-Fast Human Pacing delay before next task
                val isBatch = _executorState.value.queue.size > 1
                val pacingSec = if (isBatch) Random.nextInt(1, 3) else 1

                for (sec in pacingSec downTo 1) {
                    if (_executorState.value.isPaused) break
                    _executorState.value = _executorState.value.copy(countdown = sec)
                    delay(1000)
                }
                _executorState.value = _executorState.value.copy(countdown = 0)

                if (_executorState.value.isPaused) break

                if (currentIndex + 1 < _executorState.value.queue.size) {
                    _executorState.value = _executorState.value.copy(
                        currentIndex = currentIndex + 1
                    )
                } else {
                    // Queue finished
                    delay(500)
                    _executorState.value = _executorState.value.copy(visible = false)
                    _uiState.value = _uiState.value.copy(busyPk = null)
                    break
                }
            }
        }
    }

    private fun applyActionResult(item: ActionQueueItem) {
        val now = System.currentTimeMillis()
        val actionType = if (item.action == ActionType.UNFOLLOW) "unfollowed" else "followed"

        val existingUser = (_uiState.value.followers + _uiState.value.following)
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
        val newRecent = listOf(updatedUser) + _uiState.value.recentActivity.filter {
            it.pk != item.pk && it.username != item.username
        }
        prefs.saveRecentActivity(newRecent)

        // Update following list
        val currentFollowing = _uiState.value.following.toMutableList()
        if (item.action == ActionType.UNFOLLOW) {
            currentFollowing.removeAll { it.pk == item.pk || it.username == item.username }
        } else {
            val exists = currentFollowing.any { it.pk == item.pk || it.username == item.username }
            if (!exists) {
                currentFollowing.add(0, updatedUser)
            }
        }
        prefs.saveFollowing(currentFollowing)

        _uiState.value = _uiState.value.copy(
            following = currentFollowing,
            recentActivity = newRecent
        )
    }
}
