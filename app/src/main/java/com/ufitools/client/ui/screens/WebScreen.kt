package com.ufitools.client.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.google.gson.Gson
import com.ufitools.client.data.WebAutoLogin
import com.ufitools.client.data.WebTheme
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel

/**
 * 注入到网页版的额外样式规则（**必须带 `!important`**）。
 *
 * 用 `<style>` 标签而不是内联样式，是为了压过网页版自己写的内联样式 ——
 * `!important` 的样式表规则优先级高于普通内联样式。
 *
 * ## 两条规则的作用
 *
 * 1. `#BG_OVERLAY{background-color:transparent}` —— 网页版 `theme.js` 的
 *    `updateColor()` 会把这块覆盖层染成 `rgba(主题色, opacityPer)` 盖在 body 上，
 *    叠加后背景就不再等于 App 顶栏色了。置透明让 body 底色成为真正的背景。
 *
 * 2. `div.footer{display:none}` —— 隐藏网页底部的作者署名行
 *    （`Made by 星月 with love ❤️ 打赏作者`）。
 *    该元素结构实测为 `body#BG > div.footer > p`，`div.footer` 是它的唯一容器。
 *    项目采用 MIT 许可，**并未要求界面保留署名**（MIT 只要求在软件副本中附带
 *    LICENSE 本身的版权与许可声明），所以隐藏它不涉及许可问题。
 *    隐藏后「打赏作者」的弹窗入口（`onclick=showModal('#payModal')`）也随之消失。
 */
private const val EXTRA_CSS =
    "#BG_OVERLAY{background-color:transparent !important}" +
        "div.footer{display:none !important}"

/**
 * 生成写入网页版的注入脚本：**登录态 + 配色 + 界面微调**。
 *
 * ## 登录态
 *
 * 网页版从 localStorage 的 `kano_sms_pwd` / `kano_sms_token` 判定登录
 * （见 [WebAutoLogin]），写这两个键即可免登。
 *
 * ## 配色
 *
 * 分两层（见 [WebTheme]）：
 * 1. **色调**（色相/饱和度）写 localStorage，交给网页版的 `theme.js` 自己去算
 * 2. **背景与文字**除了写 localStorage，还要**额外用内联样式覆盖 CSS 变量** ——
 *    因为 `--dark-bg-color` 不在 theme.js 管辖的 8 个变量内，光写 localStorage 没用
 *
 * ## 返回值语义（供调用方决定是否需要 reload）
 *
 * - `"same"`    —— 所有键与现状一致，无需重载
 * - `"changed"` —— 有键被改写，**必须 reload** 才能让 theme.js / initRequestData 重新读取
 * - `"error:…"` —— 写入失败（如 localStorage 被禁用），返回原因便于排查
 *
 * ⚠️ 口令经 `Gson.toJson` 转成合法的 JS 字符串字面量再拼进去：
 * 用户口令里若含引号、反斜杠或换行，裸拼会拼出语法错误的脚本。
 *
 * @param login 登录凭据；null 表示本次不写登录态（已写过、或要尊重用户的登出）
 * @param theme 配色参数
 */
