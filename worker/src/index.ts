import { handleV2 } from "./v2";
import { handleLedger } from "./ledger";
import { ApiError, authenticate, json } from "./http";
import type { Env } from "./types";

function friendTestEnv(env: Env): Env {
  const prefix = "friend-test:";
  const storage = {
    async get(key: string, type?: string) {
      const value = await env.IDEMPOTENCY.get(`${prefix}${key}`);
      return value && type === "json" ? JSON.parse(value) : value;
    },
    put: (key: string, value: string, options?: { expirationTtl?: number }) => env.IDEMPOTENCY.put(`${prefix}${key}`, value, options),
    delete: (key: string) => env.IDEMPOTENCY.delete(`${prefix}${key}`),
  };
  return { ...env, IDEMPOTENCY: storage as unknown as KVNamespace, FEISHU_APP_TOKEN: "", FEISHU_TABLE_ID: "" };
}

async function handle(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  if (url.pathname === "/health" && request.method === "GET") return json({ok:true,build:"20261006-multiplayer"});
  const response = await handleV2(request, env, url);
  if (response) return response;
  if (env.FRIEND_TEST_ACCESS_KEY && request.headers.get("authorization") === `Bearer ${env.FRIEND_TEST_ACCESS_KEY}`) {
    return handleLedger(request, friendTestEnv(env));
  }
  authenticate(request, env.PERSONAL_ACCESS_KEY);
  return handleLedger(request, env);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const requestId = crypto.randomUUID();
    const started = Date.now();
    try {
      const response = await handle(request, env);
      console.log(JSON.stringify({ requestId, status: response.status, durationMs: Date.now() - started }));
      return response;
    } catch (error) {
      const known = error instanceof ApiError;
      const status = known ? error.status : 500;
      const code = known ? error.code : "internal_error";
      console.error(JSON.stringify({ requestId, status, code, durationMs: Date.now() - started }));
      return json({ error: { code, message: known ? error.message : "服务暂时不可用", requestId } }, status);
    }
  },
};
