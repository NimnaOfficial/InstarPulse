import os
import re

file_path = r'C:\Users\SANDANIMNE\Desktop\MyCodes\InstarPulse\app\src\main\java\com\instapulse\ui\InstaPulseViewModel.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Remove InstaWebViewBridge imports and callback interface
content = re.sub(r'import com\.instapulse\.data\.bridge\.InstaBridgeCallback\n', '', content)
content = re.sub(r'import com\.instapulse\.data\.bridge\.InstaWebViewBridge\n', '', content)

# Add URL and HttpURLConnection imports
imports = '''import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.InputStreamReader
import java.io.BufferedReader
import android.webkit.CookieManager'''

content = content.replace('import org.json.JSONObject\n', 'import org.json.JSONObject\n' + imports + '\n')

# Change class declaration
content = content.replace('class InstaPulseViewModel(application: Application) : AndroidViewModel(application), InstaBridgeCallback {', 'class InstaPulseViewModel(application: Application) : AndroidViewModel(application) {')

# Remove webViewBridge instantiation
content = re.sub(r'\s*val webViewBridge = InstaWebViewBridge\(this\)\n', '\n', content)

# Remove loadSampleData from loadInitialData
load_initial_data_replacement = '''    private fun loadInitialData() {
        var followers = prefs.getFollowers()
        var following = prefs.getFollowing()
        rawRecent = prefs.getRecentActivity()
        rawWhitelist = prefs.getWhitelist()
        originalFollowersCount = prefs.getExpectedFollowersCount()
        originalFollowingCount = prefs.getExpectedFollowingCount()

        if (originalFollowersCount == 0) originalFollowersCount = followers.size
        if (originalFollowingCount == 0) originalFollowingCount = following.size

        rawFollowers = followers
        rawFollowing = following

        triggerStateCalculation()
    }'''
content = re.sub(r'    private fun loadInitialData\(\) \{.*?(?=    // ====================================================================)', load_initial_data_replacement + '\n\n', content, flags=re.DOTALL)

# Remove loadSampleData function
content = re.sub(r'    fun loadSampleData\(\) \{.*?(?=    fun setActiveTab)', '', content, flags=re.DOTALL)

# Remove InstaBridgeCallback overrides
content = re.sub(r'    // ====================================================================\n    // INSTA WEBVIEW BRIDGE CALLBACKS\n    // ====================================================================.*?    // ====================================================================\n    // SYNC TRIGGER & ACTIONS\n    // ====================================================================', '    // ====================================================================\n    // SYNC TRIGGER & ACTIONS\n    // ====================================================================', content, flags=re.DOTALL)

