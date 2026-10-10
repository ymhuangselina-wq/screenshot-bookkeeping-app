import { decryptText, encryptText, randomToken, sha256, loginChallenge } from "./crypto";
import { ApiError, json, requireJson } from "./http";
import type { Env, UserContext, UserCredential } from "./types";

type OAuthTokenResponse = {
  code?: number;
  message?: string;
  access_token?: string;
  refresh_token?: string;
  expires_in?: number;
  refresh_expires_in?: number;
};

type UserInfoResponse = {
  code: number;
  msg?: string;
  data?: { open_id: string; tenant_key: string; name?: string; avatar_url?: string };
};

type PendingOAuth = { inviteHash?: string; deviceName?: string; appState: string; challenge: string; createdAt: number };

const SESSION_TTL_SECONDS = 60 * 60 * 24 * 30;

async function exchangeFeishuCode(env: Env, code: string): Promise<UserCredential> {
  const response = await fetch("https://open.feishu.cn/open-apis/authen/v2/oauth/token", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      grant_type: "authorization_code",
      client_id: env.FEISHU_OAUTH_APP_ID,
      client_secret: env.FEISHU_OAUTH_APP_SECRET,
      code,
      redirect_uri: env.OAUTH_REDIRECT_URL,
    }),
  });
  const body = await response.json<OAuthTokenResponse>();
  if (!response.ok || body.code || !body.access_token || !body.refresh_token) {
    throw new ApiError(502, "oauth_exchange_failed", body.message ?? "飞书授权交换失败");
  }
  const now = Date.now();
  return {
    accessToken: body.access_token,
    refreshToken: body.refresh_token,
    accessExpiresAt: now + (body.expires_in ?? 7200) * 1000,
    refreshExpiresAt: body.refresh_expires_in ? now + body.refresh_expires_in * 1000 : undefined,
  };
}

async function refreshFeishuCredential(env: Env, credential: UserCredential): Promise<UserCredential> {
  const response = await fetch("https://open.feishu.cn/open-apis/authen/v2/oauth/token", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      grant_type: "refresh_token",
      client_id: env.FEISHU_OAUTH_APP_ID,
      client_secret: env.FEISHU_OAUTH_APP_SECRET,
      refresh_token: credential.refreshToken,
    }),
  });
  const body = await response.json<OAuthTokenResponse>();
  if (!response.ok || body.code || !body.access_token || !body.refresh_token) {
    throw new ApiError(401, "reauthorization_required", "飞书授权已失效，请重新登录");
  }
  const now = Date.now();
  return {
    accessToken: body.access_token,
    refreshToken: body.refresh_token,
    accessExpiresAt: now + (body.expires_in ?? 7200) * 1000,
    refreshExpiresAt: body.refresh_expires_in ? now + body.refresh_expires_in * 1000 : credential.refreshExpiresAt,
  };
}

async function userInfo(accessToken: string): Promise<{ openId: string; tenantKey: string; name: string; avatarUrl?: string }> {
  const response = await fetch("https://open.feishu.cn/open-apis/authen/v1/user_info", {
    headers: { authorization: `Bearer ${accessToken}` },
  });
  const body = await response.json<UserInfoResponse>();
  if (!response.ok || body.code !== 0 || !body.data?.open_id || !body.data.tenant_key) {
    throw new ApiError(502, "user_info_failed", body.msg ?? "无法读取飞书用户信息");
  }
  return { openId: body.data.open_id, tenantKey: body.data.tenant_key, name: body.data.name ?? "飞书用户", avatarUrl: body.data.avatar_url };
}

async function storeCredential(env: Env, userId: string, credential: UserCredential): Promise<void> {
  const now = Date.now();
  await env.DB.prepare(`INSERT INTO oauth_credentials
    (user_id, access_token_cipher, refresh_token_cipher, access_expires_at, refresh_expires_at, updated_at)
    VALUES (?, ?, ?, ?, ?, ?)
    ON CONFLICT(user_id) DO UPDATE SET access_token_cipher=excluded.access_token_cipher,
      refresh_token_cipher=excluded.refresh_token_cipher, access_expires_at=excluded.access_expires_at,
      refresh_expires_at=excluded.refresh_expires_at, updated_at=excluded.updated_at`)
    .bind(userId, await encryptText(credential.accessToken, env.TOKEN_ENCRYPTION_KEY),
      await encryptText(credential.refreshToken, env.TOKEN_ENCRYPTION_KEY), credential.accessExpiresAt,
      credential.refreshExpiresAt ?? null, now).run();
}

