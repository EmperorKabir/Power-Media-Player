# REMOVED: WebView Google Drive Picker (archived 2026-07-25)

**Why removed:** replaced by a native in-app folder browser (`DriveFolderBrowser` in
`CloudBrowserScreen.kt` + `DriveOAuthProvider.listSubFolders`). The WebView Picker was
the sole cause of the **second in-WebView sign-in** and the **cold-start scaling**, and
after Google restricted `drive.file` folder-grants it no longer granted a picked
folder's files anyway (access now flows through `drive.readonly` REST — see
`archive/2026-07-drive-picker-oauth-saga.md` §0-NEW). The native browser lists folders
via `drive.readonly` and needs no WebView, cookies, or second login.

**How to restore (exact):** the files below existed unchanged at commit **`2a6053a`**
(the commit immediately before removal). Restore with:
```bash
git show 2a6053a:app/src/main/java/com/powermediaplayer/cloud/DrivePickerActivity.kt > app/src/main/java/com/powermediaplayer/cloud/DrivePickerActivity.kt
git show 2a6053a:app/src/main/assets/drive_picker.html > app/src/main/assets/drive_picker.html
```
Then re-add the two manifest `<activity>` blocks (below) and re-wire `launchDriveOAuth()`
in `CloudBrowserScreen.kt` to fetch a token and launch `DrivePickerActivity.intent(...)`
via a `drivePickerLauncher` (see that commit's `CloudBrowserScreen.kt` lines ~246-293).
The full inlined code is also preserved below for offline reference.

**Note:** `drive_picker.html` was already DEAD before removal — `DrivePickerActivity`
built its HTML inline via `loadDataWithBaseURL` (the asset was only referenced in
comments). Kept here for completeness.

---

## `app/src/main/AndroidManifest.xml` — the `<activity>` block (was ~L124-133)

```xml
        <!-- Drive Picker WebView host. Launched via startActivityForResult
             from CloudBrowserScreen; receives an OAuth token in
             EXTRA_OAUTH_TOKEN, returns picked folder ID + name. Single-
             task so the back button cleanly returns to the cloud screen. -->
        <activity
            android:name=".cloud.DrivePickerActivity"
            android:exported="false"
            android:label="Pick a Drive folder"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize|density"
            android:theme="@style/Theme.PowerMediaPlayer" />
```

## `app/src/debug/AndroidManifest.xml` — the debug override (was ~L13-22)

```xml
        <!-- Debug-only test hook (2026-07-19): lets adb launch the Drive
             Picker WebView directly with a token extra, so the picker's
             cookie behaviour is testable on-device without the package-bound
             OAuth step (the .test suffix is not registered at the OAuth
             console). Release manifest keeps the activity non-exported. -->
        <activity
            android:name="com.powermediaplayer.cloud.DrivePickerActivity"
            android:exported="true"
            tools:node="merge"
            tools:replace="android:exported" />
```

## `app/src/main/java/com/powermediaplayer/cloud/DrivePickerActivity.kt`

```kotlin
package com.powermediaplayer.cloud

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.powermediaplayer.BuildConfig
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Embeds Google's JS Drive Picker in a WebView. Receives an OAuth
 * access token via intent extra, loads `assets/drive_picker.html`,
 * and waits for the Picker JS callback. On pick, sets the result
 * to the picked folder's ID + display name and finishes.
 */
@AndroidEntryPoint
class DrivePickerActivity : ComponentActivity() {

    @Inject
    lateinit var driveOAuthProvider: DriveOAuthProvider

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        val token = intent.getStringExtra(EXTRA_OAUTH_TOKEN)
        if (token.isNullOrBlank()) {
            Toast.makeText(this, "No Drive token, sign in first", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val apiKey = BuildConfig.DRIVE_PICKER_API_KEY
        val appId = BuildConfig.DRIVE_PICKER_APP_ID
        if (apiKey.isBlank() || appId.isBlank()) {
            Toast.makeText(
                this,
                "Drive Picker not configured. Add DRIVE_PICKER_API_KEY and " +
                    "DRIVE_PICKER_APP_ID to local.properties and rebuild.",
                Toast.LENGTH_LONG
            ).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        webView = WebView(this).also { v ->
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
                val bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            v.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            android.webkit.CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(v, true)
            }
            v.webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView, req: android.webkit.WebResourceRequest,
                    err: android.webkit.WebResourceError
                ) {
                    if (req.isForMainFrame) com.powermediaplayer.diag.DiagLog.event(
                        "PICKER", "loadError ${'$'}{err.errorCode} ${'$'}{err.description} url=${'$'}{req.url}"
                    )
                }
                override fun onReceivedHttpError(
                    view: WebView, req: android.webkit.WebResourceRequest,
                    resp: android.webkit.WebResourceResponse
                ) {
                    com.powermediaplayer.diag.DiagLog.event(
                        "PICKER", "httpError ${'$'}{resp.statusCode} url=${'$'}{req.url}"
                    )
                }
            }
            v.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                    resultMsg: android.os.Message
                ): Boolean {
                    val href = view.hitTestResult.extra
                    if (!href.isNullOrBlank()) { view.loadUrl(href); return false }
                    val transport = resultMsg.obj as WebView.WebViewTransport
                    val popup = WebView(this@DrivePickerActivity)
                    popup.settings.javaScriptEnabled = true
                    popup.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            v2: WebView, r: android.webkit.WebResourceRequest
                        ): Boolean { view.loadUrl(r.url.toString()); return true }
                    }
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        com.powermediaplayer.diag.DiagLog.event(
                            "PICKER", "consoleErr ${'$'}{m.message()} @${'$'}{m.lineNumber()}"
                        )
                    }
                    return true
                }
            }
            v.addJavascriptInterface(JsBridge(token, apiKey, appId), "PMP_PICKER_BRIDGE")
            val baseUrl = "https://drive-picker.local.invalid/"
            // NOTE: inline HTML built here (the drive_picker.html asset was unused).
            // The DocsView is configured for FOLDER selection with NAV_HIDDEN, plus a
            // one-time "settle" watcher that rebuilds the picker once window.innerHeight
            // changes (the cold-start inset fix, commit 1c1b581), and a width-only
            // resize handler to refit folds without keyboard churn. See git blob
            // 2a6053a for the exact JS string (omitted here to avoid escaping noise;
            // it is a faithful copy of the DocsView + PickerBuilder below).
            val html = "<!-- see git show 2a6053a:.../DrivePickerActivity.kt for the exact inline HTML -->"
            v.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
        }
        setContentView(webView)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("PMP_PICKER_BRIDGE")
            runCatching {
                webView.stopLoading()
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            }
            webView.destroy()
        }
        super.onDestroy()
    }

    inner class JsBridge(
        private val oauthToken: String,
        private val apiKey: String,
        private val appId: String
    ) {
        @JavascriptInterface
        fun onPicked(folderId: String, folderName: String) {
            val data = Intent().apply {
                putExtra(RESULT_FOLDER_ID, folderId)
                putExtra(RESULT_FOLDER_NAME, folderName)
            }
            runOnUiThread { setResult(Activity.RESULT_OK, data); finish() }
        }

        @JavascriptInterface
        fun onCancel() { runOnUiThread { setResult(Activity.RESULT_CANCELED); finish() } }

        @JavascriptInterface
        fun onError(message: String) {
            runOnUiThread {
                Toast.makeText(this@DrivePickerActivity, "Drive Picker: ${'$'}message",
                    Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_OAUTH_TOKEN = "extra_oauth_token"
        const val RESULT_FOLDER_ID = "folder_id"
        const val RESULT_FOLDER_NAME = "folder_name"

        fun intent(context: android.content.Context, oauthToken: String): Intent =
            Intent(context, DrivePickerActivity::class.java).apply {
                putExtra(EXTRA_OAUTH_TOKEN, oauthToken)
            }
    }
}
```

> The full inline picker HTML (DocsView folder-select + NAV_HIDDEN + the `1c1b581`
> cold-start settle watcher + the width-only resize handler) is in the git blob
> `2a6053a:app/src/main/java/com/powermediaplayer/cloud/DrivePickerActivity.kt`
> lines ~172-286. Restore verbatim from there if the native browser must be reverted.

## `app/src/main/assets/drive_picker.html` (dead asset — unused, inline HTML was used instead)

Recoverable verbatim: `git show 2a6053a:app/src/main/assets/drive_picker.html`. It was a
standalone page loading `apis.google.com/js/api.js`, building a `DocsView` folder picker
(`setIncludeFolders(true).setSelectFolderEnabled(true).setMimeTypes("application/vnd.google-apps.folder")`)
with `NAV_HIDDEN`, calling back via `window.PMP_PICKER.onPicked/onCancel/onError`.
