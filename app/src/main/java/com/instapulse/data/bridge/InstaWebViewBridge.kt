package com.instapulse.data.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

interface InstaBridgeCallback {
    fun onProfileTotals(followersCount: Int, followingCount: Int, username: String, avatarUrl: String)
    fun onSyncProgress(phase: String, loadedFollowers: Int, expectedFollowers: Int, loadedFollowing: Int, expectedFollowing: Int, progressFraction: Float)
    fun onStreamBatch(edgeType: String, totalCount: Int, batchUsersJson: String) {}
    fun onFollowersLoaded(followersJson: String) {}
    fun onFollowingLoaded(followingJson: String) {}
    fun onSyncCompleted(followersJson: String, followingJson: String, friendshipStatusesJson: String)
    fun onSyncError(errorMsg: String)
    fun onActionResult(pk: String, actionType: String, success: Boolean, status: String = "")
}

class AndroidBridge(private val callback: InstaBridgeCallback) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(jsonString: String) {
        mainHandler.post {
            try {
                val json = JSONObject(jsonString)
                when (json.optString("type")) {
                    "PROFILE_INFO" -> {
                        callback.onProfileTotals(
                            json.optInt("followerCount"),
                            json.optInt("followingCount"),
                            json.optString("username"),
                            json.optString("avatarUrl")
                        )
                    }
                    "STATUS" -> {
                        val msg = json.optString("message")
                        callback.onSyncProgress(msg, 0, 0, 0, 0, 0.1f)
                    }
                    "SYNC_PROGRESS" -> {
                        val edgeType = json.optString("edgeType")
                        val count = json.optInt("count")
                        val phase = json.optString("phase", "Scanning $edgeType...")
                        val fraction = if (edgeType == "followers") 0.40f else 0.85f
                        callback.onSyncProgress(
                            phase,
                            if (edgeType == "followers") count else 0,
                            0,
                            if (edgeType == "following") count else 0,
                            0,
                            fraction
                        )
                    }
                    "SYNC_STREAM_BATCH" -> {
                        val edgeType = json.optString("edgeType")
                        val totalCount = json.optInt("totalCount")
                        val batchUsersJson = json.optJSONArray("batchUsers")?.toString() ?: "[]"
                        callback.onStreamBatch(edgeType, totalCount, batchUsersJson)
                    }
                    "FOLLOWERS_LOADED", "FOLLOWERS_FETCHED" -> {
                        callback.onFollowersLoaded(jsonString)
                    }
                    "FOLLOWING_LOADED", "FOLLOWING_FETCHED" -> {
                        callback.onFollowingLoaded(jsonString)
                    }
                    "SYNC_SUCCESS" -> {
                        val fol = json.optJSONArray("followers")?.toString() ?: "[]"
                        val fing = json.optJSONArray("following")?.toString() ?: "[]"
                        callback.onSyncCompleted(fol, fing, "{}")
                    }
                    "SYNC_ERROR" -> {
                        callback.onSyncError(json.optString("message", "Sync failed"))
                    }
                    "ACTION_RESULT" -> {
                        val target = json.optString("pk").ifEmpty { json.optString("targetPk") }
                        val action = json.optString("action").ifEmpty { json.optString("actionType") }
                        val status = json.optString("status")
                        val success = json.optBoolean("success", false) || status == "SUCCESS"
                        callback.onActionResult(target, action, success, status)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @JavascriptInterface
    fun onProfileTotals(followers: Int, following: Int, username: String, avatarUrl: String) {
        mainHandler.post {
            callback.onProfileTotals(followers, following, username, avatarUrl)
        }
    }

    @JavascriptInterface
    fun onSyncProgress(phase: String, loadedFollowers: Int, expectedFollowers: Int, loadedFollowing: Int, expectedFollowing: Int, progressFraction: Float) {
        mainHandler.post {
            callback.onSyncProgress(phase, loadedFollowers, expectedFollowers, loadedFollowing, expectedFollowing, progressFraction)
        }
    }

    @JavascriptInterface
    fun onSyncProgress(phase: String, loadedFollowers: Int, expectedFollowers: Int, loadedFollowing: Int, expectedFollowing: Int) {
        mainHandler.post {
            callback.onSyncProgress(phase, loadedFollowers, expectedFollowers, loadedFollowing, expectedFollowing, 0.5f)
        }
    }

    @JavascriptInterface
    fun onSyncCompleted(followersJson: String, followingJson: String, friendshipStatusesJson: String) {
        mainHandler.post {
            callback.onSyncCompleted(followersJson, followingJson, friendshipStatusesJson)
        }
    }

    @JavascriptInterface
    fun onSyncCompleted(followersJson: String, followingJson: String) {
        mainHandler.post {
            callback.onSyncCompleted(followersJson, followingJson, "{}")
        }
    }

    @JavascriptInterface
    fun onSyncError(errorMsg: String) {
        mainHandler.post {
            callback.onSyncError(errorMsg)
        }
    }

    @JavascriptInterface
    fun onActionResult(pk: String, actionType: String, success: Boolean) {
        mainHandler.post {
            callback.onActionResult(pk, actionType, success, if (success) "SUCCESS" else "ERROR")
        }
    }
}

class InstaWebViewBridge(private val callback: InstaBridgeCallback) {
    private var webView: WebView? = null
    private var isScriptInjected = false
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        val ENGINE_JS: String by lazy {
            """
            (function() {
              if (window.__INSTAPULSE_BRIDGE_ACTIVE) return;
              window.__INSTAPULSE_BRIDGE_ACTIVE = true;
              window.__IS_SYNCING_LOCK = false;

              const IG_APP_ID = '936619743392459';
              const ASBD_ID = '129477';
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

              // ====================================================================
              // 3-TIER FOLLOW / UNFOLLOW MUTATION & REAL-TIME VERIFICATION ENGINE
              // ====================================================================
              window.executeRealtimeInstaAction = async function(taskKey, targetPkRaw, targetUsername, actionType, passedCsrf) {
                const csrf = getCookie('csrftoken') || passedCsrf || '';
                const wwwClaim = (typeof sessionStorage !== 'undefined' && sessionStorage.getItem('www-claim-v2')) || '0';
                let targetPk = String(targetPkRaw || '').trim();

                try {
                  // Step A: Resolve numeric PK via web_profile_info if missing
                  if (!targetPk || !/^\d+$/.test(targetPk)) {
                    if (targetUsername) {
                      const infoRes = await fetch('https://www.instagram.com/api/v1/users/web_profile_info/?username=' + encodeURIComponent(targetUsername), {
                        method: 'GET',
                        credentials: 'include',
                        headers: {
                          'x-ig-app-id': IG_APP_ID,
                          'x-asbd-id': ASBD_ID,
                          'x-csrftoken': csrf,
                          'x-ig-www-claim': wwwClaim,
                          'x-requested-with': 'XMLHttpRequest'
                        }
                      });
                      if (infoRes.status === 404) {
                        sendMessage({ type: 'ACTION_RESULT', taskKey: taskKey, pk: targetPk, username: targetUsername, action: actionType, status: 'SKIPPED_UNAVAILABLE', success: false });
                        return;
                      }
                      if (infoRes.ok) {
                        const infoJson = await infoRes.json();
                        targetPk = String(infoJson && infoJson.data && infoJson.data.user && infoJson.data.user.id || '').trim();
                      }
                    }
                  }

                  if (!targetPk || !/^\d+$/.test(targetPk)) {
                    sendMessage({ type: 'ACTION_RESULT', taskKey: taskKey, pk: targetPkRaw, username: targetUsername, action: actionType, status: 'SKIPPED_UNAVAILABLE', success: false });
                    return;
                  }

                  // Step B: Tier 1 — Primary Mutation Endpoint with Required Form Body & Headers
                  const endpoint = (actionType === 'UNFOLLOW' || actionType === 'unfollow') ? 'destroy' : 'create';
                  const mutationUrl = 'https://www.instagram.com/api/v1/friendships/' + endpoint + '/' + targetPk + '/';
                  const formBody = 'container_module=profile&user_id=' + encodeURIComponent(targetPk);

                  const res = await fetch(mutationUrl, {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                      'accept': '*/*',
                      'content-type': 'application/x-www-form-urlencoded',
                      'x-ig-app-id': IG_APP_ID,
                      'x-asbd-id': ASBD_ID,
                      'x-csrftoken': csrf,
                      'x-ig-www-claim': wwwClaim,
                      'x-instagram-ajax': '1012875432',
                      'x-requested-with': 'XMLHttpRequest',
                      'referer': 'https://www.instagram.com/' + (targetUsername || '') + '/'
                    },
                    body: formBody
                  });

                  if (res.status === 404) {
                    sendMessage({ type: 'ACTION_RESULT', taskKey: taskKey, pk: targetPk, username: targetUsername, action: actionType, status: 'SKIPPED_UNAVAILABLE', success: false });
                    return;
                  }

                  if (res.status === 429) {
                    sendMessage({ type: 'ACTION_RESULT', taskKey: taskKey, pk: targetPk, username: targetUsername, action: actionType, status: 'RATE_LIMITED', success: false });
                    return;
                  }

                  let verifiedSuccess = false;
                  if (res.ok) {
                    const json = await res.json();
                    if (json && (json.status === 'ok' || json.friendship_status)) {
                      verifiedSuccess = true;
                    }
                  }

                  // Step C: Tier 2 — GraphQL / Polaris Mutation Fallback if Tier 1 didn't confirm
                  if (!verifiedSuccess) {
                    const isUnfollow = (actionType === 'UNFOLLOW' || actionType === 'unfollow');
                    const gqlDocId = isUnfollow ? '7301295479961089' : '7258991244196452';
                    const friendlyName = isUnfollow ? 'usePolarisUnfollowMutation' : 'usePolarisFollowMutation';
                    const gqlBody = 'av=' + encodeURIComponent(getCookie('ds_user_id') || '') +
                      '&fb_api_req_friendly_name=' + friendlyName +
                      '&variables=' + encodeURIComponent(JSON.stringify({ target_user_id: targetPk, container_module: 'profile' })) +
                      '&doc_id=' + gqlDocId;

                    try {
                      const gqlRes = await fetch('https://www.instagram.com/graphql/query', {
                        method: 'POST',
                        credentials: 'include',
                        headers: {
                          'content-type': 'application/x-www-form-urlencoded',
                          'x-ig-app-id': IG_APP_ID,
                          'x-asbd-id': ASBD_ID,
                          'x-csrftoken': csrf,
                          'x-fb-friendly-name': friendlyName
                        },
                        body: gqlBody
                      });
                      if (gqlRes.ok) verifiedSuccess = true;
                    } catch (eGql) {}
                  }

                  // Step D: Tier 3 — Real-Time Server Truth Verification
                  try {
                    const verifyRes = await fetch('https://www.instagram.com/api/v1/friendships/show/' + targetPk + '/', {
                      method: 'GET',
                      credentials: 'include',
                      headers: {
                        'x-ig-app-id': IG_APP_ID,
                        'x-asbd-id': ASBD_ID,
                        'x-csrftoken': csrf,
                        'x-requested-with': 'XMLHttpRequest'
                      }
                    });

                    if (verifyRes.ok) {
                      const fs = await verifyRes.json();
                      const isUnfollow = (actionType === 'UNFOLLOW' || actionType === 'unfollow');
                      if (isUnfollow && fs.following === false && fs.outgoing_request === false) {
                        verifiedSuccess = true;
                      } else if (!isUnfollow && (fs.following === true || fs.outgoing_request === true)) {
                        verifiedSuccess = true;
                      }
                    }
                  } catch (eVerify) {}

                  sendMessage({
                    type: 'ACTION_RESULT',
                    taskKey: taskKey,
                    pk: targetPk,
                    username: targetUsername,
                    action: actionType,
                    status: verifiedSuccess ? 'SUCCESS' : 'ERROR',
                    success: verifiedSuccess
                  });
                } catch (err) {
                  sendMessage({
                    type: 'ACTION_RESULT',
                    taskKey: taskKey,
                    pk: targetPk,
                    username: targetUsername,
                    action: actionType,
                    status: 'ERROR',
                    success: false,
                    message: err.message || 'Action failed'
                  });
                }
              };

              window.executeInstaAction = function(pk, actionType, csrfToken) {
                return window.executeRealtimeInstaAction('action_' + pk, pk, '', actionType, csrfToken);
              };

              // ====================================================================
              // REAL-TIME GRAPHQL + REST HYBRID STREAMING SYNC ENGINE
              // ====================================================================
              async function fetchGraphEdgeStreaming(userId, edgeType, passedCsrf) {
                const usersMap = new Map();
                const isFollowers = edgeType === 'followers';
                const queryHash = isFollowers ? 'c76146de99bb02f6415203be841dd25a' : 'd04b0a864b4b54837c0d870b0e77e076';

                // Pass 1: GraphQL Edge Streaming
                let endCursor = '';
                let gqlHasNext = true;
                let gqlFails = 0;

                while (gqlHasNext && gqlFails < 2) {
                  const variables = JSON.stringify({
                    id: String(userId),
                    include_reel: true,
                    fetch_mutual: false,
                    first: 50,
                    after: endCursor || undefined
                  });
                  const gqlUrl = 'https://www.instagram.com/graphql/query/?query_hash=' + queryHash + '&variables=' + encodeURIComponent(variables);

                  let pageSuccess = false;
                  try {
                    const csrf = getCookie('csrftoken') || passedCsrf || '';
                    const res = await fetch(gqlUrl, {
                      method: 'GET',
                      credentials: 'include',
                      headers: {
                        'x-ig-app-id': IG_APP_ID,
                        'x-asbd-id': ASBD_ID,
                        'x-csrftoken': csrf,
                        'x-requested-with': 'XMLHttpRequest',
                        'referer': 'https://www.instagram.com/'
                      }
                    });

                    if (res.ok) {
                      const json = await res.json();
                      const edgeObj = json && json.data && json.data.user && (isFollowers ? json.data.user.edge_followed_by : json.data.user.edge_follow);
                      if (edgeObj && Array.isArray(edgeObj.edges)) {
                        const batch = [];
                        for (let i = 0; i < edgeObj.edges.length; i++) {
                          const node = edgeObj.edges[i].node;
                          const pk = String(node.id || node.pk || '').trim();
                          const username = String(node.username || '').toLowerCase().trim();
                          if (!pk || !username) continue;
                          const userObj = {
                            pk: pk,
                            username: username,
                            fullName: String(node.full_name || ''),
                            profilePicUrl: String(node.profile_pic_url || ''),
                            isVerified: Boolean(node.is_verified),
                            isPrivate: Boolean(node.is_private)
                          };
                          if (!usersMap.has(pk)) {
                            usersMap.set(pk, userObj);
                            batch.push(userObj);
                          }
                        }

                        if (batch.length > 0) {
                          sendMessage({
                            type: 'SYNC_STREAM_BATCH',
                            edgeType: edgeType,
                            totalCount: usersMap.size,
                            batchUsers: batch
                          });
                          sendMessage({
                            type: 'SYNC_PROGRESS',
                            edgeType: edgeType,
                            count: usersMap.size,
                            phase: 'Streaming ' + edgeType + ' (' + usersMap.size + ')...'
                          });
                        }

                        if (edgeObj.page_info && edgeObj.page_info.has_next_page && edgeObj.page_info.end_cursor) {
                          endCursor = edgeObj.page_info.end_cursor;
                          await sleep(400 + Math.random() * 250);
                        } else {
                          gqlHasNext = false;
                        }
                        pageSuccess = true;
                      }
                    }
                  } catch (e) {}

                  if (!pageSuccess) {
                    gqlFails++;
                    if (gqlFails >= 2) gqlHasNext = false;
                  }
                }

                // Pass 2: REST API Coverage & Gap-Fill
                let nextMaxId = '';
                let restHasNext = true;
                let countParam = 50;

                while (restHasNext) {
                  const url = 'https://www.instagram.com/api/v1/friendships/' + userId + '/' + edgeType +
                    '/?count=' + countParam + (nextMaxId ? '&max_id=' + encodeURIComponent(nextMaxId) : '');

                  let data = null;
                  let success = false;
                  const backoffs = [1200, 2500, 4000, 6000];

                  for (let attempt = 0; attempt < 4; attempt++) {
                    try {
                      const csrf = getCookie('csrftoken') || passedCsrf || '';
                      const res = await fetch(url, {
                        method: 'GET',
                        credentials: 'include',
                        headers: {
                          'x-ig-app-id': IG_APP_ID,
                          'x-asbd-id': ASBD_ID,
                          'x-csrftoken': csrf,
                          'x-requested-with': 'XMLHttpRequest',
                          'referer': 'https://www.instagram.com/'
                        }
                      });

                      if (res.ok) {
                        data = await res.json();
                        success = true;
                        break;
                      } else if (res.status === 400 && countParam === 50) {
                        countParam = 12;
                        await sleep(600);
                      } else {
                        await sleep(backoffs[attempt]);
                      }
                    } catch (err) {
                      await sleep(backoffs[attempt]);
                    }
                  }

                  if (!success || !data) {
                    break;
                  }

                  const batchRaw = data.users || [];
                  const batch = [];
                  for (let i = 0; i < batchRaw.length; i++) {
                    const u = batchRaw[i];
                    const pk = String(u.pk || u.id || '').trim();
                    const username = String(u.username || '').toLowerCase().trim();
                    if (!pk || !username) continue;
                    const userObj = {
                      pk: pk,
                      username: username,
                      fullName: String(u.full_name || ''),
                      profilePicUrl: String(u.profile_pic_url || ''),
                      isVerified: Boolean(u.is_verified),
                      isPrivate: Boolean(u.is_private)
                    };
                    if (!usersMap.has(pk)) {
                      usersMap.set(pk, userObj);
                      batch.push(userObj);
                    }
                  }

                  if (batch.length > 0) {
                    sendMessage({
                      type: 'SYNC_STREAM_BATCH',
                      edgeType: edgeType,
                      totalCount: usersMap.size,
                      batchUsers: batch
                    });
                    sendMessage({
                      type: 'SYNC_PROGRESS',
                      edgeType: edgeType,
                      count: usersMap.size,
                      phase: 'Scanning ' + edgeType + ' (' + usersMap.size + ')...'
                    });
                  }

                  if (data.next_max_id !== null && data.next_max_id !== undefined && String(data.next_max_id) !== '' && data.big_list !== false) {
                    nextMaxId = String(data.next_max_id);
                    await sleep(500 + Math.random() * 300);
                  } else {
                    restHasNext = false;
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

                  // Step 0: Profile Info
                  try {
                    const profRes = await fetch('https://www.instagram.com/api/v1/users/' + myId + '/info/', {
                      method: 'GET',
                      credentials: 'include',
                      headers: {
                        'x-ig-app-id': IG_APP_ID,
                        'x-asbd-id': ASBD_ID,
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

                  // Step 1: Followers (Real-Time Live Streaming)
                  sendMessage({ type: 'STATUS', message: 'Streaming Followers graph...' });
                  const followers = await fetchGraphEdgeStreaming(myId, 'followers', csrf);
                  sendMessage({
                    type: 'FOLLOWERS_LOADED',
                    followers: followers
                  });

                  await sleep(1000);

                  // Step 2: Following (Real-Time Live Streaming)
                  sendMessage({ type: 'STATUS', message: 'Streaming Following graph...' });
                  const following = await fetchGraphEdgeStreaming(myId, 'following', csrf);
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

              window.InstaSyncEngine = {
                startSync: function(userId, csrf) {
                  return window.runRealInstagramSync(userId, csrf);
                },
                executeAction: function(pk, actionType, csrfToken) {
                  return window.executeRealtimeInstaAction('task_' + pk, pk, '', actionType, csrfToken);
                }
              };
            })();
            true;
            """.trimIndent()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(context: Context): WebView {
        webView?.let { return it }

        val wv = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = false
                blockNetworkImage = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = USER_AGENT
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            val bridgeObj = AndroidBridge(callback)
            addJavascriptInterface(bridgeObj, "AndroidBridge")
            addJavascriptInterface(bridgeObj, "InstaNativeBridge")
            addJavascriptInterface(bridgeObj, "ReactNativeWebView")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectEngineScript()
                }
            }

            loadUrl("https://www.instagram.com/")
        }

        webView = wv
        return wv
    }

    private fun injectEngineScript() {
        mainHandler.post {
            webView?.evaluateJavascript(ENGINE_JS) {
                isScriptInjected = true
            }
        }
    }

    fun startSync(dsUserId: String, csrfToken: String) {
        mainHandler.post {
            val wv = webView ?: return@post
            val jsCall = "$ENGINE_JS; window.runRealInstagramSync('$dsUserId', '$csrfToken');"
            wv.evaluateJavascript(jsCall, null)
        }
    }

    fun executeAction(taskKey: String, targetPk: String, username: String, actionType: String, csrfToken: String) {
        mainHandler.post {
            val wv = webView ?: return@post
            val jsCall = "$ENGINE_JS; window.executeRealtimeInstaAction('$taskKey', '$targetPk', '$username', '$actionType', '$csrfToken');"
            wv.evaluateJavascript(jsCall, null)
        }
    }

    fun clearCache() {
        mainHandler.post {
            webView?.clearCache(true)
        }
    }
}

@Composable
fun HeadlessWebViewBridgeHost(bridge: InstaWebViewBridge) {
    AndroidView(
        modifier = Modifier.size(1.dp).alpha(0f),
        factory = { context ->
            bridge.getOrCreateWebView(context)
        }
    )
}
