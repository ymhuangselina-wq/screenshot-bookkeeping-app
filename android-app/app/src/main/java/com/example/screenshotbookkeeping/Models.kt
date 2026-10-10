package com.example.screenshotbookkeeping

import kotlinx.serialization.Serializable

@Serializable data class AppConfig(
    val paymentPlatforms: List<String>, val tags: List<String>, val projects: List<String>,
    val targetUrl: String? = null, val bookName: String? = null, val tableName: String? = null,
    val fieldNames: Map<String, String> = emptyMap(), val fields: List<DynamicFieldDto> = emptyList()
)
@Serializable data class DynamicFieldDto(val fieldId: String, val displayName: String, val fieldType: Int, val primary: Boolean = false, val options: List<String> = emptyList(), val hiddenOptions: List<String> = emptyList(), val required: Boolean = false)
@Serializable data class FieldLayoutItemDto(val fieldId: String, val required: Boolean)
@Serializable data class UpdateFieldLayoutRequest(val fields: List<FieldLayoutItemDto>)
@Serializable data class AddFieldOptionRequest(val option: String)
@Serializable data class UpdateFieldOptionsRequest(val options: List<String>)
@Serializable data class RenameFieldOptionRequest(val oldOption: String, val newOption: String)
@Serializable data class FieldOptionActionRequest(val option: String)
@Serializable data class TargetRequest(val url: String)
@Serializable data class RenameFieldsRequest(val names: Map<String, String>)
@Serializable data class PersonalTargetDto(val appToken: String? = null, val wikiToken: String? = null, val tableId: String? = null, val sourceUrl: String? = null)
@Serializable data class PersonalBookDto(
    val id: String, val appToken: String, val tableId: String, val bookName: String, val tableName: String,
    val sourceUrl: String, val target: PersonalTargetDto, val mappings: List<FieldMappingDto>, val lastUsedAt: Long,
    val fields: List<DynamicFieldDto> = emptyList()
)
@Serializable data class PersonalBookCatalogDto(val currentBookId: String? = null, val books: List<PersonalBookDto> = emptyList())
@Serializable data class PersonalInspectResponse(
    val selectionRequired: Boolean, val appToken: String, val sourceUrl: String, val target: PersonalTargetDto,
    val tables: List<FeishuTableDto> = emptyList(), val table: FeishuTableDto? = null,
    val fields: List<FeishuFieldDto> = emptyList(), val suggestions: List<PersonalMappingSuggestionDto> = emptyList(), val empty: Boolean = false
)
@Serializable data class PersonalMappingSuggestionDto(
    val semanticKey: String, val standardName: String, val required: Boolean, val expectedType: Int,
    val defaultOptions: List<String> = emptyList(), val status: String,
    val suggestedField: FeishuFieldDto? = null, val conflictingField: FeishuFieldDto? = null,
    val compatibleFields: List<FeishuFieldDto> = emptyList()
)
@Serializable data class PersonalConnectRequest(
    val clientRequestId: String, val appToken: String, val tableId: String, val sourceUrl: String,
    val target: PersonalTargetDto, val mappings: List<MappingInputDto>
)
@Serializable data class SwitchBookRequest(val bookId: String)
@Serializable data class UpdateBookFieldsRequest(val mappings: List<MappingInputDto>)
@Serializable data class Confidence(val date: Double = 0.0, val purpose: Double = 0.0, val amount: Double = 0.0, val paymentPlatform: Double = 0.0, val tags: Double = 0.0, val project: Double = 0.0)
@Serializable data class DraftResponse(
    val supported: Boolean, val date: String? = null, val purpose: String? = null, val amount: Double? = null,
    val paymentPlatform: String? = null, val tags: List<String> = emptyList(), val note: String? = null, val project: String? = null,
    val confidence: Confidence = Confidence(0.0,0.0,0.0,0.0,0.0,0.0), val warnings: List<String> = emptyList(),
    val values: Map<String, String> = emptyMap(), val dynamicConfidence: Map<String, Double> = emptyMap(),
    val suggestedOptions: Map<String, List<String>> = emptyMap()
)
@Serializable data class ParseRequest(val imageDataUrl: String, val sharedAt: String)
@Serializable data class RecordRequest(
    val clientRequestId: String, val date: String, val purpose: String, val amount: Double,
    val paymentPlatform: String, val tags: List<String>, val note: String?, val project: String?,
    val values: Map<String, String>? = null
)
@Serializable data class RecordResponse(val ok: Boolean, val recordId: String, val duplicate: Boolean)
@Serializable data class ServiceHealth(val ok: Boolean, val build: String? = null)
@Serializable data class ErrorEnvelope(val error: ApiErrorBody)
@Serializable data class ApiErrorBody(val code: String, val message: String, val requestId: String? = null)

