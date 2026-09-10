package com.network24.player.common.models

data class UserInfo(

    val username: String?,
    val password: String?,
    val message: String?,
    val auth: Int?,
    val status: String?,
    val exp_date: String?,
    val is_trial: String?,
    val active_cons: String?,
    val created_at: String?,
    val max_connections: String?,
    val allowed_output_formats: List<String>?,
    // "1" grants persistent Secure Relay: once the user turns it on, it
    // stays on across app backgrounding instead of the default per-session
    // behavior (torn down the moment the app leaves the foreground).
    val vpn_access: String?

)