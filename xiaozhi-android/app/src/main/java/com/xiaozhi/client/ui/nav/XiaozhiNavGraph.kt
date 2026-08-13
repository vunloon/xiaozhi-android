package com.xiaozhi.client.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xiaozhi.client.AppContainer
import com.xiaozhi.client.ui.activation.ActivationScreen
import com.xiaozhi.client.ui.chat.ChatScreen

object Routes {
    const val ACTIVATION = "activation"
    const val CHAT = "chat"
}

@Composable
fun XiaozhiNavGraph(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val startDestination = remember {
        // Skip the activation screen if this device is already in the user's
        // account. ActivationManager.start() is still called from the chat
        // screen's VM (refreshes the websocket URL via OTA).
        if (container.efuseStore.isActivated) Routes.CHAT else Routes.ACTIVATION
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier.fillMaxSize(),
    ) {
        composable(Routes.ACTIVATION) {
            ActivationScreen(
                activationManager = container.activationManager,
                efuseStore = container.efuseStore,
                onActivated = {
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.ACTIVATION) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.CHAT) {
            ChatScreen(container = container)
        }
    }
}
