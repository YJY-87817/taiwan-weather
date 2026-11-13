package tw.personal.cwaweather.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.QueryMap

interface CwaApi {
    @GET("v1/rest/datastore/{dataset}")
    suspend fun datastore(
        @Path("dataset") dataset: String,
        @QueryMap query: Map<String, String> = emptyMap(),
    ): JsonObject

    companion object {
        private const val BASE_URL = "https://opendata.cwa.gov.tw/api/"

        /** Every [create] builds on this one, so they share its connections and threads. */
        private val sharedClient by lazy { OkHttpClient() }

        /** [apiKey] is read on every request, so a changed key applies immediately. */
        fun create(baseUrl: String = BASE_URL, apiKey: () -> String): CwaApi {
            // The key is added here so it stays out of call sites. Do not add URL logging to this client.
            val client = sharedClient.newBuilder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val url = request.url.newBuilder()
                        .addQueryParameter("Authorization", apiKey())
                        .build()
                    chain.proceed(request.newBuilder().url(url).build())
                }
                .build()

            val json = Json { ignoreUnknownKeys = true }
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(CwaApi::class.java)
        }
    }
}
