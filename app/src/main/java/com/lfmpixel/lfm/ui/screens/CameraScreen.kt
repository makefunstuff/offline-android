package com.lfmpixel.lfm.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.see.SeeKind
import com.lfmpixel.lfm.see.SeeRequest
import com.lfmpixel.lfm.see.SeeRules
import com.lfmpixel.lfm.see.MediaPrep
import com.lfmpixel.lfm.tools.PermissionGate
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.MicButton
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.DarkColors
import com.lfmpixel.lfm.ui.theme.LfmMotion
import com.lfmpixel.lfm.ui.theme.LfmType
import com.lfmpixel.lfm.ui.theme.LocalLfm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Opens the system photo picker (photos and videos) or the document picker (PDF, pictures); no storage permission. */
class Pickers(val media: ((Uri) -> Unit) -> Unit, val document: ((Uri) -> Unit) -> Unit,
              /** Android's "save as" for an export: a file name in, the place the owner chose out. */
              val save: (name: String, (Uri) -> Unit) -> Unit = { _, _ -> })

/** The question typed or said on the camera screen; the mic writes into it. */
class CameraQuestion { var text by mutableStateOf("") }

/**
 * Ask about this (spec screen 9): a viewfinder with Photo, Video and Document modes, a question, a shutter.
 * Always dark, as a camera is. What is shot or picked is prepared here and handed to the assistant.
 */
@Composable
fun CameraScreen(g: AppGraph, nav: Nav, gate: PermissionGate, pickers: Pickers, question: CameraQuestion, onMic: () -> Unit, listening: Boolean) {
    CompositionLocalProvider(LocalLfm provides DarkColors) {
        CameraContent(g, nav, gate, pickers, question, onMic, listening)
    }
}

