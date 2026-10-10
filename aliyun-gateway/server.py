import base64, copy, hashlib, hmac, json, os, re, threading, time, urllib.error, urllib.parse, urllib.request, uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT=int(os.getenv("FC_SERVER_PORT","9000")); BUCKET=os.getenv("OSS_BUCKET","screenshot-bookkeeping-hym-20260920")
# FC's injected internal OSS endpoint has shown 10-20 second stalls for this
# function.  The public endpoint stays inside the same region and is much more
# predictable for these tiny catalog/idempotency objects.
ENDPOINT=os.getenv("OSS_ENDPOINT","oss-cn-hangzhou.aliyuncs.com").replace("-internal.aliyuncs.com",".aliyuncs.com")
BUILD="20261011.1-mvp-1.0"
DS=os.getenv("DASHSCOPE_BASE_URL","https://dashscope.aliyuncs.com/compatible-mode/v1").rstrip("/"); MODEL=os.getenv("DASHSCOPE_MODEL","qwen3-vl-plus")
CATALOG_KEY="bookkeeping/catalog.json"; TOKEN={"value":None,"expires":0}; TOKEN_LOCK=threading.Lock()
CATALOG_CACHE={"value":None,"expires":0}; CATALOG_LOCK=threading.Lock()
CATALOG_PERSIST={"generation":0}; CATALOG_PERSIST_STATE_LOCK=threading.Lock(); CATALOG_PERSIST_WRITE_LOCK=threading.Lock()
SEED={"currentBookId":None,"books":[]}

class ApiError(Exception):
 def __init__(self,status,code,message): super().__init__(message); self.status=status; self.code=code
def jb(v): return json.dumps(v,ensure_ascii=False,separators=(",",":")).encode()
def web(url,method="GET",headers=None,body=None,timeout=90):
 req=urllib.request.Request(url,data=body,headers=headers or {},method=method)
 try:
  with urllib.request.urlopen(req,timeout=timeout) as r: return r.status,dict(r.headers.items()),r.read()
 except urllib.error.HTTPError as e: return e.code,dict(e.headers.items()),e.read()
 except (urllib.error.URLError,TimeoutError) as e:
  raise ApiError(504,"upstream_timeout",f"上游服务连接超时（{getattr(e,'reason',e)}）")

class Oss:
 def __init__(self,headers):
  h={k.lower():v for k,v in headers.items()}; self.ak=h.get("x-fc-access-key-id") or os.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"); self.sk=h.get("x-fc-access-key-secret") or os.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"); self.sts=h.get("x-fc-security-token") or os.getenv("ALIBABA_CLOUD_SECURITY_TOKEN")
 @property
 def ready(self): return bool(self.ak and self.sk and self.sts)
 def call(self,key,method="GET",data=None):
  if not self.ready: raise ApiError(503,"oss_role_missing","函数角色凭证未生效，请重新部署函数")
  date=formatdate(usegmt=True); typ="application/json" if data is not None else ""; resource=f"/{BUCKET}/{key}"; canonical=f"{method}\n\n{typ}\n{date}\nx-oss-security-token:{self.sts}\n{resource}"; sig=base64.b64encode(hmac.new(self.sk.encode(),canonical.encode(),hashlib.sha1).digest()).decode()
  headers={"Date":date,"Authorization":f"OSS {self.ak}:{sig}","x-oss-security-token":self.sts}
  if data is not None: headers["Content-Type"]=typ
  return web(f"https://{BUCKET}.{ENDPOINT}/{urllib.parse.quote(key)}",method,headers,data,8)
 def get(self,key):
  s,_,b=self.call(key)
  if s==404:return None
  if s>=400:raise ApiError(502,"oss_read_failed",f"OSS 读取失败（{s}）")
  return json.loads(b)
 def put(self,key,value):
  s,_,_=self.call(key,"PUT",jb(value))
  if s>=300:raise ApiError(502,"oss_write_failed",f"OSS 写入失败（{s}）")
 def delete(self,key): self.call(key,"DELETE")

