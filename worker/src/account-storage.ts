import type { Env } from "./types";

// Only get/put/delete are used by ledger services. D1 provides consistent reads,
// unlike KV, and the FK erases this namespace when an account is deleted.
export function accountEnv(env: Env, userId: string, bookId?: string): Env {
  const storage = {
    async get(key: string, type?: string) {
      const row = await env.DB.prepare("SELECT value FROM account_storage WHERE user_id=? AND key=? AND (expires_at IS NULL OR expires_at>?)")
        .bind(userId, key, Date.now()).first<{value:string}>();
      return row ? (type === "json" ? JSON.parse(row.value) : row.value) : null;
    },
    async put(key: string, value: string, options?: {expirationTtl?:number}) {
      await env.DB.prepare(`INSERT INTO account_storage(user_id,key,value,expires_at) VALUES(?,?,?,?)
        ON CONFLICT(user_id,key) DO UPDATE SET value=excluded.value,expires_at=excluded.expires_at`)
        .bind(userId,key,value,options?.expirationTtl ? Date.now()+options.expirationTtl*1000 : null).run();
    },
    async delete(key: string) {
      await env.DB.prepare("DELETE FROM account_storage WHERE user_id=? AND key=?").bind(userId,key).run();
    },
  };
  return {...env,USER_ID:userId,REQUEST_BOOK_ID:bookId,IDEMPOTENCY:storage as unknown as KVNamespace,
    FEISHU_APP_TOKEN:"",FEISHU_WIKI_TOKEN:undefined,FEISHU_TABLE_ID:""};
}
