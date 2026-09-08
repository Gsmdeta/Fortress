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
 *
 * NOTE: JSONArray access uses index loops (not Kotlin collection extensions) —
 * org.json's JSONArray is a raw Java iterable that K2 will not map over.
 */
class SessionStore(context: Context) {

    private val appContext = context.applicationContext
    private val sessionsFile: File get() = File(appContext.filesDir, "sessions.json")
    private val postureFile: File get() = File(appContext.filesDir, "posture.json")

    @Synchronized
    fun sessions(): List<ScanSession> = readSessions()

    private fun readSessions(): List<ScanSession> {
        val arr = readArray(sessionsFile)
        val out = ArrayList<ScanSession>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val findingsArr = o.optJSONArray("findings")
            val findings = ArrayList<FindingEntry>(if (findingsArr == null) 0 else findingsArr.length())
            if (findingsArr != null) {
                for (j in 0 until findingsArr.length()) {
                    val f = findingsArr.getJSONObject(j)
                    findings.add(
                        FindingEntry(
                            severity = f.optString("severity"),
                            title = f.optString("title"),
                            detail = f.optString("detail"),
                        )
                    )
                }
            }
            out.add(
                ScanSession(
                    id = o.optString("id"),
                    label = o.optString("label"),
                    startedAt = o.optLong("startedAt"),
                    finishedAt = o.optLong("finishedAt"),
                    score = o.optInt("score"),
                    verdict = o.optString("verdict"),
                    findings = findings,
                )
            )
        }
        return out
    }

    @Synchronized
    fun saveSession(session: ScanSession) {
        val all = readSessions().toMutableList()
        all.add(0, session)
        val arr = JSONArray()
        for (s in all.take(MAX_SESSIONS)) arr.put(s.toJson())
        writeArray(sessionsFile, arr)
    }

    @Synchronized
    fun sessionCount(): Int = readArray(sessionsFile).length()

    @Synchronized
    fun posture(): List<PosturePointData> {
        val arr = readArray(postureFile)
        val out = ArrayList<PosturePointData>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                PosturePointData(
                    score = o.optInt("score"),
                    label = o.optString("label"),
                    ts = o.optLong("ts"),
                )
            )
        }
        return out
    }

    @Synchronized
    fun appendPosture(point: PosturePointData) {
        val all = posture().toMutableList()
        all.add(point)
        val arr = JSONArray()
        for (p in all.takeLast(MAX_POSTURE)) arr.put(p.toJson())
        writeArray(postureFile, arr)
    }

    private fun ScanSession.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        put("score", score)
        put("verdict", verdict)
        val findingsArr = JSONArray()
        findings.forEach { f ->
            findingsArr.put(
                JSONObject().apply {
                    put("severity", f.severity)
                    put("title", f.title)
                    put("detail", f.detail)
                }
            )
        }
        put("findings", findingsArr)
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

    private fun writeArray(file: File, arr: JSONArray) {
        try {
            file.writeText(arr.toString())
        } catch (e: Exception) {
            // storage hiccup — persistence is best-effort, the console keeps working
        }
    }

    private companion object {
        const val MAX_SESSIONS = 50
        const val MAX_POSTURE = 60
    }
}
