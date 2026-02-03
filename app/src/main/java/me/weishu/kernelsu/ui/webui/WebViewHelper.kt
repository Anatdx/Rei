package me.weishu.kernelsu.ui.webui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.io.SuFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.createRootShell
import org.json.JSONArray
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
internal suspend fun prepareWebView(
    activity: Activity,
    moduleId: String,
    webUIState: WebUIState,
) {
    withContext(Dispatchers.IO) {
        webUIState.modDir = "/data/adb/modules/$moduleId"

        val shell = createRootShell(true)
        webUIState.rootShell = shell

        val moduleListJson = runCatching {
            ShellUtils.fastCmd(
                shell,
                "sh",
                "-c",
                "if [ -x /data/adb/ksud ]; then /data/adb/ksud module list; "
                    + "elif [ -x /data/adb/reid ]; then /data/adb/reid module list; "
                    + "else echo '[]'; fi",
            )
        }.getOrDefault("[]").trim()

        val moduleInfo = runCatching {
            val arr = JSONArray(moduleListJson)
            (0 until arr.length()).asSequence()
                .mapNotNull { arr.optJSONObject(it) }
                .firstOrNull { it.optString("id") == moduleId }
        }.getOrNull()

        val name = moduleInfo?.optString("name")?.ifBlank { moduleId } ?: moduleId
        val enabled = moduleInfo?.optBoolean("enabled", true) ?: true
        val update = moduleInfo?.optBoolean("update", false) ?: false
        val remove = moduleInfo?.optBoolean("remove", false) ?: false

        val webRoot = File("${webUIState.modDir}/webroot")
        val hasWebUi = runCatching {
            val f = SuFile(File(webRoot, "index.html").absolutePath).apply { setShell(shell) }
            f.exists()
        }.getOrDefault(false)

        if (!hasWebUi || !enabled || update || remove) {
            withContext(Dispatchers.Main) {
                webUIState.uiEvent = WebUIEvent.Error("Module is unavailable or has no WebUI: $name")
            }
            runCatching { shell.close() }
            return@withContext
        }

        webUIState.moduleName = name

        withContext(Dispatchers.Main) {
            val label = "Rei - $name"
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                activity.setTaskDescription(ActivityManager.TaskDescription(label))
            } else {
                val taskDescription = ActivityManager.TaskDescription.Builder().setLabel(label).build()
                activity.setTaskDescription(taskDescription)
            }

            val webView = WebView(activity)
            webView.setBackgroundColor(Color.TRANSPARENT)

            val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
            WebView.setWebContentsDebuggingEnabled(prefs.getBoolean("enable_web_debugging", false))

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
            }

            val webViewAssetLoader = WebViewAssetLoader.Builder()
                .setDomain("mui.kernelsu.org")
                .addPathHandler(
                    "/",
                    SuFilePathHandler(
                        activity,
                        webRoot,
                        shell,
                        { webUIState.currentInsets },
                        { enable -> webUIState.isInsetsEnabled = enable },
                    ),
                )
                .build()

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.scheme.equals("ksu", ignoreCase = true) && url.host.equals("icon", ignoreCase = true)) {
                        val packageName = url.path?.substring(1)
                        if (!packageName.isNullOrEmpty()) {
                            val icon = AppIconUtil.loadAppIconSync(activity, packageName, 512)
                            if (icon != null) {
                                val stream = java.io.ByteArrayOutputStream()
                                icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                                return WebResourceResponse("image/png", null, java.io.ByteArrayInputStream(stream.toByteArray()))
                            }
                        }
                    }
                    return webViewAssetLoader.shouldInterceptRequest(url)
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    webUIState.webCanGoBack = view?.canGoBack() ?: false
                    if (webUIState.isInsetsEnabled) webUIState.webView?.evaluateJavascript(webUIState.currentInsets.js, null)
                    super.doUpdateVisitedHistory(view, url, isReload)
                }
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    if (result == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowAlert(message.orEmpty(), result)
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    if (result == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowConfirm(message.orEmpty(), result)
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?,
                ): Boolean {
                    if (result == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowPrompt(message.orEmpty(), defaultValue.orEmpty(), result)
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    val intent = fileChooserParams?.createIntent() ?: return false
                    webUIState.filePathCallback = filePathCallback
                    webUIState.uiEvent = WebUIEvent.ShowFileChooser(intent)
                    return true
                }
            }

            webUIState.webView = webView
            webView.addJavascriptInterface(WebViewInterface(webUIState), "ksu")
            webUIState.uiEvent = WebUIEvent.WebViewReady
        }
    }
}
