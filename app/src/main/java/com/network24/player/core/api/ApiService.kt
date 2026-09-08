package com.network24.player.core.api

import com.network24.player.core.network.DownloadProgressListener
import com.network24.player.features.live.models.LiveCategory
import com.network24.player.features.live.models.LiveChannel
import com.network24.player.common.models.LoginResponse
import com.network24.player.features.live.models.ShortEPGResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Tag

interface ApiService {

    @GET("player_api.php")
    suspend fun login(
        @Query("username") username: String,
        @Query("password") password: String
    ): Response<LoginResponse>

    @GET("player_api.php")
    suspend fun getLiveCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_categories",
        @Tag progress: DownloadProgressListener? = null
    ): Response<List<LiveCategory>>

    @GET("player_api.php")
    suspend fun getLiveStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_streams",
        @Query("category_id") categoryId: String,
        @Tag progress: DownloadProgressListener? = null
    ): Response<List<LiveChannel>>

    @GET("player_api.php")
    suspend fun getShortEPG(

        @Query("username") username: String,

        @Query("password") password: String,

        @Query("action") action: String = "get_short_epg",

        @Query("stream_id") streamId: Int,

        @Query("limit") limit: Int = 2

    ): Response<ShortEPGResponse>

    @GET("xmltv.php")
    @Streaming
    suspend fun getXmlTv(
        @Query("username") username: String,
        @Query("password") password: String
    ): Response<ResponseBody>
}
