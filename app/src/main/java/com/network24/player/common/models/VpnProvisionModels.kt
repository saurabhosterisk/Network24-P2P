package com.network24.player.common.models

import com.google.gson.annotations.SerializedName

data class VpnProvisionResponse(
    val result: Boolean,
    val error: String? = null,
    val message: String? = null,
    @SerializedName("server_public_key") val serverPublicKey: String? = null,
    val endpoint: String? = null,
    @SerializedName("assigned_ip") val assignedIp: String? = null,
    @SerializedName("allowed_ips") val allowedIps: String? = null,
    val dns: String? = null
)
