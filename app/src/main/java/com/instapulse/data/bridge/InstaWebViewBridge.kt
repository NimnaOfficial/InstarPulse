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
    fun onFollowersLoaded(followersJson: String) {}
    fun onFollowingLoaded(followingJson: String) {}
    fun onSyncCompleted(followersJson: String, followingJson: String, friendshipStatusesJson: String)
    fun onSyncError(errorMsg: String)
    fun onActionResult(pk: String, actionType: String, success: Boolean)
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
                        callback.onActionResult(
                            json.optString("targetPk"),
                            json.optString("actionType"),
                            json.optBoolean("success", true)
                        )
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
            callback.onActionResult(pk, actionType, success)
        }
    }
}

class InstaWebViewBridge(private val callback: InstaBridgeCallback) {
    private var webView: WebView? = null
    private var isScriptInjected = false
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        val ENGINE_JS = """
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

              window.InstaSyncEngine = {
                startSync: function(userId, csrf) {
                  return window.runRealInstagramSync(userId, csrf);
                },
                executeAction: async function(pk, actionType, csrfToken) {
                  const token = csrfToken || getCookie('csrftoken') || '';
                  const endpoint = actionType === 'UNFOLLOW'
                    ? 'https://www.instagram.com/api/v1/friendships/destroy/' + pk + '/'
                    : 'https://www.instagram.com/api/v1/friendships/create/' + pk + '/';
                  try {
                    const resp = await fetch(endpoint, {
                      method: 'POST',
                      credentials: 'include',
                      headers: {
                        'x-ig-app-id': IG_APP_ID,
                        'x-csrftoken': token,
                        'x-requested-with': 'XMLHttpRequest',
                        'Content-Type': 'application/x-www-form-urlencoded'
                      },
                      body: ''
                    });
                    const success = resp.ok || resp.status === 404 || resp.status === 400;
                    sendMessage({
                      type: 'ACTION_RESULT',
                      targetPk: pk,
                      actionType: actionType,
                      success: success
                    });
                    return success;
                  } catch (e) {
                    sendMessage({
                      type: 'ACTION_RESULT',
                      targetPk: pk,
                      actionType: actionType,
                      success: true
                    });
                    return true;
                  }
                }
              };

              window.executeInstaAction = function(pk, actionType, csrfToken) {
                return window.InstaSyncEngine.executeAction(pk, actionType, csrfToken);
              };
            })();
            true;
        """.trimIndent()
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

    fun executeAction(pk: String, actionType: String, csrfToken: String) {
        mainHandler.post {
            val wv = webView ?: return@post
            val jsCall = "$ENGINE_JS; window.executeInstaAction('$pk', '$actionType', '$csrfToken');"
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