# Add new Network Helpers before SYNC TRIGGER & ACTIONS
network_helpers = '''
    // ====================================================================
    // NETWORK HELPERS & PAGINATION
    // ====================================================================
    private fun buildInstagramGetConnection(urlStr: String, cookieHeader: String, csrfToken: String): HttpURLConnection {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Cookie", cookieHeader)
        conn.setRequestProperty("x-csrftoken", csrfToken)
        conn.setRequestProperty("x-ig-app-id", "936619743392459")
        conn.setRequestProperty("x-requested-with", "XMLHttpRequest")
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Referer", "https://www.instagram.com/")
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        )
        return conn
    }

    private fun buildInstagramPostConnection(urlStr: String, cookieHeader: String, csrfToken: String, body: String): HttpURLConnection {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.setRequestProperty("Cookie", cookieHeader)
        conn.setRequestProperty("x-csrftoken", csrfToken)
        conn.setRequestProperty("x-ig-app-id", "936619743392459")
        conn.setRequestProperty("x-requested-with", "XMLHttpRequest")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Referer", "https://www.instagram.com/")
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        )
        conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
        return conn
    }

    private fun updateSyncProgress(phase: String, count: Int, total: Int, baseProgress: Float, maxProgress: Float) {
        val fraction = if (total > 0) count.toFloat() / total.toFloat() else 0f
        syncPhaseTitle = phase
        syncCounterText = "$phase ($count / $total)"
        syncProgressFraction = baseProgress + (fraction.coerceIn(0f, 1f) * (maxProgress - baseProgress))
        triggerStateCalculation()
    }

    private suspend fun fetchFullRelationshipList(dsUserId: String, edge: String, expectedTotal: Int, cookieHeader: String, csrfToken: String, phaseName: String, baseProgress: Float, maxProgress: Float): LinkedHashMap<String, IGUser> = withContext(Dispatchers.IO) {
        val userMap = LinkedHashMap<String, IGUser>()
        var nextMaxId: String? = null
        var pageCount = 0
        val maxPages = 400

        // Primary Loop (REST)
        while (pageCount < maxPages) {
            val baseUrl = "https://www.instagram.com/api/v1/friendships/$dsUserId/$edge/?count=100&search_surface=follow_list_page"
            val url = if (nextMaxId.isNullOrBlank()) baseUrl else "$baseUrl&max_id=${URLEncoder.encode(nextMaxId, "UTF-8")}"
            
            var success = false
            for (retry in 0..2) {
                try {
                    val conn = buildInstagramGetConnection(url, cookieHeader, csrfToken)
                    if (conn.responseCode == 200) {
                        val response = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(response)
                        val usersArr = json.optJSONArray("users") ?: JSONArray()
                        for (i in 0 until usersArr.length()) {
                            val u = usersArr.getJSONObject(i)
                            val pk = u.optString("pk", "").trim()
                            if (pk.isNotEmpty()) {
                                userMap[pk] = IGUser(
                                    pk = pk,
                                    username = u.optString("username", "").trim().lowercase(),
                                    fullName = u.optString("full_name", ""),
                                    profilePicUrl = u.optString("profile_pic_url", ""),
                                    isVerified = u.optBoolean("is_verified", false),
                                    isPrivate = u.optBoolean("is_private", false)
                                )
                            }
                        }
                        
                        val rawNext = json.optString("next_max_id", "").trim()
                        val hasBigList = json.optBoolean("big_list", rawNext.isNotEmpty())
                        nextMaxId = if (rawNext.isNotEmpty() && rawNext != "null" && hasBigList) rawNext else null
                        
                        success = true
                        break
                    } else if (conn.responseCode == 429) {
                        delay(1500L)
                    } else {
                        break
                    }
                } catch (e: Exception) {
                    delay(1500L)
                }
            }
            
            withContext(Dispatchers.Main) {
                updateSyncProgress(phaseName, userMap.size, expectedTotal, baseProgress, maxProgress)
            }
            
            if (!success || nextMaxId == null) break
            pageCount++
            delay(350L + (0..250).random())
        }
        
        // Automatic GraphQL Supplement
        if (expectedTotal > 0 && userMap.size < expectedTotal * 0.9) {
            val queryHash = if (edge == "followers") "c76146de99bb02f6415203be841dd25a" else "d04b0a864b4b54837c0d870b0e77e076"
            var endCursor: String? = null
            var hasNextPage = true
            var gqlPageCount = 0
            
            while (hasNextPage && gqlPageCount < maxPages) {
                val variables = """{"id":"$dsUserId","include_reel":false,"fetch_mutual":false,"first":50,"after":${if (endCursor == null) "null" else "\\"$endCursor\\""}}"""
                val gqlUrl = "https://www.instagram.com/graphql/query/?query_hash=$queryHash&variables=${URLEncoder.encode(variables, "UTF-8")}"
                
                var success = false
                for (retry in 0..2) {
                    try {
                        val conn = buildInstagramGetConnection(gqlUrl, cookieHeader, csrfToken)
                        if (conn.responseCode == 200) {
                            val response = conn.inputStream.bufferedReader().use { it.readText() }
                            val json = JSONObject(response)
                            val data = json.optJSONObject("data")?.optJSONObject("user")
                            val edgeData = if (edge == "followers") data?.optJSONObject("edge_followed_by") else data?.optJSONObject("edge_follow")
                            
                            val edges = edgeData?.optJSONArray("edges") ?: JSONArray()
                            for (i in 0 until edges.length()) {
                                val node = edges.getJSONObject(i).optJSONObject("node") ?: continue
                                val pk = (node.optString("id").takeIf { it.isNotBlank() } ?: node.optString("pk")).trim()
                                if (pk.isNotEmpty() && !userMap.containsKey(pk)) {
                                    userMap[pk] = IGUser(
                                        pk = pk,
                                        username = node.optString("username", "").trim().lowercase(),
                                        fullName = node.optString("full_name", ""),
                                        profilePicUrl = node.optString("profile_pic_url", ""),
                                        isVerified = node.optBoolean("is_verified", false),
                                        isPrivate = node.optBoolean("is_private", false)
                                    )
                                }
                            }
                            
                            val pageInfo = edgeData?.optJSONObject("page_info")
                            hasNextPage = pageInfo?.optBoolean("has_next_page", false) ?: false
                            endCursor = pageInfo?.optString("end_cursor", "")
                            if (endCursor.isNullOrBlank() || endCursor == "null") hasNextPage = false
                            
                            success = true
                            break
                        } else if (conn.responseCode == 429) {
                            delay(1500L)
                        } else {
                            break
                        }
                    } catch (e: Exception) {
                        delay(1500L)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    updateSyncProgress("$phaseName (GraphQL)", userMap.size, expectedTotal, baseProgress, maxProgress)
                }
                
                if (!success || !hasNextPage) break
                gqlPageCount++
                delay(350L + (0..250).random())
            }
        }
        
        return@withContext userMap
    }
'''
content = content.replace('    // ====================================================================\n    // SYNC TRIGGER & ACTIONS\n    // ====================================================================', network_helpers + '\n    // ====================================================================\n    // SYNC TRIGGER & ACTIONS\n    // ====================================================================')

