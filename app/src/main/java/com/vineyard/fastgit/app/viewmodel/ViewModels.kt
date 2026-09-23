package com.vineyard.fastgit.app.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vineyard.fastgit.app.database.AppDatabase
import com.vineyard.fastgit.app.database.CacheEntity
import com.vineyard.fastgit.app.models.*
import com.vineyard.fastgit.app.network.GitHubApiService
import com.vineyard.fastgit.app.network.RetrofitClient
import com.vineyard.fastgit.app.utils.DownloadUtils
import com.vineyard.fastgit.app.utils.TokenManager
import com.vineyard.fastgit.app.utils.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    val tokenManager = TokenManager(application)
    private val _isLoggedIn = MutableStateFlow(tokenManager.isLoggedIn() || tokenManager.isDemoMode())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _deviceCodeState = MutableStateFlow<com.vineyard.fastgit.app.models.DeviceCodeResponse?>(null)
    val deviceCodeState: StateFlow<com.vineyard.fastgit.app.models.DeviceCodeResponse?> = _deviceCodeState

    private val _isDeviceFlowLoading = MutableStateFlow(false)
    val isDeviceFlowLoading: StateFlow<Boolean> = _isDeviceFlowLoading

    // Multi-Account State Management
    private val _accounts = MutableStateFlow<List<GitHubAccount>>(tokenManager.getAllAccounts())
    val accounts: StateFlow<List<GitHubAccount>> = _accounts

    private val _activeAccount = MutableStateFlow<GitHubAccount?>(tokenManager.getActiveAccount())
    val activeAccount: StateFlow<GitHubAccount?> = _activeAccount

    private val _isAddingAccount = MutableStateFlow(false)
    val isAddingAccount: StateFlow<Boolean> = _isAddingAccount

    companion object {
        const val ADMIN_CLIENT_ID = "Ov23lijUer4XCyoGdmvw"
        private const val LEGACY_DUMMY_CLIENT_ID = "Ov23liaVFastGitClient"
    }

    init {
        // Automatically sanitize SharedPreferences: replace any legacy dummy ID with the valid Admin Client ID
        val currentSavedId = tokenManager.getOAuthClientId()
        if (currentSavedId == LEGACY_DUMMY_CLIENT_ID || currentSavedId.isBlank()) {
            tokenManager.saveOAuthCredentials(ADMIN_CLIENT_ID, tokenManager.getOAuthClientSecret())
        }

        refreshAccountsState()

        if (tokenManager.isLoggedIn() || tokenManager.isDemoMode()) {
            loadCurrentUser()
        }
    }

    fun refreshAccountsState() {
        val all = tokenManager.getAllAccounts()
        val active = tokenManager.getActiveAccount()
        _accounts.value = all
        _activeAccount.value = active
        _isLoggedIn.value = tokenManager.isLoggedIn() || tokenManager.isDemoMode()
    }

    fun getCurrentClientId(): String {
        val id = tokenManager.getOAuthClientId()
        return if (id.isBlank() || id == LEGACY_DUMMY_CLIENT_ID) ADMIN_CLIENT_ID else id
    }

    fun saveCustomClientId(clientId: String) {
        val targetId = if (clientId.isBlank()) ADMIN_CLIENT_ID else clientId.trim()
        tokenManager.saveOAuthCredentials(targetId, tokenManager.getOAuthClientSecret())
    }

    fun resetToDefaultClientId() {
        tokenManager.saveOAuthCredentials(ADMIN_CLIENT_ID, tokenManager.getOAuthClientSecret())
    }

    fun getOAuthAuthorizeUrl(): String {
        val clientId = getCurrentClientId()
        val redirectUri = Uri.encode(TokenManager.OAUTH_REDIRECT_URI)
        val scope = Uri.encode("repo workflow user read:org notifications gist delete_repo")
        return "https://github.com/login/oauth/authorize?client_id=$clientId&redirect_uri=$redirectUri&scope=$scope"
    }

    // Controls the "Add Account" overlay/mode safely without logging out current active session
    fun startAddAccount() {
        _isAddingAccount.value = true
        _errorMessage.value = null
    }

    fun cancelAddAccount() {
        _isAddingAccount.value = false
        _errorMessage.value = null
        _deviceCodeState.value = null
        _isDeviceFlowLoading.value = false
    }

    fun switchAccount(account: GitHubAccount) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Update SharedPreferences synchronously
                val switched = tokenManager.switchAccount(account.login)
                if (switched) {
                    tokenManager.setDemoMode(false)

                    // Immediately update in-memory state so checkmarks & profile cards update at once
                    val updatedList = _accounts.value.map {
                        it.copy(isActive = it.login.equals(account.login, ignoreCase = true))
                    }
                    val switchedAccount = updatedList.firstOrNull { it.login.equals(account.login, ignoreCase = true) } ?: account.copy(isActive = true)
                    _accounts.value = updatedList
                    _activeAccount.value = switchedAccount

                    // Pre-fill user header with known switched account info to prevent stale display
                    _user.value = User(
                        id = switchedAccount.id,
                        login = switchedAccount.login,
                        name = switchedAccount.name ?: switchedAccount.login,
                        avatarUrl = switchedAccount.avatarUrl
                    )

                    // Fetch latest profile details from GitHub API with the new token
                    loadCurrentUser()
                    com.vineyard.fastgit.app.utils.AppLogger.s("MultiAccount", "Switched active account to: ${account.login}")
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to switch account: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun removeAccount(account: GitHubAccount) {
        viewModelScope.launch {
            tokenManager.removeAccount(account.login)
            refreshAccountsState()
            if (tokenManager.isLoggedIn()) {
                loadCurrentUser()
            } else {
                logout()
            }
            com.vineyard.fastgit.app.utils.AppLogger.i("MultiAccount", "Removed account session: ${account.login}")
        }
    }

    fun signOutAllAccounts() {
        tokenManager.clearAllAccounts()
        tokenManager.setDemoMode(false)
        _user.value = null
        _accounts.value = emptyList()
        _activeAccount.value = null
        _isLoggedIn.value = false
        _isAddingAccount.value = false
        com.vineyard.fastgit.app.utils.AppLogger.i("MultiAccount", "Signed out all GitHub accounts.")
    }

    fun handleOAuthCode(code: String) {
        if (code.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val clientId = getCurrentClientId()
                com.vineyard.fastgit.app.utils.AppLogger.i("OAuth", "Exchanging OAuth authorization code with GitHub for client $clientId...")
                val response = RetrofitClient.getOAuthService().exchangeCodeForToken(
                    clientId = clientId,
                    clientSecret = tokenManager.getOAuthClientSecret(),
                    code = code,
                    redirectUri = TokenManager.OAUTH_REDIRECT_URI
                )

                val token = response.accessToken
                if (!token.isNullOrBlank()) {
                    onTokenAcquired(token)
                } else {
                    val err = response.errorDescription ?: response.error ?: "Unable to exchange code for GitHub OAuth access token."
                    _errorMessage.value = "OAuth Error: $err"
                    com.vineyard.fastgit.app.utils.AppLogger.e("OAuth", "OAuth error response: $err")
                }
            } catch (e: Exception) {
                _errorMessage.value = "OAuth Authentication failed: ${e.message}"
                com.vineyard.fastgit.app.utils.AppLogger.e("OAuth", "OAuth exception: ${e.message}", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun onTokenAcquired(token: String) {
        tokenManager.setDemoMode(false)
        com.vineyard.fastgit.app.utils.AppLogger.s("OAuth", "Token acquired successfully! Fetching user identity...")

        try {
            // Build an isolated Retrofit instance with this specific token to avoid interfering with current TokenManager state
            val isolatedClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .header("Accept", "application/vnd.github.v3+json")
                        .build()
                    chain.proceed(req)
                }
                .build()

            val isolatedService = Retrofit.Builder()
                .baseUrl("https://api.github.com/")
                .client(isolatedClient)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()
                .create(GitHubApiService::class.java)

            val profile = isolatedService.getCurrentUser()

            val unreadCount = try {
                val notifs = isolatedService.getNotifications()
                notifs.count { it.unread }
            } catch (e: Exception) {
                0
            }

            val newAccount = GitHubAccount(
                id = profile.id,
                login = profile.login,
                name = profile.name ?: profile.login,
                avatarUrl = profile.avatarUrl ?: "",
                accessToken = token,
                isActive = true,
                unreadNotifications = unreadCount
            )

            // Save and make active atomically
            tokenManager.saveOrUpdateAccount(newAccount)
            refreshAccountsState()

            _user.value = profile
            _isLoggedIn.value = true
            _isAddingAccount.value = false
            com.vineyard.fastgit.app.utils.AppLogger.s("MultiAccount", "Account '${newAccount.login}' saved and set as active session!")
        } catch (e: Exception) {
            tokenManager.saveToken(token)
            refreshAccountsState()
            _isLoggedIn.value = true
            _isAddingAccount.value = false
            loadCurrentUser()
        }
    }

    fun setOAuthError(error: String) {
        _errorMessage.value = error
    }

    fun startDeviceFlow() {
        viewModelScope.launch {
            _isDeviceFlowLoading.value = true
            _errorMessage.value = null
            try {
                val clientId = getCurrentClientId()
                com.vineyard.fastgit.app.utils.AppLogger.i("DeviceFlow", "Requesting device code from GitHub using client ID $clientId...")
                val response = RetrofitClient.getOAuthService().requestDeviceCode(
                    clientId = clientId,
                    scope = "repo workflow user read:org notifications gist delete_repo"
                )
                if (!response.deviceCode.isNullOrBlank() && !response.userCode.isNullOrBlank()) {
                    _deviceCodeState.value = response
                    com.vineyard.fastgit.app.utils.AppLogger.s("DeviceFlow", "Device Code received: ${response.userCode}")
                    pollDeviceToken(response)
                } else {
                    val err = response.errorDescription ?: response.error ?: "Failed to obtain device code from GitHub"
                    _errorMessage.value = "Device Flow Error: $err"
                }
            } catch (e: retrofit2.HttpException) {
                val activeId = getCurrentClientId()
                if (e.code() == 404) {
                    _errorMessage.value = "Device Flow Error: HTTP 404 (GitHub Client ID '$activeId' not found or Device Flow is disabled in GitHub OAuth App settings. Ensure Device Flow is enabled in your GitHub Developer settings)."
                } else {
                    _errorMessage.value = "Device Flow Error: HTTP ${e.code()} ${e.message()}"
                }
                com.vineyard.fastgit.app.utils.AppLogger.e("DeviceFlow", "HTTP Exception requesting device code: ${e.code()} ${e.message()}", e)
            } catch (e: Exception) {
                _errorMessage.value = "Device Flow Error: ${e.message}"
                com.vineyard.fastgit.app.utils.AppLogger.e("DeviceFlow", "Exception requesting device code: ${e.message}", e)
            } finally {
                _isDeviceFlowLoading.value = false
            }
        }
    }

    private fun pollDeviceToken(deviceResponse: com.vineyard.fastgit.app.models.DeviceCodeResponse) {
        viewModelScope.launch {
            val deviceCode = deviceResponse.deviceCode ?: return@launch
            val clientId = getCurrentClientId()
            var pollInterval = ((deviceResponse.interval ?: 5).coerceAtLeast(5)) * 1000L

            while (_deviceCodeState.value != null && _deviceCodeState.value?.deviceCode == deviceCode) {
                delay(pollInterval)
                if (_deviceCodeState.value?.deviceCode != deviceCode) break

                try {
                    val tokenResponse = RetrofitClient.getOAuthService().pollDeviceToken(
                        clientId = clientId,
                        deviceCode = deviceCode
                    )

                    val token = tokenResponse.accessToken
                    if (!token.isNullOrBlank()) {
                        com.vineyard.fastgit.app.utils.AppLogger.s("DeviceFlow", "Device Flow Token approved! Loading user profile...")
                        _deviceCodeState.value = null
                        onTokenAcquired(token)
                        break
                    } else {
                        when (tokenResponse.error) {
                            "authorization_pending" -> {
                                // Keep polling while user approves on GitHub
                            }
                            "slow_down" -> {
                                pollInterval += 5000L
                            }
                            "expired_token" -> {
                                _errorMessage.value = "Device code expired. Please try again."
                                _deviceCodeState.value = null
                                break
                            }
                            "access_denied" -> {
                                _errorMessage.value = "Access denied by user on GitHub."
                                _deviceCodeState.value = null
                                break
                            }
                            else -> {
                                val err = tokenResponse.errorDescription ?: tokenResponse.error ?: "Device Flow authorization failed"
                                _errorMessage.value = "Device Flow Error: $err"
                                _deviceCodeState.value = null
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    com.vineyard.fastgit.app.utils.AppLogger.e("DeviceFlow", "Polling exception: ${e.message}", e)
                }
            }
        }
    }

    fun cancelDeviceFlow() {
        _deviceCodeState.value = null
    }

    fun enableDemoMode() {
        tokenManager.setDemoMode(true)
        _isLoggedIn.value = true
        _user.value = User(
            id = 101,
            login = "developer_android",
            name = "FastGit Mobile Developer",
            avatarUrl = "https://github.com/identicons/developer_android.png",
            bio = "Building high-performance Android apps with Kotlin & Compose",
            publicRepos = 12,
            followers = 142,
            following = 38
        )
    }

    fun loadCurrentUser() {
        if (tokenManager.isDemoMode()) {
            _user.value = User(
                id = 101,
                login = "developer_android",
                name = "FastGit Mobile Developer",
                avatarUrl = "https://github.com/identicons/developer_android.png",
                bio = "Building high-performance Android apps with Kotlin & Compose",
                publicRepos = 12,
                followers = 142,
                following = 38
            )
            return
        }

        viewModelScope.launch {
            try {
                val api = RetrofitClient.getService(tokenManager)
                val u = api.getCurrentUser()
                val currentActive = tokenManager.getActiveAccount()

                // Only set user if it corresponds to the currently active account session
                if (currentActive == null || currentActive.login.equals(u.login, ignoreCase = true)) {
                    _user.value = u

                    if (currentActive != null) {
                        val updated = currentActive.copy(
                            id = u.id,
                            login = u.login,
                            name = u.name ?: currentActive.name,
                            avatarUrl = u.avatarUrl ?: currentActive.avatarUrl
                        )
                        tokenManager.saveOrUpdateAccount(updated)
                        refreshAccountsState()
                    }
                }
            } catch (e: Exception) {
                // Ignore or fallback
            }
        }
    }

    fun logout() {
        val active = tokenManager.getActiveAccount()
        if (active != null) {
            tokenManager.removeAccount(active.login)
        } else {
            tokenManager.clearToken()
        }
        tokenManager.setDemoMode(false)
        refreshAccountsState()

        if (tokenManager.isLoggedIn()) {
            loadCurrentUser()
        } else {
            _user.value = null
            _isLoggedIn.value = false
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val _recentRepos = MutableStateFlow<List<Repository>>(emptyList())
    val recentRepos: StateFlow<List<Repository>> = _recentRepos

    private val _recentCommits = MutableStateFlow<List<Commit>>(emptyList())
    val recentCommits: StateFlow<List<Commit>> = _recentCommits

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadHomeData()
    }

    fun loadHomeData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (tokenManager.isDemoMode()) {
                    _recentRepos.value = getSampleRepositories()
                    _recentCommits.value = getSampleCommits()
                } else {
                    val api = RetrofitClient.getService(tokenManager)
                    val repos = api.getUserRepositories()
                    _recentRepos.value = repos.take(5)

                    if (repos.isNotEmpty()) {
                        val first = repos.first()
                        val owner = first.owner?.login ?: ""
                        val commits = api.getCommits(owner, first.name)
                        _recentCommits.value = commits.take(5)
                    }
                }
            } catch (e: Exception) {
                _recentRepos.value = getSampleRepositories()
                _recentCommits.value = getSampleCommits()
            } finally {
                _isLoading.value = false
            }
        }
    }
}

class RepositoryViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val _repositories = MutableStateFlow<List<Repository>>(emptyList())
    val repositories: StateFlow<List<Repository>> = _repositories

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedFilter = MutableStateFlow("All") // "All", "Public", "Private", "Sources", "Forks"
    val selectedFilter: StateFlow<String> = _selectedFilter

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage

    init {
        fetchRepositories()
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onFilterSelect(filter: String) {
        _selectedFilter.value = filter
    }

    fun fetchRepositories() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (tokenManager.isDemoMode()) {
                    _repositories.value = getSampleRepositories()
                } else {
                    val api = RetrofitClient.getService(tokenManager)
                    _repositories.value = api.getUserRepositories()
                }
            } catch (e: Exception) {
                _repositories.value = getSampleRepositories()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createRepository(
        name: String,
        description: String,
        isPrivate: Boolean,
        initReadme: Boolean,
        gitignore: String?,
        license: String?,
        onSuccess: (Repository) -> Unit
    ) {
        if (name.isBlank()) {
            _statusMessage.value = "Repository name is required"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (tokenManager.isDemoMode()) {
                    val newRepo = Repository(
                        id = System.currentTimeMillis(),
                        name = name,
                        fullName = "developer_android/$name",
                        owner = User(login = "developer_android"),
                        description = description,
                        private = isPrivate,
                        defaultBranch = "main",
                        stargazersCount = 0,
                        forksCount = 0,
                        language = "Kotlin",
                        updatedAt = "Just now"
                    )
                    _repositories.value = listOf(newRepo) + _repositories.value
                    _statusMessage.value = "Repository '$name' created successfully!"
                    onSuccess(newRepo)
                } else {
                    val api = RetrofitClient.getService(tokenManager)
                    val req = CreateRepoRequest(
                        name = name,
                        description = description,
                        private = isPrivate,
                        autoInit = initReadme,
                        gitignoreTemplate = gitignore.takeIf { !it.isNullOrBlank() },
                        licenseTemplate = license.takeIf { !it.isNullOrBlank() }
                    )
                    val newRepo = api.createRepository(req)
                    fetchRepositories()
                    _statusMessage.value = "Repository '${newRepo.name}' created!"
                    onSuccess(newRepo)
                }
            } catch (e: Exception) {
                _statusMessage.value = "Failed to create repository: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteRepository(owner: String, name: String, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (tokenManager.isDemoMode()) {
                    _repositories.value = _repositories.value.filterNot { it.name == name && it.owner?.login == owner }
                    _statusMessage.value = "Repository '$name' deleted successfully (Demo Mode)!"
                    onSuccess?.invoke()
                } else {
                    val api = RetrofitClient.getService(tokenManager)
                    val response = api.deleteRepository(owner, name)
                    if (response.isSuccessful) {
                        _repositories.value = _repositories.value.filterNot { it.name == name && it.owner?.login == owner }
                        _statusMessage.value = "Repository '$name' deleted successfully!"
                        fetchRepositories()
                        onSuccess?.invoke()
                    } else {
                        val errorDetail = response.errorBody()?.string() ?: response.message()
                        _statusMessage.value = "Failed to delete: $errorDetail"
                        com.vineyard.fastgit.app.utils.AppLogger.e("RepositoryViewModel", "Delete failed: $errorDetail")
                    }
                }
            } catch (e: Exception) {
                _statusMessage.value = "Failed to delete: ${e.message}"
                com.vineyard.fastgit.app.utils.AppLogger.e("RepositoryViewModel", "Delete caught exception", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun downloadRepositoryAsZip(owner: String, repoName: String, branch: String, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _isLoading.value = true
                _statusMessage.value = "Downloading repository ZIP..."
            }

            try {
                val targetBranch = branch.ifBlank { "main" }
                val targetFileName = "$repoName-$targetBranch.zip"

                if (tokenManager.isDemoMode()) {
                    val mockItems = listOf(
                        FileItem(name = "README.md", type = "file", content = "# Demo Project\nLocal bundle saved offline.")
                    )
                    val zip = DownloadUtils.createZipFromFolderItems(context, repoName, mockItems)
                    val bytes = zip.readBytes()
                    val savedFile = DownloadUtils.saveBinaryToDownloads(context, "", targetFileName, bytes)
                    zip.delete()

                    withContext(Dispatchers.Main) {
                        if (savedFile != null) {
                            _statusMessage.value = "Saved successfully: Downloads/FastGit/$targetFileName"
                        } else {
                            _statusMessage.value = "Download failed: Local storage write error"
                        }
                    }
                    return@launch
                }

                val api = RetrofitClient.getService(tokenManager)
                com.vineyard.fastgit.app.utils.AppLogger.i("RepositoryViewModel", "Downloading zipball for $owner/$repoName ($targetBranch)")
                val response = api.downloadZipball(owner, repoName, targetBranch)

                if (response.isSuccessful && response.body() != null) {
                    val bytes = response.body()!!.bytes()
                    val savedFile = DownloadUtils.saveBinaryToDownloads(context, "", targetFileName, bytes)

                    withContext(Dispatchers.Main) {
                        if (savedFile != null) {
                            _statusMessage.value = "Saved successfully: Downloads/FastGit/$targetFileName"
                            com.vineyard.fastgit.app.utils.AppLogger.s("RepositoryViewModel", "Saved ZIP file $targetFileName successfully.")
                        } else {
                            _statusMessage.value = "Download failed: MediaStore write error"
                        }
                    }
                } else {
                    val errMsg = response.errorBody()?.string() ?: response.message()
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = "Download failed: $errMsg"
                    }
                    com.vineyard.fastgit.app.utils.AppLogger.e("RepositoryViewModel", "Download failed with error: $errMsg")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Download failed: ${e.message}"
                }
                com.vineyard.fastgit.app.utils.AppLogger.e("RepositoryViewModel", "Download exception caught", e)
            } finally {
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                }
            }
        }
    }

    fun importRepositoryUrl(
        url: String,
        newRepoName: String = "",
        isPrivate: Boolean = false,
        onSuccess: (Repository) -> Unit
    ) {
        if (url.isBlank()) {
            _statusMessage.value = "Please enter a valid GitHub source URL"
            return
        }

        val cleanUrl = url.trim().removeSuffix("/").removeSuffix(".git")
        val parts = cleanUrl.split("/")
        if (parts.size < 2) {
            _statusMessage.value = "Invalid GitHub URL format. Use https://github.com/owner/repo"
            return
        }
        val sourceOwner = parts[parts.size - 2]
        val sourceRepo = parts[parts.size - 1]
        val targetRepoName = if (newRepoName.isNotBlank()) newRepoName else sourceRepo

        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _isLoading.value = true
                _statusMessage.value = "Creating new repository '$targetRepoName' and importing files..."
            }

            try {
                if (tokenManager.isDemoMode()) {
                    val imported = Repository(
                        id = System.currentTimeMillis(),
                        name = targetRepoName,
                        fullName = "developer_android/$targetRepoName",
                        owner = User(login = "developer_android"),
                        description = "Imported repository from $url",
                        private = isPrivate,
                        defaultBranch = "main",
                        stargazersCount = 0,
                        language = "Kotlin",
                        updatedAt = "Just now"
                    )
                    withContext(Dispatchers.Main) {
                        _repositories.value = listOf(imported) + _repositories.value
                        _statusMessage.value = "Repository '$targetRepoName' imported successfully!"
                        onSuccess(imported)
                    }
                } else {
                    val api = RetrofitClient.getService(tokenManager)
                    val currentUser = try { api.getCurrentUser() } catch (e: Exception) { null }
                    val newOwner = currentUser?.login ?: "developer"

                    val createReq = CreateRepoRequest(
                        name = targetRepoName,
                        description = "Imported copy from $cleanUrl",
                        private = isPrivate,
                        autoInit = false
                    )
                    val newRepo = api.createRepository(createReq)
                    com.vineyard.fastgit.app.utils.AppLogger.s("RepositoryViewModel", "Created target repository '${newRepo.fullName}' on GitHub.")

                    var filesImportedCount = 0
                    try {
                        com.vineyard.fastgit.app.utils.AppLogger.i("RepositoryViewModel", "Downloading source zipball for $sourceOwner/$sourceRepo...")
                        val zipResponse = api.downloadZipball(sourceOwner, sourceRepo, "main")

                        if (zipResponse.isSuccessful && zipResponse.body() != null) {
                            val tempZip = File.createTempFile("import_source_", ".zip")
                            val tempDir = File.createTempFile("import_extract_", "")
                            tempDir.delete()
                            tempDir.mkdirs()

                            zipResponse.body()!!.byteStream().use { input ->
                                tempZip.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }

                            val extractedFiles = ZipUtils.unzip(tempZip.inputStream(), tempDir)
                            val scannedFiles = ZipUtils.scanDirectory(tempDir)

                            com.vineyard.fastgit.app.utils.AppLogger.i("RepositoryViewModel", "Scanned ${scannedFiles.size} files from source repository.")

                            for (scanned in scannedFiles) {
                                try {
                                    val req = CreateFileRequest(
                                        message = "Import ${scanned.relativePath} from $sourceOwner/$sourceRepo",
                                        content = scanned.contentBase64,
                                        branch = newRepo.defaultBranch
                                    )
                                    api.createOrUpdateFile(newOwner, targetRepoName, scanned.relativePath, req)
                                    filesImportedCount++
                                } catch (e: Exception) {
                                    com.vineyard.fastgit.app.utils.AppLogger.e("RepositoryViewModel", "Failed to transfer file ${scanned.relativePath}: ${e.message}")
                                }
                            }

                            tempZip.delete()
                            tempDir.deleteRecursively()
                        }
                    } catch (e: Exception) {
                        com.vineyard.fastgit.app.utils.AppLogger.e("RepositoryViewModel", "Source zipball transfer warning: ${e.message}", e)
                    }

                    withContext(Dispatchers.Main) {
                        fetchRepositories()
                        _statusMessage.value = "Repository '$targetRepoName' created and imported successfully! ($filesImportedCount files copied)"
                        onSuccess(newRepo)
                    }
                }
            } catch (e: Exception) {
                com.vineyard.fastgit.app.utils.AppLogger.e("RepositoryViewModel", "Import repository failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Import failed: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                }
            }
        }
    }

    fun clearStatus() {
        _statusMessage.value = null
    }
}

class NotificationViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val _notifications = MutableStateFlow<List<Notification>>(emptyList())
    val notifications: StateFlow<List<Notification>> = _notifications

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadNotifications()
    }

    fun loadNotifications() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (tokenManager.isDemoMode()) {
                    _notifications.value = getSampleNotifications()
                } else {
                    val api = RetrofitClient.getService(tokenManager)
                    _notifications.value = api.getNotifications()
                }
            } catch (e: Exception) {
                _notifications.value = getSampleNotifications()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun markAsRead(id: String) {
        _notifications.value = _notifications.value.map {
            if (it.id == id) it.copy(unread = false) else it
        }
        if (!tokenManager.isDemoMode()) {
            viewModelScope.launch {
                try {
                    val api = RetrofitClient.getService(tokenManager)
                    api.markNotificationAsRead(id)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }
}

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user

    private val _pinnedRepos = MutableStateFlow<List<Repository>>(emptyList())
    val pinnedRepos: StateFlow<List<Repository>> = _pinnedRepos

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadProfile()
    }

    fun loadProfile() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (tokenManager.isDemoMode()) {
                    _user.value = User(
                        id = 101,
                        login = "developer_android",
                        name = "FastGit Mobile Developer",
                        avatarUrl = "https://github.com/identicons/developer_android.png",
                        bio = "Building high-performance Android applications with Kotlin, Coroutines, and Jetpack Compose",
                        company = "Vineyard Apps",
                        location = "San Francisco, CA",
                        publicRepos = 14,
                        followers = 320,
                        following = 45
                    )
                    _pinnedRepos.value = getSampleRepositories()
                } else {
                    val api = RetrofitClient.getService(tokenManager)
                    val u = api.getCurrentUser()
                    _user.value = u
                    _pinnedRepos.value = api.getUserRepositories().take(4)
                }
            } catch (e: Exception) {
                _user.value = User(
                    login = "developer_android",
                    name = "FastGit Developer",
                    bio = "Android GitHub Workspace"
                )
                _pinnedRepos.value = getSampleRepositories()
            } finally {
                _isLoading.value = false
            }
        }
    }
}

