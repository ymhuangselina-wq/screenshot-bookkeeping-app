import { accessTokenForUser } from "./auth";
import { ApiError } from "./http";
import type { Env, RecordInput, SelectOptions, TableTarget } from "./types";

type Field = { field_id: string; field_name: string; type: number; property?: { options?: Array<{ name: string }> } };
type LegacyFieldMapping = { tableId: string; fields: Record<string, { id: string; name: string; type: number }> };

const EXPECTED: Record<string, number[]> = {
  "记账日期": [5],
  "用途": [1],
  "金额": [2],
  "支付平台": [3],
  "标签": [4],
  "备注": [1],
  "归属项目": [3],
};

async function feishuToken(env: Env): Promise<string> {
  const response = await fetch("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ app_id: env.FEISHU_APP_ID, app_secret: env.FEISHU_APP_SECRET }),
  });
  const data = await response.json<{ code: number; msg?: string; tenant_access_token?: string }>();
  if (!response.ok || data.code !== 0 || !data.tenant_access_token) {
    throw new ApiError(502, "feishu_auth_failed", data.msg ?? "飞书认证失败");
  }
  return data.tenant_access_token;
}

export async function feishuFetch<T>(env: Env, path: string, init?: RequestInit): Promise<T> {
  const token = env.USER_ID ? await accessTokenForUser(env, env.USER_ID) : await feishuToken(env);
  const response = await fetch(`https://open.feishu.cn/open-apis${path}`, {
    ...init,
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json", ...(init?.headers ?? {}) },
  });
  const body = await response.json<{ code: number; msg?: string; data?: T }>();
  if (!response.ok || body.code !== 0 || body.data === undefined) {
    if (response.status === 401 || body.code === 99991663) throw new ApiError(401, "reauthorization_required", "飞书授权已失效，请重新登录");
    throw new ApiError(response.status === 403 ? 403 : 502, "feishu_api_failed", body.msg ?? "飞书接口调用失败");
  }
  return body.data;
}

export async function bitableAppToken(env: Env, target: TableTarget): Promise<string> {
  // A Bitable opened from Feishu Wiki exposes a Wiki node token in its URL.
  // Resolve that node to the underlying Bitable app token before calling the
  // Bitable APIs. Direct app tokens continue to work unchanged.
  if (target.appToken) return target.appToken;
  const wikiToken = target.wikiToken ?? env.FEISHU_WIKI_TOKEN;
  if (!wikiToken) return env.FEISHU_APP_TOKEN;
  const query = new URLSearchParams({ token: wikiToken });
  const data = await feishuFetch<{ node: { obj_token: string; obj_type: string } }>(
    env,
    `/wiki/v2/spaces/get_node?${query}`,
  );
  if (data.node.obj_type !== "bitable" || !data.node.obj_token) {
    throw new ApiError(422, "invalid_wiki_node", "飞书知识库节点不是多维表格");
  }
  return data.node.obj_token;
}

async function currentTarget(env: Env): Promise<TableTarget> {
  const saved = await env.IDEMPOTENCY.get<TableTarget>("settings:table-target", "json");
  return saved ?? { appToken: env.FEISHU_APP_TOKEN, wikiToken: env.FEISHU_WIKI_TOKEN, tableId: env.FEISHU_TABLE_ID };
}

async function optionsForTarget(env: Env, target: TableTarget): Promise<SelectOptions> {
  const appToken = await bitableAppToken(env, target);
  const path = `/bitable/v1/apps/${appToken}/tables/${target.tableId}/fields?page_size=100`;
  const data = await feishuFetch<{ items: Field[] }>(env, path);
  const saved = await env.IDEMPOTENCY.get<LegacyFieldMapping>("settings:field-mapping", "json");
  const byName = new Map(data.items.map((field) => [field.field_name, field]));
  const byId = new Map(data.items.map((field) => [field.field_id, field]));
  const resolved = new Map<string, Field>();
  const errors: string[] = [];
  for (const [name, validTypes] of Object.entries(EXPECTED)) {
    const savedField = saved?.tableId === target.tableId ? saved.fields[name] : undefined;
    const field = savedField ? byId.get(savedField.id) : byName.get(name);
    if (!field) errors.push(`缺少字段“${name}”`);
    else if (!validTypes.includes(field.type)) errors.push(`字段“${name}”类型不正确`);
    else resolved.set(name, field);
  }
  if (errors.length) throw new ApiError(422, "invalid_table_schema", errors.join("；"));
  const mapping: LegacyFieldMapping = { tableId: target.tableId, fields: Object.fromEntries([...resolved].map(([semantic, field]) => [semantic, { id: field.field_id, name: field.field_name, type: field.type }])) };
  await env.IDEMPOTENCY.put("settings:field-mapping", JSON.stringify(mapping));
  const options = (name: string) => resolved.get(name)?.property?.options?.map((it) => it.name) ?? [];
  const tables = await feishuFetch<{ items: Array<{ table_id: string; name: string }> }>(env, `/bitable/v1/apps/${appToken}/tables?page_size=100`);
  const app = await feishuFetch<{ app: { name: string } }>(env, `/bitable/v1/apps/${appToken}`);
  const targetUrl = target.sourceUrl ?? (target.wikiToken
    ? `https://my.feishu.cn/wiki/${target.wikiToken}?table=${target.tableId}`
    : `https://my.feishu.cn/base/${appToken}?table=${target.tableId}`);
  return {
    paymentPlatforms: options("支付平台"), tags: options("标签"), projects: options("归属项目"),
    targetUrl, bookName: app.app.name, tableName: tables.items.find((it) => it.table_id === target.tableId)?.name ?? target.tableId,
    fieldNames: Object.fromEntries([...resolved].map(([semantic, field]) => [semantic, field.field_name])),
  };
}