def fs_token():
 now=time.time()
 with TOKEN_LOCK:
  if TOKEN["value"] and TOKEN["expires"]>now+60:return TOKEN["value"]
  app,secret=os.getenv("FEISHU_APP_ID"),os.getenv("FEISHU_APP_SECRET")
  if not app or not secret:raise ApiError(503,"feishu_secret_missing","阿里云函数尚未配置飞书 App ID 和 App Secret")
  s,_,b=web("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal","POST",{"Content-Type":"application/json"},jb({"app_id":app,"app_secret":secret}),8); d=json.loads(b)
  if s>=400 or d.get("code")!=0 or not d.get("tenant_access_token"):raise ApiError(502,"feishu_auth_failed",d.get("msg","飞书认证失败"))
  TOKEN.update(value=d["tenant_access_token"],expires=now+d.get("expire",7000)); return TOKEN["value"]
def fs(path,method="GET",payload=None):
 s,_,b=web("https://open.feishu.cn/open-apis"+path,method,{"Authorization":"Bearer "+fs_token(),"Content-Type":"application/json"},None if payload is None else jb(payload),12); d=json.loads(b)
 if s>=400 or d.get("code")!=0:raise ApiError(502,"feishu_api_failed",d.get("msg","飞书接口调用失败"))
 return d.get("data",{})
def list_fields(book):return fs(f"/bitable/v1/apps/{book['appToken']}/tables/{book['tableId']}/fields?page_size=100").get("items",[])
def public_fields(items,old=None):
 previous={x["fieldId"]:x for x in old or []}; order={x["fieldId"]:i for i,x in enumerate(old or [])}; out=[]
 for x in items:
  prior=previous.get(x["field_id"],{});all_options=[o["name"] for o in (x.get("property") or {}).get("options",[])];hidden=[name for name in prior.get("hiddenOptions",[]) if name in all_options]
  out.append({"fieldId":x["field_id"],"displayName":x["field_name"],"fieldType":x["type"],"primary":x.get("is_primary") is True,"options":[name for name in all_options if name not in hidden],"hiddenOptions":hidden,"required":prior.get("required",False)})
 return sorted(out,key=lambda x:order.get(x["fieldId"],len(order)))

def rename_option_payload(actual,old_name,new_name):
 clean=new_name.strip();options=copy.deepcopy((actual.get("property") or {}).get("options",[]))
 if not clean:raise ApiError(400,"option_required","选项名称不能为空")
 if old_name!=clean and any(x.get("name")==clean for x in options):raise ApiError(409,"duplicate_option","选项名称已存在")
 target=next((x for x in options if x.get("name")==old_name),None)
 if not target:raise ApiError(404,"option_not_found","选项不存在，请刷新后重试")
 target["name"]=clean
 return {"field_name":actual["field_name"],"type":actual["type"],"property":{"options":options}}
def catalog(store):
 now=time.time()
 with CATALOG_LOCK:
  if CATALOG_CACHE["value"] is not None and CATALOG_CACHE["expires"]>now:return json.loads(json.dumps(CATALOG_CACHE["value"]))
  c=store.get(CATALOG_KEY)
  if c is None:c=json.loads(json.dumps(SEED));store.put(CATALOG_KEY,c)
  repaired=False
  for book in c.get("books",[]):
   if not isinstance(book.get("lastUsedAt"),(int,float)) or book.get("lastUsedAt",0)<=0:book["lastUsedAt"]=int(now*1000);repaired=True
  if repaired:store.put(CATALOG_KEY,c)
  CATALOG_CACHE.update(value=json.loads(json.dumps(c)),expires=now+300);return c
def save_catalog(store,c):
 store.put(CATALOG_KEY,c)
 with CATALOG_LOCK:CATALOG_CACHE.update(value=json.loads(json.dumps(c)),expires=time.time()+300)
