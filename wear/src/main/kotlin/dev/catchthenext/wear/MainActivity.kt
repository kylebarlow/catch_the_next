package dev.catchthenext.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.catchthenext.wear.storage.AndroidFavoritesManager
import dev.catchthenext.wear.ui.FavoritesViewModel
import dev.catchthenext.wear.ui.FavoritesScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val favoritesManager = AndroidFavoritesManager(applicationContext)
        setContent {
            MaterialTheme {
                val navController = rememberSwipeDismissableNavController()
                SwipeDismissableNavHost(navController = navController, startDestination = "favorites") {
                    composable("favorites") {
                        val viewModel: FavoritesViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                return FavoritesViewModel(favoritesManager) as T
                            }
                        })
                        FavoritesScreen(navController = navController, viewModel = viewModel)
                    }
                }
            }
        }
    }
}
