package com.instapulse.data.local

import android.content.Context
import android.content.SharedPreferences
import com.instapulse.data.model.IGUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class InstaPulsePreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("instapulse_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var saveFollowingJob: Job? = null
    private var saveFollowersJob: Job? = null
    private var saveRecentJob: Job? = null

    // Fast in-memory cache for zero-lag access
    @Volatile private var cachedFollowers: List<IGUser>? = null
    @Volatile private var cachedFollowing: List<IGUser>? = null
    @Volatile private var cachedRecent: List<IGUser>? = null
    @Volatile private var cachedWhitelist: Set<String>? = null

    companion object {
        private const val KEY_FOLLOWERS = "key_followers_v1"
        private const val KEY_FOLLOWING = "key_following_v1"
        private const val KEY_RECENT = "key_recent_v1"
        private const val KEY_WHITELIST = "key_whitelist_v1"
        private const val KEY_ACTION_LOGS = "key_action_logs_v1"
        private const val KEY_IS_LOGGED_IN = "key_is_logged_in"
        private const val KEY_LAST_SYNC_TIME = "key_last_sync_time"
        private const val KEY_COOKIE_HEADER = "key_cookie_header"
        private const val KEY_DS_USER_ID = "key_ds_user_id"
        private const val KEY_CSRF_TOKEN = "key_csrf_token"
        private const val KEY_MY_USERNAME = "key_my_username"
        private const val KEY_MY_AVATAR = "key_my_avatar"
        private const val KEY_TOTAL_FOLLOWERS = "key_total_followers"
        private const val KEY_TOTAL_FOLLOWING = "key_total_following"
        private const val KEY_PREV_FOLLOWERS = "key_prev_followers"
    }

    fun saveAuth(cookieHeader: String, dsUserId: String, csrfToken: String) {
        prefs.edit()
            .putString(KEY_COOKIE_HEADER, cookieHeader)
            .putString(KEY_DS_USER_ID, dsUserId)
            .putString(KEY_CSRF_TOKEN, csrfToken)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
    }

    fun saveProfileInfo(username: String, avatarUrl: String, followerCount: Int, followingCount: Int) {
        prefs.edit()
            .putString(KEY_MY_USERNAME, username)
            .putString(KEY_MY_AVATAR, avatarUrl)
            .putInt(KEY_TOTAL_FOLLOWERS, followerCount)
            .putInt(KEY_TOTAL_FOLLOWING, followingCount)
            .apply()
    }

    fun getMyUsername(): String = prefs.getString(KEY_MY_USERNAME, "") ?: ""
    fun getMyAvatar(): String = prefs.getString(KEY_MY_AVATAR, "") ?: ""
    fun getExpectedFollowersCount(): Int = prefs.getInt(KEY_TOTAL_FOLLOWERS, 0)
    fun getExpectedFollowingCount(): Int = prefs.getInt(KEY_TOTAL_FOLLOWING, 0)

    fun getPreviousFollowersCount(): Int = prefs.getInt(KEY_PREV_FOLLOWERS, 0)
    fun savePreviousFollowersCount(count: Int) {
        prefs.edit().putInt(KEY_PREV_FOLLOWERS, count).apply()
    }

    fun updateFollowingCount(delta: Int) {
        val current = getExpectedFollowingCount()
        val updated = (current + delta).coerceAtLeast(0)
        prefs.edit().putInt(KEY_TOTAL_FOLLOWING, updated).apply()
    }

    fun getCookieHeader(): String? = prefs.getString(KEY_COOKIE_HEADER, null)
    fun getDsUserId(): String? = prefs.getString(KEY_DS_USER_ID, null)
    fun getCsrfToken(): String? = prefs.getString(KEY_CSRF_TOKEN, null)

    fun saveFollowers(users: List<IGUser>) {
        cachedFollowers = users
        saveFollowersJob?.cancel()
        saveFollowersJob = scope.launch(Dispatchers.IO) {
            delay(500) // 500ms debounce
            val serialized = serializeUsers(users)
            prefs.edit().putString(KEY_FOLLOWERS, serialized).apply()
        }
    }

    suspend fun saveFollowersImmediate(users: List<IGUser>) = withContext(Dispatchers.IO) {
        cachedFollowers = users
        saveFollowersJob?.cancel()
        val serialized = serializeUsers(users)
        prefs.edit().putString(KEY_FOLLOWERS, serialized).apply()
    }

    fun getFollowers(): List<IGUser> {
        cachedFollowers?.let { return it }
        val raw = prefs.getString(KEY_FOLLOWERS, null) ?: return emptyList()
        val parsed = deserializeUsers(raw)
        cachedFollowers = parsed
        return parsed
    }

    suspend fun getFollowersSuspending(): List<IGUser> = withContext(Dispatchers.IO) {
        getFollowers()
    }

    fun saveFollowing(users: List<IGUser>) {
        cachedFollowing = users
        saveFollowingJob?.cancel()
        saveFollowingJob = scope.launch(Dispatchers.IO) {
            delay(500) // 500ms debounce
            val serialized = serializeUsers(users)
            prefs.edit().putString(KEY_FOLLOWING, serialized).apply()
        }
    }

    suspend fun saveFollowingImmediate(users: List<IGUser>) = withContext(Dispatchers.IO) {
        cachedFollowing = users
        saveFollowingJob?.cancel()
        val serialized = serializeUsers(users)
        prefs.edit().putString(KEY_FOLLOWING, serialized).apply()
    }

    fun getFollowing(): List<IGUser> {
        cachedFollowing?.let { return it }
        val raw = prefs.getString(KEY_FOLLOWING, null) ?: return emptyList()
        val parsed = deserializeUsers(raw)
        cachedFollowing = parsed
        return parsed
    }

    suspend fun getFollowingSuspending(): List<IGUser> = withContext(Dispatchers.IO) {
        getFollowing()
    }

    fun saveRecentActivity(users: List<IGUser>) {
        cachedRecent = users
        saveRecentJob?.cancel()
        saveRecentJob = scope.launch(Dispatchers.IO) {
            delay(500)
            val serialized = serializeUsers(users)
            prefs.edit().putString(KEY_RECENT, serialized).apply()
        }
    }

    fun getRecentActivity(): List<IGUser> {
        cachedRecent?.let { return it }
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        val parsed = deserializeUsers(raw)
        cachedRecent = parsed
        return parsed
    }

    fun saveWhitelist(pks: Set<String>) {
        cachedWhitelist = pks
        scope.launch(Dispatchers.IO) {
            val arr = JSONArray()
            pks.forEach { arr.put(it) }
            prefs.edit().putString(KEY_WHITELIST, arr.toString()).apply()
        }
    }

    fun getWhitelist(): Set<String> {
        cachedWhitelist?.let { return it }
        val raw = prefs.getString(KEY_WHITELIST, null) ?: return emptySet()
        val set = mutableSetOf<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
        } catch (_: Exception) { }
        cachedWhitelist = set
        return set
    }

    fun saveActionTimestamps(logs: List<Long>) {
        scope.launch(Dispatchers.IO) {
            val arr = JSONArray()
            logs.forEach { arr.put(it) }
            prefs.edit().putString(KEY_ACTION_LOGS, arr.toString()).apply()
        }
    }

    fun getActionTimestamps(): List<Long> {
        val raw = prefs.getString(KEY_ACTION_LOGS, null) ?: return emptyList()
        val list = mutableListOf<Long>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                list.add(arr.getLong(i))
            }
        } catch (_: Exception) { }
        return list
    }

    fun setLoggedIn(loggedIn: Boolean) {
        prefs.edit().putBoolean(KEY_IS_LOGGED_IN, loggedIn).apply()
    }

    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_IS_LOGGED_IN, false)

    fun setLastSyncTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, time).apply()
    }

    fun getLastSyncTime(): Long = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)

    fun clearAll() {
        cachedFollowers = null
        cachedFollowing = null
        cachedRecent = null
        cachedWhitelist = null
        prefs.edit().clear().apply()
    }

    private fun serializeUsers(users: List<IGUser>): String {
        val arr = JSONArray()
        for (u in users) {
            val obj = JSONObject()
            obj.put("pk", u.pk)
            obj.put("username", u.username)
            obj.put("fullName", u.fullName)
            obj.put("profilePicUrl", u.profilePicUrl)
            obj.put("isVerified", u.isVerified)
            obj.put("isPrivate", u.isPrivate)
            if (u.lastAction != null) obj.put("lastAction", u.lastAction)
            if (u.actionTimestamp != null) obj.put("actionTimestamp", u.actionTimestamp)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun deserializeUsers(raw: String): List<IGUser> {
        val list = mutableListOf<IGUser>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    IGUser(
                        pk = obj.getString("pk"),
                        username = obj.getString("username"),
                        fullName = obj.optString("fullName", ""),
                        profilePicUrl = obj.optString("profilePicUrl", ""),
                        isVerified = obj.optBoolean("isVerified", false),
                        isPrivate = obj.optBoolean("isPrivate", false),
                        lastAction = if (obj.has("lastAction")) obj.getString("lastAction") else null,
                        actionTimestamp = if (obj.has("actionTimestamp")) obj.getLong("actionTimestamp") else null
                    )
                )
            }
        } catch (_: Exception) { }
        return list
    }

    fun loadSampleData(): Pair<List<IGUser>, List<IGUser>> {
        val sampleFollowing = listOf(
            IGUser("1001", "tech_insider", "Tech Insider Daily", "", isVerified = true, isPrivate = false),
            IGUser("1002", "crypto_whale", "Crypto News & Alpha", "", isVerified = false, isPrivate = true),
            IGUser("1003", "design_guru", "UI/UX Inspiration", "", isVerified = true, isPrivate = false),
            IGUser("1004", "sarah_wanderlust", "Sarah Jenkins | Travel", "", isVerified = false, isPrivate = false),
            IGUser("1005", "alex_photos", "Alex Chen Photography", "", isVerified = false, isPrivate = false),
            IGUser("1006", "urban_coffee_roasters", "Urban Coffee Roasters", "", isVerified = true, isPrivate = false),
            IGUser("1007", "fitness_pro_mike", "Mike Sterling Fitness", "", isVerified = false, isPrivate = false),
            IGUser("1008", "vintage_watches", "Horology & Collectibles", "", isVerified = false, isPrivate = true),
            IGUser("1009", "maria_art_studio", "Maria Silva Studio", "", isVerified = false, isPrivate = false),
            IGUser("1010", "startup_digest", "Startup & VC Wire", "", isVerified = true, isPrivate = false),
            IGUser("1011", "nature_escapes", "Epic Nature Escapes", "", isVerified = false, isPrivate = false),
            IGUser("1012", "nordic_interior", "Nordic Living & Style", "", isVerified = false, isPrivate = false),
            IGUser("1013", "david_beats", "David Kim Beats", "", isVerified = false, isPrivate = true),
            IGUser("1014", "streetwear_daily", "Streetwear Archive", "", isVerified = true, isPrivate = false),
            IGUser("1015", "chef_marcus", "Chef Marcus Bell", "", isVerified = true, isPrivate = false)
        )

        val sampleFollowers = listOf(
            IGUser("1004", "sarah_wanderlust", "Sarah Jenkins | Travel", "", isVerified = false, isPrivate = false),
            IGUser("1005", "alex_photos", "Alex Chen Photography", "", isVerified = false, isPrivate = false),
            IGUser("1006", "urban_coffee_roasters", "Urban Coffee Roasters", "", isVerified = true, isPrivate = false),
            IGUser("1007", "fitness_pro_mike", "Mike Sterling Fitness", "", isVerified = false, isPrivate = false),
            IGUser("1009", "maria_art_studio", "Maria Silva Studio", "", isVerified = false, isPrivate = false),
            IGUser("1010", "startup_digest", "Startup & VC Wire", "", isVerified = true, isPrivate = false),
            IGUser("1011", "nature_escapes", "Epic Nature Escapes", "", isVerified = false, isPrivate = false),
            IGUser("1012", "nordic_interior", "Nordic Living & Style", "", isVerified = false, isPrivate = false),
            IGUser("1013", "david_beats", "David Kim Beats", "", isVerified = false, isPrivate = true),
            IGUser("1014", "streetwear_daily", "Streetwear Archive", "", isVerified = true, isPrivate = false),
            IGUser("1015", "chef_marcus", "Chef Marcus Bell", "", isVerified = true, isPrivate = false),
            IGUser("2001", "emma_designer", "Emma Watson Design", "", isVerified = false, isPrivate = false),
            IGUser("2002", "lucas_code", "Lucas dev", "", isVerified = false, isPrivate = true)
        )

        cachedFollowing = sampleFollowing
        cachedFollowers = sampleFollowers
        cachedWhitelist = setOf("1006")

        scope.launch(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_FOLLOWING, serializeUsers(sampleFollowing))
                .putString(KEY_FOLLOWERS, serializeUsers(sampleFollowers))
                .putString(KEY_WHITELIST, JSONArray().put("1006").toString())
                .putInt(KEY_TOTAL_FOLLOWERS, 1641)
                .putInt(KEY_TOTAL_FOLLOWING, 1859)
                .putString(KEY_MY_USERNAME, "alex_creator")
                .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis() - 180000)
                .apply()
        }
        return Pair(sampleFollowers, sampleFollowing)
    }
}
