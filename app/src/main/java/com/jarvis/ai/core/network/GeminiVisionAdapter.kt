package com.jarvis.ai.core.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiVisionAdapter {

    suspend fun generateResponse(
        prompt: String,
        imageBytes: ByteArray?,
        apiKey: String
    ): String = withContext(Dispatchers.IO) {
        val generativeModel = GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = apiKey
        )

        if (imageBytes != null && imageBytes.isNotEmpty()) {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            val inputContent = content {
                image(bitmap)
                text(prompt.ifBlank { "Describe this image in detail and concise words." })
            }
            val response = generativeModel.generateContent(inputContent)
            response.text ?: "No response generated."
        } else {
            val response = generativeModel.generateContent(prompt)
            response.text ?: "No response generated."
        }
    }
}
