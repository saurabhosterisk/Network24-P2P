package com.network24.player.core.api

import com.network24.player.common.models.AiAskResponse
import com.network24.player.common.models.AiPollResponse
import com.network24.player.common.models.SupportChannelsResponse
import com.network24.player.common.models.SupportMessagesResponse
import com.network24.player.common.models.SupportSendResponse
import com.network24.player.common.models.WebLockResponse
import com.network24.player.common.models.WebOkResponse
import com.network24.player.common.models.WebStateResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/** Main Server's support_api.php - the in-app Live Support bridge to Discord. */
interface SupportApiService {

    @FormUrlEncoded
    @POST("support_api.php")
    suspend fun channels(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("action") action: String = "channels"
    ): Response<SupportChannelsResponse>

    @FormUrlEncoded
    @POST("support_api.php")
    suspend fun messages(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("channel") channel: String,
        @Field("action") action: String = "messages"
    ): Response<SupportMessagesResponse>

    @FormUrlEncoded
    @POST("support_api.php")
    suspend fun older(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("channel") channel: String,
        @Field("before") before: String,
        @Field("action") action: String = "older"
    ): Response<SupportMessagesResponse>

    @FormUrlEncoded
    @POST("support_api.php")
    suspend fun aiPoll(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("after") after: Int,
        @Field("action") action: String = "ai_poll"
    ): Response<AiPollResponse>

    @FormUrlEncoded
    @POST("support_api.php")
    suspend fun aiClear(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("action") action: String = "ai_clear"
    ): Response<AiAskResponse>

    @FormUrlEncoded
    @POST("support_api.php")
    suspend fun aiAsk(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("text") text: String,
        @Field("quick") quick: String,
        @Field("stream_id") streamId: Int,
        @Field("stream_name") streamName: String,
        @Field("account") account: String,
        @Field("action") action: String = "ai_ask"
    ): Response<AiAskResponse>

    @Multipart
    @POST("support_api.php")
    suspend fun send(
        @Part("username") username: RequestBody,
        @Part("password") password: RequestBody,
        @Part("action") action: RequestBody,
        @Part("channel") channel: RequestBody,
        @Part("text") text: RequestBody,
        @Part("reply_to") replyTo: RequestBody,
        @Part image: MultipartBody.Part?
    ): Response<SupportSendResponse>
    // ---------------------------------------------------------------- account state shared with the web player
    // Recently watched channels and the parental lock live on Main for the whole account, so the app and
    // play.web24.live show the same list and the same locked categories.

    @FormUrlEncoded
    @POST("support_api.php")
    suspend fun webState(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("action") action: String = "web_state_get"
    ): Response<WebStateResponse>

    @FormUrlEncoded
    @POST("support_api.php")
    suspend fun recentAdd(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("id") id: String,
        @Field("action") action: String = "recent_add"
    ): Response<WebOkResponse>

    @FormUrlEncoded
    @POST("support_api.php")
    suspend fun recentClear(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("action") action: String = "recent_clear"
    ): Response<WebOkResponse>

    @FormUrlEncoded
    @POST("support_api.php")
    suspend fun recentRemove(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("id") id: String,
        @Field("action") action: String = "recent_remove"
    ): Response<WebOkResponse>

    @FormUrlEncoded
    @POST("support_api.php")
    suspend fun lockVerify(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("pin") pin: String,
        @Field("action") action: String = "lock_verify"
    ): Response<WebOkResponse>

    @FormUrlEncoded
    @POST("support_api.php")
    suspend fun lockSet(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("pin") pin: String,
        @Field("current_pin") currentPin: String,
        @Field("cats[]") cats: List<String>,
        @Field("action") action: String = "lock_set"
    ): Response<WebLockResponse>

    @FormUrlEncoded
    @POST("support_api.php")
    suspend fun lockOff(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("current_pin") currentPin: String,
        @Field("action") action: String = "lock_off"
    ): Response<WebLockResponse>
}
