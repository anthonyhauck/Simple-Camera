package com.simplecamera

import android.content.Context
import android.net.Uri
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GooglePhotosUploader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Returns true if successfully uploaded to Google Photos
    suspend fun upload(context: Context, account: GoogleSignInAccount, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val token = GoogleAuthUtil.getToken(
                    context,
                    account.account ?: return@withContext false,
                    "oauth2:${MainActivity.PHOTOS_SCOPE}"
                )
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext false
                val uploadToken = uploadBytes(token, bytes) ?: return@withContext false
                createMediaItem(token, uploadToken)
            } catch (e: Exception) {
                false
            }
        }

    private fun uploadBytes(accessToken: String, bytes: ByteArray): String? {
        val request = Request.Builder()
            .url("https://photoslibrary.googleapis.com/v1/uploads")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-type", "application/octet-stream")
            .addHeader("X-Goog-Upload-Content-Type", "image/jpeg")
            .addHeader("X-Goog-Upload-Protocol", "raw")
            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()

        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string()?.trim() else null
        }
    }

    private fun createMediaItem(accessToken: String, uploadToken: String): Boolean {
        val body = JSONObject().apply {
            put("newMediaItems", JSONArray().apply {
                put(JSONObject().apply {
                    put("simpleMediaItem", JSONObject().apply {
                        put("uploadToken", uploadToken)
                    })
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("https://photoslibrary.googleapis.com/v1/mediaItems:batchCreate")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return client.newCall(request).execute().use { it.isSuccessful }
    }
}
