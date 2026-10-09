package dev.dreamz.llmwizard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dev.dreamz.llmwizard.ui.HomeViewModel
import dev.dreamz.llmwizard.ui.screens.AddServerRoute
import dev.dreamz.llmwizard.ui.screens.FirstRunScreen
import dev.dreamz.llmwizard.ui.screens.HomeScreen
import dev.dreamz.llmwizard.ui.screens.KeyRoute
import dev.dreamz.llmwizard.ui.screens.ManualRoute
import dev.dreamz.llmwizard.ui.screens.SetupRoute
import dev.dreamz.llmwizard.ui.screens.TranscriptRoute
import dev.dreamz.llmwizard.ui.theme.LlmWizardTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LlmWizardTheme { Surface { AppNav() } } }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val home: HomeViewModel = hiltViewModel()
    val accepted by home.firstRunAccepted.collectAsState()
    if (accepted == null) return
    NavHost(nav, startDestination = if (accepted == true) "home" else "first-run") {
        composable("first-run") { FirstRunScreen { home.acceptFirstRun(); nav.navigate("home") { popUpTo("first-run") { inclusive = true } } } }
        composable("home") {
            val list by home.list.collectAsState()
            HomeScreen(list, onOpen = { nav.navigate("server/$it") }, onAdd = { nav.navigate("add") }, onManual = { nav.navigate("manual") },
                onKey = { nav.navigate("key") }, onDelete = home::delete)
        }
        composable("add") {
            AddServerRoute(hiltViewModel(), onBack = { nav.popBackStack() }) { id -> nav.navigate("server/$id") { popUpTo("home") } }
        }
        composable("server/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            SetupRoute(hiltViewModel(), onBack = { nav.popBackStack() }, onTranscript = { nav.navigate("transcript/$it") })
        }
        composable("transcript/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { TranscriptRoute(hiltViewModel()) { nav.popBackStack() } }
        composable("manual") { ManualRoute(hiltViewModel()) { nav.popBackStack() } }
        composable("key") { KeyRoute(hiltViewModel()) { nav.popBackStack() } }
    }
}
