package com.example.utt_trafficjams.data.repository

import android.content.Context
import com.example.utt_trafficjams.data.model.HazardReport
import com.example.utt_trafficjams.data.model.HazardType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class HazardReportRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "hazard_reports_prefs"
        private const val KEY_REPORTS = "hazard_reports_json"
        private const val MAX_REPORTS = 200
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getReports(): List<HazardReport> {
        val raw = prefs.getString(KEY_REPORTS, null).orEmpty()
        if (raw.isBlank()) return emptyList()

        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val typeRaw = obj.optString("type", "OTHER").uppercase(Locale.US)
                    val type = runCatching { HazardType.valueOf(typeRaw) }.getOrDefault(HazardType.OTHER)
                    val lat = obj.optDouble("lat", Double.NaN)
                    val lng = obj.optDouble("lng", Double.NaN)
                    if (lat.isNaN() || lng.isNaN()) continue

                    add(
                        HazardReport(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            type = type,
                            customIssue = obj.optString("customIssue").ifBlank { null },
                            lat = lat,
                            lng = lng,
                            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun addReport(report: HazardReport) {
        val merged = (listOf(report) + getReports())
            .distinctBy { it.id }
            .take(MAX_REPORTS)
        saveReports(merged)
    }

    private fun saveReports(reports: List<HazardReport>) {
        val arr = JSONArray()
        reports.forEach { report ->
            arr.put(
                JSONObject().apply {
                    put("id", report.id)
                    put("type", report.type.name)
                    put("customIssue", report.customIssue ?: "")
                    put("lat", report.lat)
                    put("lng", report.lng)
                    put("createdAtMs", report.createdAtMs)
                }
            )
        }
        prefs.edit().putString(KEY_REPORTS, arr.toString()).apply()
    }
}
