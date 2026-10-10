import { createRecord, getFieldsAndOptions, renameCurrentFields, setCurrentTarget } from "./feishu";
import { ApiError, authenticate, json, requireJson } from "./http";
import { parseDynamicScreenshot, parseScreenshot } from "./qwen";
import type { DynamicRecordInput, Env, RecordInput } from "./types";
import {
  connectPersonalBook, createDynamicPersonalRecord, createPersonalRecord, currentBook, getBookCatalog, inspectPersonalBook,
  optionsForPersonalBook, refreshCurrentPersonalBook, removePersonalBook, switchPersonalBook,
  updateCurrentPersonalFields,
  updateCurrentPersonalLayout,
  addPersonalFieldOption,
  updatePersonalFieldOptions, managePersonalFieldOption,
} from "./personal-books";
import type { MappingInput } from "./personal-books";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function validateRecord(value: RecordInput, options: Awaited<ReturnType<typeof getFieldsAndOptions>>): void {
  if (!value || !UUID.test(value.clientRequestId)) throw new ApiError(400, "invalid_request_id", "clientRequestId 必须为 UUID");
  if (!value.purpose?.trim()) throw new ApiError(400, "invalid_purpose", "用途不能为空");
  if (!Number.isFinite(value.amount) || value.amount <= 0 || Math.round(value.amount * 100) !== value.amount * 100) throw new ApiError(400, "invalid_amount", "金额必须为正数且最多两位小数");
  if (!Number.isFinite(Date.parse(value.date))) throw new ApiError(400, "invalid_date", "日期无效");
  if (options.paymentPlatforms.length > 0 && !options.paymentPlatforms.includes(value.paymentPlatform)) throw new ApiError(400, "invalid_payment_platform", "支付平台不在飞书选项中");
  if (!Array.isArray(value.tags) || value.tags.some((tag) => !options.tags.includes(tag))) throw new ApiError(400, "invalid_tags", "标签不在飞书选项中");
  if (value.project && !options.projects.includes(value.project)) throw new ApiError(400, "invalid_project", "归属项目不在飞书选项中");
}

