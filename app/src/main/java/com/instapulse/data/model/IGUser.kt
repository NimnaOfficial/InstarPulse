package com.instapulse.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class IGUser(
    val pk: String,
    val username: String,
    val fullName: String = "",
    val profilePicUrl: String = "",
    val isVerified: Boolean = false,
    val isPrivate: Boolean = false,
    val lastAction: String? = null, // "unfollowed", "followed", "skipped_unavailable"
    val actionTimestamp: Long? = null
)

data class ActionQueueItem(
    val pk: String? = null,
    val username: String,
    val action: ActionType
)

enum class ActionType {
    UNFOLLOW,
    FOLLOW
}

enum class TabCategory {
    DONT_FOLLOW_BACK,
    FANS,
    RECENTS,
    MUTUALS,
    WHITELISTED
}

enum class SortOrder {
    DEFAULT,
    AZ,
    ZA,
    AGE_NEW,
    AGE_OLD
}

enum class SubFilter {
    ALL,
    VERIFIED,
    PRIVATE,
    PUBLIC
}
