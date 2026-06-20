package com.example.api

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: Content? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null
)

@JsonClass(generateAdapter = true)
data class InlineData(
    val mimeType: String,
    val data: String
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    val responseMimeType: String? = null,
    val temperature: Float? = null
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    val candidates: List<Candidate>?
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val content: Content?
)

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val service: GeminiApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        retrofit.create(GeminiApiService::class.java)
    }
}

// Result classes for Parsing
@JsonClass(generateAdapter = true)
data class SuggestionResult(
    val name: String,
    val price: Long
)

@JsonClass(generateAdapter = true)
data class LookupMatchResult(
    val found: Boolean,
    val id: Int? = null,
    val matchPercentage: Int? = null,
    val reason: String,
    val suggestedName: String? = null,
    val suggestedPrice: Long? = null
)

object GeminiClient {
    private const val TAG = "GeminiClient"

    private fun Bitmap.toBase64(): String {
        val outputStream = ByteArrayOutputStream()
        // Resize to maximum 800px width/height to save bandwidth and stay fast
        val maxSize = 800
        val width = this.width
        val height = this.height
        val (newWidth, newHeight) = if (width > height) {
            if (width > maxSize) {
                val ratio = maxSize.toFloat() / width
                Pair(maxSize, (height * ratio).toInt())
            } else Pair(width, height)
        } else {
            if (height > maxSize) {
                val ratio = maxSize.toFloat() / height
                Pair((width * ratio).toInt(), maxSize)
            } else Pair(width, height)
        }
        val scaledBitmap = Bitmap.createScaledBitmap(this, newWidth, newHeight, true)
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun extractJsonString(raw: String): String {
        val startIndex = raw.indexOf("{")
        val endIndex = raw.lastIndexOf("}")
        if (startIndex in 0 until endIndex) {
            return raw.substring(startIndex, endIndex + 1)
        }
        return raw
    }

    suspend fun getProductSuggestion(bitmap: Bitmap): SuggestionResult? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "API Key is missing!")
            return@withContext null
        }

        val promptText = """
            Bạn là trợ lý cho cửa hàng tạp hóa 'Tập hóa Cô 7' ở Việt Nam. Sản phẩm trong ảnh này là gì?
            Hãy nhận diện thật kỹ và đề xuất tên sản phẩm và giá bán lẻ thông thường bằng VND phù hợp tại thị trường Việt Nam.
            Trả về CHỈ một chuỗi JSON hợp lệ chứa các trường sau:
            {
              "name": "Tên sản phẩm trực quan, rõ ràng kết hợp thương hiệu (ví dụ: 'Nước ngọt Coca-Cola Lon 320ml')",
              "price": giá_gợi_ý_bán_lẻ_ở_VN (số nguyên, ví dụ: 10000)
            }
            Chú ý: Trả về ĐÚNG JSON, không bao bọc bởi tag markdown hay bất kỳ chữ gì khác ngoài định dạng JSON trên.
        """.trimIndent()

        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = promptText),
                        Part(inlineData = InlineData(mimeType = "image/jpeg", data = bitmap.toBase64()))
                    )
                )
            ),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                temperature = 0.2f
            )
        )

        try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            Log.d(TAG, "Suggestion Response: $responseText")
            if (responseText != null) {
                val cleanedJson = extractJsonString(responseText)
                val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                val adapter = moshi.adapter(SuggestionResult::class.java)
                return@withContext adapter.fromJson(cleanedJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching product suggestion: ", e)
        }
        return@withContext null
    }

    suspend fun lookupProductPrice(bitmap: Bitmap, productListText: String): LookupMatchResult? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "API Key is missing!")
            return@withContext null
        }

        val promptText = """
            Bạn là máy quét nhận diện và tra cứu giá sản phẩm cho cửa hàng 'Tạp hóa Cô 7' ở Việt Nam.
            Hình ảnh đính kèm là sản phẩm khách hàng đang quét để tra cứu giá.
            
            Đây là danh mục toàn bộ sản phẩm đang có trong cơ sở dữ liệu của cửa hàng dưới dạng: [id] Tên sản phẩm - Giá VND:
            $productListText
            
            Hãy đối chiếu sản phẩm trong hình ảnh với danh mục trên một cách cẩn thận (về thương hiệu, bao bì, thể tích, v.v.):
            
            Trường hợp 1: Nếu tìm ĐƯỢC sản phẩm khớp nhất trong danh sách (kể cả khác bao bì một xíu nhưng rõ ràng là cùng một loại sản phẩm):
            Hãy trả về một chuỗi JSON định dạng:
            {
              "found": true,
              "id": id_sản_phẩm_đã_khớp,
              "matchPercentage": phần_trăm_độ_tự_tin (số nguyên từ 50-100),
              "reason": "Giải thích ngắn gọn lý do tại sao khớp bằng Tiếng Việt (ví dụ: 'Chính xác là Lon Redbull Thái 250ml trong danh sách')"
            }
            
            Trường hợp 2: Nếu CHẮC CHẮN sản phẩm KHÔNG có trong danh sách trên:
            Hãy trả về một chuỗi JSON định dạng:
            {
              "found": false,
              "suggestedName": "Tên sản phẩm bạn nhận diện được (ví dụ: 'Mì Kokomi Đại 90 Tôm Chua Cay')",
              "suggestedPrice": giá_bán_lẻ_gợi_ý_ở_VN (số nguyên VND, ví dụ: 4500),
              "reason": "Giải thích lý do không có và mô tả sản phẩm nhận diện được bằng Tiếng Việt (ví dụ: 'Đây là Mì Kokomi Đại, cơ sở dữ liệu mới chỉ có Mì Hảo Hảo')"
            }
            
            Chú ý: Trả về ĐÚNG JSON, không bao bọc bởi tag markdown hay bất kỳ chữ gì khác ngoài định dạng JSON trên.
        """.trimIndent()

        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = promptText),
                        Part(inlineData = InlineData(mimeType = "image/jpeg", data = bitmap.toBase64()))
                    )
                )
            ),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                temperature = 0.2f
            )
        )

        try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            Log.d(TAG, "Lookup Response: $responseText")
            if (responseText != null) {
                val cleanedJson = extractJsonString(responseText)
                val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                val adapter = moshi.adapter(LookupMatchResult::class.java)
                return@withContext adapter.fromJson(cleanedJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up product price: ", e)
        }
        return@withContext null
    }
}