private fun buildInjectJs(login: WebAutoLogin?, theme: WebTheme): String {
    val gson = Gson()
    val sets = StringBuilder()
    if (login != null) {
        sets.append("            set('kano_sms_pwd', ").append(gson.toJson(login.password)).append(");\n")
        sets.append("            set('kano_sms_token', ").append(gson.toJson(login.tokenHash)).append(");\n")
    }
    // 关闭网页版的「多设备主题同步」（用户要求）。
    //
    // 网页版 `theme.js` 的 `initTheme()` 开头有这么一段：
    // ```js
    // const isSync = localStorage.getItem("isCloudSync", isCloudSync.checked)
    // if (isSync == true || isSync == "true" || sync) {
    //     result = await (await fetch('/api/get_theme')).json()
    //     Object.keys(result).forEach(key => localStorage.setItem(key, result[key]))  // ← 覆盖一切
    // }
    // ```
    // 也就是**只要开着云同步，它就会在页面加载时用云端主题覆盖 localStorage 里的全部主题键**，
    // 把 App 刚注入的配色冲掉 —— 实测设备上该键就是 `true`。
    // 关闭后网页主题完全由 App 决定，不会再被云端串扰。
    sets.append("            set('isCloudSync', 'false');\n")

    sets.append("            set('textColor', ").append(gson.toJson(theme.textColorCss)).append(");\n")
    sets.append("            set('textColorPer', ").append(theme.textColorPer).append(");\n")
    // 色调为 null 表示该主题（如「默认」的纯灰强调色）没有色调可同步，跳过让网页版保持原样
    if (theme.hue300 != null && theme.colorPer != null && theme.saturationPer != null) {
        sets.append("            set('themeColor', ").append(theme.hue300).append(");\n")
        sets.append("            set('colorPer', ").append(theme.colorPer).append(");\n")
        sets.append("            set('saturationPer', ").append(theme.saturationPer).append(");\n")
    }

    // ── 覆盖 body 底色 + 注入界面微调样式 ──
    //
    // 1) `--dark-bg-color` **不在 theme.js 管辖范围内**（写死在 style.css 的 :root），
    //    而 `body { background-color: var(--dark-bg-color) }` —— 页面底色正是用它。
    //    所以在 documentElement 上打内联样式覆盖（内联优先级高于 :root 的样式表）。
    //
    // 2) theme.js 的 `updateColor()` 会把 `#BG_OVERLAY` 盖在 body 之上，
    //    叠加后背景就不再等于目标色；网页底部还有一行作者署名。
    //    这两件事都放进 [EXTRA_CSS]，用**带 `!important` 的 `<style>` 规则**处理
    //    （`!important` 的样式表规则优先级高于 theme.js 写的内联样式，
    //    否则它每次 updateColor 都会把背景覆盖层盖回来）。
    //
    // 这段不写 localStorage、不受 changed 判断影响：内联样式不持久化，
    // 每次加载都必须重设，所以 reload 后的那次注入同样会执行。
    val cssFix = """
            var root = document.documentElement;
            if (root && root.style) {
              root.style.setProperty('--dark-bg-color', ${gson.toJson(theme.bodyBgCss)});
              root.style.setProperty('--dark-bg-color-transparent', ${gson.toJson(theme.bodyBgTransparentCss)});
            }
            var head = document.head || root;
            if (head) {
              var st = document.getElementById('kano-app-style-fix');
              if (!st) {
                st = document.createElement('style');
                st.id = 'kano-app-style-fix';
                head.appendChild(st);
              }
              // 每次重设 textContent（幂等），这样将来改规则不需要清缓存
              st.textContent = ${gson.toJson(EXTRA_CSS)};
            }
    """.trimIndent()

    val template = """
        (function () {
          try {
            var changed = false;
            // 只在值真的不同时才写，并据此判断要不要重载（避免每次进页面都闪一下）
            var set = function (k, v) {
              if (localStorage.getItem(k) !== String(v)) {
                localStorage.setItem(k, String(v));
                changed = true;
              }
            };
__SET__
__CSSFIX__
            return changed ? 'changed' : 'same';
          } catch (e) {
            return 'error:' + e;
          }
        })();
    """.trimIndent()

    return template
        .replace("__SET__", sets.toString().trimEnd())
        .replace("__CSSFIX__", cssFix)
}

/**
 * 打开设备网页版「软件更新」的注入脚本。
 *
 * 网页版的更新入口是 `#OTA` 按钮（`initUpdateSoftware()` 给它绑了
 * `onclick → checkUpdateAction()`，后者会打开 `#updateSoftwareModal` 并开始检查）。
 * 而 `checkUpdateAction` 是模块作用域的 `const`，**不是 window 全局**，
 * App 无法直接调用，只能模拟点击 `#OTA`。
 *
 * ⚠️ 必须**带重试**：注入可能发生在页面脚本跑完之前（`onPageStarted`），
 * 那时 `#OTA` 的 onclick 还没绑、登录态也可能没就绪；这里每 250ms 轮询一次、
 * 最多 24 次（约 6 秒），等按钮就绪再点。未登录时 `#OTA` 只会弹「请先登录」，
 * 所以判定里同时要求两个登录键都在。
 */