data class LedgerForm(
    val date: String = "", val purpose: String = "", val amount: String = "",
    val paymentPlatform: String = "", val tags: Set<String> = emptySet(),
    val note: String = "", val project: String = "", val lowConfidence: Set<String> = emptySet(),
    val dynamicValues: Map<String, String> = emptyMap()
)

internal fun emptyDynamicForm(config: AppConfig?, date: String): LedgerForm = LedgerForm(
    dynamicValues = config?.fields.orEmpty().filter { it.fieldType == 5 }.associate { it.fieldId to date }
)

@Serializable data class SessionResponse(val sessionToken: String, val expiresAt: Long)
@Serializable data class ExchangeRequest(val code: String, val verifier: String)
@Serializable data class UserSummary(val id: String, val name: String)
@Serializable data class QuotaSummary(val remaining: Int, val limit: Int)
@Serializable data class FieldMappingDto(
    val semanticKey: String, val fieldId: String, val displayName: String, val fieldType: Int,
    val enabled: Boolean, val options: List<String> = emptyList()
)
@Serializable data class BookDto(
    val id: String, val name: String, val tableName: String, val sourceUrl: String? = null,
    val mappings: List<FieldMappingDto> = emptyList()
)
@Serializable data class MeResponse(val user: UserSummary, val book: BookDto? = null, val quota: QuotaSummary)
@Serializable data class BookEnvelope(val book: BookDto? = null)
@Serializable data class FeishuTableDto(val table_id: String, val name: String)
@Serializable data class FeishuFieldDto(val id: String, val name: String, val type: Int, val primary: Boolean = false, val options: List<String> = emptyList())
@Serializable data class MappingSuggestionDto(
    val semanticKey: String, val standardName: String, val required: Boolean, val expectedType: Int,
    val status: String, val suggestedField: FeishuFieldDto? = null, val conflictingField: FeishuFieldDto? = null,
    val compatibleFields: List<FeishuFieldDto> = emptyList()
)
@Serializable data class InspectRequest(val url: String, val tableId: String? = null)
@Serializable data class InspectResponse(
    val selectionRequired: Boolean, val appToken: String, val sourceUrl: String,
    val tables: List<FeishuTableDto> = emptyList(), val table: FeishuTableDto? = null,
    val fields: List<FeishuFieldDto> = emptyList(), val suggestions: List<MappingSuggestionDto> = emptyList()
)
@Serializable data class MappingInputDto(
    val semanticKey: String, val enabled: Boolean, val fieldId: String? = null,
    val displayName: String, val options: List<String> = emptyList()
)
@Serializable data class ConnectBookRequest(
    val appToken: String, val tableId: String, val sourceUrl: String, val mappings: List<MappingInputDto>
)
@Serializable data class CreateBookRequest(
    val clientRequestId: String, val name: String, val tableName: String, val mappings: List<MappingInputDto>
)
@Serializable data class ParseV2Response(
    val supported: Boolean, val date: String?, val purpose: String?, val amount: Double?,
    val paymentPlatform: String?, val tags: List<String>, val note: String?, val project: String?,
    val confidence: Confidence, val warnings: List<String>, val quotaRemaining: Int
)
@Serializable data class OkResponse(val ok: Boolean)

@Serializable data class AccountCredentials(val username: String, val password: String, val inviteCode: String? = null, val name: String? = null, val deviceName: String? = null)