export async function getUserCredential(env: Env, userId: string): Promise<UserCredential> {
  const row = await env.DB.prepare(`SELECT access_token_cipher, refresh_token_cipher,
    access_expires_at, refresh_expires_at FROM oauth_credentials WHERE user_id=?`).bind(userId).first<{
      access_token_cipher: string; refresh_token_cipher: string; access_expires_at: number; refresh_expires_at: number | null;
    }>();
  if (!row) throw new ApiError(401, "reauthorization_required", "请重新登录飞书");
  let credential: UserCredential = {
    accessToken: await decryptText(row.access_token_cipher, env.TOKEN_ENCRYPTION_KEY),
    refreshToken: await decryptText(row.refresh_token_cipher, env.TOKEN_ENCRYPTION_KEY),
    accessExpiresAt: row.access_expires_at,
    refreshExpiresAt: row.refresh_expires_at ?? undefined,
  };
  if (credential.accessExpiresAt <= Date.now() + 60_000) {
    credential = await refreshFeishuCredential(env, credential);
    await storeCredential(env, userId, credential);
  }
  return credential;
}

export async function accessTokenForUser(env: Env, userId: string): Promise<string> {
  const account = await env.DB.prepare("SELECT feishu_open_id FROM users WHERE id=?")
    .bind(userId).first<{ feishu_open_id: string }>();
  if (!account) throw new ApiError(401, "reauthorization_required", "请重新登录");
  if (!account.feishu_open_id.startsWith("local:")) return (await getUserCredential(env, userId)).accessToken;

  const response = await fetch("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ app_id: env.FEISHU_APP_ID, app_secret: env.FEISHU_APP_SECRET }),
  });
  const body = await response.json<{ code: number; msg?: string; tenant_access_token?: string }>();
  if (!response.ok || body.code !== 0 || !body.tenant_access_token) {
    throw new ApiError(502, "feishu_auth_failed", body.msg ?? "飞书应用认证失败");
  }
  return body.tenant_access_token;
}

export async function startOAuth(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  if (!env.FEISHU_OAUTH_APP_ID || !env.FEISHU_OAUTH_APP_SECRET) throw new ApiError(503, "oauth_not_configured", "飞书登录尚未配置，请联系邀请人");
  const appState = url.searchParams.get("appState") ?? "";
  const challenge = url.searchParams.get("challenge") ?? "";
  if (!/^[A-Za-z0-9_-]{43,128}$/.test(appState) || !/^[a-f0-9]{64}$/.test(challenge)) throw new ApiError(400,"invalid_login_request","请从新版 App 发起登录");
  const inviteCode = url.searchParams.get("inviteCode")?.trim();
  const inviteHash = inviteCode ? await sha256(inviteCode.toUpperCase()) : undefined;
  if (inviteHash) {
    const invite = await env.DB.prepare(`SELECT status, expires_at FROM invite_codes WHERE code_hash=?`).bind(inviteHash)
      .first<{ status: string; expires_at: number | null }>();
    if (!invite || invite.status === "disabled" || (invite.expires_at && invite.expires_at < Date.now())) {
      throw new ApiError(403, "invite_invalid", "邀请码无效或已过期");
    }
  }
  const state = randomToken();
  const pending: PendingOAuth = { inviteHash, appState, challenge, deviceName: url.searchParams.get("deviceName")?.slice(0, 100), createdAt: Date.now() };
  await env.DB.prepare("DELETE FROM oauth_pending WHERE expires_at<=?").bind(Date.now()).run();
  await env.DB.prepare("INSERT INTO oauth_pending(key,value,expires_at) VALUES(?,?,?)").bind(`oauth-state:${state}`,JSON.stringify(pending),Date.now()+600000).run();
  const authorize = new URL("https://accounts.feishu.cn/open-apis/authen/v1/authorize");
  if (!env.FEISHU_OAUTH_APP_ID || !env.FEISHU_OAUTH_APP_SECRET) throw new ApiError(503, "oauth_not_configured", "跨企业飞书登录尚未配置");
  authorize.searchParams.set("app_id", env.FEISHU_OAUTH_APP_ID);
  authorize.searchParams.set("redirect_uri", env.OAUTH_REDIRECT_URL);
  authorize.searchParams.set("state", state);
  return Response.redirect(authorize.toString(), 302);
}

