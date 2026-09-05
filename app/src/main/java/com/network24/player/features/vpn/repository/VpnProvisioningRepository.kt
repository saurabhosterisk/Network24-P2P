package com.network24.player.features.vpn.repository

import com.network24.player.common.models.VpnProvisionRequest
import com.network24.player.common.models.VpnProvisionResponse
import com.network24.player.core.api.ApiClient
import retrofit2.Response

class VpnProvisioningRepository {

    suspend fun provision(
        server: String,
        username: String,
        password: String,
        publicKey: String
    ): Response<VpnProvisionResponse> {

        val baseUrl = server.trim().trimEnd('/') + "/"

        return ApiClient.get(baseUrl)
            .provisionVpn(VpnProvisionRequest(username, password, publicKey))
    }
}
