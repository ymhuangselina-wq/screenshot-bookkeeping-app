import { accessTokenForUser } from "./auth";
import { ApiError } from "./http";
import type { Env, FieldMapping, RecordInput, SelectOptions, SemanticKey, UserBook, UserContext } from "./types";

type FeishuEnvelope<T> = { code: number; msg?: string; data?: T };
type Table = { table_id: string; name: string };
type Field = { field_id: string; field_name: string; type: number; is_primary?: boolean; property?: { options?: Array<{ name: string }> } };

const SPECS: Record<SemanticKey, { standardName: string; type: number; required: boolean; aliases: string[] }> = {
  date: { standardName: "记账日期", type: 5, required: true, aliases: ["交易时间", "消费日期", "日期"] },
  purpose: { standardName: "用途", type: 1, required: true, aliases: ["消费用途", "商户", "收款方"] },
  amount: { standardName: "金额", type: 2, required: true, aliases: ["交易金额", "支出金额"] },
  paymentPlatform: { standardName: "支付平台", type: 3, required: false, aliases: ["付款渠道", "支付渠道"] },
  tags: { standardName: "标签", type: 4, required: false, aliases: ["分类"] },
  note: { standardName: "备注", type: 1, required: false, aliases: ["说明"] },
  project: { standardName: "归属项目", type: 3, required: false, aliases: ["项目"] },
};

async function userFetch<T>(env: Env, userId: string, path: string, init?: RequestInit): Promise<T> {
  const accessToken = await accessTokenForUser(env, userId);
  const response = await fetch(`https://open.feishu.cn/open-apis${path}`, {
    ...init,
    headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json", ...(init?.headers ?? {}) },
  });
  const body = await response.json<FeishuEnvelope<T>>();
  if (!response.ok || body.code !== 0 || body.data === undefined) {
    if (response.status === 401 || body.code === 99991663) throw new ApiError(401, "reauthorization_required", "飞书授权已失效，请重新登录");
    throw new ApiError(response.status === 403 ? 403 : 502, "feishu_api_failed", body.msg ?? "飞书接口调用失败");
  }
  return body.data;
}

function parseLink(rawUrl: string): { appToken?: string; wikiToken?: string; tableId?: string; sourceUrl: string } {
  let url: URL;
  try { url = new URL(rawUrl.trim()); } catch { throw new ApiError(400, "invalid_feishu_url", "请粘贴完整的飞书多维表格链接"); }
  if (!/(^|\.)feishu\.cn$/.test(url.hostname) && !/(^|\.)larksuite\.com$/.test(url.hostname)) {
    throw new ApiError(400, "invalid_feishu_url", "这不是飞书多维表格链接");
  }
  const wikiToken = url.pathname.match(/\/wiki\/([^/]+)/)?.[1];
  const appToken = url.pathname.match(/\/base\/([^/]+)/)?.[1];
  if (!wikiToken && !appToken) throw new ApiError(400, "invalid_feishu_url", "链接中未找到多维表格");
  return { appToken, wikiToken, tableId: url.searchParams.get("table") ?? undefined, sourceUrl: rawUrl.trim() };
}

async function resolveAppToken(env: Env, userId: string, parsed: ReturnType<typeof parseLink>): Promise<string> {
  if (parsed.appToken) return parsed.appToken;
  const query = new URLSearchParams({ token: parsed.wikiToken! });
  const data = await userFetch<{ node: { obj_token: string; obj_type: string } }>(env, userId, `/wiki/v2/spaces/get_node?${query}`);
  if (data.node.obj_type !== "bitable") throw new ApiError(422, "invalid_wiki_node", "该知识库页面不是多维表格");
  return data.node.obj_token;
}

async function listTables(env: Env, userId: string, appToken: string): Promise<Table[]> {
  return (await userFetch<{ items: Table[] }>(env, userId, `/bitable/v1/apps/${appToken}/tables?page_size=100`)).items;
}

async function listFields(env: Env, userId: string, appToken: string, tableId: string): Promise<Field[]> {
  return (await userFetch<{ items: Field[] }>(env, userId, `/bitable/v1/apps/${appToken}/tables/${tableId}/fields?page_size=100`)).items;
}

function suggestions(fields: Field[]) {
  return (Object.entries(SPECS) as Array<[SemanticKey, typeof SPECS[SemanticKey]]>).map(([semanticKey, spec]) => {
    const exact = fields.find((field) => field.field_name === spec.standardName && field.type === spec.type);
    const alias = fields.find((field) => spec.aliases.includes(field.field_name) && field.type === spec.type);
    const conflict = fields.find((field) => field.field_name === spec.standardName && field.type !== spec.type);
    const compatible = fields.filter((field) => field.type === spec.type).map(publicField);
    return {
      semanticKey, standardName: spec.standardName, required: spec.required, expectedType: spec.type,
      status: exact ? "matched" : alias ? "suggested" : conflict ? "conflict" : "missing",
      suggestedField: exact ? publicField(exact) : alias ? publicField(alias) : null,
      conflictingField: conflict ? publicField(conflict) : null,
      compatibleFields: compatible,
    };
  });
}

