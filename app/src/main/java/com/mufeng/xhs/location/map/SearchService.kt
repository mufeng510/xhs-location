package com.mufeng.xhs.location.map

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import javax.inject.Inject

data class NominatimPlace(
    val place_id: Long = 0,
    val lat: String = "",
    val lon: String = "",
    val display_name: String = "",
    val `type`: String = "",
    val classType: String = ""
) {
    fun toLatLon(): Pair<Double, Double> =
        Pair(lat.toDoubleOrNull() ?: 0.0, lon.toDoubleOrNull() ?: 0.0)
}

interface NominatimApi {
    @GET("search")
    suspend fun search(
        @Query("q") q: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 5,
        @Query("addressdetails") addressdetails: Int = 1,
        @Query("accept-language") acceptLanguage: String = "zh-CN,zh"
    ): Response<List<NominatimPlace>>
}

/** Nominatim 搜索服务：将查询转换为候选地点，处理网络异常。 */
open class SearchService @Inject constructor(
    private val api: NominatimApi
) {

    suspend fun searchPlaces(query: String): SearchResult {
        return try {
            val response = api.search(query)
            if (response.isSuccessful) {
                val places = response.body() ?: emptyList()
                SearchResult.Success(places)
            } else {
                SearchResult.Error("服务异常：HTTP 请求失败")
            }
        } catch (e: java.net.SocketTimeoutException) {
            SearchResult.Error("请求超时，请稍后重试")
        } catch (e: java.net.UnknownHostException) {
            SearchResult.Error("无法连接到搜索服务，请检查网络")
        } catch (e: Exception) {
            SearchResult.Error("搜索失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    sealed class SearchResult {
        data class Success(val places: List<NominatimPlace>) : SearchResult()
        data class Error(val message: String) : SearchResult()
        object Loading : SearchResult()
    }
}

/** 默认搜索服务实现。 */
class DefaultSearchService(
    api: NominatimApi
) : SearchService(api)
