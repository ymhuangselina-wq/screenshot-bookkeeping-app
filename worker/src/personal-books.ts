import { ApiError } from "./http";
import { bitableAppToken, feishuFetch, targetFromUrl } from "./feishu";
import type { DynamicRecordInput, Env, PersonalBook, PersonalBookCatalog, PersonalBookField, PersonalFieldMapping, RecordInput, SelectOptions, SemanticKey, TableTarget } from "./types";

type Table = { table_id: string; name: string };
type Field = { field_id: string; field_name: string; type: number; is_primary?: boolean; property?: { options?: Array<{ id?: string; name: string }> } };
export type MappingInput = { semanticKey: SemanticKey; enabled: boolean; fieldId?: string | null; displayName: string; options?: string[] };

const CATALOG_KEY = "settings:books-v1";
const OLD_TARGET_KEY = "settings:table-target";
const OLD_MAPPING_KEY = "settings:field-mapping";

export const BOOK_SPECS: Record<SemanticKey, { standardName: string; type: number; required: boolean; aliases: string[]; defaults: string[] }> = {
  date: { standardName: "记账日期", type: 5, required: true, aliases: ["交易时间", "消费日期", "日期"], defaults: [] },
  purpose: { standardName: "用途", type: 1, required: true, aliases: ["消费用途", "商户", "收款方"], defaults: [] },
  amount: { standardName: "金额", type: 2, required: true, aliases: ["交易金额", "支出金额"], defaults: [] },
  paymentPlatform: { standardName: "支付平台", type: 3, required: false, aliases: ["付款渠道", "支付渠道"], defaults: ["微信支付", "支付宝", "银行卡"] },
  tags: { standardName: "标签", type: 4, required: false, aliases: ["分类"], defaults: ["餐饮", "交通", "购物", "居住", "医疗", "娱乐", "办公", "差旅"] },
  note: { standardName: "备注", type: 1, required: false, aliases: ["说明"], defaults: [] },
  project: { standardName: "归属项目", type: 3, required: false, aliases: ["项目"], defaults: [] },
};

async function listTables(env: Env, appToken: string): Promise<Table[]> {
  return (await feishuFetch<{ items: Table[] }>(env, `/bitable/v1/apps/${appToken}/tables?page_size=100`)).items;
}

async function listFields(env: Env, appToken: string, tableId: string): Promise<Field[]> {
  return (await feishuFetch<{ items: Field[] }>(env, `/bitable/v1/apps/${appToken}/tables/${tableId}/fields?page_size=100`)).items;
}

async function appName(env: Env, appToken: string): Promise<string> {
  return (await feishuFetch<{ app: { name: string } }>(env, `/bitable/v1/apps/${appToken}`)).app.name;
}

function publicField(field: Field) {
  return { id: field.field_id, name: field.field_name, type: field.type, primary: field.is_primary === true, options: field.property?.options?.map((it) => it.name) ?? [] };
}

function storedFields(fields: Field[]): PersonalBookField[] {
  return fields.map((field) => ({
    fieldId: field.field_id, displayName: field.field_name, fieldType: field.type,
    primary: field.is_primary === true, options: field.property?.options?.map((it) => it.name) ?? [], required: false,
  }));
}

function mergeFieldLayout(previous: PersonalBookField[] | undefined, actual: PersonalBookField[]): PersonalBookField[] {
  if (!previous?.length) return actual;
  const actualById = new Map(actual.map((field) => [field.fieldId, field]));
  const preserved = previous.flatMap((old) => {
    const fresh = actualById.get(old.fieldId);
    if (!fresh) return [];
    actualById.delete(old.fieldId);
    const hiddenOptions = old.hiddenOptions ?? [];
    const available = fresh.options.filter(name => !hiddenOptions.includes(name));
    return [{ ...fresh, required: old.required === true, hiddenOptions,
      options: [...old.options.filter(name => available.includes(name)), ...available.filter(name => !old.options.includes(name))] }];
  });
  return [...preserved, ...actualById.values()];
}

