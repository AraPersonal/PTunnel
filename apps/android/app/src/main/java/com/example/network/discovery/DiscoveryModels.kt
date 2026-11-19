package com.example.network.discovery

import com.squareup.moshi.Json

data class OperatorEvidenceDto(
    val mcc: Int? = null,
    val mnc: Int? = null,
    val asn: Int? = null,
    @Json(name = "isp_org") val ispOrg: String? = null,
    @Json(name = "suggested_label") val suggestedLabel: String? = null,
    @Json(name = "sim_operator_name") val simOperatorName: String? = null
)

data class OperatorDto(
    @Json(name = "operator_key") val operatorKey: String,
    @Json(name = "operator_tag") val operatorTag: String,
    @Json(name = "display_name") val displayName: String,
    @Json(name = "network_type") val networkType: String,
    val status: String,
    val evidence: OperatorEvidenceDto? = null,
    val clients: Int = 0,
    val aliases: List<String> = emptyList(),
    @Json(name = "merged_into") val mergedInto: String? = null,
    val mismatch: Boolean = false
)

data class DomesticRulesDto(
    val suffixes: List<String> = listOf(".ir"),
    val geoip: String = "IR"
)

data class OperatorsResponseDto(
    val version: Int = 1,
    val operators: List<OperatorDto> = emptyList(),
    val domestic: DomesticRulesDto = DomesticRulesDto()
)

data class ClientViewDto(
    val prefix24: String? = null,
    @Json(name = "province_guess") val provinceGuess: String? = null
)

data class ObserveRequestDto(
    @Json(name = "network_type") val networkType: String,
    val evidence: OperatorEvidenceDto,
    @Json(name = "client_view") val clientView: ClientViewDto? = null,
    val platform: String = "android",
    @Json(name = "client_version") val clientVersion: Int = 67
)

data class ObserveResponseDto(
    @Json(name = "operator_key") val operatorKey: String,
    @Json(name = "operator_tag") val operatorTag: String,
    @Json(name = "display_name") val displayName: String,
    val status: String,
    val confidence: String,
    val clients: Int = 1,
    @Json(name = "network_key") val networkKey: String,
    val mismatch: Boolean = false,
    val operator: OperatorDto? = null
)

data class PresetCandidateDto(
    val priority: Int = 10,
    @Json(name = "config_id") val configId: String,
    val status: String = "seed", // "validated", "candidate", "seed", "retired"
    val confidence: Double = 0.0,
    val clients: Int = 0,
    @Json(name = "p50_latency_ms") val p50LatencyMs: Long? = null,
    @Json(name = "p50_throughput_kBps") val p50ThroughputKBps: Double? = null,
    @Json(name = "death_rate") val deathRate: Double? = null,
    val engine: String = "xray",
    val transport: String = "reality",
    val params: Map<String, Any?> = emptyMap()
)

data class PresetsResponseDto(
    @Json(name = "operator_key") val operatorKey: String? = null,
    @Json(name = "operator_tag") val operatorTag: String? = null,
    @Json(name = "operator_status") val operatorStatus: String? = null,
    @Json(name = "network_key") val networkKey: String? = null,
    @Json(name = "network_type") val networkType: String? = null,
    @Json(name = "generated_at") val generatedAt: String? = null,
    val candidates: List<PresetCandidateDto> = emptyList()
)

data class PresetSampleDto(
    @Json(name = "latency_ms") val latencyMs: Long,
    @Json(name = "throughput_kBps") val throughputKBps: Double,
    val bytes: Long,
    @Json(name = "duration_s") val durationS: Double,
    val verdict: String = "ok",
    val ts: String? = null
)

data class PresetStabilityDto(
    @Json(name = "connected_minutes") val connectedMinutes: Double,
    val reconnects: Int = 0,
    val deaths: Int = 0
)

data class PresetReportDto(
    @Json(name = "operator_key") val operatorKey: String,
    @Json(name = "operator_tag") val operatorTag: String,
    @Json(name = "network_key") val networkKey: String,
    @Json(name = "config_id") val configId: String,
    val platform: String = "android",
    @Json(name = "client_version") val clientVersion: Int = 67,
    @Json(name = "probe_method") val probeMethod: String = "range-512k",
    val samples: List<PresetSampleDto>,
    val stability: PresetStabilityDto
)

data class PresetReportResponseDto(
    val ok: Boolean = true,
    val status: String? = null,
    val samples: Int? = null,
    val confidence: Double? = null
)
