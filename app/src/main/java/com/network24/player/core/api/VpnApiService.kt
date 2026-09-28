package com.network24.player.core.api

import com.network24.player.common.models.VpnProvisionResponse
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * vpn_api.php - on Main, or the backup copy on network24.biz that forwards
 * to Main when a customer's ISP blocks it. POST (not GET) so the login
 * never ends up in a URL or a server access log; vpn_api.php reads both.
 */
interface VpnApiService {

    @FormUrlEncoded
    @POST("vpn_api.php")
    suspend fun requestPeer(
        @Field("action") action: String = "request_peer",
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("public_key") publicKey: String
    ): Response<VpnProvisionResponse>

    @FormUrlEncoded
    @POST("vpn_api.php")
    suspend fun rotatePeer(
        @Field("action") action: String = "rotate_peer",
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("public_key") publicKey: String
    ): Response<VpnProvisionResponse>

    @FormUrlEncoded
    @POST("vpn_api.php")
    suspend fun releasePeer(
        @Field("action") action: String = "release_peer",
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("public_key") publicKey: String
    ): Response<VpnProvisionResponse>
}
