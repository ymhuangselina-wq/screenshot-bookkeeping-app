import { randomToken, sha256 } from "./crypto";
import { ApiError, json, requireJson } from "./http";
import type { Env } from "./types";

const encoder = new TextEncoder();
const SESSION_TTL = 30 * 86400000;

export async function passwordDigest(password:string,salt:string): Promise<string> {
  const key = await crypto.subtle.importKey("raw",encoder.encode(password),"PBKDF2",false,["deriveBits"]);
  const bytes = new Uint8Array(await crypto.subtle.deriveBits({name:"PBKDF2",hash:"SHA-256",salt:encoder.encode(salt),iterations:100000},key,256));
  return Array.from(bytes,byte=>byte.toString(16).padStart(2,"0")).join("");
}

function validateCredentials(username:unknown,password:unknown): {username:string;password:string} {
  if (typeof username !== "string" || !/^[a-zA-Z0-9_.-]{3,40}$/.test(username.trim())) throw new ApiError(400,"invalid_username","账号需为 3–40 位字母、数字、点、横线或下划线");
  if (typeof password !== "string" || password.length < 10 || password.length > 128) throw new ApiError(400,"invalid_password","密码需为 10–128 位字符");
  return {username:username.trim().toLowerCase(),password};
}

async function rateLimit(request:Request,env:Env): Promise<void> {
  const now=Date.now();
  const ip=request.headers.get("CF-Connecting-IP") ?? "unknown";
  const key=await sha256(`${ip}:${Math.floor(now/600000)}`);
  await env.DB.prepare("DELETE FROM auth_rate_limits WHERE expires_at<=?").bind(now).run();
  await env.DB.prepare(`INSERT INTO auth_rate_limits(key,attempts,expires_at) VALUES(?,1,?)
    ON CONFLICT(key) DO UPDATE SET attempts=attempts+1`).bind(key,now+600000).run();
  const row=await env.DB.prepare("SELECT attempts FROM auth_rate_limits WHERE key=?").bind(key).first<{attempts:number}>();
  if (!row || row.attempts>20) throw new ApiError(429,"login_rate_limited","尝试过于频繁，请 10 分钟后重试");
}

async function session(env:Env,userId:string,deviceName?:string): Promise<Response> {
  const token=randomToken();const now=Date.now();
  await env.DB.prepare("INSERT INTO sessions(id,user_id,token_hash,device_name,expires_at,created_at) VALUES(?,?,?,?,?,?)")
    .bind(crypto.randomUUID(),userId,await sha256(token),deviceName?.slice(0,100) ?? null,now+SESSION_TTL,now).run();
  return json({sessionToken:token,expiresAt:now+SESSION_TTL});
}

export async function registerLocal(request:Request,env:Env): Promise<Response> {
  await rateLimit(request,env);
  const body=await requireJson<{username?:string;password?:string;inviteCode?:string;name?:string;deviceName?:string}>(request);
  const credentials=validateCredentials(body.username,body.password);
  if (!body.inviteCode?.trim()) throw new ApiError(400,"invite_required","请填写邀请人提供的一次性邀请码");
  const inviteHash=await sha256(body.inviteCode.trim().toUpperCase());
  const now=Date.now();
  const invite=await env.DB.prepare("SELECT id FROM invite_codes WHERE code_hash=? AND status='active' AND (expires_at IS NULL OR expires_at>?)")
    .bind(inviteHash,now).first<{id:string}>();
  if (!invite) throw new ApiError(403,"invite_invalid","邀请码无效、已使用或已过期");
  const duplicate=await env.DB.prepare("SELECT user_id FROM local_accounts WHERE username=?").bind(credentials.username).first();
  if (duplicate) throw new ApiError(409,"username_taken","该账号已被注册，请换一个账号或直接登录");
  const userId=crypto.randomUUID();const salt=randomToken();
  const hash=await passwordDigest(credentials.password,salt);
  let results: D1Result[];
  try {
    results=await env.DB.batch([
      env.DB.prepare(`INSERT INTO users(id,feishu_open_id,tenant_key,name,status,created_at,updated_at)
        SELECT ?,?,'local',?,'active',?,? WHERE EXISTS
        (SELECT 1 FROM invite_codes WHERE id=? AND status='active' AND (expires_at IS NULL OR expires_at>?))`)
        .bind(userId,`local:${userId}`,body.name?.trim().slice(0,40) || credentials.username,now,now,invite.id,now),
      env.DB.prepare(`INSERT INTO local_accounts(user_id,username,password_hash,salt,created_at)
        SELECT ?,?,?,?,? WHERE EXISTS(SELECT 1 FROM users WHERE id=?)`).bind(userId,credentials.username,hash,salt,now,userId),
      env.DB.prepare(`UPDATE invite_codes SET status='used',bound_user_id=?,used_at=?
        WHERE id=? AND status='active' AND EXISTS(SELECT 1 FROM local_accounts WHERE user_id=?)`).bind(userId,now,invite.id,userId),
    ]);
  } catch (error) {
    if (String(error).includes("UNIQUE")) throw new ApiError(409,"username_taken","该账号已被注册，请换一个账号或直接登录");
    throw error;
  }
  if (!results[0].meta.changes || !results[2].meta.changes) throw new ApiError(403,"invite_invalid","邀请码已被使用或已过期");
  return session(env,userId,body.deviceName);
}

export async function loginLocal(request:Request,env:Env): Promise<Response> {
  await rateLimit(request,env);
  const body=await requireJson<{username?:string;password?:string;deviceName?:string}>(request);
  const credentials=validateCredentials(body.username,body.password);
  const row=await env.DB.prepare(`SELECT a.user_id,a.password_hash,a.salt FROM local_accounts a JOIN users u ON u.id=a.user_id
    WHERE a.username=? AND u.status='active'`).bind(credentials.username).first<{user_id:string;password_hash:string;salt:string}>();
  // A dummy digest keeps non-existent usernames on the same costly path.
  const actual=await passwordDigest(credentials.password,row?.salt ?? "missing-account-salt");
  let difference=0;const expected=row?.password_hash ?? "0".repeat(64);
  for(let i=0;i<64;i++) difference |= actual.charCodeAt(i)^expected.charCodeAt(i);
  if (!row || difference) throw new ApiError(401,"invalid_credentials","账号或密码不正确");
  return session(env,row.user_id,body.deviceName);
}