async function oauthCallbackInternal(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  if (url.searchParams.get("error")) throw new ApiError(400, "oauth_denied", "你已取消飞书授权");
  const state = url.searchParams.get("state");
  const code = url.searchParams.get("code");
  if (!state || !code) throw new ApiError(400, "oauth_callback_invalid", "飞书授权未完成");
  const row = await env.DB.prepare("DELETE FROM oauth_pending WHERE key=? AND expires_at>? RETURNING value")
    .bind(`oauth-state:${state}`,Date.now()).first<{value:string}>();
  if (!row) throw new ApiError(400, "oauth_state_expired", "授权已过期，请返回 App 重试");
  const pending = JSON.parse(row.value) as PendingOAuth;
  const credential = await exchangeFeishuCode(env, code);
  const profile = await userInfo(credential.accessToken);
  const existing = await env.DB.prepare("SELECT id FROM users WHERE feishu_open_id=?").bind(profile.openId).first<{ id: string }>();
  const userId = existing?.id ?? crypto.randomUUID();
  const now = Date.now();
  const invite = pending.inviteHash ? await env.DB.prepare("SELECT id, status, bound_user_id, expires_at FROM invite_codes WHERE code_hash=?")
    .bind(pending.inviteHash).first<{ id: string; status: string; bound_user_id: string | null; expires_at: number | null }>() : null;
  if (!existing && (!invite || invite.status !== "active" || (invite.expires_at !== null && invite.expires_at <= now))) throw new ApiError(403, "invite_required", "新用户需要有效的一次性邀请码");
  if (existing) {
    await env.DB.prepare("UPDATE users SET tenant_key=?,name=?,avatar_url=?,updated_at=? WHERE id=?")
      .bind(profile.tenantKey,profile.name,profile.avatarUrl ?? null,now,userId).run();
  } else {
    // D1 batch is transactional. A concurrent registration sees the consumed invite
    // and cannot create a second user with the same one-time code.
    const results = await env.DB.batch([
      env.DB.prepare(`INSERT INTO users(id,feishu_open_id,tenant_key,name,avatar_url,status,created_at,updated_at)
        SELECT ?,?,?,?,?,'active',?,? WHERE EXISTS
        (SELECT 1 FROM invite_codes WHERE id=? AND status='active' AND (expires_at IS NULL OR expires_at>?))`)
        .bind(userId,profile.openId,profile.tenantKey,profile.name,profile.avatarUrl ?? null,now,now,invite!.id,now),
      env.DB.prepare(`UPDATE invite_codes SET status='used',bound_user_id=?,used_at=?
        WHERE id=? AND status='active' AND EXISTS(SELECT 1 FROM users WHERE id=?)`)
        .bind(userId,now,invite!.id,userId),
    ]);
    if (!results[0].meta.changes || !results[1].meta.changes) throw new ApiError(403,"invite_required","邀请码已被使用或已过期");
  }
  await storeCredential(env, userId, credential);
  const exchangeCode = randomToken();
  await env.DB.prepare("INSERT INTO oauth_pending(key,value,user_id,expires_at) VALUES(?,?,?,?)")
    .bind(`oauth-exchange:${exchangeCode}`,JSON.stringify({userId,deviceName:pending.deviceName,challenge:pending.challenge}),userId,Date.now()+300000).run();
  return Response.redirect(`screenshotbookkeeping://oauth?code=${encodeURIComponent(exchangeCode)}&appState=${encodeURIComponent(pending.appState)}`, 302);
}

export async function oauthCallback(request: Request, env: Env): Promise<Response> {
  try {
    return await oauthCallbackInternal(request, env);
  } catch (error) {
    const message = error instanceof ApiError ? error.message : "飞书登录失败，请重试";
    return Response.redirect(`screenshotbookkeeping://oauth?error=${encodeURIComponent(message)}`, 302);
  }
}

