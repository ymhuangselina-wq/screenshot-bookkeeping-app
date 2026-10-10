package com.example.screenshotbookkeeping

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.net.SocketTimeoutException
import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.HttpUrl.Companion.toHttpUrl

class ApiClient(private val settings: SecureSettings) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(50, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()

    suspend fun config(): AppConfig = get("/config")
    suspend fun health(): ServiceHealth = get("/health")
    suspend fun setTarget(url: String): AppConfig = put("/target", TargetRequest(url))
    suspend fun renameFields(names: Map<String, String>): AppConfig = put("/fields", RenameFieldsRequest(names))
    suspend fun books(): PersonalBookCatalogDto = get("/books")
    suspend fun inspectPersonalBook(url: String, tableId: String? = null): PersonalInspectResponse = post("/books/inspect", InspectRequest(url, tableId))
    suspend fun connectPersonalBook(input: PersonalConnectRequest): PersonalBookCatalogDto = post("/books/connect", input)
    suspend fun switchBook(bookId: String): PersonalBookCatalogDto {
        val result: PersonalBookCatalogDto = put("/books/current", SwitchBookRequest(bookId))
        check(result.currentBookId == bookId) { "服务器未确认切换账本，请重试" }
        settings.currentBookId = bookId
        return result
    }
    suspend fun refreshCurrentPersonalBook(): PersonalBookCatalogDto = postEmpty("/books/current/refresh")
    suspend fun updateCurrentBookFields(mappings: List<MappingInputDto>): PersonalBookCatalogDto = put("/books/current/fields", UpdateBookFieldsRequest(mappings))
    suspend fun updateCurrentBookLayout(fields: List<DynamicFieldDto>): PersonalBookCatalogDto = put("/books/current/layout", UpdateFieldLayoutRequest(fields.map { FieldLayoutItemDto(it.fieldId, it.required) }))
    suspend fun addCurrentFieldOption(fieldId: String, option: String): PersonalBookCatalogDto = post("/books/current/fields/${java.net.URLEncoder.encode(fieldId, "UTF-8")}/options", AddFieldOptionRequest(option))
    suspend fun updateCurrentFieldOptions(fieldId: String, options: List<String>): PersonalBookCatalogDto = put("/books/current/fields/${java.net.URLEncoder.encode(fieldId, "UTF-8")}/options", UpdateFieldOptionsRequest(options))
    suspend fun renameCurrentFieldOption(fieldId: String, oldOption: String, newOption: String): PersonalBookCatalogDto = post("/books/current/fields/${java.net.URLEncoder.encode(fieldId, "UTF-8")}/options/rename", RenameFieldOptionRequest(oldOption, newOption))
    suspend fun hideCurrentFieldOption(fieldId: String, option: String): PersonalBookCatalogDto = post("/books/current/fields/${java.net.URLEncoder.encode(fieldId, "UTF-8")}/options/hide", FieldOptionActionRequest(option))
    suspend fun restoreCurrentFieldOption(fieldId: String, option: String): PersonalBookCatalogDto = post("/books/current/fields/${java.net.URLEncoder.encode(fieldId, "UTF-8")}/options/restore", FieldOptionActionRequest(option))
    suspend fun removeBook(bookId: String): PersonalBookCatalogDto = delete("/books/$bookId")
    suspend fun parse(input: ParseRequest): DraftResponse = post("/parse", input)
    suspend fun save(input: RecordRequest): RecordResponse = post("/records", input)

    fun authUrl(inviteCode: String, deviceName: String, appState: String, challenge: String): String = android.net.Uri.parse(BuildConfig.SERVICE_URL + "/v2/auth/start")
        .buildUpon().apply { if (inviteCode.isNotBlank()) appendQueryParameter("inviteCode", inviteCode) }
        .appendQueryParameter("deviceName", deviceName).appendQueryParameter("appState", appState).appendQueryParameter("challenge", challenge).build().toString()
    suspend fun exchange(code: String, verifier: String): SessionResponse = v2Post("/v2/auth/exchange", ExchangeRequest(code, verifier), authenticated = false)
    suspend fun loginAccount(input: AccountCredentials): SessionResponse = v2Post("/v2/auth/login", input, authenticated = false)
    suspend fun registerAccount(input: AccountCredentials): SessionResponse = v2Post("/v2/auth/register", input, authenticated = false)
    suspend fun refreshSession(): SessionResponse = v2Post("/v2/auth/refresh", emptyMap<String, String>())
    suspend fun me(): MeResponse = v2Get("/v2/me")
    suspend fun inspectBook(url: String, tableId: String? = null): InspectResponse = v2Post("/v2/books/inspect", InspectRequest(url, tableId))
    suspend fun connectBook(input: ConnectBookRequest): BookEnvelope = v2Post("/v2/books/connect", input)
    suspend fun createBook(input: CreateBookRequest): BookEnvelope = v2Post("/v2/books/create", input)
    suspend fun refreshBook(): BookEnvelope = v2Post("/v2/books/current/refresh", emptyMap<String, String>())
    suspend fun parseV2(input: ParseRequest): ParseV2Response = v2Post("/v2/parse", input)
    suspend fun saveV2(input: RecordRequest): RecordResponse = v2Post("/v2/records", input)
    suspend fun logout(): OkResponse = v2Post("/v2/auth/logout", emptyMap<String, String>())
    suspend fun deleteAccount(): OkResponse = v2Delete("/v2/account")

    private val ledgerBase get() = if (BuildConfig.PERSONAL_MODE) settings.workerUrl else BuildConfig.SERVICE_URL + "/v2/ledger"
    private suspend inline fun <reified T> get(path: String): T = request(Request.Builder().url(ledgerBase + path).get())
    private suspend inline fun <reified I, reified O> post(path: String, value: I): O {
        val body = json.encodeToString(value).toRequestBody("application/json".toMediaType())
        return request(Request.Builder().url(ledgerBase + path).post(body))
    }
    private suspend inline fun <reified T> postEmpty(path: String): T =
        request(Request.Builder().url(ledgerBase + path).post("".toRequestBody(null)))
    private suspend inline fun <reified I, reified O> put(path: String, value: I): O {
        val body = json.encodeToString(value).toRequestBody("application/json".toMediaType())
        return request(Request.Builder().url(ledgerBase + path).put(body))
    }
    private suspend inline fun <reified T> delete(path: String): T = request(Request.Builder().url(ledgerBase + path).delete())
    private suspend inline fun <reified T> request(builder: Request.Builder): T = withContext(Dispatchers.IO) {
        val request = builder.header("Authorization", "Bearer ${if (BuildConfig.PERSONAL_MODE) settings.accessKey else settings.sessionToken}")
            .apply { if (settings.currentBookId.isNotBlank()) header("X-Book-Id", settings.currentBookId) }.build()
        val (code, body) = executeResponse(request)
        if (code !in 200..299) throw ApiFailure(
            code,
            "${httpErrorMessage(code, body, request.url.encodedPath, json)}\n服务：${request.url.host}"
        )
        json.decodeFromString<T>(body)
    }

    private suspend fun executeResponse(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        if (request.method == "GET") call.timeout().timeout(15, TimeUnit.SECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(
                    if (e is java.io.InterruptedIOException) "${requestAction(request.url.encodedPath)}超时，请重试；当前输入已保留"
                    else "网络连接中断，请检查网络后重试；当前输入已保留", e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { it.code to it.body?.string().orEmpty() }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("网络响应中断，请重试；当前输入已保留", e))
                }
            }
        })
    }

    private suspend inline fun <reified T> v2Get(path: String): T = v2Request(Request.Builder().url(BuildConfig.SERVICE_URL + path).get(), true)
    private suspend inline fun <reified I, reified O> v2Post(path: String, value: I, authenticated: Boolean = true): O {
        val body = json.encodeToString(value).toRequestBody("application/json".toMediaType())
        return v2Request(Request.Builder().url(BuildConfig.SERVICE_URL + path).post(body), authenticated)
    }
    private suspend inline fun <reified T> v2Delete(path: String): T = v2Request(Request.Builder().url(BuildConfig.SERVICE_URL + path).delete(), true)
    private suspend inline fun <reified T> v2Request(builder: Request.Builder, authenticated: Boolean): T = withContext(Dispatchers.IO) {
        if (authenticated) builder.header("Authorization", "Bearer ${settings.sessionToken}")
        val request = builder.build()
        val (code, body) = executeResponse(request)
        if (code !in 200..299) throw ApiFailure(code, httpErrorMessage(code, body, request.url.encodedPath, json))
        json.decodeFromString<T>(body)
    }
}