// Global Sample Helpers
fun getSampleRepositories(): List<Repository> {
    return listOf(
        Repository(
            id = 1,
            name = "FastGit-Android",
            fullName = "developer_android/FastGit-Android",
            owner = User(login = "developer_android"),
            description = "Complete Android GitHub Client with modern file management & ZIP upload capabilities",
            private = false,
            defaultBranch = "main",
            stargazersCount = 342,
            forksCount = 89,
            language = "Kotlin",
            updatedAt = "2 hours ago"
        ),
        Repository(
            id = 2,
            name = "android-mvvm-compose-starter",
            fullName = "developer_android/android-mvvm-compose-starter",
            owner = User(login = "developer_android"),
            description = "Clean Architecture template with Room, Retrofit, Coroutines, and Jetpack Compose",
            private = false,
            defaultBranch = "main",
            stargazersCount = 156,
            forksCount = 42,
            language = "Kotlin",
            updatedAt = "Yesterday"
        ),
        Repository(
            id = 3,
            name = "fast-zip-extractor",
            fullName = "developer_android/fast-zip-extractor",
            owner = User(login = "developer_android"),
            description = "High speed native directory scanner & zip streaming utility for Android",
            private = true,
            defaultBranch = "main",
            stargazersCount = 45,
            forksCount = 12,
            language = "Java",
            updatedAt = "3 days ago"
        ),
        Repository(
            id = 4,
            name = "github-actions-android-ci",
            fullName = "developer_android/github-actions-android-ci",
            owner = User(login = "developer_android"),
            description = "Reusable GitHub Actions workflows for Android build, linting, and Robolectric testing",
            private = false,
            defaultBranch = "main",
            stargazersCount = 88,
            forksCount = 19,
            language = "YAML",
            updatedAt = "1 week ago"
        )
    )
}

