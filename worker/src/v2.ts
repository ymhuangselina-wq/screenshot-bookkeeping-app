import { authenticateUser, createInvite, deleteAccount, exchangeAppSession, logout, oauthCallback, refreshAppSession, startOAuth } from "./auth";
import { ApiError, authenticate, json, requireJson } from "./http";
import { registerLocal, loginLocal } from "./local-auth";
import { accountEnv } from "./account-storage";
import { handleLedger } from "./ledger";
import { connectPersonalBook, getBookCatalog, currentBook as catalogBook, refreshCurrentPersonalBook } from "./personal-books";
import { parseScreenshot } from "./qwen";
import { connectBook, createBook, createUserRecord, currentBook, inspectBook, optionsFromBook, refreshCurrentBook } from "./user-feishu";
import type { Env, RecordInput, SemanticKey, UserContext } from "./types";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const DAILY_LIMIT = 20;

function shanghaiDate(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
}

async function remainingQuota(env: Env, userId: string): Promise<number> {
  const row = await env.DB.prepare("SELECT used_count FROM daily_usage WHERE user_id=? AND usage_date=?")
    .bind(userId, shanghaiDate()).first<{ used_count: number }>();
  return Math.max(0, DAILY_LIMIT - (row?.used_count ?? 0));
}

async function consumeQuota(env: Env, userId: string): Promise<number> {
  const date = shanghaiDate();
  await env.DB.prepare("INSERT OR IGNORE INTO daily_usage(user_id,usage_date,used_count) VALUES(?,?,0)").bind(userId, date).run();
  const result = await env.DB.prepare("UPDATE daily_usage SET used_count=used_count+1 WHERE user_id=? AND usage_date=? AND used_count<?")
    .bind(userId, date, DAILY_LIMIT).run();
  if (!result.meta.changes) throw new ApiError(429, "daily_quota_exhausted", "今日 AI 识别次数已用完，仍可手工记账");
  return remainingQuota(env, userId);
}

async function refundQuota(env: Env, userId: string): Promise<void> {
  await env.DB.prepare("UPDATE daily_usage SET used_count=MAX(0,used_count-1) WHERE user_id=? AND usage_date=?")
    .bind(userId, shanghaiDate()).run();
}

function validateRecord(input: RecordInput, mappings: Map<SemanticKey, { options: string[] }>): void {
  const enabled = new Set(mappings.keys());
  if (!UUID.test(input.clientRequestId)) throw new ApiError(400, "invalid_request_id", "clientRequestId 必须为 UUID");
  if (!input.purpose?.trim()) throw new ApiError(400, "invalid_purpose", "用途不能为空");
  if (!Number.isFinite(input.amount) || input.amount <= 0 || Math.round(input.amount * 100) !== input.amount * 100) throw new ApiError(400, "invalid_amount", "金额必须为正数且最多两位小数");
  if (!Number.isFinite(Date.parse(input.date))) throw new ApiError(400, "invalid_date", "日期无效");
  if (enabled.has("paymentPlatform") && !input.paymentPlatform) throw new ApiError(400, "invalid_payment_platform", "请选择支付平台");
  const platforms = mappings.get("paymentPlatform")?.options ?? [];
  const tags = mappings.get("tags")?.options ?? [];
  const projects = mappings.get("project")?.options ?? [];
  if (input.paymentPlatform && !platforms.includes(input.paymentPlatform)) throw new ApiError(400, "invalid_payment_platform", "支付平台不在当前表格选项中");
  if (input.tags.some((tag) => !tags.includes(tag))) throw new ApiError(400, "invalid_tags", "标签不在当前表格选项中");
  if (input.project && !projects.includes(input.project)) throw new ApiError(400, "invalid_project", "归属项目不在当前表格选项中");
}

function publicBook(book: Awaited<ReturnType<typeof currentBook>>) {
  if (!book) return null;
  return { id: book.id, name: book.name, tableName: book.tableName, sourceUrl: book.sourceUrl, mappings: book.mappings };
}

async function me(env: Env, user: UserContext): Promise<Response> {
  const scoped = accountEnv(env, user.id);
  let catalog = await getBookCatalog(scoped);
  // Import only this user's earlier V2 book, never the personal/admin catalog.
  if (!catalog.books.length) {
    const old = await currentBook(env, user);
    if (old) catalog = await connectPersonalBook(scoped, {appToken:old.appToken,tableId:old.tableId,sourceUrl:old.sourceUrl ?? "",target:{appToken:old.appToken,tableId:old.tableId},mappings:old.mappings});
  }
  const book = catalogBook(catalog);
  return json({ user: { id: user.id, name: user.name }, book: book ? {id:book.id,name:book.bookName,tableName:book.tableName,sourceUrl:book.sourceUrl,mappings:book.mappings} : null, quota: { remaining: await remainingQuota(env, user.id), limit: DAILY_LIMIT } });
}

