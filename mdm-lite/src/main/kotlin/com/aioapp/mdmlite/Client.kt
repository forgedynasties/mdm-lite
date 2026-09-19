package com.aioapp.mdmlite

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal JSON-over-HTTP client (HttpURLConnection: no dependency for the host to carry). */
internal class Client(serverUrl: String) {
    val base = serverUrl.trimEnd('/')

    enum class Result { OK, UNAUTHORIZED, FAILED }

    /** POST with the device key. [body] of the reply is the parsed JSON on success. */
    class Reply(val result: Result, val body: JSONObject? = null)

    fun post(path: String, body: JSONObject, deviceKey: String): Reply {
        val conn = open(path, deviceKey) ?: return Reply(Result.FAILED)
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            when (val code = conn.responseCode) {
                in 200..299 -> {
                    val text = conn.inputStream.use { it.readBytes().decodeToString() }
                    Reply(Result.OK, runCatching { JSONObject(text) }.getOrNull())
                }
                401 -> Reply(Result.UNAUTHORIZED)
                else -> { Log.w(TAG, "$path: HTTP $code"); Reply(Result.FAILED) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$path: ${e.message}")
            Reply(Result.FAILED)
        } finally {
            conn.disconnect()
        }
    }

    /** Unauthenticated POST that returns the response body (enrollment). */
    fun postForJson(path: String, body: JSONObject): JSONObject? {
        val conn = open(path, null) ?: return null
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "$path: HTTP ${conn.responseCode}")
                return null
            }
            JSONObject(conn.inputStream.use { it.readBytes().decodeToString() })
        } catch (e: Exception) {
            Log.w(TAG, "$path: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String, deviceKey: String?): HttpURLConnection? = runCatching {
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            if (!deviceKey.isNullOrEmpty()) setRequestProperty("X-API-Key", deviceKey)
        }
    }.getOrNull()

    companion object { private const val TAG = "AioMdm" }
}
