package com.instapulse.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InstagramLoginDialog(
    visible: Boolean = true,
    onDismiss: () -> Unit,
    onLoginSuccess: (cookieHeader: String, dsUserId: String, csrfToken: String) -> Unit
) {
    if (!visible) return

    var statusText by remember { mutableStateOf("Connecting to Instagram...") }
    var isLoadingPage by remember { mutableStateOf(true) }
    var hasCompletedLogin by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090A0F))
                .systemBarsPadding()
        ) {
            // Top Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF12151F))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Instagram Authentication",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = statusText,
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Close",
                        color = Color(0xFFFF3B5C),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isLoadingPage) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Color(0xFFDD2A7B),
                    trackColor = Color(0xFF12151F)
                )
            }

            // Full-Screen Native Android WebView
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                loadsImagesAutomatically = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                // Strip "; wv" so Instagram renders the mobile web login form
                                userAgentString = userAgentString.replace("; wv", "")
                            }

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    // Block intent:// and instagram:// redirects that cause blank screens
                                    return !(url.startsWith("http://") || url.startsWith("https://"))
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?
                                ) {
                                    isLoadingPage = true
                                    statusText = "Loading secure Instagram login..."
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoadingPage = false
                                    statusText = "Sign in with your Instagram account"
                                    cookieManager.flush()

                                    val cookies = cookieManager.getCookie("https://www.instagram.com") ?: ""
                                    val dsUserId = extractCookieValue(cookies, "ds_user_id")
                                    val csrfToken = extractCookieValue(cookies, "csrftoken")
                                    val sessionId = extractCookieValue(cookies, "sessionid")

                                    if (!hasCompletedLogin && !dsUserId.isNullOrBlank() && !csrfToken.isNullOrBlank() && !sessionId.isNullOrBlank()) {
                                        hasCompletedLogin = true
                                        statusText = "Connected! Syncing your graph..."
                                        onLoginSuccess(cookies, dsUserId, csrfToken)
                                    }
                                }
                            }

                            loadUrl("https://www.instagram.com/accounts/login/")
                        }
                    }
                )
            }
        }
    }
}

private fun extractCookieValue(cookieHeader: String, key: String): String? {
    return cookieHeader.split(";")
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("$key=")
        ?.trim()
}