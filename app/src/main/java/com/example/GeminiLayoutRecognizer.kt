package com.example

import android.graphics.Bitmap
import android.util.Base64
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
    val responseFormat: ResponseFormat? = null,
    val temperature: Float? = null
)

@JsonClass(generateAdapter = true)
data class ResponseFormat(
    val type: String = "json_object"
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val content: Content? = null
)

interface GeminiApiService {
    @POST("v1beta/models/gemini-2.5-flash:generateContent")
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

    val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    val service: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }
}

object GeminiLayoutRecognizer {

    private fun Bitmap.toBase64(): String {
        val outputStream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    suspend fun recognizeLayout(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Błąd: Klucz API jest niepoprawny lub pusty."
        }
        
        val prompt = """
            Przeanalizuj poniższy zrzut ekranu przedstawiający klawiaturę ekranową.
            Zwróć strukturę w formacie JSON opisującą układ klawiatury.
            Skup się na:
            - klawiszach głównych i ich położeniu,
            - znakach alternatywnych po długim przytrzymaniu (jeśli są widoczne lub logicznie wynikają z układu),
            - stanie kontrolek (np. shift, spacja, enter, usuń).
            Zignoruj obiekty poza klawiaturą, w szczególności pasek wyszukiwania, wyniki i inne elementy UI systemu lub aplikacji.
            W przypadku zasłoniętych klawiszy, na podstawie języka (np. polski na spacji - QWERTY) zinterpoluj i zrekonstruuj zasłonięte klawisze.
            Zwróć JSON z kluczem 'rows', w którym każdy wiersz to lista klawiszy (główny znak, alternatywne znaki, typ specjalny np. SPACE, BACKSPACE).
        """.trimIndent()
        
        val request = GenerateContentRequest(
            contents = listOf(Content(
                parts = listOf(
                    Part(text = prompt),
                    Part(inlineData = InlineData(mimeType = "image/jpeg", data = bitmap.toBase64()))
                )
            )),
            generationConfig = GenerationConfig(
                temperature = 0.2f
            )
        )
        
        try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
        } catch (e: Exception) {
            "Błąd połączenia z API: ${e.message}"
        }
    }
}
