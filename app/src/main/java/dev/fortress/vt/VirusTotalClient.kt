package dev.fortress.vt

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * VirusTotal v3 client for user-selected artifacts.
 *
 * PRIVACY / ETHICS RULES baked into the flow:
 *  1. Hash-first: [lookupByHash] is always tried before any upload — if the
 *     sample is already known we NEVER transmit the bytes.
 *  2. Upload requires an explicit user consent flag AND an API key the user
 *     stored themselves (kept in EncryptedSharedPreferences; also why the
 *     manifest sets allowBackup=false).
 *  3. Only files the operator picked through the file picker are scannable;
 *     there is no directory-walking uploader here.
 *
 * RATE LIMITING: VT's free tier allows 4 requests/minute; exceeding it returns
 * HTTP 429. acquireSlot() spaces requests on a rolling 60s window.
 */
class VirusTotalClient(context: Context) {

    data class VtReport(
        val id: String,          // analysis id or file sha256, depending on source
        val sha256: String,
        val malicious: Int,
        val suspicious: Int,
        val undetected: Int,
        val permalink: String?,
        val pending: Boolean,
    ) {
        val detectionRatio: String get() = "$malicious/${malicious + suspicious + undetected}"
    }

    private val prefs by lazy {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "fortress_secure", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val rateMutex = Mutex()
    private val requestTimes = ArrayDeque<Long>()

    // -- key management ---------------------------------------------------------

    fun setApiKey(key: String) {
        prefs.edit().putString(KEY_VT, key.trim()).apply()
    }

    fun hasKey(): Boolean = !apiKey().isNullOrBlank()

    private fun apiKey(): String? = prefs.getString(KEY_VT, null)

    // -- rate limiter -------------------------------------------------------------

    /** Rolling 4-per-60s gate; suspends (never drops) until a slot frees up. */
    private suspend fun acquireSlot() = rateMutex.withLock {
        while (requestTimes.size >= MAX_PER_MINUTE) {
            val oldest = requestTimes.first()
            val waitMs = oldest + 60_000 - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            while (requestTimes.isNotEmpty() && requestTimes.first() + 60_000 <= System.currentTimeMillis()) {
                requestTimes.removeFirst()
            }
        }
        requestTimes.addLast(System.currentTimeMillis())
    }

    // -- API calls ---------------------------------------------------------------

    /** GET /api/v3/files/{sha256} — privacy-preserving, no upload happens here. */
    suspend fun lookupByHash(sha256: String): VtReport? = withContext(Dispatchers.IO) {
        val key = apiKey() ?: return@withContext null
        acquireSlot()
        val req = Request.Builder()
            .url("$BASE/files/$sha256")
            .header("x-apikey", key)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null // 404 = unknown sample → caller may offer upload
            val body = resp.body?.string() ?: return@use null
            parseReport(body, sha256, pending = false)
        }
    }

    /**
     * POST /api/v3/files — multipart upload. Caller MUST have obtained the
     * user's explicit consent ([allowUpload]); the guard throws otherwise so a
     * UI regression cannot silently exfiltrate bytes.
     */
    suspend fun scanFile(file: File, allowUpload: Boolean): VtReport = withContext(Dispatchers.IO) {
        check(allowUpload) { "upload requires explicit user consent" }
        val key = apiKey() ?: error("no VirusTotal API key configured")
        val sha = sha256Of(file)

        lookupByHash(sha)?.let { return@withContext it }

        acquireSlot()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", file.name,
                file.asRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        val req = Request.Builder()
            .url("$BASE/files")
            .header("x-apikey", key)
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("upload failed: HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            val id = json.optJSONObject("data")?.optString("id") ?: sha
            // The scan is async server-side; report as pending, the console
            // re-polls lookupByHash(sha) after ~30s.
            VtReport(id, sha, 0, 0, 0, permalink = null, pending = true)
        }
    }

    // -- helpers -------------------------------------------------------------

    private fun parseReport(raw: String, sha: String, pending: Boolean): VtReport? = try {
        val data = JSONObject(raw).getJSONObject("data")
        val attrs = data.getJSONObject("attributes")
        val stats = attrs.getJSONObject("last_analysis_stats")
        VtReport(
            id = data.optString("id", sha),
            sha256 = attrs.optString("sha256", sha),
            malicious = stats.optInt("malicious", 0),
            suspicious = stats.optInt("suspicious", 0),
            undetected = stats.optInt("undetected", 0),
            permalink = attrs.optString("permalink", null),
            pending = pending,
        )
    } catch (e: Exception) {
        null
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val BASE = "https://www.virustotal.com/api/v3"
        private const val KEY_VT = "vt_api_key"
        private const val MAX_PER_MINUTE = 4
    }
}