@Composable
private fun CameraContent(g: AppGraph, nav: Nav, gate: PermissionGate, pickers: Pickers, question: CameraQuestion, onMic: () -> Unit, listening: Boolean) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(SeeKind.Photo) }
    var back by remember { mutableStateOf(true) }
    var flash by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var noCamera by remember { mutableStateOf(false) }
    var canFlip by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recordedMs by remember { mutableIntStateOf(0) }
    val frames = remember { mutableListOf<Bitmap>() }
    val executor = cameraExecutor
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    // About 2 MP is plenty: photos go to the model at 768 px and pages at 1280 px.
    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build()
    }
    val analysis = remember {
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
    }

    // Light status bar icons over the black camera, put back on the way out.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        val status = ctl?.isAppearanceLightStatusBars
        val navBar = ctl?.isAppearanceLightNavigationBars
        ctl?.isAppearanceLightStatusBars = false
        ctl?.isAppearanceLightNavigationBars = false
        onDispose { status?.let { ctl.isAppearanceLightStatusBars = it }; navBar?.let { ctl.isAppearanceLightNavigationBars = it } }
    }
    // A question left from an earlier visit is not asked about the next picture.
    DisposableEffect(Unit) { onDispose { question.text = "" } }
    // Allowed in Android settings and back: turn the camera on.
    LifecycleResumeEffect(Unit) {
        if (!allowed) allowed = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        onPauseOrDispose {
            // Frames stop coming while LFM is in the background: drop a clip cut short rather than send it.
            if (recording) { recording = false; analysis.clearAnalyzer(); synchronized(frames) { frames.clear() } }
        }
    }

    LaunchedEffect(Unit) {
        if (!allowed) { allowed = gate.ensure(listOf(Manifest.permission.CAMERA)); asked = true }
    }
    // Photo and Document share one use case: only a switch to or from Video rebinds.
    val video = mode == SeeKind.Video
    LaunchedEffect(allowed, back, video) {
        if (!allowed) return@LaunchedEffect
        camera = null
        val provider = runCatching { ProcessCameraProvider.awaitInstance(ctx) }.getOrNull()?.also { provider = it }
            ?: run { noCamera = true; return@LaunchedEffect }
        val selector = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        canFlip = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        if (!provider.hasCamera(selector)) {
            if (back && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) back = false else noCamera = true
            return@LaunchedEffect
        }
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        provider.unbindAll()
        camera = runCatching {
            // Video takes frames straight from the stream at 2 a second: no file, no encoder.
            provider.bindToLifecycle(owner, selector, preview, if (video) analysis else capture)
        }.getOrElse { noCamera = true; null }
        noCamera = camera == null
    }
    DisposableEffect(Unit) { onDispose { provider?.unbindAll() } }
    LaunchedEffect(flash, camera, mode) {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        capture.flashMode = if (flash && hasFlash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        if (hasFlash) camera?.cameraControl?.enableTorch(flash && mode == SeeKind.Video)
    }

    fun send(make: suspend () -> SeeRequest) {
        if (busy != null) return
        busy = if (mode == SeeKind.Document) "Getting the page ready…" else "Getting it ready…"
        error = null
        scope.launch {
            try {
                val req = make()
                g.assistant.ask(req)
                question.text = ""
                nav.back()
                nav.go(Route.Chat)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "That did not work"
            } finally {
                busy = null
            }
        }
    }
    fun asked() = question.text.trim().ifEmpty { SeeRules.defaultQuestion(mode) }

    fun stopRecording() {
        if (!recording) return
        recording = false
        analysis.clearAnalyzer()
        val shot = synchronized(frames) { frames.toList().also { frames.clear() } }
        val seconds = maxOf(1, (recordedMs + 500) / 1000)
        send { g.prep.frames(shot, seconds, asked()) }
    }

    fun shutter() {
        if (busy != null || camera == null) return
        when (mode) {
            SeeKind.Video -> if (recording) stopRecording() else {
                synchronized(frames) { frames.clear() }
                recordedMs = 0
                recording = true
                // The activity handles rotation itself, so tell CameraX how the phone is held now.
                view.display?.rotation?.let { analysis.targetRotation = it }
                var last = 0L
                val start = System.currentTimeMillis()
                analysis.setAnalyzer(executor) { img ->
                    img.use {
                        val now = System.currentTimeMillis()
                        if (now - last >= 1000L / SeeRules.VIDEO_FPS && now - start <= SeeRules.VIDEO_MAX_SECONDS * 1000L) {
                            last = now
                            val b = upright(it)
                            synchronized(frames) { frames.add(b) }
                        }
                    }
                }
                scope.launch {
                    while (recording) {
                        delay(100)
                        recordedMs = (System.currentTimeMillis() - start).toInt()
                        if (recordedMs >= SeeRules.VIDEO_MAX_SECONDS * 1000) stopRecording()
                    }
                }
            }
            else -> {
                val kind = mode
                view.display?.rotation?.let { capture.targetRotation = it }
                send { g.prep.photo(takePicture(capture, executor), asked(), kind) }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)).background(Color(0xFF161618))) {
            if (allowed && !noCamera) AndroidView({ previewView }, Modifier.fillMaxSize())
            else Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LIcon("camera", size = 28.dp, tint = DarkColors.text2)
                Txt(when {
                    noCamera -> "No camera here. Pick a photo, a video or a document below."
                    !asked -> ""
                    else -> "The camera is off for Inkling. Allow it in Android settings, or pick from your photos below."
                }, LfmType.small, DarkColors.text2, textAlign = TextAlign.Center)
            }
            // Top bar: close, mode, flash.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon("x", "Close", onClick = { analysis.clearAnalyzer(); recording = false; nav.back() }, plain = true)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ModeSwitch(mode, enabled = !recording && busy == null) { mode = it; error = null }
                }
                RoundIcon("flash", if (flash) "Flash on" else "Flash off", onClick = { flash = !flash }, plain = true, on = flash)
            }
            // Recording time and what is happening, over the bottom of the picture.
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedVisibility(recording, enter = fadeIn(LfmMotion.spec(LfmMotion.SHORT)), exit = fadeOut(LfmMotion.spec(LfmMotion.SHORT))) {
                    Txt("● " + MediaPrep.clock(recordedMs / 1000) + " / " + MediaPrep.clock(SeeRules.VIDEO_MAX_SECONDS), LfmType.label,
                        Color.White, Modifier.background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
                }
                val status = busy ?: error
                // Keeps the last words while the pill fades out, instead of an empty box.
                var shown by remember { mutableStateOf("") }
                if (status != null) shown = status
                AnimatedVisibility(status != null, enter = fadeIn(LfmMotion.spec(LfmMotion.SHORT)), exit = fadeOut(LfmMotion.spec(LfmMotion.SHORT))) {
                    Txt(shown, LfmType.small, Color.White,
                        Modifier.padding(horizontal = 24.dp).background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        textAlign = TextAlign.Center)
                }
            }
        }
        // The question: said with the mic or typed. Optional; a plain one is asked otherwise.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp).background(DarkColors.fill, RoundedCornerShape(8.dp))
            .border(1.dp, DarkColors.control, RoundedCornerShape(8.dp)).padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MicButton(onMic, size = 36.dp, listening = listening)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (question.text.isEmpty()) Txt("Ask about it, or leave blank", LfmType.small, DarkColors.text2, maxLines = 1)
                BasicTextField(question.text, { question.text = it }, textStyle = LfmType.small.copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White), maxLines = 3,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Question" })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp), horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(if (mode == SeeKind.Document) "doc" else "image", if (mode == SeeKind.Document) "Pick a document" else "Pick from photos", onClick = {
                if (busy != null || recording) return@RoundIcon
                if (mode == SeeKind.Document) pickers.document { uri -> send { g.prep.document(uri, asked()) } }
                else pickers.media { uri -> send { g.prep.picked(uri, asked()) } }
            })
            Shutter(mode, recording, enabled = camera != null && busy == null, onClick = ::shutter)
            Box(Modifier.alpha(if (canFlip) 1f else 0.35f)) {
                RoundIcon("flip", "Switch camera", onClick = { if (!recording && canFlip) back = !back })
            }
        }
    }
}

