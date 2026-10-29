package com.decio.skipii

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&hl=en" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26hl%3Den%26next%3D%252F"

/**
 * Google's own sign-in page. Skipii never sees the password; once the page lands back on
 * YouTube signed in, the youtube.com cookies are taken over (encrypted) and the WebView's
 * copy is wiped.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInScreen(onDone: () -> Unit, onCancel: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(c.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).pressable(onClick = onCancel), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Cancel")
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Sign in to YouTube", fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = c.onSurfaceVariant, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("accounts.google.com · kept encrypted on this phone", color = c.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
        Box(Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val cookies = CookieManager.getInstance()
                    // Start clean so a half-finished earlier attempt can't leak in.
                    cookies.removeAllCookies(null)
                    cookies.setAcceptCookie(true)
                    WebView(context).apply {
                        cookies.setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google refuses sign-in from user agents that announce an embedded
                        // WebView ("; wv"); this is the same Chrome engine without the marker.
                        settings.userAgentString = WebSettings.getDefaultUserAgent(context).replace("; wv", "")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                if (finishing) return
                                val header = cookies.getCookie(Account.ORIGIN)
                                if (!Account.looksSignedIn(header)) return
                                finishing = true
                                scope.launch {
                                    runCatching { Account.completeSignIn(header) }
                                        .onSuccess {
                                            cookies.removeAllCookies(null)
                                            cookies.flush()
                                            onDone()
                                        }
                                        .onFailure {
                                            finishing = false
                                            error = "Signed in, but YouTube didn't answer. Check your connection and try again."
                                        }
                                }
                            }
                        }
                        loadUrl(SIGN_IN_URL)
                    }
                },
            )
            if (finishing || error != null) {
                Column(
                    Modifier.fillMaxSize().background(c.surface).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    if (error == null) {
                        CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Finishing sign-in…", fontWeight = FontWeight.Bold)
                    } else {
                        Text(error.orEmpty(), textAlign = TextAlign.Center, color = c.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        PillButton("Close", onCancel)
                    }
                }
            }
        }
    }
}

/** Account button in the headers: the avatar when signed in, a person icon otherwise. */
@Composable
fun AccountAction(onClick: () -> Unit) {
    val profile = Account.profile
    if (profile == null) {
        CircleAction(Icons.Rounded.AccountCircle, "Sign in", onClick)
    } else {
        Box(Modifier.size(44.dp).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
            ChannelAvatar(profile.avatar, profile.name, size = 40)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(onSignIn: () -> Unit, onSignedOut: () -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val profile = Account.profile
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (profile == null) {
                Text("Your YouTube, without the ads", fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sign in to get your recommendations, subscriptions, history and playlists. " +
                        "Likes, subscribes, Watch later and watch history sync back to your account.\n\n" +
                        "Google treats unofficial apps as against its terms and can suspend accounts that use them. " +
                        "A secondary Google account keeps that risk away from your main one.",
                    color = c.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                PillButton("Sign in with Google", { onDismiss(); onSignIn() })
            } else {
                ChannelAvatar(profile.avatar, profile.name, size = 72)
                Spacer(Modifier.height(12.dp))
                Text(profile.name, fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                profile.handle?.let { Text(it, color = c.onSurfaceVariant, fontSize = 14.sp) }
                Spacer(Modifier.height(20.dp))
                PillButton("Sign out", { Account.signOut(); onSignedOut(); onDismiss() }, Icons.AutoMirrored.Rounded.Logout)
            }
            // Settings live here (Derek's choice): every tab's header has this sheet.
            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceContainer).padding(18.dp)) {
                AppearanceSettings()
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceContainer).padding(18.dp)) {
                ShortsSettings()
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceContainer).padding(18.dp)) {
                SponsorBlockSettings()
            }
        }
    }
}
