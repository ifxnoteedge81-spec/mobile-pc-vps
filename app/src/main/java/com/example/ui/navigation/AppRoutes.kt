package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

sealed class AppDestination(
    val route: String,
    val title: String,
    val shortLabel: String,
    val icon: ImageVector,
    val primaryNav: Boolean = false
) {
    data object Home : AppDestination("home", "Home Dashboard", "Home", Icons.Filled.Home, true)
    data object CommandCenter : AppDestination("command_center", "AI Command Center", "Agent", Icons.Filled.AutoAwesome, true)
    data object VirtualDesktop : AppDestination("virtual_desktop", "Virtual Desktop", "Desktop", Icons.Filled.Computer, true)
    data object Browser : AppDestination("browser", "Browser Workspace", "Browser", Icons.Filled.Language, true)
    data object Terminal : AppDestination("terminal", "Linux Terminal", "Terminal", Icons.Filled.Terminal, true)
    data object FileManager : AppDestination("file_manager", "File Manager & IDE", "Files", Icons.Filled.Folder)
    data object AppManager : AppDestination("app_manager", "App Manager", "Apps", Icons.Filled.Apps)
    data object AutomationManager : AppDestination("automation_manager", "Automation Manager", "Automate", Icons.Filled.Schedule)
    data object AgentMemory : AppDestination("agent_memory", "Agent Memory", "Memory", Icons.Filled.Psychology)
    data object TaskHistory : AppDestination("task_history", "Task History", "History", Icons.Filled.History)
    data object AiModelSettings : AppDestination("ai_model_settings", "AI Model Settings", "Models", Icons.Filled.Tune)
    data object ResourceManager : AppDestination("resource_manager", "Resource Manager", "Resources", Icons.Filled.Memory)
    data object PermissionsSecurity : AppDestination("permissions_security", "Permissions & Security", "Security", Icons.Filled.Security)
    data object About : AppDestination("about", "About Application", "About", Icons.Filled.Info)

    companion object {
        val allDestinations = listOf(
            Home,
            CommandCenter,
            VirtualDesktop,
            Browser,
            Terminal,
            FileManager,
            AppManager,
            AutomationManager,
            AgentMemory,
            TaskHistory,
            AiModelSettings,
            ResourceManager,
            PermissionsSecurity,
            About
        )

        val primaryDestinations = allDestinations.filter { it.primaryNav }

        fun fromRoute(route: String?): AppDestination =
            allDestinations.firstOrNull { it.route == route } ?: Home
    }
}
