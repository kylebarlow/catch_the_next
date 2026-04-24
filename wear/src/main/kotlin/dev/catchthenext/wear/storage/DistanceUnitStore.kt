package dev.catchthenext.wear.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

enum class DistanceUnit { MILES, KM }

// US, UK, and Myanmar use miles; rest of world uses km
fun localeDefaultUnit(): DistanceUnit =
    if (Locale.getDefault().country in setOf("US", "GB", "MM")) DistanceUnit.MILES else DistanceUnit.KM

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class DistanceUnitStore(private val context: Context) {
    private val useMilesKey = booleanPreferencesKey("use_miles")

    val unitFlow: Flow<DistanceUnit> = context.settingsDataStore.data.map { prefs ->
        val stored = prefs[useMilesKey]
        if (stored != null) {
            if (stored) DistanceUnit.MILES else DistanceUnit.KM
        } else {
            localeDefaultUnit()
        }
    }

    suspend fun setUnit(unit: DistanceUnit) {
        context.settingsDataStore.edit { prefs ->
            prefs[useMilesKey] = unit == DistanceUnit.MILES
        }
    }
}
