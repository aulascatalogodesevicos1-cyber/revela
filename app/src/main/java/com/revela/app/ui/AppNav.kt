package com.revela.app.ui

import android.hardware.usb.UsbDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.revela.app.tether.TetherManager
import com.revela.app.ui.editor.EditorScreen
import com.revela.app.ui.hub.HubScreen
import com.revela.app.ui.tether.TetherScreen

@Composable
fun AppNav(attachedCamera: UsbDevice?, onAttachedCameraHandled: () -> Unit) {
    val nav = rememberNavController()

    LaunchedEffect(attachedCamera) {
        if (attachedCamera != null) {
            TetherManager.connectUsb(attachedCamera)
            nav.navigate("tether") { launchSingleTop = true }
            onAttachedCameraHandled()
        }
    }

    NavHost(navController = nav, startDestination = "hub") {
        composable("hub") {
            HubScreen(
                onOpenTether = { nav.navigate("tether") { launchSingleTop = true } },
                onOpenPhoto = { id -> nav.navigate("editor/$id") },
            )
        }
        composable("tether") {
            TetherScreen(
                onBack = { nav.popBackStack() },
                onOpenPhoto = { id -> nav.navigate("editor/$id") },
            )
        }
        composable(
            "editor/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            EditorScreen(onBack = { nav.popBackStack() })
        }
    }
}