function publicField(field: Field) {
  return { id: field.field_id, name: field.field_name, type: field.type, primary: field.is_primary === true, options: field.property?.options?.map((it) => it.name) ?? [] };
}

export async function inspectBook(env: Env, user: UserContext, rawUrl: string, selectedTableId?: string) {
  const parsed = parseLink(rawUrl);
  const appToken = await resolveAppToken(env, user.id, parsed);
  const tables = await listTables(env, user.id, appToken);
  const tableId = selectedTableId ?? parsed.tableId ?? (tables.length === 1 ? tables[0].table_id : undefined);
  if (!tableId) return { selectionRequired: true, appToken, sourceUrl: parsed.sourceUrl, tables };
  const table = tables.find((item) => item.table_id === tableId);
  if (!table) throw new ApiError(404, "table_not_found", "未找到指定数据表");
  const fields = await listFields(env, user.id, appToken, tableId);
  return { selectionRequired: false, appToken, table, sourceUrl: parsed.sourceUrl, fields: fields.map(publicField), suggestions: suggestions(fields) };
}

type MappingInput = { semanticKey: SemanticKey; enabled: boolean; fieldId?: string; displayName: string; options?: string[] };

async function createField(env: Env, userId: string, appToken: string, tableId: string, input: MappingInput): Promise<Field> {
  const spec = SPECS[input.semanticKey];
  const property = spec.type === 3 || spec.type === 4 ? { options: (input.options ?? []).map((name) => ({ name })) } : undefined;
  const data = await userFetch<{ field: Field }>(env, userId, `/bitable/v1/apps/${appToken}/tables/${tableId}/fields`, {
    method: "POST", body: JSON.stringify({ field_name: input.displayName || spec.standardName, type: spec.type, ...(property ? { property } : {}) }),
  });
  return data.field;
}

async function saveCurrentBook(env: Env, user: UserContext, book: Omit<UserBook, "id" | "userId">): Promise<UserBook> {
  const id = crypto.randomUUID();
  const now = Date.now();
  const statements = [
    env.DB.prepare("UPDATE books SET is_current=0,updated_at=? WHERE user_id=?").bind(now, user.id),
    env.DB.prepare(`INSERT INTO books(id,user_id,app_token,table_id,name,table_name,source_url,is_current,created_at,updated_at)
      VALUES(?,?,?,?,?,?,?,1,?,?)`).bind(id, user.id, book.appToken, book.tableId, book.name, book.tableName, book.sourceUrl ?? null, now, now),
    ...book.mappings.map((mapping) => env.DB.prepare(`INSERT INTO field_mappings
      (book_id,semantic_key,field_id,display_name,field_type,enabled,options_json) VALUES(?,?,?,?,?,?,?)`)
      .bind(id, mapping.semanticKey, mapping.fieldId, mapping.displayName, mapping.fieldType, mapping.enabled ? 1 : 0, JSON.stringify(mapping.options))),
  ];
  await env.DB.batch(statements);
  return { ...book, id, userId: user.id };
}

export async function connectBook(env: Env, user: UserContext, input: {
  appToken: string; tableId: string; sourceUrl: string; mappings: MappingInput[];
}): Promise<UserBook> {
  const tables = await listTables(env, user.id, input.appToken);
  const table = tables.find((item) => item.table_id === input.tableId);
  if (!table) throw new ApiError(404, "table_not_found", "数据表不存在");
  const fields = await listFields(env, user.id, input.appToken, input.tableId);
  const mappings: FieldMapping[] = [];
  for (const semanticKey of Object.keys(SPECS) as SemanticKey[]) {
    const supplied = input.mappings.find((item) => item.semanticKey === semanticKey);
    if (!supplied && SPECS[semanticKey].required) throw new ApiError(400, "required_mapping_missing", `缺少必选字段“${SPECS[semanticKey].standardName}”`);
    if (!supplied || !supplied.enabled) continue;
    let field = supplied.fieldId ? fields.find((item) => item.field_id === supplied.fieldId) : undefined;
    if (field && field.type !== SPECS[semanticKey].type) throw new ApiError(422, "field_type_conflict", `字段“${field.field_name}”类型不兼容`);
    if (!field) field = await createField(env, user.id, input.appToken, input.tableId, supplied);
    mappings.push({ semanticKey, fieldId: field.field_id, displayName: field.field_name, fieldType: field.type, enabled: true, options: field.property?.options?.map((it) => it.name) ?? supplied.options ?? [] });
  }
  return saveCurrentBook(env, user, { appToken: input.appToken, tableId: input.tableId, name: table.name, tableName: table.name, sourceUrl: input.sourceUrl, mappings });
}

