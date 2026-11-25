// Navigation.kt — which screen is shown, and how screens hand results back
// (e.g. the VIN scanner returns the scanned VIN to the vehicle form).
package com.ratchetwrench.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ratchetwrench.app.camera.CaptureScreen
import com.ratchetwrench.app.camera.VinScanScreen
import com.ratchetwrench.app.data.Repository
import com.ratchetwrench.app.ui.screens.EntryDetailScreen
import com.ratchetwrench.app.ui.screens.EntryEditScreen
import com.ratchetwrench.app.ui.screens.GarageScreen
import com.ratchetwrench.app.ui.screens.LoginScreen
import com.ratchetwrench.app.ui.screens.SettingsScreen
import com.ratchetwrench.app.ui.screens.StickerScreen
import com.ratchetwrench.app.ui.screens.VehicleEditScreen
import com.ratchetwrench.app.ui.screens.VehicleScreen
import com.ratchetwrench.app.ui.screens.WelcomeScreen

val LocalRepo = staticCompositionLocalOf<Repository> { error("no repository") }

/** Lets Settings change the theme for the whole app immediately. */
val LocalSetTheme = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Run screen changes (go back / open a screen) on the main thread.  After
 * saving, a coroutine can resume on another thread, and the navigation
 * library throws if it's used off the main thread.
 */
suspend fun onMain(block: () -> Unit) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { block() }

/** Read a value a child screen handed back, once. */
fun <T> NavHostController.takeResult(key: String): T? =
    currentBackStackEntry?.savedStateHandle?.remove<T>(key)

/** Hand a value back to the previous screen and go back. */
fun NavHostController.returnResult(key: String, value: Any) {
    previousBackStackEntry?.savedStateHandle?.set(key, value)
    popBackStack()
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val repo = remember { Repository(context.applicationContext) }
    var theme by remember { mutableStateOf(repo.store.theme) }
    val nav = rememberNavController()
    // First screen: phone-only users and connected users go straight to the
    // garage; a brand-new install shows the welcome choice.  (Installs from
    // before phone-only mode existed are "server" if they have a login.)
    // Decided ONCE per launch (remember): the navigation graph's start screen
    // must not change while the app is running.
    val start = remember {
        when {
            repo.store.mode == "local" -> "garage"
            repo.store.token != null -> { if (repo.store.mode.isEmpty()) repo.store.mode = "server"; "garage" }
            else -> "welcome"
        }
    }

    CompositionLocalProvider(LocalRepo provides repo, LocalSetTheme provides { t: String -> repo.store.theme = t; theme = t }) {
        RatchetTheme(theme) {
            NavHost(nav, startDestination = start) {
                composable("welcome") {
                    WelcomeScreen(
                        onPhoneOnly = { repo.store.mode = "local"; nav.navigate("garage") { popUpTo(0) } },
                        onConnect = { nav.navigate("login") },
                    )
                }
                composable("login") {
                    LoginScreen(
                        onSignedIn = { theme = repo.store.theme; nav.navigate("garage") { popUpTo(0) } },
                        onBack = if (nav.previousBackStackEntry != null) ({ nav.popBackStack() }) else null,
                    )
                }
                composable("garage") { GarageScreen(nav) }
                composable("vehicle/{id}") { VehicleScreen(nav, it.arguments!!.getString("id")!!) }
                composable("vehicle-edit/{id}") { VehicleEditScreen(nav, it.arguments!!.getString("id")!!.takeIf { id -> id != "new" }) }
                composable("entry-edit/{vehicleId}/{entryId}") {
                    EntryEditScreen(nav, it.arguments!!.getString("vehicleId")!!, it.arguments!!.getString("entryId")!!.takeIf { id -> id != "new" })
                }
                composable("entry/{id}") { EntryDetailScreen(nav, it.arguments!!.getString("id")!!) }
                composable("sticker/{vehicleId}") { StickerScreen(nav, it.arguments!!.getString("vehicleId")!!) }
                composable("scan-vin") { VinScanScreen(onResult = { vin -> nav.returnResult("vin", vin) }, onCancel = { nav.popBackStack() }) }
                composable("capture") { CaptureScreen(onDone = { paths -> nav.returnResult("photos", ArrayList(paths)) }, onCancel = { nav.popBackStack() }) }
                composable("settings") {
                    SettingsScreen(nav, onSignedOut = { nav.navigate("welcome") { popUpTo(0) } })
                }
            }
        }
    }
}
