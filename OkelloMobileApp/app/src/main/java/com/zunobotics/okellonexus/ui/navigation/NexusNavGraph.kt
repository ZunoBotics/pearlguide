package com.zunobotics.okellonexus.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.zunobotics.okellonexus.ui.screens.command.CommandScreen
import com.zunobotics.okellonexus.ui.screens.learning.LearningModeScreen
import com.zunobotics.okellonexus.ui.screens.connect.ConnectScreen
import com.zunobotics.okellonexus.ui.screens.home.HomeScreen
import com.zunobotics.okellonexus.ui.screens.knowledge.AddFactScreen
import com.zunobotics.okellonexus.ui.screens.knowledge.KnowledgeScreen
import com.zunobotics.okellonexus.ui.screens.language.LanguageScreen
import com.zunobotics.okellonexus.ui.screens.location.CreateLocationScreen
import com.zunobotics.okellonexus.ui.screens.location.LocationScreen
import com.zunobotics.okellonexus.ui.screens.monitor.MonitorScreen
import com.zunobotics.okellonexus.ui.screens.persona.CreatePersonaScreen
import com.zunobotics.okellonexus.ui.screens.persona.PersonaScreen
import com.zunobotics.okellonexus.ui.screens.people.AddPersonScreen
import com.zunobotics.okellonexus.ui.screens.people.PeopleScreen
import com.zunobotics.okellonexus.ui.screens.people.PersonDetailScreen
import com.zunobotics.okellonexus.ui.screens.safety.SafetyScreen
import com.zunobotics.okellonexus.ui.screens.settings.SettingsScreen
import com.zunobotics.okellonexus.ui.screens.splash.SplashScreen

@Composable
fun NexusNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Splash.route) {
        composable(Screen.Splash.route) {
            SplashScreen(onReady = { connected ->
                if (connected) navController.navigate(Screen.Home.route) { popUpTo(Screen.Splash.route) { inclusive = true } }
                else navController.navigate(Screen.Connect.route) { popUpTo(Screen.Splash.route) { inclusive = true } }
            })
        }
        composable(Screen.Connect.route) {
            ConnectScreen(onConnected = {
                navController.navigate(Screen.Home.route) { popUpTo(Screen.Connect.route) { inclusive = true } }
            })
        }
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigatePersona = { navController.navigate(Screen.Persona.route) },
                onNavigateLocation = { navController.navigate(Screen.Location.route) },
                onNavigateKnowledge = { navController.navigate(Screen.Knowledge.route) },
                onNavigateCommand = { navController.navigate(Screen.Command.route) },
                onNavigateMonitor = { navController.navigate(Screen.Monitor.route) },
                onNavigateLanguage = { navController.navigate(Screen.Language.route) },
                onNavigateSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateSafety = { navController.navigate(Screen.Safety.route) },
                onNavigatePeople = { navController.navigate(Screen.People.route) },
                onNavigateLearning = { navController.navigate(Screen.LearningMode.route) }
            )
        }
        composable(Screen.Persona.route) {
            PersonaScreen(
                onBack = { navController.popBackStack() },
                onCreatePersona = { navController.navigate(Screen.CreatePersona.route(null)) },
                onEditPersona = { id -> navController.navigate(Screen.CreatePersona.route(id)) }
            )
        }
        composable(
            route = Screen.CreatePersona.route,
            arguments = listOf(navArgument("personaId") { type = NavType.StringType; defaultValue = "" })
        ) { backStackEntry ->
            CreatePersonaScreen(
                personaId = backStackEntry.arguments?.getString("personaId") ?: "",
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Location.route) {
            LocationScreen(
                onBack = { navController.popBackStack() },
                onCreateLocation = { personaId ->
                    navController.navigate(Screen.CreateLocation.route(null, personaId))
                },
                onEditLocation = { id -> navController.navigate(Screen.CreateLocation.route(id)) }
            )
        }
        composable(
            route = Screen.CreateLocation.route,
            arguments = listOf(
                navArgument("locationId") { type = NavType.StringType; defaultValue = "" },
                navArgument("personaId") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            CreateLocationScreen(
                locationId = backStackEntry.arguments?.getString("locationId") ?: "",
                personaId = backStackEntry.arguments?.getString("personaId") ?: "",
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Language.route) { LanguageScreen(onBack = { navController.popBackStack() }) }
        composable(
            route = Screen.Knowledge.route,
            arguments = emptyList()
        ) {
            KnowledgeScreen(
                onBack = { navController.popBackStack() },
                onAddFact = { personaId ->
                    navController.navigate(Screen.AddFact.route(personaId))
                }
            )
        }
        composable(
            route = Screen.AddFact.route,
            arguments = listOf(navArgument("personaId") { type = NavType.StringType; defaultValue = "" })
        ) { backStackEntry ->
            AddFactScreen(
                personaId = backStackEntry.arguments?.getString("personaId") ?: "",
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.LearningMode.route) { LearningModeScreen(onBack = { navController.popBackStack() }) }
        composable(Screen.Command.route) { CommandScreen(onBack = { navController.popBackStack() }) }
        composable(Screen.Monitor.route) { MonitorScreen(onBack = { navController.popBackStack() }) }
        composable(Screen.Settings.route) { SettingsScreen(onBack = { navController.popBackStack() }) }
        composable(Screen.Safety.route) { SafetyScreen(onBack = { navController.popBackStack() }) }
        composable(Screen.People.route) {
            PeopleScreen(
                onBack = { navController.popBackStack() },
                onAddPerson = { navController.navigate(Screen.AddPerson.route(null)) },
                onViewPerson = { id -> navController.navigate(Screen.PersonDetail.route(id)) }
            )
        }
        composable(
            route = Screen.AddPerson.route,
            arguments = listOf(navArgument("personId") { type = NavType.StringType; defaultValue = "" })
        ) { back ->
            AddPersonScreen(
                personId = back.arguments?.getString("personId") ?: "",
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.PersonDetail.route,
            arguments = listOf(navArgument("personId") { type = NavType.StringType })
        ) { back ->
            PersonDetailScreen(
                personId = back.arguments?.getString("personId") ?: "",
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Screen.AddPerson.route(id)) }
            )
        }
    }
}
