import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { afterEach, describe, expect, it, vi } from "vitest";
import { accountEnv } from "../src/account-storage";
import { accessTokenForUser, authenticateUser, deleteAccount, exchangeAppSession } from "../src/auth";
import { loginChallenge, sha256 } from "../src/crypto";
import { getBookCatalog } from "../src/personal-books";
import { registerLocal, loginLocal } from "../src/local-auth";
import worker from "../src/index";
import type { Env } from "../src/types";

function fixture() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec("PRAGMA foreign_keys=ON");
  sqlite.exec(readFileSync(new URL("../migrations/0001_multiplayer.sql",import.meta.url),"utf8"));
  sqlite.exec(readFileSync(new URL("../migrations/0002_account_storage.sql",import.meta.url),"utf8"));
  sqlite.exec(readFileSync(new URL("../migrations/0003_local_accounts.sql",import.meta.url),"utf8"));
  const prepare = (sql:string) => {
    let args: Array<string|number|null> = [];
    const statement = {
      bind(...values: Array<string|number|null>) { args=values;return statement; },
      async first<T>() { return (sqlite.prepare(sql).get(...args) ?? null) as T|null; },
      async all<T>() { return {results:sqlite.prepare(sql).all(...args) as T[]}; },
      async run() { const result=sqlite.prepare(sql).run(...args);return {meta:{changes:Number(result.changes)}}; },
    };
    return statement;
  };
  const DB = {prepare,async batch(statements:Array<{run:()=>Promise<unknown>}>) {
    sqlite.exec("BEGIN");
    try { const result=[];for (const s of statements) result.push(await s.run());sqlite.exec("COMMIT");return result; }
    catch(error) { sqlite.exec("ROLLBACK");throw error; }
  }};
  for (const id of ["alice","bob"]) sqlite.prepare("INSERT INTO users(id,feishu_open_id,tenant_key,name,status,created_at,updated_at) VALUES(?,?,? ,?,'active',0,0)").run(id,id,"tenant",id);
  const env={DB,IDEMPOTENCY:{},PERSONAL_ACCESS_KEY:"admin",FEISHU_APP_TOKEN:"PRIVATE_ADMIN_TABLE",FEISHU_TABLE_ID:"private"} as unknown as Env;
  return {env,sqlite};
}
const user=(id:string)=>({id,openId:id,tenantKey:"tenant",name:id});
afterEach(()=>vi.restoreAllMocks());

describe("multiplayer account boundary",()=>{
  it("isolates catalogs and request IDs even when users choose identical keys",async()=>{
    const {env}=fixture();const a=accountEnv(env,"alice"),b=accountEnv(env,"bob");
    await a.IDEMPOTENCY.put("settings:books-v1",JSON.stringify({books:[{id:"a"}],currentBookId:"a"}));
    await b.IDEMPOTENCY.put("settings:books-v1",JSON.stringify({books:[{id:"b"}],currentBookId:"b"}));
    await a.IDEMPOTENCY.put("record:same-id","record-alice");
    expect(await b.IDEMPOTENCY.get("record:same-id")).toBeNull();
    expect(await a.IDEMPOTENCY.get("settings:books-v1","json")).toMatchObject({currentBookId:"a"});
    expect(await b.IDEMPOTENCY.get("settings:books-v1","json")).toMatchObject({currentBookId:"b"});
  });
  it("never imports the owner's default table into a new account",async()=>{
    const {env}=fixture();expect(await getBookCatalog(accountEnv(env,"alice"))).toEqual({books:[],currentBookId:null});
  });
  it("rejects another user's book ID before making a Feishu request",async()=>{
    const {env}=fixture();const a=accountEnv(env,"alice");
    await a.IDEMPOTENCY.put("settings:books-v1",JSON.stringify({books:[{id:"a",appToken:"app",tableId:"t"}],currentBookId:"a"}));
    await expect(getBookCatalog(accountEnv(env,"alice","bob-book"))).rejects.toMatchObject({status:404});
  });
  it("removes a bound invitation and cascades account records on deletion",async()=>{
    const {env,sqlite}=fixture();await accountEnv(env,"alice").IDEMPOTENCY.put("catalog","private");
    sqlite.prepare("INSERT INTO invite_codes(id,code_hash,status,bound_user_id,created_at) VALUES('invite','hash','used','alice',0)").run();
    await deleteAccount(user("alice"),env);
    expect(sqlite.prepare("SELECT count(*) AS n FROM account_storage WHERE user_id='alice'").get()).toMatchObject({n:0});
    expect(sqlite.prepare("SELECT status,bound_user_id FROM invite_codes").get()).toMatchObject({status:"disabled",bound_user_id:null});
    expect(sqlite.prepare("SELECT id FROM users WHERE id='bob'").get()).toMatchObject({id:"bob"});
  });
  it("requires a user session on the new ledger routes; admin token cannot substitute",async()=>{
    const {env}=fixture();const response=await worker.fetch(new Request("https://api.test/v2/ledger/books",{headers:{authorization:"Bearer admin"}}),env);
    expect(response.status).toBe(401);
  });
  it("authenticates only the session owner",async()=>{
    const {env,sqlite}=fixture();sqlite.prepare("INSERT INTO sessions(id,user_id,token_hash,expires_at,created_at) VALUES(?,?,?,?,0)").run("s","alice",await sha256("alice-token"),Date.now()+100000);
    expect(await authenticateUser(new Request("https://api.test",{headers:{authorization:"Bearer alice-token"}}),env)).toMatchObject({id:"alice"});
    await expect(authenticateUser(new Request("https://api.test",{headers:{authorization:"Bearer other-token"}}),env)).rejects.toMatchObject({status:401});
  });
  it("does not erase valid account state on expired storage reads",async()=>{
    const {env,sqlite}=fixture();await accountEnv(env,"alice").IDEMPOTENCY.put("expired","secret",{expirationTtl:1});
    sqlite.prepare("UPDATE account_storage SET expires_at=0").run();
    expect(await accountEnv(env,"alice").IDEMPOTENCY.get("expired")).toBeNull();
  });
});

