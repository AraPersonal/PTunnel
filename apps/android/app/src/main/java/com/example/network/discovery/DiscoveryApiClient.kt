package com.example.network.discovery

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

interface DiscoveryApi {
    @GET
    suspend fun getOperators(
        @Url url: String,
        @Header("Authorization") token: String,
        @Header("If-None-Match") ifNoneMatch: String? = null
    ): Response<OperatorsResponseDto>

    @POST
    suspend fun observeOperator(
        @Url url: String,
        @Header("Authorization") token: String,
        @Body request: ObserveRequestDto
    ): Response<ObserveResponseDto>

    @GET
    suspend fun getPresets(
        @Url url: String,
        @Header("Authorization") token: String,
        @Query("operator_key") operatorKey: String?,
        @Query("operator_tag") operatorTag: String?,
        @Query("network_key") networkKey: String?,
        @Query("network_type") networkType: String?
    ): Response<PresetsResponseDto>

    @POST
    suspend fun reportPreset(
        @Url url: String,
        @Header("Authorization") token: String,
        @Body report: PresetReportDto
    ): Response<PresetReportResponseDto>
}

class DiscoveryApiClient(
    private val client: OkHttpClient = createProtectedOkHttpClient()
) {
    companion object {
        private const val TAG = "DiscoveryApiClient"
    }

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val api: DiscoveryApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://localhost/") // Base URL overridden per request via @Url
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DiscoveryApi::class.java)
    }

    private var cachedOperatorsEtag: String? = null

    suspend fun fetchOperators(
        token: String,
        baseUrl: String
    ): Result<OperatorsResponseDto?> = withContext(Dispatchers.IO) {
        try {
            val url = cleanUrl(baseUrl, "operators")
            val resp = api.getOperators(url, "Bearer $token", cachedOperatorsEtag)
            if (resp.code() == 304) {
                Log.d(TAG, "Operators unchanged (304 Not Modified)")
                return@withContext Result.success(null)
            }
            if (resp.isSuccessful) {
                cachedOperatorsEtag = resp.headers()["ETag"]
                val body = resp.body()
                if (body != null) {
                    DomesticSplitTunnel.updateDomesticRules(body.domestic.suffixes)
                }
                Result.success(body)
            } else {
                Result.failure(IllegalStateException("HTTP ${resp.code()} fetching operators"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun observeOperator(
        token: String,
        baseUrl: String,
        request: ObserveRequestDto
    ): Result<ObserveResponseDto> = withContext(Dispatchers.IO) {
        try {
            val url = cleanUrl(baseUrl, "operators/observe")
            val resp = api.observeOperator(url, "Bearer $token", request)
            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!)
            } else {
                Result.failure(IllegalStateException("HTTP ${resp.code()} registering operator"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun fetchPresets(
        token: String,
        baseUrl: String,
        operatorKey: String?,
        operatorTag: String?,
        networkKey: String?,
        networkType: String?
    ): Result<PresetsResponseDto> = withContext(Dispatchers.IO) {
        try {
            val url = cleanUrl(baseUrl, "presets")
            val resp = api.getPresets(url, "Bearer $token", operatorKey, operatorTag, networkKey, networkType)
            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!)
            } else {
                Result.failure(IllegalStateException("HTTP ${resp.code()} fetching presets"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun reportPreset(
        token: String,
        baseUrl: String,
        report: PresetReportDto
    ): Result<PresetReportResponseDto> = withContext(Dispatchers.IO) {
        try {
            val url = cleanUrl(baseUrl, "presets/report")
            val resp = api.reportPreset(url, "Bearer $token", report)
            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!)
            } else {
                Result.failure(IllegalStateException("HTTP ${resp.code()} reporting preset"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun cleanUrl(baseUrl: String, endpoint: String): String {
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return "$base$endpoint"
    }
}
