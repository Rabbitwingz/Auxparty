package app.auxparty.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.auxparty.ThemeMode
import app.auxparty.ui.devices.DevicesScreen
import app.auxparty.ui.diagnostics.DiagnosticsScreen
import app.auxparty.ui.home.HomeActions
import app.auxparty.ui.home.HomeScreen
import app.auxparty.ui.home.InviteSheet
import app.auxparty.ui.onboarding.OnboardingScreen
import app.auxparty.ui.party.PartyActions
import app.auxparty.ui.party.PartyScreen
import app.auxparty.ui.search.SearchActions
import app.auxparty.ui.search.SearchScreen
import app.auxparty.ui.settings.SettingsActions
import app.auxparty.ui.settings.SettingsScreen
import app.auxparty.ui.state.Connection
import app.auxparty.ui.state.partyShareText
import app.auxparty.ui.theme.AuxpartyTheme

private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val DEVICES = "devices"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val PARTY = "party"
    const val SEARCH = "search"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AuxpartyApp(vm: HostViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Permissions are granted in system Settings, so re-check on every return.
    LifecycleResumeEffect(Unit) {
        vm.refreshSetup()
        onPauseOrDispose { }
    }

    val dark = when (state.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    AuxpartyTheme(seed = state.seed, darkTheme = dark) {
        val nav = rememberNavController()
        var inviteOpen by rememberSaveable { mutableStateOf(false) }
        val start = remember { if (state.onboardingDone && state.setup.ready) Routes.HOME else Routes.ONBOARDING }

        val openInvite = {
            vm.requestInvite()
            inviteOpen = true
        }

        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            vm.refreshSetup()
        }

        val context = LocalContext.current
        val sharePartyLink: (String) -> Unit = { link ->
            SystemIntents.open(context, SystemIntents.share(partyShareText(link)))
        }

        // Screens slide on the theme's spatial spring; read it here because the
        // transition lambdas aren't composable.
        val slide = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
        val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

        NavHost(
            navController = nav,
            startDestination = start,
            enterTransition = { slideInHorizontally(slide) { it / 4 } + fadeIn(fade) },
            exitTransition = { slideOutHorizontally(slide) { -it / 4 } + fadeOut(fade) },
            popEnterTransition = { slideInHorizontally(slide) { -it / 4 } + fadeIn(fade) },
            popExitTransition = { slideOutHorizontally(slide) { it / 4 } + fadeOut(fade) },
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    setup = state.setup,
                    onFinish = {
                        vm.completeOnboarding()
                        nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    },
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    state = state,
                    actions = HomeActions(
                        onPlayPause = vm::playPause,
                        onNext = vm::next,
                        onPrevious = vm::previous,
                        onSeek = vm::seekTo,
                        onVolume = vm::setVolume,
                        onInvite = openInvite,
                        onDevices = { nav.navigate(Routes.DEVICES) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                        onFinishSetup = { nav.navigate(Routes.ONBOARDING) },
                        onStartParty = vm::startParty,
                        onEndParty = vm::endParty,
                        onOpenParty = { nav.navigate(Routes.PARTY) },
                        onShareParty = sharePartyLink,
                        onSearch = { nav.navigate(Routes.SEARCH) },
                    ),
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    search = state.search,
                    partyActive = state.party.active,
                    actions = SearchActions(
                        onBack = { nav.popBackStack() },
                        onQuery = vm::setSearchQuery,
                        onPlayNow = vm::playNow,
                        onAddToQueue = vm::addToQueue,
                        onMessageShown = vm::consumeSearchMessage,
                    ),
                )
            }
            composable(Routes.PARTY) {
                // Leave when the party ends (from here, or from Home in another window).
                LaunchedEffect(state.party.active) {
                    if (!state.party.active) nav.popBackStack(Routes.HOME, inclusive = false)
                }
                PartyScreen(
                    party = state.party,
                    actions = PartyActions(
                        onBack = { nav.popBackStack() },
                        onShare = sharePartyLink,
                        onNewLink = vm::newPartyLink,
                        onEndParty = vm::endParty,
                        onRemove = vm::removeFromQueue,
                        onPlayNext = vm::playNextInQueue,
                        onClear = vm::clearQueue,
                        onRemoveGuest = vm::removeGuest,
                        onGuestLimit = vm::setGuestLimit,
                        onAddSongs = { nav.navigate(Routes.SEARCH) },
                    ),
                )
            }
            composable(Routes.DEVICES) {
                DevicesScreen(
                    linked = state.linked,
                    onBack = { nav.popBackStack() },
                    onRevoke = vm::revoke,
                    onInvite = openInvite,
                    inviteEnabled = state.connection == Connection.Online,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    state = state,
                    actions = SettingsActions(
                        onBack = { nav.popBackStack() },
                        onDevices = { nav.navigate(Routes.DEVICES) },
                        onThemeMode = vm::setThemeMode,
                        onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                        onRequestNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    ),
                )
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(onBack = { nav.popBackStack() })
            }
        }

        if (inviteOpen) {
            InviteSheet(
                state = state,
                onDismiss = {
                    inviteOpen = false
                    vm.dismissInvite()
                },
                onNewCode = vm::requestInvite,
            )
        }
    }
}