describe("device-bound login exchange",()=>{
  it("rejects an intercepted code without consuming it; consumes it once for the correct device",async()=>{
    const {env,sqlite}=fixture();const verifier="a".repeat(43);
    sqlite.prepare("INSERT INTO oauth_pending(key,value,user_id,expires_at) VALUES(?,?,?,?)").run("oauth-exchange:code",JSON.stringify({userId:"alice",challenge:await loginChallenge(verifier)}),"alice",Date.now()+100000);
    const req=(v:string)=>new Request("https://api.test/v2/auth/exchange",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({code:"code",verifier:v})});
    await expect(exchangeAppSession(req("b".repeat(43)),env)).rejects.toMatchObject({status:400});
    const response=await exchangeAppSession(req(verifier),env);const body=await response.json() as {sessionToken:string};
    expect(body.sessionToken).toBeTruthy();
    await expect(exchangeAppSession(req(verifier),env)).rejects.toMatchObject({status:400});
    expect(sqlite.prepare("SELECT count(*) AS n FROM sessions WHERE user_id='alice'").get()).toMatchObject({n:1});
  });
});


describe("local account invitation login",()=>{
  const request=(path:string,body:unknown)=>new Request(`https://api.test/v2/auth/${path}`,{method:"POST",headers:{"content-type":"application/json","CF-Connecting-IP":"127.0.0.1"},body:JSON.stringify(body)});
  it("registers a private account, hashes its password, and signs in without Feishu",async()=>{
    const {env,sqlite}=fixture();
    sqlite.prepare("INSERT INTO invite_codes(id,code_hash,status,expires_at,created_at) VALUES('new',?,'active',?,0)").run(await sha256("INVITE"),Date.now()+60000);
    const payload={username:"Tester",password:"test-passphrase-123",inviteCode:"invite"};
    const response=await registerLocal(request("register",payload),env);
    expect(response.status).toBe(200);
    const body=await response.json() as {sessionToken:string};
    const identity=await authenticateUser(new Request("https://api.test",{headers:{authorization:`Bearer ${body.sessionToken}`}}),env);
    expect(identity.tenantKey).toBe("local");
    expect(await getBookCatalog(accountEnv(env,identity.id))).toEqual({books:[],currentBookId:null});
    const stored=sqlite.prepare("SELECT password_hash FROM local_accounts WHERE username='tester'").get() as {password_hash:string};
    expect(stored.password_hash).toHaveLength(64);expect(stored.password_hash).not.toBe(payload.password);
    expect((await loginLocal(request("login",payload),env)).status).toBe(200);
    await expect(registerLocal(request("register",{...payload,username:"second"}),env)).rejects.toMatchObject({status:403});
    await expect(loginLocal(request("login",{...payload,password:"wrong-passphrase"}),env)).rejects.toMatchObject({status:401});
  });
  it("uses the deployer's Feishu app credential for a local account",async()=>{
    const {env,sqlite}=fixture();
    sqlite.prepare("INSERT INTO users(id,feishu_open_id,tenant_key,name,status,created_at,updated_at) VALUES('local-user','local:local-user','local','owner','active',0,0)").run();
    env.FEISHU_APP_ID="app-id";env.FEISHU_APP_SECRET="app-secret";
    const request=vi.fn().mockResolvedValue(new Response(JSON.stringify({code:0,tenant_access_token:"tenant-token"}),{status:200}));
    vi.stubGlobal("fetch",request);
    expect(await accessTokenForUser(env,"local-user")).toBe("tenant-token");
    expect(JSON.parse(request.mock.calls[0][1].body)).toEqual({app_id:"app-id",app_secret:"app-secret"});
  });
  it("rejects expired invitations and limits repeated login attempts",async()=>{
    const {env,sqlite}=fixture();sqlite.prepare("INSERT INTO invite_codes(id,code_hash,status,expires_at,created_at) VALUES('expired',?,'active',0,0)").run(await sha256("EXPIRED"));
    await expect(registerLocal(request("register",{username:"test",password:"test-passphrase",inviteCode:"expired"}),env)).rejects.toMatchObject({status:403});
    sqlite.prepare("UPDATE auth_rate_limits SET attempts=20").run();
    await expect(loginLocal(request("login",{username:"test",password:"test-passphrase"}),env)).rejects.toMatchObject({status:429});
  });
});