@Composable
private fun ModeSwitch(mode: SeeKind, enabled: Boolean, onSelect: (SeeKind) -> Unit) {
    val itemW = 86.dp
    val modes = SeeKind.entries
    val x by animateDpAsState(itemW * modes.indexOf(mode), LfmMotion.spec(LfmMotion.MEDIUM, LfmMotion.emphasizedDecelerate), label = "mode")
    Box(Modifier.background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(3.dp)) {
        Box(Modifier.offset(x = x).size(itemW, 30.dp).background(Color.White, RoundedCornerShape(4.dp)))
        Row {
            modes.forEach { m ->
                val on = m == mode
                val color by androidx.compose.animation.animateColorAsState(if (on) Color.Black else Color.White,
                    LfmMotion.spec(LfmMotion.SHORT), label = "modeText")
                Box(Modifier.size(itemW, 30.dp).clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = enabled, role = Role.Tab) { onSelect(m) }, contentAlignment = Alignment.Center) {
                    Txt(m.label, LfmType.label, color)
                }
            }
        }
    }
}

@Composable
private fun Shutter(mode: SeeKind, recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, LfmMotion.spec(LfmMotion.SHORT), label = "shutterPress")
    val inner by animateDpAsState(if (recording) 28.dp else 58.dp, LfmMotion.spec(LfmMotion.MEDIUM, LfmMotion.emphasizedDecelerate), label = "shutterSize")
    val corner by animateDpAsState(if (recording) 6.dp else 29.dp, LfmMotion.spec(LfmMotion.MEDIUM, LfmMotion.emphasizedDecelerate), label = "shutterCorner")
    val desc = when { mode != SeeKind.Video -> "Take photo"; recording -> "Stop recording"; else -> "Record video" }
    Box(Modifier.size(76.dp).scale(scale).border(2.dp, Color.White.copy(alpha = if (enabled || recording) 1f else 0.4f), CircleShape)
        .clip(CircleShape).clickable(press, null, enabled = enabled || recording, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(inner).background(Color.White.copy(alpha = if (enabled || recording) 1f else 0.4f), RoundedCornerShape(corner)))
    }
}

@Composable
private fun RoundIcon(icon: String, desc: String, onClick: () -> Unit, plain: Boolean = false, on: Boolean = false) {
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.size(46.dp).clip(shape).let { if (plain) it.background(if (on) Color.White else Color(0x66000000), shape) else it.border(1.dp, DarkColors.control, shape) }
        .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Crossfade(on, animationSpec = LfmMotion.spec(LfmMotion.SHORT), label = "icon") { o -> LIcon(icon, tint = if (o) Color.Black else Color.White) }
    }
}

/** One thread for camera callbacks, for the app's life: a callback after the screen closes still has somewhere to run. */
private val cameraExecutor by lazy { Executors.newSingleThreadExecutor() }

private suspend fun takePicture(capture: ImageCapture, executor: java.util.concurrent.Executor): Bitmap =
    suspendCancellableCoroutine { cont ->
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val b = image.use { upright(it) }
                if (cont.isActive) cont.resume(b)
            }
            override fun onError(e: ImageCaptureException) {
                if (cont.isActive) cont.resumeWithException(com.lfmpixel.lfm.llm.LlmException("The camera could not take the picture"))
            }
        })
    }

/** The frame as a bitmap, turned the way the phone was held. */
private fun upright(img: ImageProxy): Bitmap {
    val b = img.toBitmap()
    val deg = img.imageInfo.rotationDegrees
    if (deg == 0) return b
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
}
