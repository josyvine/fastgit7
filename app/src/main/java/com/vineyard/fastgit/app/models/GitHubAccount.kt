package com.vineyard.fastgit.app.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents a saved GitHub user account session inside FastGit.
 * Holds credentials, profile metadata, and active state to enable
 * seamless switching like the official GitHub Android app.
 */
@JsonClass(generateAdapter = true)
data class GitHubAccount(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "login") val login: String = "",
    @Json(name = "name") val name: String? = null,
    @Json(name = "avatar_url") val avatarUrl: String = "",
    @Json(name = "access_token") val accessToken: String = "",
    @Json(name = "is_active") val isActive: Boolean = false,
    @Json(name = "unread_notifications") val unreadNotifications: Int = 0
)