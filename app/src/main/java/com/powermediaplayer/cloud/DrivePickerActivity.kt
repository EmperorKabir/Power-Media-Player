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
 *
 * Why a WebView: under the drive.file OAuth scope, the only way to
 * grant the app per-folder access is through Google's hosted Picker
 * JS — there is no native equivalent. The Storage Access Framework
 * picker that we use for OneDrive / local sources hides Drive on
 * some devices (notably Samsung fold landscape mode).
 *
 * No verification or CASA paperwork is required for the drive.file
 * scope, so this picker works for unlimited end-users.
 */
@AndroidEntryPoint
class DrivePickerActivity : ComponentActivity() {

    @Inject
    lateinit var driveOAuthProvider: DriveOAuthProvider

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Picker iframe needs measurable height; full-screen WebView
        // satisfies that on every device.
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
            v.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            // 2026-07-19 (new-device regression): the Picker iframe is served
            // from Google's origin while our page origin is the local base
            // URL, so every Google cookie here is THIRD-party — and WebView
            // blocks third-party cookies by default. On a fresh WebView
            // profile (new phone) Google then refuses the picker outright:
            // "Can't access your Google Account … try … allowing cookie
            // access to proceed." Old installs worked only because their
            // WebView profile predated the stricter enforcement. Scope is
            // THIS WebView instance only.
            android.webkit.CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(v, true)
            }
            v.webViewClient = object : WebViewClient() {
                // Release-safe diagnostics: a picker load failure now lands in
                // the opt-in DiagLog FILE (not just logcat), so a stuck sign-in
                // on the Play build is diagnosable from a pulled log.
                override fun onReceivedError(
                    view: WebView, req: android.webkit.WebResourceRequest,
                    err: android.webkit.WebResourceError
                ) {
                    if (req.isForMainFrame) com.powermediaplayer.diag.DiagLog.event(
                        "PICKER", "loadError ${err.errorCode} ${err.description} url=${req.url}"
                    )
                }
                override fun onReceivedHttpError(
                    view: WebView, req: android.webkit.WebResourceRequest,
                    resp: android.webkit.WebResourceResponse
                ) {
                    com.powermediaplayer.diag.DiagLog.event(
                        "PICKER", "httpError ${resp.statusCode} url=${req.url}"
                    )
                }
            }
            v.webChromeClient = object : android.webkit.WebChromeClient() {
                // 2026-07-22 FIX (new-device "tap Sign in does nothing"): the
                // picker's Drive iframe opens its Google sign-in as a POPUP
                // window. With multiple-windows enabled but no onCreateWindow,
                // WebView silently dropped that popup — the exact dead tap the
                // user reported. Route the popup's first navigation back into
                // the main WebView so the sign-in can actually render. Only
                // fires when a popup is requested, so devices whose picker
                // needed no sign-in (the old phone) are unaffected.
                override fun onCreateWindow(
                    view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                    resultMsg: android.os.Message
                ): Boolean {
                    val href = view.hitTestResult.extra
                    if (!href.isNullOrBlank()) {
                        view.loadUrl(href)
                        return false
                    }
                    // No direct href (JS window.open): hand the popup a transport
                    // WebView whose first URL we redirect into the main view.
                    val transport = resultMsg.obj as WebView.WebViewTransport
                    val popup = WebView(this@DrivePickerActivity)
                    popup.settings.javaScriptEnabled = true
                    popup.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            v: WebView, r: android.webkit.WebResourceRequest
                        ): Boolean {
                            view.loadUrl(r.url.toString())
                            return true
                        }
                    }
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        com.powermediaplayer.diag.DiagLog.event(
                            "PICKER", "consoleErr ${m.message()} @${m.lineNumber()}"
                        )
                    }
                    return true
                }
            }
            v.addJavascriptInterface(
                JsBridge(token, apiKey, appId),
                "PMP_PICKER_BRIDGE"
            )
            // Inject the runtime configuration into window globals
            // BEFORE the page's inline script runs by hooking
            // onPageStarted via a small client. Simpler approach: load
            // a redirector page that sets window.__OAUTH_TOKEN__ etc.
            // then includes drive_picker.html via document.write. We
            // do that inline via loadDataWithBaseURL so the page can
            // reach apis.google.com (origin must be a real https URL
            // for the Picker iframe to receive postMessage).
            val baseUrl = "https://drive-picker.local.invalid/"
            val html = """
                <!DOCTYPE html><html><head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
                <style>
                  html, body { margin: 0; padding: 0; width: 100%; height: 100%;
                               background: #000; overflow: hidden; }
                </style>
                </head>
                <body><script>
                  window.__OAUTH_TOKEN__ = "${token.replace("\"", "\\\"")}";
                  window.__API_KEY__     = "${apiKey.replace("\"", "\\\"")}";
                  window.__APP_ID__      = "${appId.replace("\"", "\\\"")}";
                  window.PMP_PICKER = {
                    onPicked: function (id, name) {
                      window.PMP_PICKER_BRIDGE.onPicked(id, name);
                    },
                    onCancel: function () { window.PMP_PICKER_BRIDGE.onCancel(); },
                    onError:  function (m) { window.PMP_PICKER_BRIDGE.onError(m); }
                  };
                  var pmpPicker = null;
                  function pmpRebuildPicker() {
                    try {
                      var view = new google.picker.DocsView()
                        .setIncludeFolders(true)
                        .setSelectFolderEnabled(true)
                        .setMimeTypes("application/vnd.google-apps.folder");
                      pmpPicker = new google.picker.PickerBuilder()
                        .addView(view)
                        .setOAuthToken(window.__OAUTH_TOKEN__)
                        .setDeveloperKey(window.__API_KEY__)
                        .setAppId(window.__APP_ID__)
                        .setSize(window.innerWidth, window.innerHeight)
                        .setCallback(function (data) {
                          var action = data[google.picker.Response.ACTION];
                          if (action === google.picker.Action.PICKED) {
                            var d = (data[google.picker.Response.DOCUMENTS] || [])[0];
                            if (d) window.PMP_PICKER.onPicked(
                              d[google.picker.Document.ID],
                              d[google.picker.Document.NAME] || "Picked folder"
                            );
                          } else if (action === google.picker.Action.CANCEL) {
                            window.PMP_PICKER.onCancel();
                          }
                        })
                        .enableFeature(google.picker.Feature.NAV_HIDDEN)
                        .build();
                      pmpPicker.setVisible(true);
                    } catch (e) {
                      window.PMP_PICKER.onError("Picker init: " + e.message);
                    }
                  }
                  fetch("https://www.googleapis.com/")
                    .catch(function(){})
                    .finally(function() {
                      var s = document.createElement("script");
                      s.src = "https://apis.google.com/js/api.js";
                      s.onload = function () {
                        gapi.load("picker", { callback: function () {
                          pmpRebuildPicker();
                          // Re-render on WIDTH (orientation / fold) changes ONLY.
                          // A height-only resize is the soft keyboard opening; do
                          // NOT rebuild then, disposing the picker blurs the
                          // focused search box, so the IME is dismissed the instant
                          // it tries to show (show/hide churn → keyboard never
                          // appears). Width changes still rebuild to refit the fold.
                          var pmpLastW = window.innerWidth;
                          var pmpResizeTimer = null;
                          window.addEventListener("resize", function () {
                            if (pmpResizeTimer) clearTimeout(pmpResizeTimer);
                            // Debounce + require a real WIDTH change (>40px). The
                            // soft keyboard fires several jittery resizes while it
                            // animates; rebuilding on those disposes the focused
                            // search box and the keyboard flickers (3 show/hide
                            // cycles seen in logs). Only a fold/orientation change
                            // (big, settled width delta) should rebuild.
                            pmpResizeTimer = setTimeout(function () {
                              if (Math.abs(window.innerWidth - pmpLastW) < 40) return;
                              pmpLastW = window.innerWidth;
                              if (pmpPicker) { try { pmpPicker.dispose(); } catch(_) {} }
                              pmpRebuildPicker();
                            }, 400);
                          });
                        }});
                      };
                      s.onerror = function () {
                        window.PMP_PICKER.onError("apis.google.com unreachable");
                      };
                      document.head.appendChild(s);
                    });
                </script></body></html>
            """.trimIndent()
            v.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
        }
        setContentView(webView)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("PMP_PICKER_BRIDGE")
            // destroy() on a still-attached WebView is documented-unsupported
            // and can keep renderer resources alive until window teardown.
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
            runOnUiThread {
                setResult(Activity.RESULT_OK, data)
                finish()
            }
        }

        @JavascriptInterface
        fun onCancel() {
            runOnUiThread {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            runOnUiThread {
                Toast.makeText(this@DrivePickerActivity, "Drive Picker: $message",
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