def save_catalog_async(store,c):
 snapshot=json.loads(json.dumps(c))
 with CATALOG_LOCK:CATALOG_CACHE.update(value=snapshot,expires=time.time()+300)
 with CATALOG_PERSIST_STATE_LOCK:
  CATALOG_PERSIST["generation"]+=1;generation=CATALOG_PERSIST["generation"]
 def persist():
  with CATALOG_PERSIST_WRITE_LOCK:
   with CATALOG_PERSIST_STATE_LOCK:
    if generation!=CATALOG_PERSIST["generation"]:return
   for attempt in range(3):
    try:
     tick=time.time();store.put(CATALOG_KEY,snapshot);print(json.dumps({"path":"catalog-background-write","generation":generation,"attempt":attempt+1,"durationMs":int((time.time()-tick)*1000)},ensure_ascii=False),flush=True);return
    except Exception as e:
     print(json.dumps({"path":"catalog-background-write","generation":generation,"attempt":attempt+1,"error":str(e)},ensure_ascii=False),flush=True)
     time.sleep(attempt+1)
 threading.Thread(target=persist,name="catalog-writer",daemon=True).start()
def current(c):return next((x for x in c["books"] if x["id"]==c.get("currentBookId")),None)
def select_book(c,book_id,now_ms=None):
 chosen=next((x for x in c.get("books",[]) if x.get("id")==book_id),None)
 if not chosen:raise ApiError(404,"book_not_found","账本不存在")
 chosen["lastUsedAt"]=now_ms if now_ms is not None else int(time.time()*1000)
 c["currentBookId"]=chosen["id"]
 c["books"]=[chosen]+[x for x in c["books"] if x.get("id")!=chosen["id"]]
 return c
def refresh(book):
 with ThreadPoolExecutor(max_workers=3) as pool:
  fields_future=pool.submit(list_fields,book);tables_future=pool.submit(fs,f"/bitable/v1/apps/{book['appToken']}/tables?page_size=100");app_future=pool.submit(fs,f"/bitable/v1/apps/{book['appToken']}")
  actual=fields_future.result();tables=tables_future.result().get("items",[]);app=app_future.result().get("app",{})
 book["fields"]=public_fields(actual,book.get("fields"));book["tableName"]=next((x["name"] for x in tables if x["table_id"]==book["tableId"]),book["tableName"]);book["bookName"]=app.get("name",book["bookName"]);return book
def config(book):
 fields=book.get("fields",[])
 def opts(pattern):return next((x.get("options",[]) for x in fields if re.search(pattern,x["displayName"])),[])
 return {"paymentPlatforms":opts(r"支付平台|支付渠道"),"tags":opts(r"标签|分类"),"projects":opts(r"归属项目|项目"),"targetUrl":book["sourceUrl"],"bookName":book["bookName"],"tableName":book["tableName"],"fieldNames":{},"fields":fields}
def target_from_url(raw):
 try:u=urllib.parse.urlparse(raw.strip())
 except:raise ApiError(400,"invalid_feishu_url","请粘贴完整的飞书多维表格链接")
 if not (u.hostname and (u.hostname.endswith("feishu.cn") or u.hostname.endswith("larksuite.com"))):raise ApiError(400,"invalid_feishu_url","这不是飞书多维表格链接")
 wiki=re.search(r"/wiki/([^/]+)",u.path);base=re.search(r"/base/([^/]+)",u.path);q=urllib.parse.parse_qs(u.query)
 if not wiki and not base:raise ApiError(400,"invalid_feishu_url","链接中未找到多维表格标识")
 return {**({"wikiToken":wiki.group(1)} if wiki else {"appToken":base.group(1)}),"tableId":q.get("table",[None])[0],"sourceUrl":raw.strip()}
def resolve_app(target):
 if target.get("appToken"):return target["appToken"]
 token=target.get("wikiToken");d=fs("/wiki/v2/spaces/get_node?"+urllib.parse.urlencode({"token":token}));node=d.get("node",{})
 if node.get("obj_type")!="bitable" or not node.get("obj_token"):raise ApiError(422,"invalid_wiki_node","飞书知识库节点不是多维表格")
 return node["obj_token"]
def inspect_book(raw,selected=None):
 target=target_from_url(raw);app=resolve_app(target);tables=fs(f"/bitable/v1/apps/{app}/tables?page_size=100").get("items",[]);tid=selected or target.get("tableId") or (tables[0]["table_id"] if len(tables)==1 else None)
 base={"appToken":app,"sourceUrl":target["sourceUrl"],"target":target,"tables":tables}
 if not tid:return {**base,"selectionRequired":True,"fields":[],"suggestions":[],"empty":False}
 table=next((x for x in tables if x["table_id"]==tid),None)
 if not table:raise ApiError(404,"table_not_found","未找到指定数据表")
 holder={"appToken":app,"tableId":tid};fields=public_fields(list_fields(holder));return {**base,"selectionRequired":False,"target":{**target,"tableId":tid},"table":table,"fields":[{"id":x["fieldId"],"name":x["displayName"],"type":x["fieldType"],"primary":x["primary"],"options":x["options"]} for x in fields],"suggestions":[],"empty":len(fields)<=1}
