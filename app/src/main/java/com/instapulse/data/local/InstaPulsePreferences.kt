package com.instapulse.data.local

import android.content.Context
import android.content.SharedPreferences
import com.instapulse.data.model.IGUser
import org.json.JSONArray
import org.json.JSONObject

class InstaPulsePreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("instapulse_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FOLLOWERS = "key_followers_v1"
        private const val KEY_FOLLOWING = "key_following_v1"
        private const val KEY_RECENT = "key_recent_v1"
        private const val KEY_WHITELIST = "key_whitelist_v1"
        private const val KEY_ACTION_LOGS = "key_action_logs_v1"
        private const val KEY_IS_LOGGED_IN = "key_is_logged_in"
        private const val KEY_LAST_SYNC_TIME = "key_last_sync_time"
    }

    fun saveFollowers(users: List<IGUser>) {
        prefs.edit().putString(KEY_FOLLOWERS, serializeUsers(users)).apply()
    }

    fun getFollowers(): List<IGUser> {
        val raw = prefs.getString(KEY_FOLLOWERS, null) ?: return emptyList()
        return deserializeUsers(raw)
    }

    fun saveFollowing(users: List<IGUser>) {
        prefs.edit().putString(KEY_FOLLOWING, serializeUsers(users)).apply()
    }

    fun getFollowing(): List<IGUser> {
        val raw = prefs.getString(KEY_FOLLOWING, null) ?: return emptyList()
        return deserializeUsers(raw)
    }

    fun saveRecentActivity(users: List<IGUser>) {
        prefs.edit().putString(KEY_RECENT, serializeUsers(users)).apply()
    }

    fun getRecentActivity(): List<IGUser> {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        return deserializeUsers(raw)
    }

    fun saveWhitelist(pks: Set<String>) {
        val arr = JSONArray()
        pks.forEach { arr.put(it) }
        prefs.edit().putString(KEY_WHITELIST, arr.toString()).apply()
    }

    fun getWhitelist(): Set<String> {
        val raw = prefs.getString(KEY_WHITELIST, null) ?: return emptySet()
        val set = mutableSetOf<String>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            set.add(arr.getString(i))
        }
        return set
    }

    fun saveActionTimestamps(logs: List<Long>) {
        val arr = JSONArray()
        logs.forEach { arr.put(it) }
        prefs.edit().putString(KEY_ACTION_LOGS, arr.toString()).apply()
    }

    fun getActionTimestamps(): List<Long> {
        val raw = prefs.getString(KEY_ACTION_LOGS, null) ?: return emptyList()
        val list = mutableListOf<Long>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            list.add(arr.getLong(i))
        }
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
            IGUser("1009", "maria_art_studio", "Maria Silva Studio", "", isVerified = false, isPrivate = false),
            IGUser("1011", "nature_escapes", "Epic Nature Escapes", "", isVerified = false, isPrivate = false),
            IGUser("1015", "chef_marcus", "Chef Marcus Bell", "", isVerified = true, isPrivate = false),
            IGUser("2001", "emma_designer", "Emma Watson Design", "", isVerified = false, isPrivate = false),
            IGUser("2002", "lucas_code", "Lucas dev", "", isVerified = false, isPrivate = true),
            IGUser("2003", "neon_cyber", "Cyberpunk Vibes", "", isVerified = true, isPrivate = false),
            IGUser("2004", "clara_reads", "Clara's Book Club", "", isVerified = false, isPrivate = false),
            IGUser("2005", "kevin_runner", "Kevin Marathoner", "", isVerified = false, isPrivate = false),
            IGUser("2006", "bella_foodie", "Bella Eats World", "", isVerified = false, isPrivate = false),
            IGUser("2007", "sunset_captures", "Golden Hour Daily", "", isVerified = false, isPrivate = false)
        )

        saveFollowing(sampleFollowing)
        saveFollowers(sampleFollowers)
        saveWhitelist(setOf("1006"))
        setLastSyncTime(System.currentTimeMillis() - 180000)
        return Pair(sampleFollowers, sampleFollowing)
    }
}
