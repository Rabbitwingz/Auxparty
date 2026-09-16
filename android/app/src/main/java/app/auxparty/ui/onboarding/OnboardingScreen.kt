package app.auxparty.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import app.auxparty.ui.SystemIntents
import app.auxparty.ui.components.ShapeIllustration
import app.auxparty.ui.icons.AuxIcons
import app.auxparty.ui.state.SetupState
import app.auxparty.ui.state.SetupStep
import app.auxparty.ui.state.currentStep
import app.auxparty.ui.state.permissionSteps
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun OnboardingScreen(setup: SetupState, onFinish: () -> Unit) {
    val context = LocalContext.current
    var welcomeSeen by rememberSaveable { mutableStateOf(false) }
    var skipped by rememberSaveable { mutableStateOf(listOf<String>()) }
    val step = currentStep(setup, welcomeSeen, skipped.mapTo(HashSet()) { SetupStep.valueOf(it) })

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The result is picked up by the next setup refresh; if denied, move on.
        if (!it) skipped = skipped + SetupStep.Notifications.name
    }

    OnboardingStep(
        step = step,
        setup = setup,
        onPrimary = {
            when (step) {
                SetupStep.Welcome -> welcomeSeen = true
                SetupStep.NotificationAccess ->
                    SystemIntents.open(context, SystemIntents.notificationAccess(context), SystemIntents.appInfo(context))
                SetupStep.Overlay -> SystemIntents.open(context, SystemIntents.overlay(context), SystemIntents.appInfo(context))
                SetupStep.Battery -> SystemIntents.open(context, SystemIntents.battery(context), SystemIntents.appInfo(context))
                SetupStep.Notifications ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                SetupStep.Done -> onFinish()
            }
        },
        onSkip = { skipped = skipped + step.name },
        onOpenAppInfo = { SystemIntents.open(context, SystemIntents.appInfo(context)) },
    )
}

private data class StepCopy(
    val shape: RoundedPolygon,
    val icon: ImageVector,
    val title: String,
    val body: String,
    val primary: String,
    val skip: String?,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun copyFor(step: SetupStep, setup: SetupState): StepCopy = when (step) {
    SetupStep.Welcome -> StepCopy(
        MaterialShapes.Cookie9Sided, AuxIcons.MusicNote,
        "Your phone, everyone's DJ",
        "Auxparty lets friends pick the music from their own phones and laptops. It keeps playing here, through your speakers, headphones or car.",
        "Get started", null,
    )
    SetupStep.NotificationAccess -> StepCopy(
        MaterialShapes.Clover4Leaf, AuxIcons.GraphicEq,
        "See what's playing",
        "Notification access lets Auxparty see and control the music in YouTube Music and other apps. It never reads your messages.",
        "Allow access", null,
    )
    SetupStep.Overlay -> StepCopy(
        MaterialShapes.Arch, AuxIcons.Layers,
        "Start songs for friends",
        "\"Display over other apps\" lets a song picked from a browser start playing while Auxparty is in the background.",
        "Allow", "Not now",
    )
    SetupStep.Battery -> StepCopy(
        MaterialShapes.Pill, AuxIcons.BatteryChargingFull,
        "Stay reachable",
        "Android, and Samsung especially, puts background apps to sleep. Unrestricted battery keeps Auxparty ready for your friends.",
        "Allow", "Not now",
    )
    SetupStep.Notifications -> StepCopy(
        MaterialShapes.SoftBurst, AuxIcons.Notifications,
        "Know when it's on",
        "A quiet notification shows while friends can control your music, so it's never on without you knowing.",
        "Allow", "Skip",
    )
    SetupStep.Done -> StepCopy(
        MaterialShapes.Sunny, AuxIcons.CheckCircle,
        "You're all set",
        if (setup.ytmPackage == null) {
            "One more thing: install YouTube Music, so friends' picks have somewhere to play."
        } else {
            "Invite a friend and let them pick the next song."
        },
        "Start the party", null,
    )
}

/** Stateless step UI, used directly by screenshot tests. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingStep(
    step: SetupStep,
    setup: SetupState,
    onPrimary: () -> Unit = {},
    onSkip: () -> Unit = {},
    onOpenAppInfo: () -> Unit = {},
) {
    val slide = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            StepProgress(step, setup)
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(slide) { w -> if (forward) w / 3 else -w / 3 } + fadeIn(fade))
                        .togetherWith(slideOutHorizontally(slide) { w -> if (forward) -w / 3 else w / 3 } + fadeOut(fade))
                },
                label = "onboarding step",
                modifier = Modifier.weight(1f),
            ) { current ->
                val copy = copyFor(current, setup)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(40.dp))
                    ShapeIllustration(shape = copy.shape, icon = copy.icon, size = 220.dp)
                    Spacer(Modifier.height(40.dp))
                    Text(
                        copy.title,
                        style = MaterialTheme.typography.displaySmallEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        copy.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (current == SetupStep.NotificationAccess) {
                        Spacer(Modifier.height(20.dp))
                        RestrictedSettingsHelp(onOpenAppInfo)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val copy = copyFor(step, setup)
                Button(
                    onClick = onPrimary,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Text(copy.primary, style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
                Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                    copy.skip?.let { TextButton(onClick = onSkip) { Text(it) } }
                }
            }
        }
    }
}

/** Segmented progress across the permission steps; the current one widens. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepProgress(step: SetupStep, setup: SetupState) {
    val steps = permissionSteps(setup)
    val visible = step != SetupStep.Welcome && step != SetupStep.Done
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (!visible) return@Row
        val index = steps.indexOf(step)
        steps.forEachIndexed { i, _ ->
            val width by animateDpAsState(if (i == index) 28.dp else 8.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "dot")
            Box(
                Modifier
                    .size(width = width, height = 8.dp)
                    .background(
                        if (i <= index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        CircleShape,
                    ),
            )
        }
    }
}

/**
 * Android 13+ blocks notification access for apps installed outside a store until
 * the user allows "restricted settings". It's the most common place people get stuck.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RestrictedSettingsHelp(onOpenAppInfo: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
                Icon(AuxIcons.LockOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Switch greyed out?", modifier = Modifier.weight(1f))
                Icon(if (open) AuxIcons.Close else AuxIcons.ArrowForward, null, Modifier.size(18.dp))
            }
            AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Open App info below.",
                        "Tap the ⋮ menu in the top corner.",
                        "Choose \"Allow restricted settings\" and confirm.",
                        "Come back here and tap Allow access again.",
                    ).forEachIndexed { i, line ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    OutlinedButton(onClick = onOpenAppInfo, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(top = 4.dp)) {
                        Text("Open App info")
                        Spacer(Modifier.width(8.dp))
                        Icon(AuxIcons.OpenInNew, null, Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