def auth(headers):
 expected=os.getenv("PERSONAL_ACCESS_KEY")
 if not expected:raise ApiError(503,"access_key_missing","阿里云函数尚未配置个人访问密钥")
 if not hmac.compare_digest(headers.get("Authorization",""),"Bearer "+expected):raise ApiError(401,"unauthorized","访问密钥无效")
def parse_time(v):
 try:
  v=v.replace("Z","+00:00")
  if re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}",v):v=v.replace(" ","T")+"+08:00"
  return datetime.fromisoformat(v).timestamp()
 except:return None
def normalize(raw,fields,shared):
 allowed={x["fieldId"]:x for x in fields};values={};suggested={};raw_values=raw.get("values") if isinstance(raw,dict) else {}
 if not isinstance(raw_values,dict):raw_values={}
 for fid,v in raw_values.items():
  f=allowed.get(fid)
  if not f:
   matches=[x for x in fields if x.get("displayName")==fid]
   if len(matches)==1:f=matches[0];fid=f["fieldId"]
  if f and f["fieldType"]==2 and isinstance(v,(int,float)) and not isinstance(v,bool):v=str(v)
  if not f or not isinstance(v,str) or not v.strip():continue
  v=v.strip()
  if f["fieldType"]==2 and re.search(r"金额|支出|费用|实付",f["displayName"]):
   try:v=f"{abs(float(v)):.2f}"
   except:pass
  if f["fieldType"]==3 and v not in f.get("options",[]):suggested[fid]=[v];continue
  if f["fieldType"]==4:
   parts=[x.strip() for x in re.split(r"[、,，]",v) if x.strip()];miss=[x for x in parts if x not in f.get("options",[])];known=[x for x in parts if x in f.get("options",[])]
   if miss:suggested[fid]=miss
   if known:values[fid]="、".join(known)
  else:values[fid]=v
 raw_warnings=raw.get("warnings",[]) if isinstance(raw,dict) else []
 if not isinstance(raw_warnings,list):raw_warnings=[]
 warnings=[x for x in raw_warnings if isinstance(x,str)][:8]; shared_ts=parse_time(shared)
 if any(f["fieldType"]==5 and parse_time(values.get(f["fieldId"],"")) and shared_ts and abs(parse_time(values[f["fieldId"]])-shared_ts)>60 for f in fields):warnings=[x for x in warnings if not re.search(r"分享时间|无交易时间|没有交易时间",x)]
 raw_confidence=raw.get("confidence",{}) if isinstance(raw,dict) else {}
 confidence={}
 for k in values:
  candidate=raw_confidence.get(k,0) if isinstance(raw_confidence,dict) else raw_confidence
  try:confidence[k]=max(0,min(1,float(candidate)))
  except (TypeError,ValueError):confidence[k]=0
 return {"supported":raw.get("supported") is True if isinstance(raw,dict) else False,"values":values,"confidence":confidence,"suggestedOptions":suggested,"warnings":warnings}
def parse_ai_content(response):
 try:content=((response.get("choices") or [])[0].get("message") or {}).get("content")
 except (AttributeError,IndexError,TypeError):content=None
 if isinstance(content,list):
  content="\n".join(x.get("text","") for x in content if isinstance(x,dict) and isinstance(x.get("text"),str))
 if not isinstance(content,str) or not content.strip():raise ApiError(502,"ai_invalid_response","百炼未返回可识别内容，请重试")
 text=content.strip();text=re.sub(r"^```(?:json)?\s*|\s*```$","",text,flags=re.I)
 try:value=json.loads(text)
 except json.JSONDecodeError:
  match=re.search(r"\{.*\}",text,re.S)
  if not match:raise ApiError(502,"ai_invalid_response","百炼返回格式异常，请重试")
  try:value=json.loads(match.group(0))
  except json.JSONDecodeError:raise ApiError(502,"ai_invalid_response","百炼返回格式异常，请重试")
 if not isinstance(value,dict):raise ApiError(502,"ai_invalid_response","百炼返回格式异常，请重试")
 return value