export async function createBook(env: Env, user: UserContext, input: {
  name: string; tableName: string; mappings: MappingInput[];
}): Promise<UserBook> {
  const created = await userFetch<{ app: { app_token: string; name: string; url?: string } }>(env, user.id, "/bitable/v1/apps", {
    method: "POST", body: JSON.stringify({ name: input.name || "我的记账本" }),
  });
  const appToken = created.app.app_token;
  const tables = await listTables(env, user.id, appToken);
  if (!tables[0]) throw new ApiError(502, "create_book_incomplete", "多维表格已创建，但未找到默认数据表");
  const tableId = tables[0].table_id;
  const tableName = input.tableName || "账目";
  await userFetch<{ table: Table }>(env, user.id, `/bitable/v1/apps/${appToken}/tables/${tableId}`, {
    method: "PATCH", body: JSON.stringify({ name: tableName }),
  });
  const fields = await listFields(env, user.id, appToken, tableId);
  const primary = fields.find((field) => field.is_primary) ?? fields[0];
  const purposeInput = input.mappings.find((item) => item.semanticKey === "purpose")!;
  if (!primary) throw new ApiError(502, "primary_field_missing", "新数据表缺少主字段");
  await userFetch<{ field: Field }>(env, user.id, `/bitable/v1/apps/${appToken}/tables/${tableId}/fields/${primary.field_id}`, {
    method: "PUT", body: JSON.stringify({ field_name: purposeInput.displayName || "用途", type: 1 }),
  });
  const normalized = input.mappings.map((item) => item.semanticKey === "purpose" ? { ...item, fieldId: primary.field_id } : item);
  return connectBook(env, user, { appToken, tableId, sourceUrl: created.app.url ?? `https://feishu.cn/base/${appToken}?table=${tableId}`, mappings: normalized });
}

export async function currentBook(env: Env, user: UserContext): Promise<UserBook | null> {
  const book = await env.DB.prepare(`SELECT id,user_id,app_token,table_id,name,table_name,source_url FROM books
    WHERE user_id=? AND is_current=1`).bind(user.id).first<{
      id: string; user_id: string; app_token: string; table_id: string; name: string; table_name: string; source_url: string | null;
    }>();
  if (!book) return null;
  const rows = await env.DB.prepare(`SELECT semantic_key,field_id,display_name,field_type,enabled,options_json
    FROM field_mappings WHERE book_id=?`).bind(book.id).all<{
      semantic_key: SemanticKey; field_id: string; display_name: string; field_type: number; enabled: number; options_json: string;
    }>();
  return {
    id: book.id, userId: book.user_id, appToken: book.app_token, tableId: book.table_id, name: book.name,
    tableName: book.table_name, sourceUrl: book.source_url ?? undefined,
    mappings: rows.results.map((row) => ({ semanticKey: row.semantic_key, fieldId: row.field_id, displayName: row.display_name,
      fieldType: row.field_type, enabled: row.enabled === 1, options: JSON.parse(row.options_json) as string[] })),
  };
}

export function optionsFromBook(book: UserBook): SelectOptions {
  const options = (key: SemanticKey) => book.mappings.find((item) => item.semanticKey === key)?.options ?? [];
  return { paymentPlatforms: options("paymentPlatform"), tags: options("tags"), projects: options("project"), targetUrl: book.sourceUrl };
}

export async function refreshCurrentBook(env: Env, user: UserContext): Promise<UserBook> {
  const book = await currentBook(env, user);
  if (!book) throw new ApiError(409, "book_required", "请先设置记账表格");
  const fields = await listFields(env, user.id, book.appToken, book.tableId);
  for (const mapping of book.mappings) {
    const actual = fields.find((field) => field.field_id === mapping.fieldId);
    if (!actual || actual.type !== mapping.fieldType) throw new ApiError(409, "book_schema_changed", "表格字段已变更，请重新检查字段映射");
    mapping.displayName = actual.field_name;
    mapping.options = actual.property?.options?.map((it) => it.name) ?? [];
  }
  await env.DB.batch(book.mappings.map((mapping) => env.DB.prepare(`UPDATE field_mappings SET display_name=?,options_json=?
    WHERE book_id=? AND semantic_key=?`).bind(mapping.displayName, JSON.stringify(mapping.options), book.id, mapping.semanticKey)));
  return book;
}

export async function createUserRecord(env: Env, user: UserContext, input: RecordInput): Promise<string> {
  const book = await refreshCurrentBook(env, user);
  const byKey = new Map(book.mappings.map((item) => [item.semanticKey, item]));
  const values: Partial<Record<SemanticKey, unknown>> = {
    date: Date.parse(input.date), purpose: input.purpose, amount: input.amount,
    paymentPlatform: input.paymentPlatform || undefined, tags: input.tags, note: input.note ?? "", project: input.project || undefined,
  };
  const fields: Record<string, unknown> = {};
  for (const [semanticKey, value] of Object.entries(values) as Array<[SemanticKey, unknown]>) {
    const mapping = byKey.get(semanticKey);
    if (mapping && value !== undefined) fields[mapping.displayName] = value;
  }
  const data = await userFetch<{ record: { record_id: string } }>(env, user.id,
    `/bitable/v1/apps/${book.appToken}/tables/${book.tableId}/records`, { method: "POST", body: JSON.stringify({ fields }) });
  return data.record.record_id;
}
