package com.example.jibberish.managers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ApiKeyManager(private val context: Context) {

    private val Context.apiKeyDataStore: DataStore<Preferences> by preferencesDataStore(name = "api_key_settings")

    companion object {
        private val GROQ_API_KEY = stringPreferencesKey("groq_api_key")
    }

    fun getApiKey(): Flow<String> {
        return context.apiKeyDataStore.data.map { preferences ->
            preferences[GROQ_API_KEY] ?: ""
        }
    }

    suspend fun getApiKeyOnce(): String {
        return context.apiKeyDataStore.data.first()[GROQ_API_KEY] ?: ""
    }

    suspend fun setApiKey(apiKey: String) {
        context.apiKeyDataStore.edit { preferences ->
            preferences[GROQ_API_KEY] = apiKey.trim()
        }
    }

    fun hasApiKey(): Flow<Boolean> {
        return context.apiKeyDataStore.data.map { preferences ->
            !preferences[GROQ_API_KEY].isNullOrBlank()
        }
    }
}