def ai(body,fields):
 key=os.getenv("DASHSCOPE_API_KEY")
 if not key:raise ApiError(503,"dashscope_key_missing","阿里云函数尚未配置百炼 API Key")
 defs=[{"id":x["fieldId"],"name":x["displayName"],"type":x["fieldType"],"options":x.get("options",[])} for x in fields]
 prompt="\n".join(["你是支出记账截图数据录入助手，只提取图中明确信息。","支持单笔已支付订单详情、外卖订单、转账结果和支付结果；有实付金额且有商户、订单或支付证据时 supported=true。未支付购物车、订单列表、纯商品页、退款或收入为 false。","目标字段："+json.dumps(defs,ensure_ascii=False),"values 用字段 id 作键。金额取实付金额绝对值且保留两位小数；日期用 yyyy-MM-dd HH:mm:ss。","用途优先填商品或订单内容，其次商户、收款方或转账对象，不得遗漏。订单无支付时间可用下单时间。","单选、多选的新值也如实返回。未知字段省略。",f"只有图中完全没有交易/下单时间时才用分享时间 {body.get('sharedAt')} 并添加警告。","只输出 {supported,values,confidence,warnings} JSON。"])
 payload={"model":MODEL,"enable_thinking":False,"max_tokens":700,"response_format":{"type":"json_object"},"messages":[{"role":"system","content":prompt},{"role":"user","content":[{"type":"image_url","image_url":{"url":body["imageDataUrl"]}},{"type":"text","text":"读取单笔已支付截图或订单详情。"}]}]}
 s,_,b=web(DS+"/chat/completions","POST",{"Authorization":"Bearer "+key,"Content-Type":"application/json"},jb(payload),35);d=json.loads(b)
 if s>=400:raise ApiError(502,"ai_failed",(d.get("error") or {}).get("message","百炼识别失败"))
 raw=parse_ai_content(d);result=normalize(raw,fields,body.get("sharedAt"))
 raw_values=raw.get("values");allowed={x["fieldId"] for x in fields};names={x["displayName"] for x in fields}
 diagnostic={"path":"/parse","stage":"normalize","model":MODEL,"supported":result["supported"],"fieldCount":len(fields),"rawValuesType":type(raw_values).__name__,"rawValueCount":len(raw_values) if isinstance(raw_values,dict) else 0,"normalizedValueCount":len(result["values"]),"suggestionFieldCount":len(result["suggestedOptions"])}
 if isinstance(raw_values,dict):
  diagnostic.update({"idKeyCount":sum(k in allowed for k in raw_values),"nameKeyCount":sum(k in names for k in raw_values),"unknownKeyCount":sum(k not in allowed and k not in names for k in raw_values),"valueTypes":sorted(set(type(v).__name__ for v in raw_values.values()))})
 print(json.dumps(diagnostic,ensure_ascii=False),flush=True)
 return result

