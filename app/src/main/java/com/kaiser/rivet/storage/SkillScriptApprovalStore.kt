package com.kaiser.rivet.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.skillScriptData by preferencesDataStore(name = "skill_script_approvals")

/**
 * Skills whose bundled scripts the user let run without the first-run prompt. Keyed by the
 * skill name, so reinstalling or updating a skill keeps its approval only while the name
 * stays the same; a renamed skill asks again.
 */
class SkillScriptApprovalStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.applicationContext.skillScriptData,
) {
    private val approvedKey = stringSetPreferencesKey("approved")

    suspend fun approved(): Set<String> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> preferences[approvedKey].orEmpty() }
        .first()

    suspend fun approve(skillName: String) {
        dataStore.edit { preferences ->
            preferences[approvedKey] = preferences[approvedKey].orEmpty() + skillName
        }
    }
}