# Rewrite startLiveSync
start_live_sync = '''    fun startLiveSync() {
        if (isSyncing) return
        val cookieHeader = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: ""
        val dsUserId = extractCookieValue(cookieHeader, "ds_user_id") ?: prefs.getDsUserId() ?: ""
        val csrfToken = extractCookieValue(cookieHeader, "csrftoken") ?: prefs.getCsrfToken() ?: ""

        if (dsUserId.isEmpty() || csrfToken.isEmpty()) {
            openLoginModal()
            return
        }

        isSyncing = true
        syncPhaseTitle = "Stage 1/3: Fetching Profile Info..."
        syncCounterText = "Stage 1/3: Fetching Profile Info..."
        syncProgressFraction = 0.02f
        triggerStateCalculation()

        syncTimeoutJob?.cancel()
        syncTimeoutJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Fetch Profile Totals
                var folCount = originalFollowersCount
                var fingCount = originalFollowingCount
                try {
                    val infoUrl = "https://www.instagram.com/api/v1/users/$dsUserId/info/"
                    val conn = buildInstagramGetConnection(infoUrl, cookieHeader, csrfToken)
                    if (conn.responseCode == 200) {
                        val resp = conn.inputStream.bufferedReader().use { it.readText() }
                        val userObj = JSONObject(resp).optJSONObject("user")
                        if (userObj != null) {
                            folCount = userObj.optInt("follower_count", folCount)
                            fingCount = userObj.optInt("following_count", fingCount)
                            val un = userObj.optString("username", "")
                            val pic = userObj.optString("profile_pic_url", "")
                            withContext(Dispatchers.Main) {
                                if (folCount > 0) originalFollowersCount = folCount
                                if (fingCount > 0) originalFollowingCount = fingCount
                                prefs.saveProfileInfo(un, pic, originalFollowersCount, originalFollowingCount)
                                triggerStateCalculation()
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore profile fetch failure
                }

                // Stage 1: Followers
                val followersMap = fetchFullRelationshipList(dsUserId, "followers", folCount, cookieHeader, csrfToken, "Fetching Followers", 0.05f, 0.40f)
                
                // Stage 2: Following
                val followingMap = fetchFullRelationshipList(dsUserId, "following", fingCount, cookieHeader, csrfToken, "Fetching Following", 0.40f, 0.80f)

                // Stage 3: Server-Truth Matrix (show_many)
                withContext(Dispatchers.Main) {
                    syncPhaseTitle = "Stage 3/3: Verifying Relationships..."
                    syncCounterText = "Stage 3/3: Verifying Relationships..."
                    syncProgressFraction = 0.85f
                    triggerStateCalculation()
                }

                val statuses = mutableMapOf<String, FriendshipStatus>()
                val allCandidatePks = mutableSetOf<String>()
                allCandidatePks.addAll(followingMap.keys.filter { !followersMap.containsKey(it) })
                allCandidatePks.addAll(followersMap.keys.filter { !followingMap.containsKey(it) })
                
                val pksList = allCandidatePks.toList()
                val chunks = pksList.chunked(100)
                
                for ((index, chunk) in chunks.withIndex()) {
                    try {
                        val body = "user_ids=${chunk.joinToString(",")}"
                        val url = "https://www.instagram.com/api/v1/friendships/show_many/"
                        val conn = buildInstagramPostConnection(url, cookieHeader, csrfToken, body)
                        if (conn.responseCode == 200) {
                            val resp = conn.inputStream.bufferedReader().use { it.readText() }
                            val fsObj = JSONObject(resp).optJSONObject("friendship_statuses")
                            if (fsObj != null) {
                                for (pk in chunk) {
                                    val st = fsObj.optJSONObject(pk)
                                    if (st != null) {
                                        statuses[pk] = FriendshipStatus(
                                            following = st.optBoolean("following", false) || st.optBoolean("outgoing_request", false),
                                            followedBy = st.optBoolean("followed_by", false),
                                            outgoingRequest = st.optBoolean("outgoing_request", false)
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        delay(500)
                    }
                    delay(120)
                    withContext(Dispatchers.Main) {
                        val pct = 0.85f + (0.15f * (index + 1) / chunks.size)
                        syncProgressFraction = pct
                        triggerStateCalculation()
                    }
                }

                val finalFollowers = followersMap.values.toList()
                val finalFollowing = followingMap.values.toList()

                withContext(Dispatchers.Main) {
                    rawFollowers = finalFollowers
                    rawFollowing = finalFollowing
                    rawFriendshipStatuses = statuses
                    
                    prefs.saveFollowersImmediate(finalFollowers)
                    prefs.saveFollowingImmediate(finalFollowing)
                    
                    val now = System.currentTimeMillis()
                    prefs.setLastSyncTime(now)
                    prefs.savePreviousFollowersCount(originalFollowersCount)
                    
                    isSyncing = false
                    syncPhaseTitle = "Sync Complete!"
                    syncCounterText = "100% Graph & Server Truth Reconciled (${finalFollowers.size} followers, ${finalFollowing.size} following)"
                    syncProgressFraction = 1f
                    triggerStateCalculation()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSyncing = false
                    syncPhaseTitle = "Sync Error"
                    syncCounterText = e.message ?: "Unknown error"
                    triggerStateCalculation()
                }
            }
        }
    }'''

