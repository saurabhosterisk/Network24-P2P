package com.network24.player.core.api

import com.network24.player.common.models.SupportChannelsResponse
import com.network24.player.common.models.SupportMessagesResponse
import com.network24.player.common.models.SupportSendResponse
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
}