class Handler(BaseHTTPRequestHandler):
 protocol_version="HTTP/1.1"
 def sendj(self,status,value):
  b=jb(value);self.send_response(status);self.send_header("Content-Type","application/json; charset=utf-8");self.send_header("Cache-Control","no-store");self.send_header("X-Bookkeeping-Build",BUILD);self.send_header("Content-Length",str(len(b)));self.end_headers();self.wfile.write(b)
 def body(self):
  n=int(self.headers.get("Content-Length","0"))
  if n>24_000_000:raise ApiError(413,"payload_too_large","图片过大")
  return json.loads(self.rfile.read(n) or b"{}")
 def run(self):
  rid=str(uuid.uuid4());start=time.time()
  try:
   store=Oss(self.headers);path=urllib.parse.urlparse(self.path).path
   if self.command=="GET" and path=="/health":self.sendj(200,{"ok":True,"runtime":"aliyun-fc-python","build":BUILD,"ossEndpoint":ENDPOINT,"roleCredentials":store.ready,"secrets":{"feishu":bool(os.getenv("FEISHU_APP_ID") and os.getenv("FEISHU_APP_SECRET")),"dashscope":bool(os.getenv("DASHSCOPE_API_KEY")),"accessKey":bool(os.getenv("PERSONAL_ACCESS_KEY"))}});return
   auth(self.headers);c=catalog(store);book=current(c)
   requested_book=self.headers.get("X-Book-Id")
   if requested_book and path not in ("/books","/books/current","/books/connect","/books/inspect"):
    book=next((x for x in c["books"] if x["id"]==requested_book),None)
    if not book:raise ApiError(404,"book_not_found","账本不存在，请刷新账本列表")
    c["currentBookId"]=book["id"]
   if path=="/books" and self.command=="GET":self.sendj(200,c);return
   if path=="/books/inspect" and self.command=="POST":
    body=self.body()
    if not body.get("url"):raise ApiError(400,"url_required","请填写多维表格链接")
    self.sendj(200,inspect_book(body["url"],body.get("tableId")));return
   if path=="/books/connect" and self.command=="POST":
    body=self.body();app=body.get("appToken");tid=body.get("tableId")
    if not app or not tid:raise ApiError(400,"table_required","请选择数据表")
    tables=fs(f"/bitable/v1/apps/{app}/tables?page_size=100").get("items",[]);table=next((x for x in tables if x["table_id"]==tid),None)
    if not table:raise ApiError(404,"table_not_found","数据表不存在")
    existing=next((x for x in c["books"] if x["appToken"]==app and x["tableId"]==tid),None);bid=existing["id"] if existing else str(uuid.uuid4())
    source=body.get("sourceUrl") or body.get("target",{}).get("sourceUrl") or f"https://my.feishu.cn/base/{app}?table={tid}"
    added={"id":bid,"appToken":app,"tableId":tid,"bookName":fs(f"/bitable/v1/apps/{app}").get("app",{}).get("name",app),"tableName":table["name"],"sourceUrl":source,"target":{**body.get("target",{}),"tableId":tid,"sourceUrl":source},"mappings":[],"fields":public_fields(list_fields({"appToken":app,"tableId":tid}),existing.get("fields") if existing else None),"lastUsedAt":int(time.time()*1000)}
    others=[x for x in c["books"] if x["id"]!=bid]
    if not existing and len(others)>=10:raise ApiError(409,"book_limit_reached","常用账本已达 10 个，请先移除旧账本")
    c={"currentBookId":bid,"books":[added]+others};save_catalog(store,c);self.sendj(201,c);return
   if path=="/config" and self.command=="GET":
    if not book:raise ApiError(409,"book_required","请先设置记账表格")
    if not book.get("fields"):refresh(book);save_catalog(store,c)
    self.sendj(200,config(book));return
   if path=="/books/current/refresh" and self.command=="POST":self.body();refresh(book);save_catalog(store,c);self.sendj(200,c);return
   if path=="/books/current" and self.command=="PUT":
    body=self.body();select_book(c,body.get("bookId"));chosen=current(c)
    # Persist before acknowledging so another FC instance sees the same book.
    tick=time.time();save_catalog(store,c);print(json.dumps({"path":path,"timing":{"responsePreparationMs":int((time.time()-tick)*1000)}},ensure_ascii=False),flush=True);self.sendj(200,c);return
   if path=="/books/current/layout" and self.command=="PUT":
    cur={x["fieldId"]:x for x in book.get("fields",[])};ordered=[]
    for item in self.body().get("fields",[]):
     if item["fieldId"] in cur:ordered.append({**cur.pop(item["fieldId"]),"required":item.get("required") is True})
    book["fields"]=ordered+list(cur.values());save_catalog(store,c);self.sendj(200,c);return
   oa=re.fullmatch(r"/books/current/fields/([^/]+)/options/(rename|hide|restore)",path)
   if oa and self.command=="POST":
    body=self.body();fid=urllib.parse.unquote(oa.group(1));action=oa.group(2);actual=next((x for x in list_fields(book) if x["field_id"]==fid),None)
    if not actual or actual["type"] not in (3,4):raise ApiError(400,"invalid_option_field","该字段不是单选或多选字段")
    if not any(x["fieldId"]==fid for x in book.get("fields",[])):book["fields"]=public_fields(list_fields(book),book.get("fields"))
    stored=next((x for x in book.get("fields",[]) if x["fieldId"]==fid),None)
    if not stored:raise ApiError(404,"field_not_found","字段不存在，请刷新账本后重试")
    if action=="rename":
     old=(body.get("oldOption") or "").strip();new=(body.get("newOption") or "").strip();fs(f"/bitable/v1/apps/{book['appToken']}/tables/{book['tableId']}/fields/{fid}","PUT",rename_option_payload(actual,old,new));stored["hiddenOptions"]=[new if x==old else x for x in stored.get("hiddenOptions",[])];stored["options"]=[new if x==old else x for x in stored.get("options",[])]
    else:
     value=(body.get("option") or "").strip();names=[x.get("name") for x in (actual.get("property") or {}).get("options",[])]
     if value not in names:raise ApiError(404,"option_not_found","选项不存在，请刷新后重试")
     hidden=stored.get("hiddenOptions",[])
     stored["hiddenOptions"]=list(dict.fromkeys(hidden+[value])) if action=="hide" else [x for x in hidden if x!=value]
     stored["options"]=[x for x in names if x not in stored["hiddenOptions"]]
    save_catalog(store,c);self.sendj(200,c);return
   om=re.fullmatch(r"/books/current/fields/([^/]+)/options",path)
   if om and self.command=="POST":
    value=(self.body().get("option") or "").strip();fid=urllib.parse.unquote(om.group(1));actual=next((x for x in list_fields(book) if x["field_id"]==fid),None)
    if not value or not actual or actual["type"] not in (3,4):raise ApiError(400,"invalid_option_field","选项字段或候选值无效")
    opts=(actual.get("property") or {}).get("options",[])
    if value not in [x["name"] for x in opts]:fs(f"/bitable/v1/apps/{book['appToken']}/tables/{book['tableId']}/fields/{fid}","PUT",{"field_name":actual["field_name"],"type":actual["type"],"property":{"options":opts+[{"name":value}]}})
    else:
     stored=next((x for x in book.get("fields",[]) if x["fieldId"]==fid),None)
     if stored:stored["hiddenOptions"]=[x for x in stored.get("hiddenOptions",[]) if x!=value]
    stored=next((x for x in book.get("fields",[]) if x["fieldId"]==fid),None)
    if stored is None:
     stored=public_fields([actual])[0];book.setdefault("fields",[]).append(stored)
    stored["hiddenOptions"]=[x for x in stored.get("hiddenOptions",[]) if x!=value];stored["options"]=[value]+[x for x in stored.get("options",[]) if x!=value]
    save_catalog(store,c);self.sendj(200,c);return
   if om and self.command=="PUT":
    fid=urllib.parse.unquote(om.group(1));actual=next((x for x in list_fields(book) if x["field_id"]==fid),None);raw=self.body().get("options")
    if not actual or actual["type"] not in (3,4) or not isinstance(raw,list):raise ApiError(400,"invalid_option_field","选项字段或候选值无效")
    opts=list(dict.fromkeys(x.strip() for x in raw if isinstance(x,str) and x.strip()))
    existing=(actual.get("property") or {}).get("options",[]);names=[x["name"] for x in existing]
    if any(x not in opts for x in names):raise ApiError(409,"destructive_option_update_blocked","为保护已有记录，请在飞书中重命名或删除选项")
    additions=[x for x in opts if x not in names]
    if additions:fs(f"/bitable/v1/apps/{book['appToken']}/tables/{book['tableId']}/fields/{fid}","PUT",{"field_name":actual["field_name"],"type":actual["type"],"property":{"options":existing+[{"name":x} for x in additions]}})
    refresh(book);save_catalog(store,c);self.sendj(200,c);return
   if path=="/parse" and self.command=="POST":
    body=self.body();fields=[x for x in book.get("fields",[]) if x["fieldType"] in (1,2,3,4,5)]
    if not body.get("imageDataUrl") or not body.get("sharedAt"):raise ApiError(400,"invalid_parse_request","缺少图片或分享时间")
    tick=time.time();result=ai(body,fields);print(json.dumps({"path":path,"timing":{"dashscopeMs":int((time.time()-tick)*1000)}},ensure_ascii=False),flush=True);self.sendj(200,result);return
   if path=="/records" and self.command=="POST":
    body=self.body();key=body.get("clientRequestId")
    if not key:raise ApiError(400,"invalid_request_id","缺少请求 ID")
    idem=f"bookkeeping/idempotency/{key}.json";tick=time.time();old=store.get(idem);idem_read_ms=int((time.time()-tick)*1000)
    if old:self.sendj(200,{"ok":True,"recordId":old["recordId"],"duplicate":True});return
    incoming=body.get("values") or {}
    if not incoming:raise ApiError(400,"empty_record","没有收到表单数据，请更新 APP 后重试")
    if not book.get("fields") or any(fid not in {x["fieldId"] for x in book.get("fields",[])} for fid in incoming):
     book["fields"]=public_fields(list_fields(book),book.get("fields"));save_catalog(store,c)
    actual={x["fieldId"]:x for x in book.get("fields",[])};out={}
    if any(fid not in actual for fid in incoming):raise ApiError(409,"stale_fields","字段已变化或账本不一致，请刷新字段后重试，当前输入仍保留")
    for fid,raw in (body.get("values") or {}).items():
     f=actual.get(fid);v=str(raw).strip()
     if not f or not v:continue
     if f["fieldType"]==2:
      try:v=abs(float(v)) if re.search(r"金额|支出|费用|实付",f["displayName"]) else float(v)
      except:raise ApiError(400,"invalid_number",f"“{f['displayName']}”必须是数字")
     elif f["fieldType"]==5:
      stamp=parse_time(v)
      if stamp is None:raise ApiError(400,"invalid_date",f"“{f['displayName']}”日期无效")
      v=int(stamp*1000)
     elif f["fieldType"]==4:v=[x.strip() for x in re.split(r"[、,，]",v) if x.strip()]
     out[f["displayName"]]=v
    for f in book.get("fields",[]):
     if f.get("required") and f["displayName"] not in out:raise ApiError(400,"required_field_missing",f"“{f['displayName']}”为必填字段")
    if not out:raise ApiError(400,"empty_record","请至少填写一个字段")
    tick=time.time();data=fs(f"/bitable/v1/apps/{book['appToken']}/tables/{book['tableId']}/records","POST",{"fields":out});feishu_ms=int((time.time()-tick)*1000);record=data["record"]["record_id"];tick=time.time();store.put(idem,{"recordId":record});idem_write_ms=int((time.time()-tick)*1000);print(json.dumps({"path":path,"timing":{"idempotencyReadMs":idem_read_ms,"feishuWriteMs":feishu_ms,"idempotencyWriteMs":idem_write_ms}},ensure_ascii=False),flush=True);self.sendj(201,{"ok":True,"recordId":record,"duplicate":False});return
   rm=re.fullmatch(r"/books/([^/]+)",path)
   if rm and self.command=="DELETE":
    bid=urllib.parse.unquote(rm.group(1))
    if c.get("currentBookId")==bid:raise ApiError(409,"cannot_remove_current_book","请先切换到其他账本再移除")
    c["books"]=[x for x in c["books"] if x["id"]!=bid];save_catalog(store,c);self.sendj(200,c);return
   raise ApiError(404,"not_found","接口不存在")
  except ApiError as e:self.sendj(e.status,{"error":{"code":e.code,"message":str(e),"requestId":rid}})
  except Exception as e:print(json.dumps({"path":self.path,"error":str(e),"requestId":rid},ensure_ascii=False),flush=True);self.sendj(500,{"error":{"code":"internal_error","message":"服务暂时不可用","requestId":rid}})
  finally:print(json.dumps({"path":self.path,"durationMs":int((time.time()-start)*1000),"requestId":rid}),flush=True)
 do_GET=run;do_POST=run;do_PUT=run;do_DELETE=run
 def log_message(self,fmt,*args):print(fmt%args,flush=True)
if __name__=="__main__":ThreadingHTTPServer(("0.0.0.0",PORT),Handler).serve_forever()