fun getSampleCommits(): List<Commit> {
    return listOf(
        Commit(
            sha = "7f8b92a",
            commit = CommitDetail(
                message = "Add ZIP upload progress dialog and directory tree preservation",
                author = CommitUser(name = "FastGit Dev", date = "10 mins ago")
            )
        ),
        Commit(
            sha = "3e2a10c",
            commit = CommitDetail(
                message = "Refactor Room caching layer and Retrofit API interceptors",
                author = CommitUser(name = "FastGit Dev", date = "2 hours ago")
            )
        ),
        Commit(
            sha = "9c01d4f",
            commit = CommitDetail(
                message = "Implement syntax highlighting for Kotlin, Java, and XML files",
                author = CommitUser(name = "FastGit Dev", date = "Yesterday")
            )
        )
    )
}

fun getSampleNotifications(): List<Notification> {
    return listOf(
        Notification(
            id = "1",
            repository = Repository(name = "FastGit-Android", fullName = "developer_android/FastGit-Android"),
            subject = NotificationSubject(title = "Pull Request #14: Optimize ZIP extraction speed", type = "PullRequest"),
            reason = "author",
            unread = true,
            updatedAt = "15 mins ago"
        ),
        Notification(
            id = "2",
            repository = Repository(name = "android-mvvm-compose-starter", fullName = "developer_android/android-mvvm-compose-starter"),
            subject = NotificationSubject(title = "Issue #8: Add Room DB flow unit tests", type = "Issue"),
            reason = "assignee",
            unread = true,
            updatedAt = "1 hour ago"
        ),
        Notification(
            id = "3",
            repository = Repository(name = "github-actions-android-ci", fullName = "developer_android/github-actions-android-ci"),
            subject = NotificationSubject(title = "Workflow Run #102 succeeded on main", type = "Workflow"),
            reason = "subscribed",
            unread = false,
            updatedAt = "Yesterday"
        )
    )
}