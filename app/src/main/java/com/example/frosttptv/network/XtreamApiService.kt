package com.example.frosttptv.network

import com.example.frosttptv.data.AuthResponse
import com.example.frosttptv.data.LiveCategory
import com.example.frosttptv.data.LiveStream
import com.example.frosttptv.data.XtreamSeriesInfoResponse
import com.example.frosttptv.data.XtreamSeriesItem
import com.example.frosttptv.data.XtreamVodStream
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

interface XtreamApiService {

    @GET("player_api.php")
    suspend fun authenticate(
        @Query("username") username: String,
        @Query("password") password: String
    ): AuthResponse

    @GET("player_api.php?action=get_live_categories")
    suspend fun getLiveCategories(
        @Query("username") username: String,
        @Query("password") password: String
    ): List<LiveCategory>

    @GET("player_api.php?action=get_live_streams")
    suspend fun getLiveStreams(
        @Query("username") username: String,
        @Query("password") password: String
    ): List<LiveStream>

    @GET("player_api.php?action=get_vod_categories")
    suspend fun getVodCategories(
        @Query("username") username: String,
        @Query("password") password: String
    ): List<LiveCategory>

    @GET("player_api.php?action=get_vod_streams")
    suspend fun getVodStreams(
        @Query("username") username: String,
        @Query("password") password: String
    ): List<XtreamVodStream>

    @GET("player_api.php?action=get_series_categories")
    suspend fun getSeriesCategories(
        @Query("username") username: String,
        @Query("password") password: String
    ): List<LiveCategory>

    @GET("player_api.php?action=get_series")
    suspend fun getSeries(
        @Query("username") username: String,
        @Query("password") password: String
    ): List<XtreamSeriesItem>

    @GET("player_api.php?action=get_series_info")
    suspend fun getSeriesInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("series_id") seriesId: Int
    ): XtreamSeriesInfoResponse

    @GET
    @Streaming
    suspend fun downloadPlaylist(@Url url: String): ResponseBody

    companion object {
        fun create(baseUrl: String): XtreamApiService {
            val client = OkHttpClient.Builder()
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(XtreamApiService::class.java)
        }
    }
}
