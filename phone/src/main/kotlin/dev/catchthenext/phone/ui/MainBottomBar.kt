package dev.catchthenext.phone.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavController

@Composable
fun MainBottomBar(navController: NavController, currentRoute: String) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == "departures",
            onClick = {
                if (currentRoute != "departures") navController.navigate("departures") {
                    popUpTo("departures") { inclusive = true }
                }
            },
            icon = { Icon(Icons.Default.Home, contentDescription = "Departures") },
            label = { Text("Departures") },
        )
        NavigationBarItem(
            selected = currentRoute == "favorites",
            onClick = {
                if (currentRoute != "favorites") navController.navigate("favorites") {
                    popUpTo("departures")
                }
            },
            icon = { Icon(Icons.Default.Favorite, contentDescription = "Favorites") },
            label = { Text("Favorites") },
        )
    }
}