private fun buildSoftwareUpdateJs(): String = """
    (function () {
      var tries = 0;
      (function attempt() {
        var pwd = localStorage.getItem('kano_sms_pwd');
        var tok = localStorage.getItem('kano_sms_token');
        var b = document.querySelector('#OTA');
        if (pwd && tok && b) { b.click(); return; }
        if (++tries < 24) setTimeout(attempt, 250);
      })();
    })();
""".trimIndent()

/**
 * 设备「网页版 UFI-TOOLS」的内嵌浏览器页面。
 *
 * ## 为什么内嵌而不是跳系统浏览器
 *
 * 网页版与 App 是**同一个服务**（`ufi-tools-u60pro` 同时监听 `:2333`，
 * 既给 `/api/` 也给网页），用户从仪表盘点型号进来，期望的是"设备的一个界面"，
 * 而不是"跳到浏览器、再自己输地址登录一遍"。内嵌能保持 App 内的连贯体验：
 * 返回键回到仪表盘、登录态与 App 同域共享。
 *
 * ## ⚠️ 不能放进 [com.ufitools.client.ui.components.SubPageScaffold]
 *
 * 那个壳的内容区是 `verticalScroll`，测量子元素时传下来的是
 * **`Constraints.Infinity`（无限高）** —— 与本项目「设备高级设置」页
 * `OutlinedTextField` 崩溃是同一个成因。WebView 需要**有界高度**，
 * 所以这里自己实现顶栏 + `Box(weight(1f))` 撑满剩余空间。
 *
 * ## WebSettings 里几个必须项
 *
 * - [WebSettings.setDomStorageEnabled] —— 网页版的登录态、插件台账都放在
 *   localStorage（官方文档也记过"已安装插件存在 localStorage 台账"），
 *   不开的话每次进来都要重新登录。
 * - [WebSettings.setJavaScriptEnabled] —— 网页端是纯前端应用，不开就是白屏。
 * - [WebSettings.setMixedContentMode] —— 设备是 http，但页面里可能引用 https 资源；
 *   默认的 `MIXED_CONTENT_NEVER_ALLOW` 会把它们拦掉，用兼容模式放行。
 * - [CookieManager.setAcceptCookie] —— 网页端的会话 Cookie，与上面同理。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(
    vm: MainViewModel,
    // 本页**不再用 nav**：顶栏去掉后，退出改由系统返回键（见下方 BackHandler）
    // 与底栏的「仪表盘」tab 完成。保留该参数是为了与其它二级页的签名一致，
    // 将来若要在页内做跳转可直接使用。
    @Suppress("UNUSED_PARAMETER") nav: NavHostController
) {
    val url = vm.webUiUrl()

    /** 加载进度（0~100）。只在 1~99 之间显示进度条，加载完立刻收起来 */
    var progress by remember { mutableIntStateOf(0) }
    /** 加载失败的原因；null 表示正常 */
    var errorMsg by remember { mutableStateOf<String?>(null) }
    /** 当前 WebView 实例：刷新按钮、返回键、进度都要用到 */
    var webView by remember { mutableStateOf<WebView?>(null) }
    /** WebView 内部是否还有可后退的历史（决定返回键交给谁） */
    var canGoBack by remember { mutableStateOf(false) }

    // ── 网页里的文件上传（网页版装插件要传 .txt） ──
    //
    // WebChromeClient.onShowFileChooser 是同步回调，只能先把 callback 存起来，
    // 等用户在系统文件选择器里选完（异步）再回传结果。
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@rememberLauncherForActivityResult
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        // ⚠️ 取消时也必须回传（传 null），否则网页的 <input type=file> 会永远卡住
        callback.onReceiveValue(uris)
        filePathCallback = null
    }

    // 返回键：**不画顶栏之后它就承担了"退出本页"的职责**。
    //
    // `enabled = canGoBack` 这个条件是有意保留的：
    // - 正常情况下网页版不做页面跳转（`history.length == 1`，功能都是模态框），
    //   `canGoBack` 恒为 false → 这里不拦截 → 系统默认把当前导航目的地弹出 → **回到仪表盘** ✓
    // - 万一将来网页里真的发生了跳转（点了外链等），则先退网页，
    //   退到头再按才回仪表盘 —— 避免"一按就直接丢掉网页里的浏览位置"。
    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    /**
     * 把登录态与主题配色注入网页版，并在需要时重载。
     *
     * ## 为什么是「注入 + 重载」而不是直接注入
     *
     * 页面自身的脚本在 `onPageFinished` 触发时**已经跑完了**：此刻
     * `initRequestData()` 与 `initTheme()` 早读过一次 localStorage，
     * 读到的还是旧值，界面已按旧配置渲染。只写 localStorage 不重载，
     * 用户看到的仍是登录框 / 旧配色。
     * 所以写入后判断"值是否真的变了"，变了才重载一次让页面重新初始化。
     *
     * ## 登录只在首次、主题每次都同步
     *
     * - **主题**：每次进页面都对齐 App（用户可能刚在 App 里换了配色），
     *   但值没变时不会重载，所以只有真的换过主题才会闪一下。
     * - **登录**：只用 [MainViewModel.webAutoLoginDone]（跨页面实例保持）控制，
     *   首次进入才写。因为用户点网页版的「登出」会清掉 localStorage，
     *   若每次进页面都重新注入，就成了"登出无效、一进来又被登回去"。
     */
    val tryInject: (WebView) -> Unit = { view ->
        // ⚠️ 每次都重新取（而不是捕获外层的 remember 值）：WebViewClient 是
        // AndroidView 的 factory 里创建的一次性对象，factory 不会随重组重跑，
        // 捕获外层变量会一直用首次的旧值（用户中途改了配色或口令也不生效）。
        val login = if (vm.webAutoLoginDone) null else vm.webAutoLogin()
        view.evaluateJavascript(buildInjectJs(login, vm.webTheme())) { result ->
            // 只在成功写入后才记「已尝试登录」；出错（如 localStorage 被禁用）
            // 不记账，给下次进入留重试机会
            val failed = result == null || result.contains("error")
            if (!failed) vm.webAutoLoginDone = true
            // 只有确实改了 localStorage 才重载；已是同值就不必闪一下
            if (result != null && result.contains("changed")) {
                view.reload()
            } else if (!failed) {
                // 注入完成且本帧无需 reload：登录态已就绪、页面脚本已跑完，
                // 这时才执行待办动作 —— 若放在 changed/reload 那帧，动作会被重载丢掉
                if (vm.webAutoAction != null) {
                    vm.webAutoAction = null
                    view.evaluateJavascript(buildSoftwareUpdateJs(), null)
                }
            }
        }
    }

    // ── 无顶栏布局 ──
    //
    // 刻意**不画顶栏**（用户要求）：返回交给「系统返回键」与底栏的「仪表盘」tab，
    // 页面因此能占满整个内容区、也不再有水平内边距，观感更接近真正的内嵌浏览器。
    //
    // 这样做是安全的 —— 实测网页版**所有功能都以模态框实现**，不做页面跳转：
    // `window.history.length == 1`、DOM 里有 66 个 `.mask/.modal` 元素。
    // 所以 `WebView.canGoBack` 恒为 false，返回键必然落到"回仪表盘"，
    // 不存在"网页内部还有上一页、却被顶栏的返回按钮跳过"的问题。
    Column(
        Modifier.fillMaxSize()
    ) {
        // 进度条：无顶栏后它是唯一的加载反馈，贴在最顶端
        // （2dp 的细线，几乎不占空间）
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = AppTheme.accent,
                trackColor = AppTheme.textPrimary.copy(alpha = 0.08f)
            )
        }

        // ── WebView 主体 ──
        //
        // ⚠️ `weight(1f)` 是关键：它让 Box 拿到**有界高度**，
        // 这样传给 WebView 的约束才是有限值。放进 verticalScroll 会拿到 Infinity。
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            // 网页版是纯前端应用，不开 JS 就是白屏
                            javaScriptEnabled = true
                            // ⚠️ 必须开：登录态与插件台账都在 localStorage
                            domStorageEnabled = true
                            databaseEnabled = true
                            // 设备是 http，页面里可能有 https 资源；默认策略会拦掉
                            mixedContentMode =
                                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            // 网页版是移动端布局（viewport 写了 user-scalable=no），
                            // 让 WebView 按内容宽度自适应而不是 980px 桌面模式
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            // 允许缩放，长页面/图表看不清时能放大
                            builtInZoomControls = true
                            displayZoomControls = false
                            // 缓存：设备端页面不常变，优先用缓存能显著加快二次进入
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }
                        CookieManager.getInstance().setAcceptCookie(true)

                        // 页内跳转一律留在 WebView 里 —— 默认的 WebViewClient 会把
                        // 非同域链接甩给系统浏览器，用户就"掉出"App 了。
                        webViewClient = object : WebViewClient() {
                            /**
                             * 首帧之前就静默写入，尽量让页面**第一次**读 localStorage
                             * 就拿到登录态与配色，从而省掉 [onPageFinished] 里那次 reload。
                             *
                             * 这里不关心结果：写成功的话 onPageFinished 会读到 `"same"`、
                             * 自然跳过 reload；万一此时 WebView 还没准备好 localStorage
                             * （onPageStarted 阶段 DOM 尚未建立），onPageFinished 会兜底补上。
                             * 也就是说两条路径合起来是幂等的，不存在"漏注入"。
                             */
                            override fun onPageStarted(
                                view: WebView,
                                startedUrl: String,
                                favicon: Bitmap?
                            ) {
                                try {
                                    tryInject(view)
                                } catch (_: Exception) {
                                    // 此时注入失败是预期内的，交给 onPageFinished 兜底
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                // 只拦 http/https；其它 scheme（tel:、intent: 等）
                                // 交回系统处理，避免网页里点了个拨号链接就白屏
                                val u = request.url
                                return if (u.scheme == "http" || u.scheme == "https") {
                                    view.loadUrl(u.toString())
                                    true
                                } else {
                                    false
                                }
                            }

                            override fun onPageFinished(view: WebView, finishedUrl: String) {
                                canGoBack = view.canGoBack()
                                tryInject(view)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError
                            ) {
                                // 只关心主框架的错误：子资源（图片/JS）失败不该整页报错
                                if (request.isForMainFrame) {
                                    errorMsg = "加载失败（${error.errorCode}）：${error.description}"
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                                // 加载完成：收起进度条并清掉上一次的错误
                                if (newProgress >= 100) {
                                    errorMsg = null
                                    canGoBack = view.canGoBack()
                                }
                            }

                            // 网页版上传插件（<input type=file>）走到这里
                            override fun onShowFileChooser(
                                view: WebView,
                                callback: ValueCallback<Array<Uri>>,
                                params: FileChooserParams
                            ): Boolean {
                                filePathCallback?.onReceiveValue(null)
                                filePathCallback = callback
                                return try {
                                    fileLauncher.launch(params.createIntent())
                                    true
                                } catch (e: Exception) {
                                    filePathCallback = null
                                    Toast.makeText(
                                        ctx, "无法打开文件选择器：${e.message}", Toast.LENGTH_SHORT
                                    ).show()
                                    false
                                }
                            }
                        }

                        loadUrl(url)
                    }.also { webView = it }
                },
                // ⚠️ 必须显式销毁：WebView 持有 Activity 引用，
                // 不 destroy 的话离开页面后会泄漏整个 Activity。
                //
                // client 置空要**赋空实例而不是 null**：`setWebViewClient` /
                // `setWebChromeClient` 的 Java 参数在 Kotlin 里是**非空平台类型**，
                // 传 null 编译期就报 "Null cannot be a value of a non-null type"。
                // 赋一个默认实现同样能断开对我们（持有 Activity 引用）的回调的持有。
                onRelease = { view ->
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    view.apply {
                        stopLoading()
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
                        destroy()
                    }
                    webView = null
                }
            )

            // 首屏转圈：进度还没起步时给个反馈，避免"点了没反应"
            if (progress == 0 && errorMsg == null) {
                CircularProgressIndicator(
                    color = AppTheme.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.Center)
                )
            }

            // 错误页：给重试，不要让用户困在空白页里
            if (errorMsg != null) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        errorMsg ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.textSecondary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        url,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        errorMsg = null
                        progress = 0
                        webView?.reload()
                    }) {
                        Text("重试", color = AppTheme.accent)
                    }
                }
            }
        }
    }
}