function inspectSuggestions(fields: Field[]) {
  return (Object.entries(BOOK_SPECS) as Array<[SemanticKey, typeof BOOK_SPECS[SemanticKey]]>).map(([semanticKey, spec]) => {
    const exact = fields.find((field) => field.field_name === spec.standardName && field.type === spec.type);
    const alias = fields.find((field) => spec.aliases.includes(field.field_name) && field.type === spec.type);
    const conflict = fields.find((field) => field.field_name === spec.standardName && field.type !== spec.type);
    const compatible = fields.filter((field) => field.type === spec.type).map(publicField);
    return {
      semanticKey, standardName: spec.standardName, required: spec.required, expectedType: spec.type, defaultOptions: spec.defaults,
      status: exact ? "matched" : alias ? "suggested" : conflict ? "conflict" : "missing",
      suggestedField: exact ? publicField(exact) : alias ? publicField(alias) : null,
      conflictingField: conflict ? publicField(conflict) : null,
      compatibleFields: compatible,
    };
  });
}

async function readCatalog(env: Env): Promise<PersonalBookCatalog | null> {
  const catalog = await env.IDEMPOTENCY.get<PersonalBookCatalog>(CATALOG_KEY, "json");
  if (catalog && env.REQUEST_BOOK_ID) {
    if (!catalog.books.some(book => book.id === env.REQUEST_BOOK_ID)) throw new ApiError(404, "book_not_found", "账本不存在，请刷新账本列表");
    catalog.currentBookId = env.REQUEST_BOOK_ID;
  }
  return catalog;
}

async function writeCatalog(env: Env, catalog: PersonalBookCatalog): Promise<void> {
  // A request can pin a different book without changing the account's default.
  if (env.REQUEST_BOOK_ID) {
    const stored = await env.IDEMPOTENCY.get<PersonalBookCatalog>(CATALOG_KEY,"json");
    if (stored) catalog = {...catalog,currentBookId:stored.currentBookId};
  }
  await env.IDEMPOTENCY.put(CATALOG_KEY, JSON.stringify(catalog));
}

function canonicalKey(name: string): SemanticKey | undefined {
  return (Object.entries(BOOK_SPECS) as Array<[SemanticKey, typeof BOOK_SPECS[SemanticKey]]>).find(([, spec]) => spec.standardName === name)?.[0];
}

async function migrateLegacy(env: Env): Promise<PersonalBookCatalog> {
  const target = await env.IDEMPOTENCY.get<TableTarget>(OLD_TARGET_KEY, "json") ?? {
    appToken: env.FEISHU_APP_TOKEN, wikiToken: env.FEISHU_WIKI_TOKEN, tableId: env.FEISHU_TABLE_ID,
  };
  if (!target.tableId) return { currentBookId: null, books: [] };
  const appToken = await bitableAppToken(env, target);
  const fields = await listFields(env, appToken, target.tableId);
  const old = await env.IDEMPOTENCY.get<{ tableId: string; fields: Record<string, { id: string; name: string; type: number }> }>(OLD_MAPPING_KEY, "json");
  const mappings: PersonalFieldMapping[] = [];
  for (const [semanticKey, spec] of Object.entries(BOOK_SPECS) as Array<[SemanticKey, typeof BOOK_SPECS[SemanticKey]]>) {
    const oldEntry = Object.entries(old?.fields ?? {}).find(([name]) => canonicalKey(name) === semanticKey)?.[1];
    const field = (oldEntry ? fields.find((item) => item.field_id === oldEntry.id) : undefined) ?? fields.find((item) => item.field_name === spec.standardName && item.type === spec.type);
    if (field) mappings.push({ semanticKey, fieldId: field.field_id, displayName: field.field_name, fieldType: field.type, enabled: true, options: field.property?.options?.map((it) => it.name) ?? [] });
  }
  const tables = await listTables(env, appToken);
  const now = Date.now();
  const id = crypto.randomUUID();
  const sourceUrl = target.sourceUrl ?? (target.wikiToken ? `https://my.feishu.cn/wiki/${target.wikiToken}?table=${target.tableId}` : `https://my.feishu.cn/base/${appToken}?table=${target.tableId}`);
  const book: PersonalBook = { id, appToken, tableId: target.tableId, bookName: await appName(env, appToken), tableName: tables.find((it) => it.table_id === target.tableId)?.name ?? target.tableId, sourceUrl, target, mappings, lastUsedAt: now };
  const catalog = { currentBookId: id, books: [book] };
  await writeCatalog(env, catalog);
  return catalog;
}

