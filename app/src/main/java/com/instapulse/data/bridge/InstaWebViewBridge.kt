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

interface InstaBridgeCallback {
    fun onProfileTotals(followersCount: Int, followingCount: Int, username: String, avatarUrl: String)
    fun onSyncProgress(phase: String, loadedFollowers: Int, expectedFollowers: Int, loadedFollowing: Int, expectedFollowing: Int, progressFraction: Float)
    fun onSyncCompleted(followersJson: String, followingJson: String, friendshipStatusesJson: String)
    fun onSyncError(errorMsg: String)
    fun onActionResult(pk: String, actionType: String, success: Boolean)
}

class AndroidBridge(private val callback: InstaBridgeCallback) {
    private val mainHandler = Handler(Looper.getMainLooper())

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
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private val ENGINE_JS = """
            (function() {
                if (window.InstaSyncEngine) return;
                
                function getCookie(name) {
                    const value = "; " + document.cookie;
                    const parts = value.split("; " + name + "=");
                    if (parts.length === 2) return parts.pop().split(";").shift();
                    return "";
                }
                
                window.InstaSyncEngine = {
                    isSyncing: false,
                    
                    async fetchProfileTotals(dsUserId, csrfToken) {
                        let followers = 0;
                        let following = 0;
                        let username = "";
                        let avatarUrl = "";
                        const token = csrfToken || getCookie("csrftoken") || "";
                        
                        try {
                            const resp = await fetch("https://www.instagram.com/api/v1/users/" + dsUserId + "/info/", {
                                method: "GET",
                                credentials: "include",
                                headers: {
                                    "x-ig-app-id": "936619743392459",
                                    "x-csrftoken": token,
                                    "x-requested-with": "XMLHttpRequest"
                                }
                            });
                            if (resp.ok) {
                                const json = await resp.json();
                                if (json && json.user) {
                                    followers = json.user.follower_count || 0;
                                    following = json.user.following_count || 0;
                                    username = json.user.username || "";
                                    avatarUrl = json.user.profile_pic_url || "";
                                }
                            }
                        } catch (e) {
                            console.error("fetchProfileTotals error", e);
                        }
                        
                        if (followers === 0 && following === 0) {
                            try {
                                const target = username || "me";
                                const resp2 = await fetch("https://www.instagram.com/api/v1/users/web_profile_info/?username=" + encodeURIComponent(target), {
                                    credentials: "include",
                                    headers: {
                                        "x-ig-app-id": "936619743392459",
                                        "x-requested-with": "XMLHttpRequest"
                                    }
                                });
                                if (resp2.ok) {
                                    const json2 = await resp2.json();
                                    const u = json2 && json2.data && json2.data.user;
                                    if (u) {
                                        followers = (u.edge_followed_by && u.edge_followed_by.count) || 0;
                                        following = (u.edge_follow && u.edge_follow.count) || 0;
                                        if (!username) username = u.username || "";
                                        if (!avatarUrl) avatarUrl = u.profile_pic_url || "";
                                    }
                                }
                            } catch (e2) {}
                        }
                        
                        if (window.AndroidBridge && window.AndroidBridge.onProfileTotals) {
                            window.AndroidBridge.onProfileTotals(followers, following, username, avatarUrl);
                        }
                        return { followers, following, username, avatarUrl };
                    },
                    
                    async paginateGraphQLEdge(dsUserId, edgeType, expectedCount, queryHash, csrfToken, onProgress) {
                        const userMap = new Map();
                        let afterCursor = null;
                        let hasNext = true;
                        let errorCount = 0;
                        const token = csrfToken || getCookie("csrftoken") || "";
                        
                        while (hasNext && errorCount < 4) {
                            const variables = JSON.stringify({
                                id: String(dsUserId),
                                include_reel: false,
                                fetch_mutual: false,
                                first: 50,
                                after: afterCursor || null
                            });
                            const url = "https://www.instagram.com/graphql/query/?query_hash=" + queryHash + "&variables=" + encodeURIComponent(variables);
                            
                            try {
                                const resp = await fetch(url, {
                                    method: "GET",
                                    credentials: "include",
                                    headers: {
                                        "x-ig-app-id": "936619743392459",
                                        "x-csrftoken": token,
                                        "x-requested-with": "XMLHttpRequest"
                                    }
                                });
                                
                                if (resp.ok) {
                                    errorCount = 0;
                                    const json = await resp.json();
                                    const userObj = json && json.data && json.data.user;
                                    const edge = edgeType === "followers"
                                        ? (userObj && userObj.edge_followed_by)
                                        : (userObj && userObj.edge_follow);
                                        
                                    if (edge && edge.edges) {
                                        const edges = edge.edges;
                                        for (let i = 0; i < edges.length; i++) {
                                            const node = edges[i].node;
                                            if (!node) continue;
                                            const pk = String(node.id || node.pk || "").trim();
                                            if (pk) {
                                                userMap.set(pk, {
                                                    pk: pk,
                                                    username: String(node.username || "").trim().toLowerCase(),
                                                    fullName: String(node.full_name || ""),
                                                    profilePicUrl: String(node.profile_pic_url || ""),
                                                    isVerified: Boolean(node.is_verified),
                                                    isPrivate: Boolean(node.is_private)
                                                });
                                            }
                                        }
                                        
                                        if (onProgress) onProgress(userMap.size);
                                        
                                        const pageInfo = edge.page_info;
                                        if (pageInfo && pageInfo.has_next_page && pageInfo.end_cursor) {
                                            afterCursor = pageInfo.end_cursor;
                                            await new Promise(function(r) { setTimeout(r, 180); });
                                        } else {
                                            hasNext = false;
                                        }
                                    } else {
                                        hasNext = false;
                                    }
                                } else {
                                    errorCount++;
                                    await new Promise(function(r) { setTimeout(r, 800 * errorCount); });
                                }
                            } catch (e) {
                                errorCount++;
                                await new Promise(function(r) { setTimeout(r, 800 * errorCount); });
                            }
                        }
                        
                        // Dual-merge with REST /api/v1/friendships if needed
                        if (expectedCount > 0 && userMap.size < expectedCount * 0.7) {
                            let restCursor = null;
                            let restHasNext = true;
                            let restErrors = 0;
                            
                            while (restHasNext && restErrors < 3) {
                                let rUrl = "https://www.instagram.com/api/v1/friendships/" + dsUserId + "/" + edgeType + "/?count=50";
                                if (restCursor) rUrl += "&max_id=" + encodeURIComponent(restCursor);
                                
                                try {
                                    const rResp = await fetch(rUrl, {
                                        method: "GET",
                                        credentials: "include",
                                        headers: {
                                            "x-ig-app-id": "936619743392459",
                                            "x-csrftoken": token,
                                            "x-requested-with": "XMLHttpRequest"
                                        }
                                    });
                                    if (rResp.ok) {
                                        restErrors = 0;
                                        const rJson = await rResp.json();
                                        const rUsers = rJson.users || [];
                                        for (let i = 0; i < rUsers.length; i++) {
                                            const u = rUsers[i];
                                            const pk = String(u.pk || u.id || "").trim();
                                            if (pk && !userMap.has(pk)) {
                                                userMap.set(pk, {
                                                    pk: pk,
                                                    username: String(u.username || "").trim().toLowerCase(),
                                                    fullName: String(u.full_name || ""),
                                                    profilePicUrl: String(u.profile_pic_url || ""),
                                                    isVerified: Boolean(u.is_verified),
                                                    isPrivate: Boolean(u.is_private)
                                                });
                                            }
                                        }
                                        if (onProgress) onProgress(userMap.size);
                                        
                                        if (rJson.next_max_id && String(rJson.next_max_id) !== "null") {
                                            restCursor = String(rJson.next_max_id);
                                            await new Promise(function(r) { setTimeout(r, 180); });
                                        } else {
                                            restHasNext = false;
                                        }
                                    } else {
                                        restErrors++;
                                        await new Promise(function(r) { setTimeout(r, 800); });
                                    }
                                } catch (e) {
                                    restErrors++;
                                    await new Promise(function(r) { setTimeout(r, 800); });
                                }
                            }
                        }
                        
                        return Array.from(userMap.values());
                    },
                    
                    async verifyFriendshipStatuses(allPks, csrfToken, onProgress) {
                        const serverTruth = {};
                        const token = csrfToken || getCookie("csrftoken") || "";
                        const chunkSize = 100;
                        
                        for (let i = 0; i < allPks.length; i += chunkSize) {
                            const chunk = allPks.slice(i, i + chunkSize);
                            try {
                                const resp = await fetch("https://www.instagram.com/api/v1/friendships/show_many/", {
                                    method: "POST",
                                    credentials: "include",
                                    headers: {
                                        "Content-Type": "application/x-www-form-urlencoded",
                                        "x-ig-app-id": "936619743392459",
                                        "x-csrftoken": token,
                                        "x-requested-with": "XMLHttpRequest"
                                    },
                                    body: "user_ids=" + chunk.join(",")
                                });
                                
                                if (resp.ok) {
                                    const json = await resp.json();
                                    if (json && json.friendship_statuses) {
                                        Object.assign(serverTruth, json.friendship_statuses);
                                    }
                                }
                            } catch (e) {
                                console.error("show_many chunk error", e);
                            }
                            
                            if (onProgress) {
                                const pct = Math.round(((i + chunk.length) / allPks.length) * 100);
                                onProgress(pct);
                            }
                            
                            await new Promise(function(r) { setTimeout(r, 120); });
                        }
                        
                        return serverTruth;
                    },
                    
                    async startSync(dsUserId, csrfToken) {
                        if (this.isSyncing) return;
                        this.isSyncing = true;
                        try {
                            const totals = await this.fetchProfileTotals(dsUserId, csrfToken);
                            
                            // Stage 1 (0% -> 40%): Scanning Followers Graph
                            const followers = await this.paginateGraphQLEdge(
                                dsUserId,
                                "followers",
                                totals.followers,
                                "c76146de99bb02f6415203be841dd25a",
                                csrfToken,
                                function(count) {
                                    const folTarget = totals.followers > 0 ? totals.followers : count;
                                    const pct = Math.min(1, count / Math.max(1, folTarget));
                                    const progress = 0.05 + pct * 0.35;
                                    if (window.AndroidBridge && window.AndroidBridge.onSyncProgress) {
                                        window.AndroidBridge.onSyncProgress(
                                            "Stage 1/3: Scanning Followers Graph (" + count + " / " + folTarget + ")...",
                                            count, totals.followers, 0, totals.following, progress
                                        );
                                    }
                                }
                            );
                            
                            // Stage 2 (40% -> 80%): Scanning Following Graph
                            const following = await this.paginateGraphQLEdge(
                                dsUserId,
                                "following",
                                totals.following,
                                "d04b0a864b4b54837c0d870b0e77e076",
                                csrfToken,
                                function(count) {
                                    const fingTarget = totals.following > 0 ? totals.following : count;
                                    const pct = Math.min(1, count / Math.max(1, fingTarget));
                                    const progress = 0.40 + pct * 0.40;
                                    if (window.AndroidBridge && window.AndroidBridge.onSyncProgress) {
                                        window.AndroidBridge.onSyncProgress(
                                            "Stage 2/3: Scanning Following Graph (" + count + " / " + fingTarget + ")...",
                                            followers.length, totals.followers, count, totals.following, progress
                                        );
                                    }
                                }
                            );
                            
                            // Stage 3 (80% -> 100%): Server-Truth Matrix Verification
                            const pkSet = new Set();
                            following.forEach(function(u) { pkSet.add(u.pk); });
                            followers.forEach(function(u) { pkSet.add(u.pk); });
                            const allPks = Array.from(pkSet);
                            
                            let serverTruth = {};
                            if (allPks.length > 0) {
                                serverTruth = await this.verifyFriendshipStatuses(allPks, csrfToken, function(pct) {
                                    const progress = 0.80 + (pct / 100) * 0.20;
                                    if (window.AndroidBridge && window.AndroidBridge.onSyncProgress) {
                                        window.AndroidBridge.onSyncProgress(
                                            "Stage 3/3: Verifying Mutuals & Fans via Server Matrix (" + pct + "%)...",
                                            followers.length, totals.followers, following.length, totals.following, progress
                                        );
                                    }
                                });
                            }
                            
                            if (window.AndroidBridge && window.AndroidBridge.onSyncProgress) {
                                window.AndroidBridge.onSyncProgress(
                                    "Sync Complete!",
                                    followers.length, totals.followers, following.length, totals.following, 1.0
                                );
                            }
                            
                            if (window.AndroidBridge && window.AndroidBridge.onSyncCompleted) {
                                window.AndroidBridge.onSyncCompleted(
                                    JSON.stringify(followers),
                                    JSON.stringify(following),
                                    JSON.stringify(serverTruth)
                                );
                            }
                        } catch (e) {
                            if (window.AndroidBridge && window.AndroidBridge.onSyncError) {
                                window.AndroidBridge.onSyncError(String(e));
                            }
                        } finally {
                            this.isSyncing = false;
                        }
                    },
                    
                    async executeAction(pk, actionType, csrfToken) {
                        const token = csrfToken || getCookie("csrftoken") || "";
                        const endpoint = actionType === "UNFOLLOW"
                            ? "https://www.instagram.com/api/v1/friendships/destroy/" + pk + "/"
                            : "https://www.instagram.com/api/v1/friendships/create/" + pk + "/";
                        try {
                            const resp = await fetch(endpoint, {
                                method: "POST",
                                credentials: "include",
                                headers: {
                                    "x-ig-app-id": "936619743392459",
                                    "x-csrftoken": token,
                                    "x-requested-with": "XMLHttpRequest",
                                    "Content-Type": "application/x-www-form-urlencoded"
                                },
                                body: ""
                            });
                            const success = resp.ok || resp.status === 404 || resp.status === 400;
                            if (window.AndroidBridge && window.AndroidBridge.onActionResult) {
                                window.AndroidBridge.onActionResult(pk, actionType, success);
                            }
                            return success;
                        } catch (e) {
                            if (window.AndroidBridge && window.AndroidBridge.onActionResult) {
                                window.AndroidBridge.onActionResult(pk, actionType, true);
                            }
                            return true;
                        }
                    }
                };
                
                window.executeInstaAction = function(pk, actionType, csrfToken) {
                    return window.InstaSyncEngine.executeAction(pk, actionType, csrfToken);
                };
            })();
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

            addJavascriptInterface(AndroidBridge(callback), "AndroidBridge")

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
            val jsCall = "$ENGINE_JS; window.InstaSyncEngine.startSync('$dsUserId', '$csrfToken');"
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
