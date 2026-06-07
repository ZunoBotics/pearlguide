package com.zunobotics.okellonexus.ui.navigation

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Connect : Screen("connect")
    object Home : Screen("home")
    object Persona : Screen("persona")
    object CreatePersona : Screen("create_persona?personaId={personaId}") {
        fun route(id: String? = null) = if (id != null) "create_persona?personaId=$id" else "create_persona?personaId="
    }
    object Location : Screen("location")
    object CreateLocation : Screen("create_location?locationId={locationId}&personaId={personaId}") {
        fun route(id: String? = null, personaId: String = "") =
            "create_location?locationId=${id ?: ""}&personaId=$personaId"
    }
    object Language : Screen("language")
    object Knowledge : Screen("knowledge")
    object AddFact : Screen("add_fact?personaId={personaId}") {
        fun route(personaId: String = "") = "add_fact?personaId=$personaId"
    }
    object Command : Screen("command")
    object Monitor : Screen("monitor")
    object Settings : Screen("settings")
    object Safety : Screen("safety")
    object People : Screen("people")
    object AddPerson : Screen("add_person?personId={personId}") {
        fun route(id: String? = null) = "add_person?personId=${id ?: ""}"
    }
    object PersonDetail : Screen("person_detail/{personId}") {
        fun route(id: String) = "person_detail/$id"
    }
}
