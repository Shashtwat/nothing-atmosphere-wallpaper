package io.github.shashtwat.atmosphere

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.shashtwat.atmosphere.engine.AtmosphereRenderer
import io.github.shashtwat.atmosphere.engine.SourceLoader
import io.github.shashtwat.atmosphere.ui.NButton
import io.github.shashtwat.atmosphere.ui.NothingTheme
import io.github.shashtwat.atmosphere.ui.NtFooter
import io.github.shashtwat.atmosphere.ui.NtGroup
import io.github.shashtwat.atmosphere.ui.NtHeader
import io.github.shashtwat.atmosphere.ui.NtItem
import io.github.shashtwat.atmosphere.ui.NtSwitch
import io.github.shashtwat.atmosphere.ui.NtTopBar
import io.github.shashtwat.atmosphere.ui.nt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The app's one screen (launcher icon, or the wallpaper's settings in the system picker):
 * a live preview of the unlock morph, choose a photo, apply.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NothingTheme {
                Box(Modifier.fillMaxSize().background(nt.surfacePrimary)) { WallpaperScreen(::applyWallpaper) }
            }
        }
    }

    private fun applyWallpaper() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, AtmosphereWallpaperService::class.java))
        runCatching { startActivity(intent) }.onFailure { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
    }
}

@Composable
private fun WallpaperScreen(onApply: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AtmosphereSettings.prefs(context) }
    var busy by remember { mutableStateOf(false) }
    // A photo picked here is a draft: only the preview shows it until Apply.
    remember { ImageStore.clearDraft(context); true }
    val applied = remember { prefs.getString("picked_uri", null)?.let(android.net.Uri::parse) }
    var draft by remember { mutableStateOf<android.net.Uri?>(null) }
    var previewKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun select(uri: android.net.Uri) {
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.Default) { runCatching { ImageStore.save(context, uri, draft = true) }.isSuccess }
            busy = false
            if (ok) { draft = uri; previewKey++ }
            else Toast.makeText(context, "Couldn't open that image", Toast.LENGTH_SHORT).show()
        }
    }
    fun apply() {
        busy = true
        scope.launch {
            val committed = withContext(Dispatchers.Default) { ImageStore.commitDraft(context) }
            busy = false
            if (committed) {
                draft?.let { prefs.edit().putString("picked_uri", it.toString()).apply() }
                AtmosphereSettings(System.currentTimeMillis()).save(prefs, context)
            }
            onApply()
        }
    }
    // Any single image from storage (system file picker).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) select(uri)
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
            NtTopBar("Atmosphere")
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PreviewPhone(previewKey)
                if (busy) CircularProgressIndicator(color = nt.iconPrimary)
            }
            NtFooter("Tap an image to preview it, then Apply. Choose \"Home screen and lock screen\".")
            WallpaperGrid(draft ?: applied, enabled = !busy) { select(it) }
            // Room for the bottom bar.
            Spacer(Modifier.navigationBarsPadding().height(104.dp))
        }
        // Always within reach, however far down the grid you are.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(nt.surfacePrimary)
                .navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NButton("Choose image", { picker.launch(arrayOf("image/*")) }, Modifier.weight(1f), primary = false, enabled = !busy)
            NButton("Apply", ::apply, Modifier.weight(1f), enabled = !busy)
        }
    }
}

/** Live preview running the wallpaper renderer, looping the unlock morph. */
@Composable
private fun PreviewPhone(reloadKey: Int) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { object { lateinit var view: GLSurfaceView; lateinit var renderer: AtmosphereRenderer; lateinit var loader: SourceLoader } }
    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3); setEGLConfigChooser(8, 8, 8, 0, 0, 0); preserveEGLContextOnPause = true
                holder.view = this
                holder.loader = SourceLoader(ctx.applicationContext, draft = true) { holder.renderer }
                holder.renderer = AtmosphereRenderer(ctx.applicationContext, { requestRender() }, { holder.loader.reload() })
                setRenderer(holder.renderer); renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                // The preview plays the unlock on repeat: photo, morph, settled effect, again.
                holder.renderer.setLocked(false, animate = false)
                holder.loader.start(); holder.renderer.startMorphLoop()
            }
        },
        modifier = Modifier.width(220.dp).aspectRatio(9f / 19.5f).clip(RoundedCornerShape(28.dp)).background(Color.Black),
    )
    // A new draft: reload the preview (the morph loop restarts with it).
    LaunchedEffect(reloadKey) { if (reloadKey > 0) holder.loader.reload() }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> { holder.view.onResume(); holder.renderer.startMorphLoop() }
                Lifecycle.Event.ON_PAUSE -> holder.view.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); holder.loader.close() }
    }
}
