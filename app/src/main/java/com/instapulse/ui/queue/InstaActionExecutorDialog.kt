package com.instapulse.ui.queue

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instapulse.data.model.ActionQueueItem
import com.instapulse.data.model.ActionType
import com.instapulse.ui.theme.Amber
import com.instapulse.ui.theme.Crimson
import com.instapulse.ui.theme.Cyan
import com.instapulse.ui.theme.TextPrimary
import com.instapulse.ui.theme.TextSecondary

@Composable
fun InstaActionExecutorHud(
    visible: Boolean,
    queue: List<ActionQueueItem>,
    currentIndex: Int,
    isPaused: Boolean,
    countdown: Int,
    statusMessage: String,
    onTogglePause: () -> Unit,
    onCancel: () -> Unit,
    onActionResult: (String, String, Boolean) -> Unit
) {
    AnimatedVisibility(
        visible = visible && queue.isNotEmpty() && currentIndex < queue.size,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        val currentItem = queue.getOrNull(currentIndex) ?: return@AnimatedVisibility

        // Offscreen WebView for executing action script
        val executorWebView = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<android.webkit.WebView?>(null) }
        
        // Execute script when the current item changes or on load
        androidx.compose.runtime.LaunchedEffect(currentItem.username, currentItem.action) {
            val url = "https://www.instagram.com/${currentItem.username}/"
            executorWebView.value?.loadUrl(url)
            kotlinx.coroutines.delay(2000) // Wait for page load
            val actionType = if (currentItem.action == ActionType.UNFOLLOW) "unfollow" else "follow"
            val targetPk = currentItem.pk ?: ""

            val cookieManager = android.webkit.CookieManager.getInstance()
            val rawCookies = cookieManager.getCookie("https://www.instagram.com") ?: ""
            var csrfToken = ""
            rawCookies.split(";").forEach { part ->
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0].trim() == "csrftoken") {
                    csrfToken = kv[1].trim()
                }
            }

            val script = """
                (async function() {
                  const actionType = '$actionType';
                  const targetPk = '$targetPk';
                  const username = '${currentItem.username}';
                  const csrfToken = '$csrfToken';
                  const IG_APP_ID = '936619743392459';
                  
                  function getCookie(name) {
                    const match = document.cookie.match(new RegExp('(^| )' + name + '=([^;]+)'));
                    return match ? decodeURIComponent(match[2]) : null;
                  }
                  const effectiveCsrf = csrfToken || getCookie('csrftoken') || '';
                  
                  function reportNative(success) {
                    if(window.InstaNativeBridge) {
                        window.InstaNativeBridge.postMessage(JSON.stringify({
                            type: 'ACTION_RESULT',
                            targetPk: targetPk,
                            actionType: actionType,
                            success: success
                        }));
                    }
                  }

                  // Step A: checkAlreadyInDesiredState
                  const btnTexts = Array.from(document.querySelectorAll('button')).map(b => b.innerText.toLowerCase());
                  const isFollowing = btnTexts.includes('following') || btnTexts.includes('requested');
                  if (actionType === 'unfollow' && !isFollowing) {
                      return reportNative(true);
                  }
                  if (actionType === 'follow' && isFollowing) {
                      return reportNative(true);
                  }
                  
                  // Step B: executeApiAction
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
                    if (res.ok && (data.status === 'ok' || Boolean(data.friendship_status))) {
                        return reportNative(true);
                    }
                  } catch (e) {
                    console.error(e);
                  }
                  
                  // Step C: executeDomFallback
                  try {
                      if (actionType === 'unfollow') {
                          const followingBtn = Array.from(document.querySelectorAll('button')).find(b => b.innerText.toLowerCase() === 'following' || b.innerText.toLowerCase() === 'requested');
                          if (followingBtn) {
                              followingBtn.click();
                              await new Promise(r => setTimeout(r, 1000));
                              const unfollowConfirm = Array.from(document.querySelectorAll('button')).find(b => b.innerText.toLowerCase() === 'unfollow');
                              if (unfollowConfirm) {
                                  unfollowConfirm.click();
                                  return reportNative(true);
                              }
                          }
                      } else {
                          const followBtn = Array.from(document.querySelectorAll('button')).find(b => b.innerText.toLowerCase() === 'follow' || b.innerText.toLowerCase() === 'follow back');
                          if (followBtn) {
                              followBtn.click();
                              return reportNative(true);
                          }
                      }
                  } catch (e) {
                      console.error(e);
                  }
                  
                  reportNative(false);
                })();
            """.trimIndent()
            executorWebView.value?.evaluateJavascript(script, null)
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier
                    .size(4.dp, 4.dp)
                    .alpha(0.02f),
                factory = { context ->
                    android.webkit.WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            // Default User Agent to bypass bot checks natively
                        }
                        val cookieManager = android.webkit.CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        
                        class ActionJsBridge {
                            @android.webkit.JavascriptInterface
                            fun postMessage(jsonString: String) {
                                try {
                                    val json = org.json.JSONObject(jsonString)
                                    val type = json.optString("type")
                                    if (type == "ACTION_RESULT") {
                                        val targetPk = json.optString("targetPk")
                                        val actionType = json.optString("actionType")
                                        val success = json.optBoolean("success")
                                        onActionResult(targetPk, actionType, success)
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                        addJavascriptInterface(ActionJsBridge(), "InstaNativeBridge")
                    }
                },
                update = { webView ->
                    if (executorWebView.value == null) {
                        executorWebView.value = webView
                    }
                }
            )
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp)
            ) {
            // Glass container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xF0131724))
                    .border(1.dp, Color(0x20FFFFFF), RoundedCornerShape(20.dp))
                    .border(
                        width = 2.dp,
                        color = if (currentItem.action == ActionType.UNFOLLOW) Crimson else Cyan,
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(16.dp)
            ) {
                Column {
                    // Top Row: Status + Badge + Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (!isPaused && countdown == 0) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Cyan,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (isPaused) Crimson else Amber)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Text(
                                text = "${if (currentItem.action == ActionType.UNFOLLOW) "Unfollowing" else "Following"} @${currentItem.username}",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x2638BDF8))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${currentIndex + 1} / ${queue.size}",
                                    color = Cyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Pause / Resume
                            IconButton(
                                onClick = onTogglePause,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isPaused) "Resume" else "Pause",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Close / Cancel
                            IconButton(
                                onClick = onCancel,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel Queue",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Status Message
                    Text(
                        text = statusMessage,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Cooldown Indicator
                    if (isPaused) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x20FF3B5C))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Queue Paused — Tap Play to Resume",
                                color = Crimson,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    } else if (countdown > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x20F59E0B))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Ultra-Fast Pacing... wait ${countdown}s",
                                color = Amber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Progress Bar
                    val progress = ((currentIndex + 1).toFloat() / queue.size.coerceAtLeast(1).toFloat()).coerceIn(0.05f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0x20FFFFFF))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Cyan)
                        )
                    }
                }
            }
        }
    }
}
}
