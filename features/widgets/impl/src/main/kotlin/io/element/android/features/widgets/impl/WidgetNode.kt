/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.widgets.impl

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Parcelable
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.social.api.HavenAppItem
import io.element.android.features.social.api.HavenAppsService
import io.element.android.features.widgets.api.WidgetsEntryPoint
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.architecture.inputs
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.DropdownMenu
import io.element.android.libraries.designsystem.theme.components.DropdownMenuItem
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.widget.MatrixWidgetDriver
import io.element.android.libraries.matrix.api.widget.MatrixWidgetSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import timber.log.Timber
import java.util.Locale

private sealed interface WidgetLoadState {
    data object Loading : WidgetLoadState
    data object NotJoined : WidgetLoadState
    data object Removed : WidgetLoadState
    data class NeedsConsent(val widget: RoomWidget) : WidgetLoadState
    data class Ready(val widget: RoomWidget, val room: JoinedRoom, val driver: MatrixWidgetDriver, val url: String) : WidgetLoadState
    data class Failed(val message: String) : WidgetLoadState
}

/** A room widget, running through the Matrix widget API (like Haven Desktop's widget app page). Can go full screen. */
@ContributesNode(SessionScope::class)
@AssistedInject
class WidgetNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val matrixClient: MatrixClient,
    private val roomWidgets: RoomWidgets,
    private val appsService: HavenAppsService,
) : Node(buildContext, plugins = plugins) {
    @Parcelize
    data class Inputs(val roomId: RoomId, val widgetId: String) : NodeInputs, Parcelable

    private val inputs = inputs<Inputs>()
    private val callback: WidgetsEntryPoint.Callback = callback()

    @Composable
    override fun View(modifier: Modifier) {
        val scope = rememberCoroutineScope()
        var consentGiven by remember { mutableStateOf(false) }
        var pendingCapabilities by remember { mutableStateOf<Pair<List<String>, CompletableDeferred<Boolean>>?>(null) }
        var fullscreen by remember { mutableStateOf(false) }
        // The app's own theme (not the system's), like Haven Desktop's $org.matrix.msc2873.client_theme.
        val clientTheme = if (ElementTheme.isLightTheme) "light" else "dark"
        val state by produceState<WidgetLoadState>(WidgetLoadState.Loading, consentGiven, clientTheme) {
            val widget = roomWidgets.get(inputs.roomId, inputs.widgetId)
            if (widget == null) {
                value = WidgetLoadState.Removed
                return@produceState
            }
            val room = matrixClient.getJoinedRoom(inputs.roomId)
            if (room == null) {
                value = WidgetLoadState.NotJoined
                return@produceState
            }
            if (!consentGiven && !roomWidgets.isLoadAllowed(widget)) {
                room.destroy()
                value = WidgetLoadState.NeedsConsent(widget)
                return@produceState
            }
            val settings = MatrixWidgetSettings(
                id = widget.widgetId,
                initAfterContentLoad = !widget.waitForIframeLoad,
                rawUrl = substituteDataTemplates(widget, clientTheme),
            )
            val url = room.generateWidgetWebViewUrl(
                widgetSettings = settings,
                clientId = CLIENT_ID,
                languageTag = Locale.getDefault().toLanguageTag(),
                theme = clientTheme,
            ).getOrElse {
                room.destroy()
                value = WidgetLoadState.Failed(it.message ?: it.toString())
                return@produceState
            }
            val driver = room.havenGetWidgetDriver(settings) { descriptions ->
                val answer = CompletableDeferred<Boolean>()
                pendingCapabilities = descriptions to answer
                answer.await()
            }.getOrElse {
                room.destroy()
                value = WidgetLoadState.Failed(it.message ?: it.toString())
                return@produceState
            }
            value = WidgetLoadState.Ready(widget, room, driver, url)
        }
        val ready = state as? WidgetLoadState.Ready
        DisposableEffect(ready) {
            onDispose {
                ready?.let {
                    runCatching { it.driver.close() }
                    it.room.destroy()
                }
            }
        }
        ImmersiveMode(enabled = fullscreen)
        BackHandler(enabled = fullscreen) { fullscreen = false }
        val widgetName = when (val s = state) {
            is WidgetLoadState.Ready -> s.widget.name
            is WidgetLoadState.NeedsConsent -> s.widget.name
            else -> stringResource(R.string.widgets_widget)
        }
        Scaffold(
            modifier = modifier,
            topBar = {
                if (!fullscreen) {
                    TopAppBar(
                        titleStr = widgetName,
                        navigationIcon = { BackButton(onClick = callback::onDone) },
                        actions = {
                            IconButton(onClick = { fullscreen = true }) {
                                Icon(CompoundIcons.Expand(), contentDescription = stringResource(R.string.widgets_fullscreen))
                            }
                            WidgetMenu(appsService = appsService, onGoToRoom = { callback.navigateToRoom(inputs.roomId) }, onDone = callback::onDone)
                        },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (val s = state) {
                    WidgetLoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    WidgetLoadState.NotJoined -> Message(stringResource(R.string.widgets_not_joined))
                    WidgetLoadState.Removed -> Message(stringResource(R.string.widgets_removed))
                    is WidgetLoadState.Failed -> Message(s.message)
                    is WidgetLoadState.NeedsConsent -> Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.widgets_consent, s.widget.sender, hostOf(s.widget.url)),
                            textAlign = TextAlign.Center,
                            color = ElementTheme.colors.textPrimary,
                        )
                        Button(text = stringResource(R.string.widgets_continue), onClick = {
                            scope.launch {
                                roomWidgets.allowLoad(s.widget)
                                consentGiven = true
                            }
                        })
                    }
                    is WidgetLoadState.Ready -> WidgetWebView(s)
                }
                if (fullscreen) {
                    IconButton(
                        onClick = { fullscreen = false },
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    ) {
                        Icon(CompoundIcons.Collapse(), contentDescription = stringResource(R.string.widgets_exit_fullscreen))
                    }
                }
            }
        }
        pendingCapabilities?.let { (descriptions, answer) ->
            ConfirmationDialog(
                title = stringResource(R.string.widgets_capabilities_title),
                content = stringResource(R.string.widgets_capabilities_content) + "\n\n" + descriptions.joinToString("\n") { "• $it" },
                submitText = stringResource(R.string.widgets_allow),
                cancelText = stringResource(R.string.widgets_deny),
                onSubmitClick = {
                    pendingCapabilities = null
                    answer.complete(true)
                },
                onDismiss = {
                    pendingCapabilities = null
                    answer.complete(false)
                },
            )
        }
    }

    @Composable
    private fun WidgetMenu(appsService: HavenAppsService, onGoToRoom: () -> Unit, onDone: () -> Unit) {
        val scope = rememberCoroutineScope()
        var expanded by remember { mutableStateOf(false) }
        val widgetApps by appsService.widgetApps.collectAsState()
        val appId = HavenAppItem.widgetAppId(inputs.roomId.value, inputs.widgetId)
        val isApp = widgetApps.any { it.id == appId }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(CompoundIcons.OverflowVertical(), contentDescription = stringResource(R.string.widgets_more))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (!appsService.appPreferences.collectAsState().value.disabled) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (isApp) R.string.widgets_remove_from_apps else R.string.widgets_add_to_apps)) },
                        onClick = {
                            expanded = false
                            scope.launch {
                                if (isApp) appsService.removeWidgetApp(appId) else appsService.addWidgetApp(inputs.roomId.value, inputs.widgetId)
                            }
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.widgets_go_to_room)) },
                    onClick = {
                        expanded = false
                        onGoToRoom()
                    },
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun WidgetWebView(ready: WidgetLoadState.Ready) {
        // A WebView reports `prefers-color-scheme` from its context's theme (isLightTheme), and Element X's Android theme
        // is light-based (Compose does the real theming): give the WebView a theme matching what the app shows.
        val isLight = ElementTheme.isLightTheme
        // Messages from the widget (fromWidget requests, toWidget responses), relayed to the SDK's widget driver.
        val fromWidget = remember { MutableSharedFlow<String>(extraBufferCapacity = 256) }
        var webView by remember { mutableStateOf<WebView?>(null) }
        LaunchedEffect(ready.driver) {
            // Off the main thread: widgets can send many messages, and each one is a call into the Rust SDK.
            launch(Dispatchers.Default) {
                ready.driver.incomingMessages.collect { message ->
                    withContext(Dispatchers.Main) {
                        webView?.evaluateJavascript("window.havenToWidget($message)", null)
                    }
                }
            }
            launch(Dispatchers.Default) {
                fromWidget.collect { ready.driver.send(it) }
            }
            launch(Dispatchers.Default) { ready.driver.run() }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val themedContext = android.view.ContextThemeWrapper(
                    context,
                    if (isLight) android.R.style.Theme_DeviceDefault_Light_NoActionBar else android.R.style.Theme_DeviceDefault_NoActionBar,
                )
                WebView(themedContext).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // Third-party content: no file or content access.
                        allowFileAccess = false
                        allowContentAccess = false
                        mediaPlaybackRequiresUserGesture = false
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) {
                            // First pass: no camera/microphone for widgets.
                            request.deny()
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            Timber.w("Widget load error: ${error?.errorCode}")
                            super.onReceivedError(view, request, error)
                        }
                    }
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun postMessage(json: String?) {
                                json?.let { fromWidget.tryEmit(it) }
                            }
                        },
                        LISTENER,
                    )
                    // Like Element/Haven Desktop, the widget runs in an iframe of a client page, so window.parent is the
                    // client: the page relays the widget API messages between the iframe and the SDK's widget driver.
                    loadDataWithBaseURL(HOST_PAGE_URL, hostPage(widgetUrl(ready)), "text/html", "utf-8", null)
                    webView = this
                }
            },
            onRelease = { it.destroy() },
        )
    }

    /** The widget URL with the `widgetId` and `parentUrl` query parameters Element adds (matrix-widget-api reads them). */
    private fun widgetUrl(ready: WidgetLoadState.Ready): String {
        val uri = android.net.Uri.parse(ready.url)
        val builder = uri.buildUpon()
        if (uri.getQueryParameter("widgetId") == null) builder.appendQueryParameter("widgetId", ready.widget.widgetId)
        if (uri.getQueryParameter("parentUrl") == null) builder.appendQueryParameter("parentUrl", HOST_PAGE_URL)
        return builder.build().toString()
    }

    private fun hostPage(widgetUrl: String): String {
        val src = widgetUrl.replace("&", "&amp;").replace("\"", "&quot;")
        return """
            <!doctype html>
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html,body{margin:0;padding:0;height:100%;overflow:hidden;background:transparent}
            iframe{border:0;width:100%;height:100%;display:block}</style></head>
            <body>
            <iframe id="widget" src="$src"
                sandbox="allow-forms allow-popups allow-popups-to-escape-sandbox allow-same-origin allow-scripts allow-presentation allow-downloads"
                allow="encrypted-media; autoplay; clipboard-write; clipboard-read; fullscreen" allowfullscreen></iframe>
            <script>
                var frame = document.getElementById('widget');
                window.addEventListener('message', function(event) {
                    if (event.source !== frame.contentWindow) return;
                    var d = event.data;
                    if (d && typeof d === 'object' && (d.api === 'fromWidget' || d.api === 'toWidget')) {
                        $LISTENER.postMessage(JSON.stringify(d));
                    }
                });
                window.havenToWidget = function(message) {
                    frame.contentWindow.postMessage(message, '*');
                };
            </script>
            </body></html>
        """.trimIndent()
    }

    @Composable
    private fun Message(text: String) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(text, textAlign = TextAlign.Center, color = ElementTheme.colors.textSecondary)
        }
    }

    private companion object {
        const val LISTENER = "havenWidget"
        const val CLIENT_ID = "software.haven.android"

        /** Origin of the page hosting the widget iframe (never fetched: the page is loaded from a string). */
        const val HOST_PAGE_URL = "https://widgets.haven-android.invalid/"
    }
}

/**
 * Fills `$key` placeholders from the widget's `data` (matrix-widget-api templating), and Element's legacy `$theme`
 * (used e.g. by matrix-appservice-nostr's games hub); the SDK fills the `$matrix_*` and `$org.matrix.msc2873.*` ones.
 */
private fun substituteDataTemplates(widget: RoomWidget, theme: String): String {
    var url = widget.url.replace(Regex("\\\$theme(?![A-Za-z0-9_.])"), theme)
    widget.data.keys().forEach { key ->
        val value = widget.data.opt(key)
        if (value is String || value is Number || value is Boolean) {
            url = url.replace("\$$key", android.net.Uri.encode(value.toString()))
        }
    }
    return url
}

private fun hostOf(url: String) = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: url

/** Hides the system bars while [enabled] (widget full screen). */
@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(enabled) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