export function targetFromUrl(rawUrl: string): Omit<TableTarget, "tableId"> & { tableId?: string } {
  let url: URL;
  try { url = new URL(rawUrl); } catch { throw new ApiError(400, "invalid_feishu_url", "请粘贴完整的飞书多维表格链接"); }
  if (!/(^|\.)feishu\.cn$/.test(url.hostname) && !/(^|\.)larksuite\.com$/.test(url.hostname)) {
    throw new ApiError(400, "invalid_feishu_url", "这不是飞书多维表格链接");
  }
  const wiki = url.pathname.match(/\/wiki\/([^/]+)/)?.[1];
  const base = url.pathname.match(/\/base\/([^/]+)/)?.[1];
  if (!wiki && !base) throw new ApiError(400, "invalid_feishu_url", "链接中未找到多维表格标识");
  return { ...(wiki ? { wikiToken: wiki } : { appToken: base }), tableId: url.searchParams.get("table") ?? undefined, sourceUrl: rawUrl };
}

export async function setCurrentTarget(env: Env, rawUrl: string): Promise<SelectOptions> {
  const parsed = targetFromUrl(rawUrl.trim());
  const appToken = await bitableAppToken(env, { ...parsed, tableId: parsed.tableId ?? "" } as TableTarget);
  let tableId = parsed.tableId;
  if (!tableId) {
    const tables = await feishuFetch<{ items: Array<{ table_id: string }> }>(env, `/bitable/v1/apps/${appToken}/tables?page_size=100`);
    if (tables.items.length !== 1) throw new ApiError(422, "table_id_required", "该多维表格包含多个数据表，请打开要记账的具体数据表后再复制链接");
    tableId = tables.items[0].table_id;
  }
  const target: TableTarget = { ...parsed, tableId };
  await env.IDEMPOTENCY.delete("settings:field-mapping");
  const options = await optionsForTarget(env, target);
  await env.IDEMPOTENCY.put("settings:table-target", JSON.stringify(target));
  return options;
}

export async function renameCurrentFields(env: Env, names: Record<string, string>): Promise<SelectOptions> {
  const expected = Object.keys(EXPECTED);
  const normalized = Object.fromEntries(expected.map((name) => [name, names[name]?.trim()]));
  if (expected.some((name) => !normalized[name])) throw new ApiError(400, "invalid_field_names", "七个表头名称都不能为空");
  if (new Set(Object.values(normalized)).size !== expected.length) throw new ApiError(400, "duplicate_field_names", "表头名称不能重复");
  const target = await currentTarget(env);
  await optionsForTarget(env, target);
  const mapping = await env.IDEMPOTENCY.get<LegacyFieldMapping>("settings:field-mapping", "json");
  if (!mapping) throw new ApiError(500, "field_mapping_missing", "表头映射未建立");
  const appToken = await bitableAppToken(env, target);
  for (const semantic of expected) {
    const field = mapping.fields[semantic];
    if (field.name === normalized[semantic]) continue;
    await feishuFetch<{ field: Field }>(env, `/bitable/v1/apps/${appToken}/tables/${target.tableId}/fields/${field.id}`, {
      method: "PUT", body: JSON.stringify({ field_name: normalized[semantic], type: field.type }),
    });
    field.name = normalized[semantic];
    await env.IDEMPOTENCY.put("settings:field-mapping", JSON.stringify(mapping));
  }
  return optionsForTarget(env, target);
}

export async function getFieldsAndOptions(env: Env): Promise<SelectOptions> {
  return optionsForTarget(env, await currentTarget(env));
}

export async function createRecord(env: Env, input: RecordInput): Promise<string> {
  const target = await currentTarget(env);
  const appToken = await bitableAppToken(env, target);
  await optionsForTarget(env, target);
  const mapping = await env.IDEMPOTENCY.get<LegacyFieldMapping>("settings:field-mapping", "json");
  if (!mapping) throw new ApiError(500, "field_mapping_missing", "表头映射未建立");
  const fieldName = (semantic: string) => mapping.fields[semantic].name;
  const path = `/bitable/v1/apps/${appToken}/tables/${target.tableId}/records`;
  const timestamp = Date.parse(input.date);
  if (!Number.isFinite(timestamp)) throw new ApiError(400, "invalid_date", "记账日期无效");
  const fields: Record<string, unknown> = {
    [fieldName("记账日期")]: timestamp,
    [fieldName("用途")]: input.purpose,
    [fieldName("金额")]: input.amount,
    [fieldName("支付平台")]: input.paymentPlatform,
    [fieldName("标签")]: input.tags,
    [fieldName("备注")]: input.note ?? "",
  };
  if (input.project) fields[fieldName("归属项目")] = input.project;
  const data = await feishuFetch<{ record: { record_id: string } }>(env, path, {
    method: "POST",
    body: JSON.stringify({ fields }),
  });
  return data.record.record_id;
}
