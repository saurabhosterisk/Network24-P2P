package com.network24.player.common.models

data class VpnProvisionRequest(
    val username: String,
    val password: String,
    val public_key: String
)

data class VpnProvisionResponse(
    val assigned_ip: String?,
    val server_public_key: String?,
    val endpoint: String?,
    val allowed_ips: List<String>?
)