export async function handleLedger(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  if (url.pathname === "/health" && request.method === "GET") return json({ ok: true });

  if (url.pathname === "/books" && request.method === "GET") return json(await getBookCatalog(env));
  if (url.pathname === "/books/inspect" && request.method === "POST") {
    const body = await requireJson<{ url?: string; tableId?: string }>(request);
    if (!body.url) throw new ApiError(400, "url_required", "请填写多维表格链接");
    return json(await inspectPersonalBook(env, body.url, body.tableId));
  }
  if (url.pathname === "/books/connect" && request.method === "POST") {
    const body = await requireJson<{ clientRequestId?: string; appToken: string; tableId: string; sourceUrl: string; target: Parameters<typeof connectPersonalBook>[1]["target"]; mappings: MappingInput[] }>(request);
    if (!body.clientRequestId || !UUID.test(body.clientRequestId)) throw new ApiError(400, "invalid_request_id", "缺少有效的请求 ID");
    const key = `personal:connect:${body.clientRequestId}`;
    const previous = await env.IDEMPOTENCY.get(key);
    if (previous) return json(JSON.parse(previous));
    const result = await connectPersonalBook(env, body);
    await env.IDEMPOTENCY.put(key, JSON.stringify(result), { expirationTtl: 86_400 });
    return json(result, 201);
  }
  if (url.pathname === "/books/current" && request.method === "PUT") {
    const body = await requireJson<{ bookId?: string }>(request);
    if (!body.bookId) throw new ApiError(400, "book_id_required", "请选择账本");
    return json(await switchPersonalBook(env, body.bookId));
  }
  if (url.pathname === "/books/current/refresh" && request.method === "POST") return json(await refreshCurrentPersonalBook(env));
  if (url.pathname === "/books/current/fields" && request.method === "PUT") {
    const body = await requireJson<{ mappings?: MappingInput[] }>(request);
    if (!body.mappings) throw new ApiError(400, "mappings_required", "请填写字段设置");
    return json(await updateCurrentPersonalFields(env, body.mappings));
  }
  if (url.pathname === "/books/current/layout" && request.method === "PUT") {
    const body = await requireJson<{ fields?: Array<{ fieldId: string; required: boolean }> }>(request);
    if (!body.fields) throw new ApiError(400, "fields_required", "请提交字段顺序");
    return json(await updateCurrentPersonalLayout(env, body.fields));
  }
  const optionMatch = url.pathname.match(/^\/books\/current\/fields\/([^/]+)\/options$/);
  if (optionMatch && request.method === "POST") {
    const body = await requireJson<{ option?: string }>(request);
    if (!body.option) throw new ApiError(400, "option_required", "候选值不能为空");
    return json(await addPersonalFieldOption(env, decodeURIComponent(optionMatch[1]), body.option));
  }
  if (optionMatch && request.method === "PUT") {
    const body = await requireJson<{ options?: string[] }>(request);
    if (!Array.isArray(body.options)) throw new ApiError(400, "options_required", "请提交候选值列表");
    return json(await updatePersonalFieldOptions(env, decodeURIComponent(optionMatch[1]), body.options));
  }
  const actionMatch = url.pathname.match(/^\/books\/current\/fields\/([^/]+)\/options\/(rename|hide|restore)$/);
  if (actionMatch && request.method === "POST") {
    const body = await requireJson<{option?:string;oldOption?:string;newOption?:string}>(request);
    return json(await managePersonalFieldOption(env,decodeURIComponent(actionMatch[1]),actionMatch[2],body));
  }
  const removeMatch = url.pathname.match(/^\/books\/([^/]+)$/);
  if (removeMatch && request.method === "DELETE") return json(await removePersonalBook(env, decodeURIComponent(removeMatch[1])));
  if (url.pathname === "/config" && request.method === "GET") {
    const book = currentBook(await refreshCurrentPersonalBook(env));
    if (!book) throw new ApiError(409, "book_required", "请先设置记账表格");
    return json(optionsForPersonalBook(book));
  }
  if (url.pathname === "/target" && request.method === "PUT") {
    const body = await requireJson<{ url?: string }>(request);
    if (!body.url?.trim()) throw new ApiError(400, "missing_target_url", "请填写飞书多维表格链接");
    const result = await setCurrentTarget(env, body.url);
    await env.IDEMPOTENCY.delete("settings:books-v1");
    return json(result);
  }
  if (url.pathname === "/fields" && request.method === "PUT") {
    const body = await requireJson<{ names?: Record<string, string> }>(request);
    if (!body.names) throw new ApiError(400, "missing_field_names", "请填写表头名称");
    const result = await renameCurrentFields(env, body.names);
    await env.IDEMPOTENCY.delete("settings:books-v1");
    return json(result);
  }
  if (url.pathname === "/parse" && request.method === "POST") {
    const body = await requireJson<{ imageDataUrl?: string; sharedAt?: string }>(request);
    if (!body.imageDataUrl || !body.sharedAt || !Number.isFinite(Date.parse(body.sharedAt))) throw new ApiError(400, "invalid_parse_request", "缺少图片或分享时间");
    const book = currentBook(await refreshCurrentPersonalBook(env));
    if (!book) throw new ApiError(409, "book_required", "请先设置记账表格");
    const options = optionsForPersonalBook(book);
    const editableFields = options.fields?.filter((field) => [1, 2, 3, 4, 5].includes(field.fieldType)) ?? [];
    const draft = editableFields.length
      ? await parseDynamicScreenshot(env, body.imageDataUrl, body.sharedAt, editableFields)
      : await parseScreenshot(env, body.imageDataUrl, body.sharedAt, options);
    return json(draft);
  }
  if (url.pathname === "/records" && request.method === "POST") {
    const input = await requireJson<RecordInput & DynamicRecordInput>(request);
    if (!input.clientRequestId || !UUID.test(input.clientRequestId)) throw new ApiError(400,"invalid_request_id","缺少有效的请求 ID");
    const key = `record:${input.clientRequestId}`;
    const previous = await env.IDEMPOTENCY.get(key);
    if (previous) return json({ ok: true, recordId: previous, duplicate: true });
    const catalog = await refreshCurrentPersonalBook(env);
    const book = currentBook(catalog);
    if (!book) throw new ApiError(409, "book_required", "请先设置记账表格");
    const dynamic = input.values && typeof input.values === "object";
    const recordId = dynamic
      ? await createDynamicPersonalRecord(env, book, input)
      : await (async () => { const options = optionsForPersonalBook(book); validateRecord(input, options); return createPersonalRecord(env, book, { ...input, purpose: input.purpose.trim() }); })();
    await env.IDEMPOTENCY.put(key, recordId, { expirationTtl: 60 * 60 * 24 * 30 });
    return json({ ok: true, recordId, duplicate: false }, 201);
  }
  throw new ApiError(404, "not_found", "接口不存在");
}

