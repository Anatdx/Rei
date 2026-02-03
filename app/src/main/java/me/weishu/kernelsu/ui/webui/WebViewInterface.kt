package me.weishu.kernelsu.ui.webui

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Window
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.topjohnwu.superuser.ShellUtils
import me.weishu.kernelsu.ui.util.withNewRootShell
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WebViewInterface(private val state: WebUIState) {
    private val webView get() = state.webView!!
    private val modDir get() = state.modDir

    @JavascriptInterface
    fun exec(cmd: String): String {
        return withNewRootShell(true) { ShellUtils.fastCmd(this, cmd) }
    }

    @JavascriptInterface
    fun exec(cmd: String, callbackFunc: String) {
        exec(cmd, null, callbackFunc)
    }

    private fun processOptions(sb: StringBuilder, options: String?) {
        val opts = if (options == null) JSONObject() else JSONObject(options)

        val cwd = opts.optString("cwd")
        if (!TextUtils.isEmpty(cwd)) {
            sb.append("cd ").append(cwd).append(';')
        }

        opts.optJSONObject("env")?.let { env ->
            env.keys().forEach { key ->
                sb.append("export ").append(key).append('=').append(env.getString(key)).append(';')
            }
        }
    }

    @JavascriptInterface
    fun exec(cmd: String, options: String?, callbackFunc: String) {
        val finalCommand = StringBuilder()
        processOptions(finalCommand, options)
        finalCommand.append(cmd)

        val result = withNewRootShell(true) {
            newJob().add(finalCommand.toString()).to(ArrayList(), ArrayList()).exec()
        }
        val stdout = result.out.joinToString("\n")
        val stderr = result.err.joinToString("\n")

        val jsCode = "javascript:(function(){try{${callbackFunc}(${result.code},${JSONObject.quote(stdout)},${JSONObject.quote(stderr)});}catch(e){console.error(e);}})();"
        webView.post { webView.loadUrl(jsCode) }
    }

    /**
     * Simplified spawn implementation (non-streaming). Keeps the upstream JS API shape.
     */
    @JavascriptInterface
    fun spawn(command: String, args: String, options: String?, callbackFunc: String) {
        val finalCommand = StringBuilder()
        processOptions(finalCommand, options)

        if (!TextUtils.isEmpty(args)) {
            finalCommand.append(command).append(' ')
            val argsArray = JSONArray(args)
            for (i in 0 until argsArray.length()) {
                finalCommand.append(argsArray.getString(i)).append(' ')
            }
        } else {
            finalCommand.append(command)
        }

        val result = withNewRootShell(true) {
            newJob().add(finalCommand.toString()).to(ArrayList(), ArrayList()).exec()
        }

        val stdout = result.out.joinToString("\n")
        val stderr = result.err.joinToString("\n")

        val emitStdout = "javascript:(function(){try{${callbackFunc}.stdout.emit('data',${JSONObject.quote(stdout)});}catch(e){console.error('emitStdout',e);}})();"
        val emitStderr = "javascript:(function(){try{${callbackFunc}.stderr.emit('data',${JSONObject.quote(stderr)});}catch(e){console.error('emitStderr',e);}})();"
        val emitExit = "javascript:(function(){try{${callbackFunc}.emit('exit',${result.code});}catch(e){console.error('emitExit',e);}})();"

        webView.post {
            webView.loadUrl(emitStdout)
            webView.loadUrl(emitStderr)
            webView.loadUrl(emitExit)
        }
    }

    @JavascriptInterface
    fun toast(msg: String) {
        webView.post { Toast.makeText(webView.context, msg, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun fullScreen(enable: Boolean) {
        val context = webView.context
        if (context is Activity) {
            Handler(Looper.getMainLooper()).post {
                if (enable) hideSystemUI(context.window) else showSystemUI(context.window)
            }
        }
        enableEdgeToEdge(enable)
    }

    @JavascriptInterface
    fun enableEdgeToEdge(enable: Boolean = true) {
        state.isInsetsEnabled = enable
    }

    private fun moduleListJson(): String {
        return withNewRootShell(true) {
            ShellUtils.fastCmd(
                this,
                "sh",
                "-c",
                "if [ -x /data/adb/ksud ]; then /data/adb/ksud module list; "
                    + "elif [ -x /data/adb/reid ]; then /data/adb/reid module list; "
                    + "else echo '[]'; fi",
            )
        }.trim()
    }

    @JavascriptInterface
    fun moduleInfo(): String {
        val moduleInfos = runCatching { JSONArray(moduleListJson()) }.getOrElse { JSONArray() }
        val currentModuleInfo = JSONObject()
        currentModuleInfo.put("moduleDir", modDir)
        val moduleId = File(modDir).name

        for (i in 0 until moduleInfos.length()) {
            val currentInfo = moduleInfos.optJSONObject(i) ?: continue
            if (currentInfo.optString("id") != moduleId) continue

            val keys = currentInfo.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                currentModuleInfo.put(key, currentInfo.opt(key))
            }
            break
        }

        return currentModuleInfo.toString()
    }

    @JavascriptInterface
    fun listPackages(type: String): String {
        val pm = webView.context.packageManager
        val pkgs = runCatching { pm.getInstalledPackages(0) }.getOrElse { emptyList() }

        fun isSystem(app: ApplicationInfo?): Boolean {
            val flags = app?.flags ?: 0
            return (flags and ApplicationInfo.FLAG_SYSTEM) != 0
        }

        val wanted = pkgs.asSequence()
            .mapNotNull { it.packageName }
            .filter { pkgName ->
                val ai = runCatching { pm.getApplicationInfo(pkgName, 0) }.getOrNull()
                when (type.lowercase()) {
                    "system" -> isSystem(ai)
                    "user" -> !isSystem(ai)
                    else -> true
                }
            }
            .sorted()
            .toList()

        return JSONArray(wanted).toString()
    }

    @JavascriptInterface
    fun getPackagesInfo(packageNamesJson: String): String {
        val pm = webView.context.packageManager
        val names = runCatching { JSONArray(packageNamesJson) }.getOrElse { JSONArray() }
        val out = JSONArray()

        for (i in 0 until names.length()) {
            val pkgName = names.optString(i)
            if (pkgName.isNullOrEmpty()) continue

            try {
                val pkg = getPackageInfoCompat(pm, pkgName)
                val app = pkg.applicationInfo ?: throw IllegalStateException("no applicationInfo")

                val obj = JSONObject()
                obj.put("packageName", pkg.packageName)
                obj.put("versionName", pkg.versionName ?: "")
                obj.put("versionCode", PackageInfoCompat.getLongVersionCode(pkg))
                obj.put("appLabel", runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkgName))
                obj.put("isSystem", (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                obj.put("uid", app.uid)
                out.put(obj)
            } catch (_: Exception) {
                val obj = JSONObject()
                obj.put("packageName", pkgName)
                obj.put("error", "Package not found or inaccessible")
                out.put(obj)
            }
        }

        return out.toString()
    }

    @Suppress("DEPRECATION")
    private fun getPackageInfoCompat(pm: PackageManager, pkg: String) =
        if (BuildCompat.isTiramisuOrAbove()) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(pkg, 0)
        }

    @JavascriptInterface
    fun exit() {
        state.requestExit()
    }
}

private object BuildCompat {
    fun isTiramisuOrAbove(): Boolean = android.os.Build.VERSION.SDK_INT >= 33
}

fun hideSystemUI(window: Window) {
    WindowInsetsControllerCompat(window, window.decorView).let { controller ->
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

fun showSystemUI(window: Window) {
    WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
}
