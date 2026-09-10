package com.network24.player.core.api

import com.network24.player.common.models.VpnProvisionResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface VpnApiService {

    @GET("vpn_api.php")
    suspend fun requestPeer(
        @Query("action") action: String = "request_peer",
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("public_key") publicKey: String
    ): Response<VpnProvisionResponse>

    @GET("vpn_api.php")
    suspend fun rotatePeer(
        @Query("action") action: String = "rotate_peer",
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("public_key") publicKey: String
    ): Response<VpnProvisionResponse>

    @GET("vpn_api.php")
    suspend fun releasePeer(
        @Query("action") action: String = "release_peer",
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("public_key") publicKey: String
    ): Response<VpnProvisionResponse>
}