export async function getBookCatalog(env: Env): Promise<PersonalBookCatalog> {
  const catalog = await readCatalog(env) ?? await migrateLegacy(env);
  const ordered = [...catalog.books].sort((a, b) => a.id === catalog.currentBookId ? -1 : b.id === catalog.currentBookId ? 1 : 0);
  const seen = new Set<string>();
  const books = ordered.filter((book) => {
    const key = `${book.appToken}:${book.tableId}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  if (books.length !== catalog.books.length) {
    catalog.books = books;
    if (!books.some((book) => book.id === catalog.currentBookId)) catalog.currentBookId = books[0]?.id ?? null;
    await writeCatalog(env, catalog);
  }
  return catalog;
}

export async function inspectPersonalBook(env: Env, rawUrl: string, selectedTableId?: string) {
  const parsed = targetFromUrl(rawUrl);
  const appToken = await bitableAppToken(env, { ...parsed, tableId: parsed.tableId ?? "" } as TableTarget);
  const tables = await listTables(env, appToken);
  const tableId = selectedTableId ?? parsed.tableId ?? (tables.length === 1 ? tables[0].table_id : undefined);
  if (!tableId) return { selectionRequired: true, appToken, sourceUrl: parsed.sourceUrl, target: parsed, tables };
  const table = tables.find((it) => it.table_id === tableId);
  if (!table) throw new ApiError(404, "table_not_found", "未找到指定数据表");
  const fields = await listFields(env, appToken, tableId);
  return { selectionRequired: false, appToken, sourceUrl: parsed.sourceUrl, target: { ...parsed, tableId }, table, fields: fields.map(publicField), suggestions: inspectSuggestions(fields), empty: fields.length <= 1 && !Object.values(BOOK_SPECS).some((spec) => fields.some((field) => field.field_name === spec.standardName && field.type === spec.type)) };
}

function validateMappingInputs(inputs: MappingInput[]): void {
  for (const key of ["date", "purpose", "amount"] as SemanticKey[]) {
    if (!inputs.some((item) => item.semanticKey === key && item.enabled)) throw new ApiError(400, "required_mapping_missing", `必须启用“${BOOK_SPECS[key].standardName}”`);
  }
  const enabled = inputs.filter((item) => item.enabled);
  if (enabled.some((item) => !item.displayName.trim())) throw new ApiError(400, "field_name_required", "已启用字段的名称不能为空");
  if (new Set(enabled.map((item) => item.displayName.trim())).size !== enabled.length) throw new ApiError(400, "duplicate_field_names", "表头名称不能重复");
}

async function createField(env: Env, appToken: string, tableId: string, input: MappingInput): Promise<Field> {
  const spec = BOOK_SPECS[input.semanticKey];
  const property = [3, 4].includes(spec.type) ? { options: (input.options ?? spec.defaults).map((name) => ({ name })) } : undefined;
  return (await feishuFetch<{ field: Field }>(env, `/bitable/v1/apps/${appToken}/tables/${tableId}/fields`, {
    method: "POST", body: JSON.stringify({ field_name: input.displayName.trim(), type: spec.type, ...(property ? { property } : {}) }),
  })).field;
}

export async function connectPersonalBook(env: Env, input: { appToken: string; tableId: string; sourceUrl: string; target: TableTarget; mappings?: MappingInput[] }): Promise<PersonalBookCatalog> {
  const fields = await listFields(env, input.appToken, input.tableId);
  const tables = await listTables(env, input.appToken);
  const table = tables.find((it) => it.table_id === input.tableId);
  if (!table) throw new ApiError(404, "table_not_found", "数据表不存在");
  const mappings: PersonalFieldMapping[] = [];
  for (const item of (input.mappings ?? []).filter((entry) => entry.enabled)) {
    const spec = BOOK_SPECS[item.semanticKey];
    let field = item.fieldId ? fields.find((candidate) => candidate.field_id === item.fieldId) : undefined;
    if (field && field.type !== spec.type) throw new ApiError(422, "field_type_conflict", `字段“${field.field_name}”类型不兼容`);
    // A previous attempt may have created this field before the request timed out.
    // Reuse the compatible field so a safe retry does not add duplicate columns.
    if (!field) field = fields.find((candidate) => candidate.field_name === item.displayName.trim() && candidate.type === spec.type);
    if (!field) {
      field = await createField(env, input.appToken, input.tableId, item);
      fields.push(field);
    }
    mappings.push({ semanticKey: item.semanticKey, fieldId: field.field_id, displayName: field.field_name, fieldType: field.type, enabled: true, options: field.property?.options?.map((it) => it.name) ?? item.options ?? spec.defaults });
  }
  const now = Date.now();
  const catalog = await getBookCatalog(env);
  const existing = catalog.books.find((book) => book.appToken === input.appToken && book.tableId === input.tableId);
  const id = existing?.id ?? crypto.randomUUID();
  const book: PersonalBook = { id, appToken: input.appToken, tableId: input.tableId, bookName: await appName(env, input.appToken), tableName: table.name, sourceUrl: input.sourceUrl, target: { ...input.target, tableId: input.tableId, sourceUrl: input.sourceUrl }, mappings, fields: storedFields(fields), lastUsedAt: now };
  const without = catalog.books.filter((item) => item.id !== id);
  if (!existing && without.length >= 10) throw new ApiError(409, "book_limit_reached", "常用账本已达 10 个，请先移除一个旧账本");
  const updated = { currentBookId: id, books: [book, ...without].slice(0, 10) };
  await writeCatalog(env, updated);
  return updated;
}

export async function refreshPersonalBook(env: Env, book: PersonalBook): Promise<PersonalBook> {
  const fields = await listFields(env, book.appToken, book.tableId);
  const tables = await listTables(env, book.appToken);
  for (const mapping of book.mappings) {
    const actual = fields.find((field) => field.field_id === mapping.fieldId);
    if (!actual || actual.type !== mapping.fieldType) throw new ApiError(409, "book_schema_changed", `账本“${book.tableName}”的字段已变更，请修复字段映射`);
    mapping.displayName = actual.field_name;
    mapping.options = actual.property?.options?.map((it) => it.name) ?? [];
  }
  book.bookName = await appName(env, book.appToken);
  book.tableName = tables.find((it) => it.table_id === book.tableId)?.name ?? book.tableName;
  book.fields = mergeFieldLayout(book.fields, storedFields(fields));
  return book;
}

export async function switchPersonalBook(env: Env, id: string): Promise<PersonalBookCatalog> {
  const catalog = await getBookCatalog(env);
  const book = catalog.books.find((item) => item.id === id);
  if (!book) throw new ApiError(404, "book_not_found", "账本不存在");
  await refreshPersonalBook(env, book);
  book.lastUsedAt = Date.now();
  catalog.currentBookId = id;
  catalog.books = [book, ...catalog.books.filter((item) => item.id !== id)];
  await writeCatalog(env, catalog);
  return catalog;
}

export async function refreshCurrentPersonalBook(env: Env): Promise<PersonalBookCatalog> {
  const catalog = await getBookCatalog(env);
  const book = catalog.books.find((item) => item.id === catalog.currentBookId);
  if (!book) throw new ApiError(409, "book_required", "请先设置记账表格");
  await refreshPersonalBook(env, book);
  await writeCatalog(env, catalog);
  return catalog;
}

export async function updateCurrentPersonalFields(env: Env, inputs: MappingInput[]): Promise<PersonalBookCatalog> {
  validateMappingInputs(inputs);
  const catalog = await getBookCatalog(env);
  const book = catalog.books.find((item) => item.id === catalog.currentBookId);
  if (!book) throw new ApiError(409, "book_required", "请先设置记账表格");
  const actualFields = await listFields(env, book.appToken, book.tableId);
  const updated: PersonalFieldMapping[] = [];
  for (const input of inputs.filter((item) => item.enabled)) {
    const spec = BOOK_SPECS[input.semanticKey];
    const old = book.mappings.find((item) => item.semanticKey === input.semanticKey);
    let field = old ? actualFields.find((item) => item.field_id === old.fieldId) : undefined;
    if (!field) field = await createField(env, book.appToken, book.tableId, input);
    // Never rewrite an existing choice field's options from the mapping screen.
    // Feishu option values are identity-based; rebuilding the option array from
    // names can detach existing record cells from their original option IDs.
    else if (field.field_name !== input.displayName.trim()) {
      field = (await feishuFetch<{ field: Field }>(env, `/bitable/v1/apps/${book.appToken}/tables/${book.tableId}/fields/${field.field_id}`, {
        method: "PUT", body: JSON.stringify({ field_name: input.displayName.trim(), type: field.type }),
      })).field;
    }
    updated.push({ semanticKey: input.semanticKey, fieldId: field.field_id, displayName: field.field_name, fieldType: field.type, enabled: true, options: field.property?.options?.map((it) => it.name) ?? input.options ?? spec.defaults });
  }
  book.mappings = updated;
  await writeCatalog(env, catalog);
  return catalog;
}

export async function removePersonalBook(env: Env, id: string): Promise<PersonalBookCatalog> {
  const catalog = await getBookCatalog(env);
  if (catalog.currentBookId === id) throw new ApiError(409, "cannot_remove_current_book", "请先切换到其他账本再移除");
  catalog.books = catalog.books.filter((book) => book.id !== id);
  await writeCatalog(env, catalog);
  return catalog;
}

export async function updateCurrentPersonalLayout(env: Env, layout: Array<{ fieldId: string; required: boolean }>): Promise<PersonalBookCatalog> {
  const catalog = await getBookCatalog(env);
  const book = catalog.books.find((item) => item.id === catalog.currentBookId);
  if (!book) throw new ApiError(409, "book_required", "请先设置记账表格");
  await refreshPersonalBook(env, book);
  const current = new Map((book.fields ?? []).map((field) => [field.fieldId, field]));
  if (new Set(layout.map((item) => item.fieldId)).size !== layout.length) throw new ApiError(400, "duplicate_fields", "字段顺序中存在重复项");
  const ordered = layout.flatMap((item) => {
    const field = current.get(item.fieldId);
    if (!field) return [];
    current.delete(item.fieldId);
    return [{ ...field, required: item.required === true }];
  });
  book.fields = [...ordered, ...current.values()];
  await writeCatalog(env, catalog);
  return catalog;
}

export async function addPersonalFieldOption(env: Env, fieldId: string, option: string): Promise<PersonalBookCatalog> {
  const clean = option.trim();
  if (!clean) throw new ApiError(400, "option_required", "候选值不能为空");
  const catalog = await getBookCatalog(env);
  const book = catalog.books.find((item) => item.id === catalog.currentBookId);
  if (!book) throw new ApiError(409, "book_required", "请先设置记账表格");
  await refreshPersonalBook(env, book);
  const field = book.fields?.find((item) => item.fieldId === fieldId);
  if (!field || ![3, 4].includes(field.fieldType)) throw new ApiError(400, "invalid_option_field", "该字段不是单选或多选字段");
  if (!field.options.includes(clean)) {
    const options = [...field.options, clean];
    const actual = (await listFields(env, book.appToken, book.tableId)).find((item) => item.field_id === fieldId);
    const existingOptions = actual?.property?.options ?? field.options.map((name) => ({ name }));
    await feishuFetch(env, `/bitable/v1/apps/${book.appToken}/tables/${book.tableId}/fields/${field.fieldId}`, {
      method: "PUT", body: JSON.stringify({ field_name: field.displayName, type: field.fieldType, property: { options: [...existingOptions, { name: clean }] } }),
    });
    field.options = options;
  }
  field.hiddenOptions = (field.hiddenOptions ?? []).filter(name => name !== clean);
  field.options = [clean,...field.options.filter(name => name !== clean)];
  await writeCatalog(env, catalog);
  return catalog;
}

export async function updatePersonalFieldOptions(env: Env, fieldId: string, rawOptions: string[]): Promise<PersonalBookCatalog> {
  const options = [...new Set(rawOptions.map((item) => item.trim()).filter(Boolean))];
  const catalog = await getBookCatalog(env);
  const book = catalog.books.find((item) => item.id === catalog.currentBookId);
  if (!book) throw new ApiError(409, "book_required", "请先设置记账表格");
  await refreshPersonalBook(env, book);
  const field = book.fields?.find((item) => item.fieldId === fieldId);
  if (!field || ![3, 4].includes(field.fieldType)) throw new ApiError(400, "invalid_option_field", "该字段不是单选或多选字段");
  const actual = (await listFields(env, book.appToken, book.tableId)).find((item) => item.field_id === fieldId);
  if (!actual) throw new ApiError(404, "field_not_found", "飞书字段不存在，请刷新账本后重试");
  const existingOptions = actual.property?.options ?? [];
  const existingNames = existingOptions.map((item) => item.name);
  if (existingNames.some((name) => !options.includes(name))) {
    throw new ApiError(409, "destructive_option_update_blocked", "为保护已有记录，请在飞书中重命名或删除选项");
  }
  const additions = options.filter((name) => !existingNames.includes(name));
  if (additions.length === 0) return catalog;
  await feishuFetch(env, `/bitable/v1/apps/${book.appToken}/tables/${book.tableId}/fields/${field.fieldId}`, {
    method: "PUT", body: JSON.stringify({
      field_name: actual.field_name,
      type: actual.type,
      property: { options: [...existingOptions, ...additions.map((name) => ({ name }))] },
    }),
  });
  field.options = [...existingNames, ...additions];
  await writeCatalog(env, catalog);
  return catalog;
}

export function currentBook(catalog: PersonalBookCatalog): PersonalBook | null {
  return catalog.books.find((book) => book.id === catalog.currentBookId) ?? null;
}

export function optionsForPersonalBook(book: PersonalBook): SelectOptions {
  const mapping = (key: SemanticKey) => book.mappings.find((item) => item.semanticKey === key);
  return {
    paymentPlatforms: mapping("paymentPlatform")?.options ?? [], tags: mapping("tags")?.options ?? [], projects: mapping("project")?.options ?? [],
    targetUrl: book.sourceUrl, bookName: book.bookName, tableName: book.tableName,
    fieldNames: Object.fromEntries(book.mappings.map((item) => [BOOK_SPECS[item.semanticKey].standardName, item.displayName])),
    fields: book.fields ?? [],
  };
}

export async function createDynamicPersonalRecord(env: Env, book: PersonalBook, input: DynamicRecordInput): Promise<string> {
  await refreshPersonalBook(env, book);
  const fields: Record<string, unknown> = {};
  for (const field of book.fields ?? []) {
    const raw = input.values[field.fieldId]?.trim();
    if (!raw) {
      if (field.required) throw new ApiError(400, "required_field_missing", `“${field.displayName}”为必填字段`);
      continue;
    }
    if (field.fieldType === 1) fields[field.displayName] = raw;
    else if (field.fieldType === 2) {
      let number = Number(raw);
      if (!Number.isFinite(number)) throw new ApiError(400, "invalid_number", `“${field.displayName}”必须是数字`);
      if (/金额|支出|费用/.test(field.displayName)) number = Math.abs(number);
      fields[field.displayName] = number;
    } else if (field.fieldType === 5) {
      const normalized = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(raw) ? `${raw.replace(" ", "T")}+08:00` : raw;
      const date = Date.parse(normalized);
      if (!Number.isFinite(date)) throw new ApiError(400, "invalid_date", `“${field.displayName}”日期格式不正确`);
      fields[field.displayName] = date;
    } else if (field.fieldType === 3) {
      if (!field.options.includes(raw)) throw new ApiError(400, "invalid_option", `“${field.displayName}”选项无效`);
      fields[field.displayName] = raw;
    } else if (field.fieldType === 4) {
      const selected = raw.split(/[、,，]/).map((it) => it.trim()).filter(Boolean);
      if (selected.some((it) => !field.options.includes(it))) throw new ApiError(400, "invalid_option", `“${field.displayName}”包含无效选项`);
      fields[field.displayName] = selected;
    }
  }
  if (Object.keys(fields).length === 0) throw new ApiError(400, "empty_record", "请至少填写一个字段");
  const data = await feishuFetch<{ record: { record_id: string } }>(env, `/bitable/v1/apps/${book.appToken}/tables/${book.tableId}/records`, { method: "POST", body: JSON.stringify({ fields }) });
  return data.record.record_id;
}

export async function createPersonalRecord(env: Env, book: PersonalBook, input: RecordInput): Promise<string> {
  await refreshPersonalBook(env, book);
  const mapping = (key: SemanticKey) => book.mappings.find((item) => item.semanticKey === key);
  const values: Partial<Record<SemanticKey, unknown>> = { date: Date.parse(input.date), purpose: input.purpose, amount: input.amount, paymentPlatform: input.paymentPlatform || undefined, tags: input.tags, note: input.note ?? "", project: input.project || undefined };
  const fields: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(values) as Array<[SemanticKey, unknown]>) {
    const field = mapping(key);
    if (field && value !== undefined) fields[field.displayName] = value;
  }
  const data = await feishuFetch<{ record: { record_id: string } }>(env, `/bitable/v1/apps/${book.appToken}/tables/${book.tableId}/records`, { method: "POST", body: JSON.stringify({ fields }) });
  return data.record.record_id;
}

export async function managePersonalFieldOption(env: Env, fieldId: string, action: string, input: {option?:string;oldOption?:string;newOption?:string}): Promise<PersonalBookCatalog> {
  const catalog = await refreshCurrentPersonalBook(env);
  const book = currentBook(catalog)!;
  const field = book.fields?.find(item => item.fieldId === fieldId);
  const actual = (await listFields(env,book.appToken,book.tableId)).find(item => item.field_id === fieldId);
  if (!field || !actual || ![3,4].includes(actual.type)) throw new ApiError(400,"invalid_option_field","该字段不是单选或多选字段");
  const options = actual.property?.options ?? [];
  if (action === "rename") {
    const oldName = input.oldOption?.trim();
    const newName = input.newOption?.trim();
    if (!oldName || !newName || !options.some(item => item.name === oldName)) throw new ApiError(400,"invalid_option","选项不存在或名称为空");
    if (oldName !== newName && options.some(item => item.name === newName)) throw new ApiError(409,"duplicate_option","已有同名选项");
    const changed = options.find(item => item.name === oldName)!;
    if (!changed.id) throw new ApiError(409,"option_identity_missing","无法保留选项标识，请到飞书中修改");
    await feishuFetch(env,`/bitable/v1/apps/${book.appToken}/tables/${book.tableId}/fields/${fieldId}`,{
      method:"PUT",body:JSON.stringify({field_name:actual.field_name,type:actual.type,property:{options:options.map(item => item.name === oldName ? {...item,name:newName} : item)}})
    });
    field.options = field.options.map(name => name === oldName ? newName : name);
    field.hiddenOptions = (field.hiddenOptions ?? []).map(name => name === oldName ? newName : name);
  } else {
    const name = input.option?.trim();
    if (!name || !options.some(item => item.name === name)) throw new ApiError(404,"option_not_found","选项不存在，请刷新后重试");
    field.hiddenOptions = action === "hide" ? [...new Set([...(field.hiddenOptions ?? []),name])] : (field.hiddenOptions ?? []).filter(item => item !== name);
    field.options = options.map(item => item.name).filter(item => !field.hiddenOptions!.includes(item));
  }
  await writeCatalog(env,catalog);
  return catalog;
}
