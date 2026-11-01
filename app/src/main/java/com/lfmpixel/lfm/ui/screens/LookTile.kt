package com.lfmpixel.lfm.ui.screens

import android.graphics.Bitmap
import com.lfmpixel.lfm.ui.theme.CameraColors
import com.lfmpixel.lfm.ui.theme.LfmShape
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.see.Look
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Look, in Talk: the camera's view in a tile, and what the server's VL model sees in it, one line under it. Frames
 * come from CameraX's analysis stream at about 640×480 (never a full photo), at most [FPS] a second, shrunk to
 * [Look.SIDE] and handed to [Look], which sends only changed views, one at a time. A tap looks again now.
 */
@Composable
fun LookTile(g: AppGraph, showSpeed: Boolean, modifier: Modifier = Modifier) {
    val c = Lfm.c
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val scene by g.look.scene.collectAsState()
    val stats by g.look.stats.collectAsState()
    val busy by g.look.busy.collectAsState()
    val now = remember { AtomicBoolean(true) }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val analysis = remember {
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()).build()
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    LaunchedEffect(Unit) {
        val provider = runCatching { ProcessCameraProvider.awaitInstance(ctx) }.getOrNull() ?: return@LaunchedEffect
        // hasCamera throws while the camera service restarts or another app holds it.
        val selector = if (runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(true)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        view.display?.rotation?.let { analysis.targetRotation = it }
        g.look.start()
        var last = 0L
        analysis.setAnalyzer(executor) { img ->
            img.use {
                val t = System.currentTimeMillis()
                if (t - last < 1000L / FPS) return@use
                last = t
                // The camera's own thread has no handler: a frame that fails to shrink is skipped.
                runCatching { small(it) }.onSuccess { b -> g.look.offer(b, now.getAndSet(false)) }
            }
        }
        runCatching { provider.unbindAll(); provider.bindToLifecycle(owner, selector, preview, analysis) }
    }
    DisposableEffect(Unit) {
        onDispose {
            analysis.clearAnalyzer()
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
            g.look.stop()
            executor.shutdown()
        }
    }
    val shape = LfmShape.card
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(190.dp).clip(shape).background(CameraColors.ground)
            .clickable(role = Role.Button, onClickLabel = "Look again") { now.set(true) }
            .semantics { contentDescription = "Camera, Look is on" }) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            if (busy) Txt("Looking…", LfmType.meta, CameraColors.ink, Modifier.align(Alignment.TopEnd).padding(8.dp)
                .background(CameraColors.glass, LfmShape.tag).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Txt(scene?.text ?: "Looking at what the camera sees…", LfmType.small, c.text2,
            Modifier.padding(top = 8.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, maxLines = 3)
        if (showSpeed && stats.sent > 0) Txt("Look · %d KB · %d ms · %d sent, %d dropped, %d unchanged".format(java.util.Locale.ENGLISH,
            stats.kb, stats.lastMs, stats.sent, stats.dropped, stats.unchanged), LfmType.meta, c.text3, Modifier.padding(top = 2.dp).fillMaxWidth())
    }
}

/** At most this many frames a second are looked at; most are dropped as unchanged before any encoding. */
private const val FPS = 4

/** The analysis frame upright and shrunk to [Look.SIDE] in one step. */
private fun small(img: androidx.camera.core.ImageProxy): Bitmap {
    val b = img.toBitmap()
    val deg = img.imageInfo.rotationDegrees
    val scale = minOf(1f, Look.SIDE.toFloat() / maxOf(b.width, b.height))
    if (deg == 0 && scale == 1f) return b
    val m = Matrix().apply { postScale(scale, scale); postRotate(deg.toFloat()) }
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { if (it != b) b.recycle() }
}
