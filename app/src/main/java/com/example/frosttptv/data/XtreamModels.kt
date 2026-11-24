package com.example.frosttptv.data

import com.google.gson.annotations.SerializedName

data class AuthResponse(
    @SerializedName("user_info") val userInfo: UserInfo?,
    @SerializedName("server_info") val serverInfo: ServerInfo?
)

data class UserInfo(
    val username: String?,
    val status: String?,
    @SerializedName("exp_date") val expDate: String?,
    @SerializedName("active_cons") val activeCons: String?,
    @SerializedName("max_connections") val maxConnections: String?
)

data class ServerInfo(
    val url: String?,
    val port: String?
)

data class LiveCategory(
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("category_name") val categoryName: String
)

data class LiveStream(
    @SerializedName("stream_id") val streamId: Int,
    val name: String,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("container_extension") val containerExtension: String? = null,
    val mediaType: String = "LIVE", // "LIVE", "VOD", "SERIES"
    val seriesId: Int? = null
)

data class XtreamVodStream(
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("name") val name: String,
    @SerializedName("stream_icon") val streamIcon: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("container_extension") val containerExtension: String? = null
)

data class XtreamSeriesItem(
    @SerializedName("series_id") val seriesId: Int,
    @SerializedName("name") val name: String,
    @SerializedName("cover") val cover: String? = null,
    @SerializedName("category_id") val categoryId: String? = null
)

data class XtreamSeriesInfoResponse(
    @SerializedName("episodes") val episodesBySeason: Map<String, List<XtreamEpisodeItem>>? = null
)

data class XtreamEpisodeItem(
    @SerializedName("id") val id: String? = null,
    @SerializedName("episode_num") val episodeNum: Int? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("season") val season: Int? = null,
    @SerializedName("container_extension") val containerExtension: String? = null
)
