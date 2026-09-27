package com.instapulse.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.instapulse.ui.theme.CanvasBg
import com.instapulse.ui.theme.Crimson
import com.instapulse.ui.theme.Cyan
import com.instapulse.ui.theme.TextPrimary
import com.instapulse.ui.theme.TextSecondary

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InstagramLoginDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onLoginSuccess: (dsUserId: String, csrfToken: String) -> Unit
) {
    if (!visible) return

    var isLoading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("Connecting to Instagram...") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CanvasBg)
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Instagram Authentication",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = statusText,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 12.dp),
                        color = Cyan,
                        strokeWidth = 2.dp
                    )
                }

                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Close",
                        color = Crimson,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(Color_0x14White))

            // WebView
            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                cacheMode = WebSettings.LOAD_DEFAULT
                                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    isLoading = newProgress < 95
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    checkCookies(url)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    checkCookies(url)
                                }

                                private fun checkCookies(url: String?) {
                                    val cookies = cookieManager.getCookie(url ?: "https://www.instagram.com/") ?: return
                                    var dsUserId: String? = null
                                    var csrfToken: String? = null

                                    cookies.split(";").forEach { rawCookie ->
                                        val trimmed = rawCookie.trim()
                                        if (trimmed.startsWith("ds_user_id=")) {
                                            dsUserId = trimmed.substringAfter("ds_user_id=")
                                        } else if (trimmed.startsWith("csrftoken=")) {
                                            csrfToken = trimmed.substringAfter("csrftoken=")
                                        }
                                    }

                                    if (!dsUserId.isNullOrEmpty() && !csrfToken.isNullOrEmpty()) {
                                        statusText = "Authenticated successfully!"
                                        onLoginSuccess(dsUserId!!, csrfToken!!)
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

private val Color_0x14White = androidx.compose.ui.graphics.Color(0x14FFFFFF)