export async function handleV2(request: Request, env: Env, url: URL): Promise<Response | null> {
  if (!url.pathname.startsWith("/v2/")) return null;
  if (url.pathname === "/v2/auth/register" && request.method === "POST") return registerLocal(request,env);
  if (url.pathname === "/v2/auth/login" && request.method === "POST") return loginLocal(request,env);
  if (url.pathname === "/v2/auth/start" && request.method === "GET") return startOAuth(request, env);
  if (url.pathname === "/v2/auth/callback" && request.method === "GET") return oauthCallback(request, env);
  if (url.pathname === "/v2/auth/exchange" && request.method === "POST") return exchangeAppSession(request, env);
  if (url.pathname === "/v2/admin/invites" && request.method === "POST") {
    authenticate(request, env.PERSONAL_ACCESS_KEY);
    const body = await requireJson<{ expiresInDays?: number }>(request);
    return createInvite(env, body.expiresInDays);
  }
  const user = await authenticateUser(request, env);
  if (url.pathname.startsWith("/v2/ledger/")) {
    const target = new URL(request.url);
    target.pathname = target.pathname.slice("/v2/ledger".length);
    const pinned = !["/books", "/books/current", "/books/connect", "/books/inspect"].includes(target.pathname);
    const scoped = accountEnv(env, user.id, pinned ? request.headers.get("X-Book-Id") ?? undefined : undefined);
    const forwarded = new Request(target, request);
    if (target.pathname === "/parse" && request.method === "POST") {
      await refreshCurrentPersonalBook(scoped);
      await consumeQuota(env, user.id);
      try { return await handleLedger(forwarded, scoped); }
      catch (error) { await refundQuota(env, user.id); throw error; }
    }
    return handleLedger(forwarded, scoped);
  }
  if (url.pathname === "/v2/auth/refresh" && request.method === "POST") return refreshAppSession(request, env, user);
  if (url.pathname === "/v2/auth/logout" && request.method === "POST") return logout(request, env);
  if (url.pathname === "/v2/account" && request.method === "DELETE") return deleteAccount(user, env);
  if (url.pathname === "/v2/me" && request.method === "GET") return me(env, user);
  if (url.pathname === "/v2/books/current" && request.method === "GET") return json({ book: publicBook(await currentBook(env, user)) });
  if (url.pathname === "/v2/books/current/refresh" && request.method === "POST") {
    await refreshCurrentPersonalBook(accountEnv(env,user.id));
    return me(env,user);
  }
  if (url.pathname === "/v2/books/inspect" && request.method === "POST") {
    const body = await requireJson<{ url?: string; tableId?: string }>(request);
    if (!body.url) throw new ApiError(400, "url_required", "请填写多维表格链接");
    return json(await inspectBook(env, user, body.url, body.tableId));
  }
  if (url.pathname === "/v2/books/connect" && request.method === "POST") {
    const body = await requireJson<Parameters<typeof connectBook>[2]>(request);
    const connected = await connectBook(env,user,body);
    await connectPersonalBook(accountEnv(env,user.id),{...body,target:{appToken:body.appToken,tableId:body.tableId},mappings:connected.mappings});
    return json({book:publicBook(connected)},201);
  }
  if (url.pathname === "/v2/books/create" && request.method === "POST") {
    const body = await requireJson<Parameters<typeof createBook>[2] & { clientRequestId?: string }>(request);
    if (!body.clientRequestId || !UUID.test(body.clientRequestId)) throw new ApiError(400, "invalid_request_id", "缺少有效的创建请求 ID");
    const key = `v2:create-book:${user.id}:${body.clientRequestId}`;
    const previous = await env.IDEMPOTENCY.get(key);
    if (previous) return json(JSON.parse(previous));
    const created = await createBook(env, user, body);
    await connectPersonalBook(accountEnv(env,user.id), {appToken:created.appToken,tableId:created.tableId,sourceUrl:created.sourceUrl ?? "",target:{appToken:created.appToken,tableId:created.tableId},mappings:created.mappings});
    const response = { book: publicBook(created) };
    await env.IDEMPOTENCY.put(key, JSON.stringify(response), { expirationTtl: 86_400 });
    return json(response, 201);
  }
  if (url.pathname === "/v2/parse" && request.method === "POST") {
    const body = await requireJson<{ imageDataUrl?: string; sharedAt?: string }>(request);
    if (!body.imageDataUrl || !body.sharedAt || !Number.isFinite(Date.parse(body.sharedAt))) throw new ApiError(400, "invalid_parse_request", "缺少图片或分享时间");
    const book = await refreshCurrentBook(env, user);
    const remaining = await consumeQuota(env, user.id);
    try {
      return json({ ...(await parseScreenshot(env, body.imageDataUrl, body.sharedAt, optionsFromBook(book))), quotaRemaining: remaining });
    } catch (error) {
      await refundQuota(env, user.id);
      throw error;
    }
  }
  if (url.pathname === "/v2/records" && request.method === "POST") {
    const input = await requireJson<RecordInput>(request);
    const book = await currentBook(env, user);
    if (!book) throw new ApiError(409, "book_required", "请先设置记账表格");
    validateRecord(input, new Map(book.mappings.filter((item) => item.enabled).map((item) => [item.semanticKey, { options: item.options }])));
    const key = `v2:record:${user.id}:${input.clientRequestId}`;
    const previous = await env.IDEMPOTENCY.get(key);
    if (previous) return json({ ok: true, recordId: previous, duplicate: true });
    const recordId = await createUserRecord(env, user, { ...input, purpose: input.purpose.trim() });
    await env.IDEMPOTENCY.put(key, recordId, { expirationTtl: 60 * 60 * 24 * 30 });
    return json({ ok: true, recordId, duplicate: false }, 201);
  }
  throw new ApiError(404, "not_found", "接口不存在");
}