internal fun serviceHost(url: String): String = runCatching {
    url.toHttpUrl().host
}.getOrDefault(url)

internal fun requestAction(path: String): String = when {
    path == "/config" -> "加载服务配置"
    path == "/books" -> "加载账本"
    path.contains("/parse") -> "识别截图"
    path.contains("/records") -> "保存账目"
    path.contains("/layout") -> "保存字段顺序"
    path.contains("/options") -> "同步字段选项"
    path.contains("/refresh") -> "刷新账本"
    else -> "服务请求"
}

internal fun httpErrorMessage(code: Int, body: String, path: String, json: Json = Json { ignoreUnknownKeys = true }): String {
    val structured = runCatching { json.decodeFromString<ErrorEnvelope>(body).error.message }.getOrNull()
        ?: runCatching {
            val objectBody = json.parseToJsonElement(body).jsonObject
            listOf("message", "Message", "errorMessage", "ErrorMessage")
                .firstNotNullOfOrNull { objectBody[it]?.jsonPrimitive?.contentOrNull }
        }.getOrNull()
    val clean = (structured ?: body)
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(240)
    val detail = when {
        clean.contains("current user is in debt", ignoreCase = true) || clean.contains("account is in debt", ignoreCase = true) ->
            "阿里云账户欠费，服务已暂停。充值后请等待几分钟再重试。"
        clean.contains("MissingRequiredHeader", ignoreCase = true) ->
            "阿里云 HTTP 触发器仍要求签名认证，请将认证方式改为无需认证。"
        clean.contains("invalid access key", ignoreCase = true) || clean.contains("访问密钥无效") ->
            "个人访问密钥无效，请在设置中重新填写。"
        clean.isNotBlank() -> clean
        else -> "服务器没有返回具体原因，请查看阿里云函数日志。"
    }
    return "${requestAction(path)}失败：$detail（HTTP $code）"
}

class ApiFailure(val status: Int, message: String) : IllegalStateException(message)
