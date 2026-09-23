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
            val list = accountsAdapter.fromJson(json) ?: emptyList()
            val activeLogin = prefs.getString(KEY_ACTIVE_LOGIN, null)
            if (activeLogin != null) {
                list.map { it.copy(isActive = it.login.equals(activeLogin, ignoreCase = true)) }
            } else {
                list
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getActiveAccount(): GitHubAccount? {
        val accounts = getAllAccounts()
        if (accounts.isEmpty()) return null

        val activeLogin = prefs.getString(KEY_ACTIVE_LOGIN, null)
        val active = if (!activeLogin.isNullOrBlank()) {
            accounts.firstOrNull { it.login.equals(activeLogin, ignoreCase = true) }
        } else {
            accounts.firstOrNull { it.isActive }
        } ?: accounts.first()

        return active.copy(isActive = true)
    }

    @Synchronized
    fun saveOrUpdateAccount(account: GitHubAccount) {
        val currentAccounts = getAllAccounts().toMutableList()
        val index = currentAccounts.indexOfFirst {
            (it.id != 0L && it.id == account.id) || (it.login.isNotBlank() && it.login.equals(account.login, ignoreCase = true))
        }

        val shouldBeActive = account.isActive || currentAccounts.isEmpty() || currentAccounts.none { it.isActive }
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

        if (shouldBeActive) {
            prefs.edit()
                .putString(KEY_ACTIVE_LOGIN, newAccount.login)
                .putString(KEY_TOKEN, newAccount.accessToken)
                .commit()
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
        prefs.edit()
            .putString(KEY_ACTIVE_LOGIN, target.login)
            .putString(KEY_TOKEN, target.accessToken)
            .commit()

        return true
    }

    @Synchronized
    fun removeAccount(login: String) {
        val accounts = getAllAccounts().toMutableList()
        val toRemove = accounts.firstOrNull { it.login.equals(login, ignoreCase = true) } ?: return
        val wasActive = toRemove.isActive || login.equals(prefs.getString(KEY_ACTIVE_LOGIN, null), ignoreCase = true)

        accounts.removeAll { it.login.equals(login, ignoreCase = true) }

        if (accounts.isNotEmpty()) {
            if (wasActive) {
                val newActive = accounts[0].copy(isActive = true)
                accounts[0] = newActive
                saveAccountsList(accounts)
                prefs.edit()
                    .putString(KEY_ACTIVE_LOGIN, newActive.login)
                    .putString(KEY_TOKEN, newActive.accessToken)
                    .commit()
            } else {
                saveAccountsList(accounts)
            }
        } else {
            saveAccountsList(emptyList())
            prefs.edit()
                .remove(KEY_ACTIVE_LOGIN)
                .remove(KEY_TOKEN)
                .commit()
        }
    }

    @Synchronized
    fun clearAllAccounts() {
        prefs.edit()
            .remove(KEY_ACCOUNTS_LIST)
            .remove(KEY_ACTIVE_LOGIN)
            .remove(KEY_TOKEN)
            .commit()
    }

    private fun saveAccountsList(accounts: List<GitHubAccount>) {
        val json = accountsAdapter.toJson(accounts)
        prefs.edit()
            .putString(KEY_ACCOUNTS_LIST, json)
            .commit()
    }

    // ==========================================
    // Backward-Compatible Single Token Methods
    // ==========================================

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).commit()

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
            prefs.edit().remove(KEY_TOKEN).remove(KEY_ACTIVE_LOGIN).commit()
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
        prefs.edit().putBoolean(KEY_DEMO, isDemo).commit()
    }

    fun isDemoMode(): Boolean {
        return prefs.getBoolean(KEY_DEMO, false)
    }

    fun saveThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).commit()
    }

    fun getThemeMode(): String {
        return prefs.getString(KEY_THEME_MODE, "System") ?: "System"
    }

    fun saveOAuthCredentials(clientId: String, clientSecret: String) {
        prefs.edit()
            .putString(KEY_OAUTH_CLIENT_ID, clientId)
            .putString(KEY_OAUTH_CLIENT_SECRET, clientSecret)
            .commit()
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
        private const val KEY_ACTIVE_LOGIN = "github_active_login"
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