content = re.sub(r'    fun startLiveSync\(\) \{.*?(?=    fun startSingleAction)', start_live_sync + '\n\n', content, flags=re.DOTALL)

# Rewrite startSingleAction to use HttpURLConnection
start_single_action = '''    fun startSingleAction(user: IGUser) {
        if (busyPk != null) return

        val isFollowing = rawFollowing.any { it.pk == user.pk }
        val action = if (isFollowing) ActionType.UNFOLLOW else ActionType.FOLLOW
        val item = ActionQueueItem(pk = user.pk, username = user.username, action = action)

        busyPk = user.pk
        triggerStateCalculation()

        val cookieHeader = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: ""
        val csrfToken = extractCookieValue(cookieHeader, "csrftoken") ?: prefs.getCsrfToken() ?: ""

        if (csrfToken.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                val endpoint = if (action == ActionType.UNFOLLOW) {
                    "https://www.instagram.com/api/v1/friendships/destroy/${user.pk}/"
                } else {
                    "https://www.instagram.com/api/v1/friendships/create/${user.pk}/"
                }
                
                try {
                    val conn = buildInstagramPostConnection(endpoint, cookieHeader, csrfToken, "")
                    conn.responseCode // Wait for response
                } catch (e: Exception) {
                    // Ignore for now
                }
                
                withContext(Dispatchers.Main) {
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
    }'''
