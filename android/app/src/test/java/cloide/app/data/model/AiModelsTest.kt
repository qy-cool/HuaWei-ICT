package cloide.app.data.model

import cloide.app.data.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI DTO 反序列化测试：JSON 样本取自 OpenAPI `openapi-ai.json` 契约示例。
 */
class AiModelsTest {

    @Test
    fun `diagnosis response decodes from openapi example`() {
        val json = """
            {
              "code": 0,
              "message": "success",
              "data": {
                "summary": "CPU saturation coincided with elevated load.",
                "severity": "warning",
                "findings": [
                  {"title": "Sustained CPU", "description": "CPU above range.", "confidence": "medium"}
                ],
                "evidence": [
                  {"metric": "cpu_percent", "value": 92.5, "observed_at": "2026-08-30T10:00:00Z", "source": "monitoring_summary"}
                ],
                "recommendations": ["Review the workload."],
                "limitations": ["Read-only analysis."],
                "model_used": true,
                "provider": "approved-provider",
                "model": "approved-model",
                "prompt_version": "ai-diagnosis-v1",
                "usage": {
                  "input_tokens": 850, "output_tokens": 220, "total_tokens": 1070,
                  "estimated_cost": 0.0021, "currency": "USD"
                }
              }
            }
        """.trimIndent()

        val response: ApiResponse<AiDiagnosis> = AppJson.decodeFromString(json)
        val diagnosis = response.data
        assertNotNull(diagnosis)
        assertEquals("warning", diagnosis!!.severity)
        assertTrue(diagnosis.modelUsed)
        assertEquals(1, diagnosis.findings.size)
        assertEquals("medium", diagnosis.findings[0].confidence)
        assertEquals(1070, diagnosis.usage?.totalTokens)
        assertEquals("USD", diagnosis.usage?.currency)
    }

    @Test
    fun `evidence value accepts number string and null`() {
        val number: AiEvidence = AppJson.decodeFromString(
            """{"metric":"cpu_percent","value":92.5,"observed_at":"2026-08-30T10:00:00Z","source":"monitoring_summary"}""",
        )
        assertEquals("92.5", number.value?.jsonPrimitive?.content)

        val text: AiEvidence = AppJson.decodeFromString(
            """{"metric":"status","value":"degraded","observed_at":"2026-08-30T10:00:00Z","source":"alert_summary"}""",
        )
        assertEquals("degraded", text.value?.jsonPrimitive?.content)

        val absent: AiEvidence = AppJson.decodeFromString(
            """{"metric":"load","value":null,"observed_at":"2026-08-30T10:00:00Z","source":"monitoring_summary"}""",
        )
        assertNull(absent.value)
    }

    @Test
    fun `qa response decodes with tool calls and degraded flags`() {
        val json = """
            {
              "code": 0, "message": "success",
              "data": {
                "answer": "CPU is at 23%.",
                "tool_calls": [
                  {"tool": "get_latest_metrics", "args": "{\"server_id\":123}"},
                  {"tool": "list_alert_records", "args": "{\"status\":\"unread\"}"}
                ],
                "model_used": false,
                "degraded": true,
                "provider": "openai-compatible",
                "model": "approved-model",
                "prompt_version": "ai-qa-v1",
                "usage": {"input_tokens": 0, "output_tokens": 0, "total_tokens": 0, "estimated_cost": 0, "currency": "USD"}
              }
            }
        """.trimIndent()

        val response: ApiResponse<AiQa> = AppJson.decodeFromString(json)
        val qa = response.data
        assertNotNull(qa)
        assertFalse(qa!!.modelUsed)
        assertTrue(qa.degraded)
        assertEquals(2, qa.toolCalls.size)
        assertEquals("get_latest_metrics", qa.toolCalls[0].tool)
    }

    @Test
    fun `qa request omits null server_id for global questions`() {
        val encoded = AppJson.encodeToString(AiQaRequest(serverId = null, question = "集群状态如何？"))
        assertFalse(encoded.contains("server_id"))

        val targeted = AppJson.encodeToString(AiQaRequest(serverId = 3, question = "CPU 情况？"))
        assertTrue(targeted.contains(""""server_id":3"""))
    }

    @Test
    fun `alert explanation decodes from contract fields`() {
        val json = """
            {
              "record_id": 102,
              "summary": "磁盘写入突增导致 IO 等待。",
              "possible_causes": ["备份任务", "日志暴涨"],
              "impact": ["写入延迟上升"],
              "suggestions": ["检查 crontab"],
              "limitations": ["只读分析，不含可执行指令"],
              "usage": {"input_tokens": 1, "output_tokens": 2, "total_tokens": 3, "estimated_cost": 0.0, "currency": "USD"},
              "provider": "p", "model": "m", "prompt_version": "v1",
              "created_at": "2026-09-05T08:00:00Z"
            }
        """.trimIndent()

        val explanation: AiAlertExplanation = AppJson.decodeFromString(json)
        assertEquals(102L, explanation.recordId)
        assertEquals(2, explanation.possibleCauses.size)
        assertEquals("检查 crontab", explanation.suggestions[0])
    }

