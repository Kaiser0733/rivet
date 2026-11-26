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
 * Approval keys (`skillName:sha256`) for bundled skill scripts the user let run. Replacing
 * the script changes its hash and requires a fresh approval.
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

    suspend fun approve(approvalKey: String) {
        dataStore.edit { preferences ->
            preferences[approvedKey] = preferences[approvedKey].orEmpty() + approvalKey
        }
    }
}
