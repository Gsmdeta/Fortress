package dev.fortress.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One finding row inside a persisted scan session. */
data class FindingEntry(val severity: String, val title: String, val detail: String)

/**
 * A finished deep scan — the Android counterpart of the web console's
 * DemoScanSession. Persisted to filesDir/sessions.json (most recent first).
 */
data class ScanSession(
    val id: String,
    val label: String,
    val startedAt: Long,
    val finishedAt: Long,
    val score: Int,
    val verdict: String,
    val findings: List<FindingEntry>,
)

/** Posture history sample (mirrors the web PosturePoint model). */
data class PosturePointData(val score: Int, val label: String, val ts: Long)

/**
 * File-backed JSON store for scan sessions + posture history. Deliberately
 * plain org.json (no Room/kapt) so the build stays dependency-light; the
 * datasets are tiny (capped), so file rewrites are cheap.
 */
class SessionStore(context: Context) {

    private val appContext = context.applicationContext
    private val sessionsFile: File get() = File(appContext.filesDir, "sessions.json")
    private val postureFile: File get() = File(appContext.filesDir, "posture.json")

    @Synchronized
    fun sessions(): List<ScanSession> =
        readArray(sessionsFile).map { o ->
            ScanSession(
                id = o.optString("id"),
                label = o.optString("label"),
                startedAt = o.optLong("startedAt"),
                finishedAt = o.optLong("finishedAt"),
                score = o.optInt("score"),
                verdict = o.optString("verdict"),
                findings = o.optJSONArray("findings")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val f = arr.getJSONObject(i)
                        FindingEntry(f.optString("severity"), f.optString("title"), f.optString("detail"))
                    }
                } ?: emptyList(),
            )
        }

    @Synchronized
    fun saveSession(session: ScanSession) {
        val all = sessions().toMutableList()
        all.add(0, session)
        writeArray(sessionsFile, all.take(MAX_SESSIONS).map { it.toJson() })
    }

    @Synchronized
    fun sessionCount(): Int = readArray(sessionsFile).length()

    @Synchronized
    fun posture(): List<PosturePointData> =
        readArray(postureFile).map { o ->
            PosturePointData(
                score = o.optInt("score"),
                label = o.optString("label"),
                ts = o.optLong("ts"),
            )
        }

    @Synchronized
    fun appendPosture(point: PosturePointData) {
        val all = posture().toMutableList()
        all.add(point)
        writeArray(postureFile, all.takeLast(MAX_POSTURE).map { it.toJson() })
    }

    private fun ScanSession.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        put("score", score)
        put("verdict", verdict)
        put("findings", JSONArray().apply {
            findings.forEach { f ->
                put(JSONObject().apply {
                    put("severity", f.severity)
                    put("title", f.title)
                    put("detail", f.detail)
                })
            }
        })
    }

    private fun PosturePointData.toJson(): JSONObject = JSONObject().apply {
        put("score", score)
        put("label", label)
        put("ts", ts)
    }

    private fun readArray(file: File): JSONArray = try {
        JSONArray(file.readText())
    } catch (e: Exception) {
        JSONArray() // missing/corrupt file — start empty, never crash the console
    }

    private fun writeArray(file: File, objects: List<JSONObject>) {
        try {
            file.writeText(JSONArray(objects).toString())
        } catch (e: Exception) {
            // storage hiccup — persistence is best-effort, the console keeps working
        }
    }

    private companion object {
        const val MAX_SESSIONS = 50
        const val MAX_POSTURE = 60
    }
}