export async function exchangeAppSession(request: Request, env: Env): Promise<Response> {
  const body = await requireJson<{ code?: string; verifier?: string }>(request);
  if (!body.code) throw new ApiError(400, "exchange_code_required", "缺少授权交换码");
  if (!body.verifier || !/^[A-Za-z0-9_-]{43,128}$/.test(body.verifier)) throw new ApiError(400,"verifier_required","请重新发起登录");
  const row = await env.DB.prepare("DELETE FROM oauth_pending WHERE key=? AND expires_at>? AND json_extract(value,'$.challenge')=? RETURNING value")
    .bind(`oauth-exchange:${body.code}`,Date.now(),await loginChallenge(body.verifier)).first<{value:string}>();
  if (!row) throw new ApiError(400,"exchange_code_expired","登录凭证已过期或不属于此设备，请重试");
  const pending = JSON.parse(row.value) as {userId:string;deviceName?:string};
  const token = randomToken();
  const now = Date.now();
  await env.DB.prepare("INSERT INTO sessions(id,user_id,token_hash,device_name,expires_at,created_at) VALUES(?,?,?,?,?,?)")
    .bind(crypto.randomUUID(), pending.userId, await sha256(token), pending.deviceName ?? null, now + SESSION_TTL_SECONDS * 1000, now).run();
  return json({ sessionToken: token, expiresAt: now + SESSION_TTL_SECONDS * 1000 });
}

export async function authenticateUser(request: Request, env: Env): Promise<UserContext> {
  const header = request.headers.get("authorization");
  if (!header?.startsWith("Bearer ")) throw new ApiError(401, "login_required", "请先登录");
  const row = await env.DB.prepare(`SELECT u.id,u.feishu_open_id,u.tenant_key,u.name,s.expires_at
    FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND u.status='active'`)
    .bind(await sha256(header.slice(7))).first<{ id: string; feishu_open_id: string; tenant_key: string; name: string; expires_at: number }>();
  if (!row || row.expires_at < Date.now()) throw new ApiError(401, "session_expired", "登录已过期，请重新登录");
  return { id: row.id, openId: row.feishu_open_id, tenantKey: row.tenant_key, name: row.name };
}

export async function logout(request: Request, env: Env): Promise<Response> {
  const token = request.headers.get("authorization")?.slice(7) ?? "";
  await env.DB.prepare("DELETE FROM sessions WHERE token_hash=?").bind(await sha256(token)).run();
  return json({ ok: true });
}

export async function refreshAppSession(request: Request, env: Env, user: UserContext): Promise<Response> {
  const oldToken = request.headers.get("authorization")?.slice(7) ?? "";
  const token = randomToken();
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM sessions WHERE token_hash=?").bind(await sha256(oldToken)),
    env.DB.prepare("INSERT INTO sessions(id,user_id,token_hash,expires_at,created_at) VALUES(?,?,?,?,?)")
      .bind(crypto.randomUUID(), user.id, await sha256(token), now + SESSION_TTL_SECONDS * 1000, now),
  ]);
  return json({ sessionToken: token, expiresAt: now + SESSION_TTL_SECONDS * 1000 });
}

export async function createInvite(env: Env, expiresInDays = 30): Promise<Response> {
  if (!Number.isFinite(expiresInDays)) throw new ApiError(400,"invalid_expiry","邀请码有效天数无效");
  const code = randomToken(9).toUpperCase();
  const now = Date.now();
  await env.DB.prepare("INSERT INTO invite_codes(id,code_hash,status,expires_at,created_at) VALUES(?,?,'active',?,?)")
    .bind(crypto.randomUUID(), await sha256(code), now + Math.max(1, Math.min(expiresInDays, 365)) * 86_400_000, now).run();
  return json({ code, expiresAt: now + Math.max(1, Math.min(expiresInDays, 365)) * 86_400_000 }, 201);
}

export async function deleteAccount(user: UserContext, env: Env): Promise<Response> {
  await env.DB.batch([
    env.DB.prepare("UPDATE invite_codes SET bound_user_id=NULL,status='disabled' WHERE bound_user_id=?").bind(user.id),
    env.DB.prepare("DELETE FROM users WHERE id=?").bind(user.id),
  ]);
  return json({ ok: true });
}