content = re.sub(r'    fun startSingleAction\(user: IGUser\) \{.*?(?=    fun startBatchQueueFromSelection)', start_single_action + '\n\n', content, flags=re.DOTALL)

# Rewrite runExecutorLoop
run_executor_loop = '''    private fun runExecutorLoop() {
        executorJob?.cancel()
        executorJob = viewModelScope.launch(Dispatchers.IO) {
            val cookieHeader = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: ""
            val csrfToken = extractCookieValue(cookieHeader, "csrftoken") ?: prefs.getCsrfToken() ?: ""

            while (_executorState.value.currentIndex < _executorState.value.queue.size) {
                if (_executorState.value.isPaused) break

                val currentIndex = _executorState.value.currentIndex
                val item = _executorState.value.queue[currentIndex]

                withContext(Dispatchers.Main) {
                    busyPk = item.pk
                    triggerStateCalculation()
                    _executorState.value = _executorState.value.copy(
                        statusMessage = "Executing ${item.action.name.lowercase()} for @${item.username}..."
                    )
                }

                if (csrfToken.isNotEmpty() && item.pk != null) {
                    val endpoint = if (item.action == ActionType.UNFOLLOW) {
                        "https://www.instagram.com/api/v1/friendships/destroy/${item.pk}/"
                    } else {
                        "https://www.instagram.com/api/v1/friendships/create/${item.pk}/"
                    }
                    try {
                        val conn = buildInstagramPostConnection(endpoint, cookieHeader, csrfToken, "")
                        conn.responseCode
                    } catch (e: Exception) {
                    }
                    delay(800)
                } else {
                    delay(350)
                }

                if (_executorState.value.isPaused) break

                withContext(Dispatchers.Main) {
                    applyActionResultInMemory(item)
                    triggerStateCalculation()
                    
                    val actionWord = if (item.action == ActionType.UNFOLLOW) "unfollowed" else "followed"
                    _executorState.value = _executorState.value.copy(
                        statusMessage = "✅ Successfully $actionWord @${item.username}"
                    )
                }

                // Sequential queue pacing: 2.0s - 3.5s delay
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
                        _executorState.value = _executorState.value.copy(
                            currentIndex = currentIndex + 1
                        )
                    } else {
                        _executorState.value = _executorState.value.copy(visible = false)
                        busyPk = null
                        triggerStateCalculation()
                    }
                }
                
                if (currentIndex + 1 >= _executorState.value.queue.size) break
            }
        }
    }'''

content = re.sub(r'    private fun runExecutorLoop\(\) \{.*?(?=    private fun applyActionResultInMemory)', run_executor_loop + '\n\n', content, flags=re.DOTALL)

# Fix clearCache issue
content = content.replace('webViewBridge.clearCache()', 'CookieManager.getInstance().removeAllCookies(null)\n        CookieManager.getInstance().flush()')

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
