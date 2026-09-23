package com.vineyard.fastgit.app.utils

import android.content.Context
import android.content.SharedPreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.vineyard.fastgit.app.models.GitHubAccount

class TokenManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("fastgit_prefs", Context.MODE_PRIVATE)

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val accountsListType = Types.newParameterizedType(List::class.java, GitHubAccount::class.java)
    private val accountsAdapter = moshi.adapter<List<GitHubAccount>>(accountsListType)

    // ==========================================
    // Multi-Account Operations
    // ==========================================

    @Synchronized
    fun getAllAccounts(): List<GitHubAccount> {
        val json = prefs.getString(KEY_ACCOUNTS_LIST, null) ?: return emptyList()
        return try {
            accountsAdapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getActiveAccount(): GitHubAccount? {
        val accounts = getAllAccounts()
        return accounts.firstOrNull { it.isActive } ?: accounts.firstOrNull()
    }

    @Synchronized
    fun saveOrUpdateAccount(account: GitHubAccount) {
        val currentAccounts = getAllAccounts().toMutableList()
        val index = currentAccounts.indexOfFirst {
            (it.id != 0L && it.id == account.id) || (it.login.isNotBlank() && it.login.equals(account.login, ignoreCase = true))
        }

        // Deactivate all others if this account is active
        val shouldBeActive = account.isActive || currentAccounts.isEmpty()
        val updatedList = currentAccounts.map {
            if (shouldBeActive) it.copy(isActive = false) else it
        }.toMutableList()

        val newAccount = account.copy(isActive = shouldBeActive)

        if (index != -1) {
            updatedList[index] = newAccount
        } else {
            updatedList.add(newAccount)
        }

        saveAccountsList(updatedList)

        // Keep legacy KEY_TOKEN in sync with active account
        if (shouldBeActive) {
            prefs.edit().putString(KEY_TOKEN, newAccount.accessToken).apply()
        }
    }

    @Synchronized
    fun switchAccount(login: String): Boolean {
        val accounts = getAllAccounts()
        val target = accounts.firstOrNull { it.login.equals(login, ignoreCase = true) } ?: return false

        val updated = accounts.map {
            it.copy(isActive = it.login.equals(login, ignoreCase = true))
        }

        saveAccountsList(updated)
        prefs.edit().putString(KEY_TOKEN, target.accessToken).apply()
        return true
    }

    @Synchronized
    fun removeAccount(login: String) {
        val accounts = getAllAccounts().toMutableList()
        val toRemove = accounts.firstOrNull { it.login.equals(login, ignoreCase = true) } ?: return
        val wasActive = toRemove.isActive

        accounts.removeAll { it.login.equals(login, ignoreCase = true) }

        if (wasActive && accounts.isNotEmpty()) {
            val newActive = accounts[0].copy(isActive = true)
            accounts[0] = newActive
            saveAccountsList(accounts)
            prefs.edit().putString(KEY_TOKEN, newActive.accessToken).apply()
        } else if (accounts.isEmpty()) {
            saveAccountsList(emptyList())
            clearToken()
        } else {
            saveAccountsList(accounts)
        }
    }

    @Synchronized
    fun clearAllAccounts() {
        prefs.edit()
            .remove(KEY_ACCOUNTS_LIST)
            .remove(KEY_TOKEN)
            .apply()
    }

    private fun saveAccountsList(accounts: List<GitHubAccount>) {
        val json = accountsAdapter.toJson(accounts)
        prefs.edit().putString(KEY_ACCOUNTS_LIST, json).apply()
    }

    // ==========================================
    // Backward-Compatible Single Token Methods
    // ==========================================

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()

        // Update active account token if exists
        val active = getActiveAccount()
        if (active != null) {
            saveOrUpdateAccount(active.copy(accessToken = token, isActive = true))
        }
    }

    fun getToken(): String? {
        val active = getActiveAccount()
        if (active != null && active.accessToken.isNotBlank()) {
            return active.accessToken
        }
        return prefs.getString(KEY_TOKEN, null)
    }

    fun clearToken() {
        val active = getActiveAccount()
        if (active != null) {
            removeAccount(active.login)
        } else {
            prefs.edit().remove(KEY_TOKEN).apply()
        }
    }

    fun isLoggedIn(): Boolean {
        val token = getToken()
        return !token.isNullOrBlank()
    }

    // ==========================================
    // App Preferences & OAuth Settings
    // ==========================================

    fun setDemoMode(isDemo: Boolean) {
        prefs.edit().putBoolean(KEY_DEMO, isDemo).apply()
    }

    fun isDemoMode(): Boolean {
        return prefs.getBoolean(KEY_DEMO, false)
    }

    fun saveThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
    }

    fun getThemeMode(): String {
        return prefs.getString(KEY_THEME_MODE, "System") ?: "System"
    }

    fun saveOAuthCredentials(clientId: String, clientSecret: String) {
        prefs.edit()
            .putString(KEY_OAUTH_CLIENT_ID, clientId)
            .putString(KEY_OAUTH_CLIENT_SECRET, clientSecret)
            .apply()
    }

    fun getOAuthClientId(): String {
        val saved = prefs.getString(KEY_OAUTH_CLIENT_ID, null)
        return if (!saved.isNullOrBlank()) saved else DEFAULT_CLIENT_ID
    }

    fun getOAuthClientSecret(): String {
        val saved = prefs.getString(KEY_OAUTH_CLIENT_SECRET, null)
        return if (!saved.isNullOrBlank()) saved else DEFAULT_CLIENT_SECRET
    }

    companion object {
        private const val KEY_TOKEN = "github_access_token"
        private const val KEY_ACCOUNTS_LIST = "github_accounts_list"
        private const val KEY_DEMO = "is_demo_mode"
        private const val KEY_THEME_MODE = "app_theme_mode"
        private const val KEY_OAUTH_CLIENT_ID = "oauth_client_id"
        private const val KEY_OAUTH_CLIENT_SECRET = "oauth_client_secret"

        // Production Admin GitHub OAuth Client ID (Device Flow enabled)
        const val DEFAULT_CLIENT_ID = "Ov23lijUer4XCyoGdmvw"
        const val DEFAULT_CLIENT_SECRET = "fastgit_oauth_app_secret"
        const val OAUTH_REDIRECT_URI = "fastgit://oauth-callback"
    }
}