    @Test
    fun `health report decodes succeeded with facts snapshot`() {
        val json = """
            {
              "id": 9,
              "report_date": "2026-09-11",
              "status": "succeeded",
              "provider": "openai-compatible",
              "model": "approved-model",
              "prompt_version": "ai-health-report-v1",
              "summary": "整体健康。\n磁盘峰值值得关注。",
              "top_concerns": ["db-7 磁盘峰值 91%"],
              "limitations": ["离线清单为快照口径"],
              "facts": {
                "report_date": "2026-09-11",
                "server_inventory": {"total_count": 3, "online_count": 2, "offline_count": 1, "online_rate": 66.7},
                "metric_peaks": {
                  "avg_cpu_percent": 41.5, "max_cpu_percent": 88.0, "max_cpu_server_id": 7,
                  "avg_memory_percent": null, "max_memory_percent": null, "max_memory_server_id": null,
                  "avg_disk_percent": 60.0, "max_disk_percent": 91.0, "max_disk_server_id": 7
                },
                "alert_statistics": {
                  "total_triggered": 2, "critical_count": 1, "warning_count": 1,
                  "resolved_count": 1, "unresolved_count": 1,
                  "top_servers": [{"server_id": 7, "server_name": "db-7", "alert_count": 2, "critical_count": 1}]
                },
                "offline_servers": [{"server_id": 9, "server_name": "edge-9", "agent_status": "offline", "last_heartbeat_at": null}]
              },
              "error_code": null,
              "usage": {"input_tokens": 20, "output_tokens": 10, "total_tokens": 30, "estimated_cost": 0.0, "currency": "USD"},
              "duration_ms": 1200,
              "created_at": "2026-09-12T07:30:00Z"
            }
        """.trimIndent()

        val report: AiHealthReport = AppJson.decodeFromString(json)
        assertEquals(9L, report.id)
        assertEquals("2026-09-11", report.reportDate)
        assertEquals(AiHealthReportStatusValues.SUCCEEDED, report.status)
        assertEquals("整体健康。\n磁盘峰值值得关注。", report.summary)
        assertNull(report.errorCode)
        assertEquals(66.7, report.facts?.serverInventory?.onlineRate)
        assertNull(report.facts?.metricPeaks?.avgMemoryPercent)
        assertEquals(7L, report.facts?.metricPeaks?.maxCpuServerId)
        assertEquals(1, report.facts?.alertStatistics?.topServers?.size)
        assertNull(report.facts?.offlineServers?.first()?.lastHeartbeatAt)
        assertEquals(30, report.usage?.totalTokens)
    }

    @Test
    fun `health report decodes degraded with error code and null summary`() {
        val json = """
            {
              "id": 10, "report_date": "2026-09-10", "status": "degraded",
              "provider": "openai-compatible", "model": "",
              "prompt_version": "ai-health-report-v1",
              "summary": null,
              "top_concerns": [], "limitations": ["Model summary unavailable."],
              "facts": {
                "report_date": "2026-09-10",
                "server_inventory": {"total_count": 1, "online_count": 1, "offline_count": 0, "online_rate": 100.0},
                "metric_peaks": {},
                "alert_statistics": {"total_triggered": 0, "critical_count": 0, "warning_count": 0,
                                     "resolved_count": 0, "unresolved_count": 0, "top_servers": []},
                "offline_servers": []
              },
              "error_code": 42906,
              "usage": {"input_tokens": 0, "output_tokens": 0, "total_tokens": 0, "estimated_cost": 0.0, "currency": "USD"},
              "duration_ms": 80, "created_at": "2026-09-11T07:30:00Z"
            }
        """.trimIndent()

        val report: AiHealthReport = AppJson.decodeFromString(json)
        assertEquals(AiHealthReportStatusValues.DEGRADED, report.status)
        assertNull(report.summary)
        assertEquals(42906, report.errorCode)
        assertEquals(0, report.facts?.alertStatistics?.totalTriggered)
        assertTrue(report.topConcerns.isEmpty())
    }

    @Test
    fun `generate request omits null report_date and encodes explicit date`() {
        val empty = AppJson.encodeToString(GenerateHealthReportRequest())
        assertFalse(empty.contains("report_date"))

        val explicit = AppJson.encodeToString(GenerateHealthReportRequest(reportDate = "2026-09-11"))
        assertTrue(explicit.contains(""""report_date":"2026-09-11""""))
    }
}
