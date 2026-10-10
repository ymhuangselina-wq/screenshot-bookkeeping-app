import http from "node:http";

const upstream = (process.env.CLOUDFLARE_UPSTREAM || "https://example.com").replace(/\/$/, "");
const dashscopeBase = (process.env.DASHSCOPE_BASE_URL || "https://dashscope.aliyuncs.com/compatible-mode/v1").replace(/\/$/, "");
const model = process.env.DASHSCOPE_MODEL || "qwen3-vl-flash";
const port = Number(process.env.FC_SERVER_PORT || 9000);

function send(res, status, value) {
  const body = typeof value === "string" ? value : JSON.stringify(value);
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(body);
}

async function bodyBuffer(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 24_000_000) throw Object.assign(new Error("图片过大"), { status: 413 });
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function proxy(req, res, rawBody) {
  const headers = {};
  for (const [key, value] of Object.entries(req.headers)) {
    if (value && !["host", "content-length", "connection"].includes(key)) headers[key] = Array.isArray(value) ? value.join(",") : value;
  }
  const response = await fetch(upstream + req.url, { method: req.method, headers, body: ["GET", "HEAD"].includes(req.method) ? undefined : rawBody });
  const text = await response.text();
  res.writeHead(response.status, { "content-type": response.headers.get("content-type") || "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(text);
}

function normalizeDynamic(raw, fields, sharedAt) {
  const allowed = new Map(fields.map((field) => [field.fieldId, field]));
  const values = {};
  const suggestedOptions = {};
  for (const [id, value] of Object.entries(raw.values || {})) {
    const field = allowed.get(id);
    if (!field || typeof value !== "string" || !value.trim()) continue;
    let clean = value.trim();
    if (field.fieldType === 2 && /金额|支出|费用/.test(field.displayName)) {
      const number = Number(clean);
      if (Number.isFinite(number)) clean = Math.abs(number).toFixed(2);
    }
    if (field.fieldType === 3 && !field.options.includes(clean)) { suggestedOptions[id] = [clean]; continue; }
    if (field.fieldType === 4) {
      const items = clean.split(/[、,，]/).map((item) => item.trim()).filter(Boolean);
      const missing = items.filter((item) => !field.options.includes(item));
      if (missing.length) suggestedOptions[id] = missing;
      const existing = items.filter((item) => field.options.includes(item));
      if (existing.length) values[id] = existing.join("、");
      continue;
    }
    values[id] = clean;
  }
  const purposeFields = fields.filter((field) => /用途|消费用途/.test(field.displayName));
  const fallback = fields.find((field) => /名称|商户|收款方|转账对象/.test(field.displayName) && values[field.fieldId]);
  if (fallback) for (const field of purposeFields) if (!values[field.fieldId]) values[field.fieldId] = values[fallback.fieldId];
  const confidence = Object.fromEntries(Object.keys(values).map((id) => [id, Math.max(0, Math.min(1, Number(raw.confidence?.[id]) || 0))]));
  let warnings = Array.isArray(raw.warnings) ? raw.warnings.filter((item) => typeof item === "string").slice(0, 8) : [];
  const sharedMs = Date.parse(sharedAt);
  const screenshotDateFound = fields.filter((field) => field.fieldType === 5).some((field) => {
    const value = values[field.fieldId];
    const parsed = value && Date.parse(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value) ? `${value.replace(" ", "T")}+08:00` : value);
    return Number.isFinite(parsed) && Number.isFinite(sharedMs) && Math.abs(parsed - sharedMs) > 60_000;
  });
  if (screenshotDateFound) warnings = warnings.filter((warning) => !/分享时间|无交易时间|没有交易时间/.test(warning));
  return { supported: raw.supported === true, values, confidence, suggestedOptions, warnings };
}

async function parseLocally(req, rawBody) {
  if (!process.env.DASHSCOPE_API_KEY) throw Object.assign(new Error("阿里云函数尚未配置 DASHSCOPE_API_KEY"), { status: 503 });
  const auth = req.headers.authorization || "";
  const configResponse = await fetch(upstream + "/config", { headers: { authorization: auth } });
  if (!configResponse.ok) throw Object.assign(new Error(await configResponse.text()), { status: configResponse.status, raw: true });
  const config = await configResponse.json();
  const fields = (config.fields || []).filter((field) => [1, 2, 3, 4, 5].includes(field.fieldType));
  const input = JSON.parse(rawBody.toString("utf8"));
  const definitions = fields.map((field) => ({ id: field.fieldId, name: field.displayName, type: field.fieldType, options: field.options }));
  const instructions = [
    "你是支付结果截图的数据录入助手，只提取截图中明确出现的信息。仅支持单笔支出支付结果。",
    `目标字段：${JSON.stringify(definitions)}。`,
    "values 以字段 id 为键、字符串为值。金额用绝对值且保留两位小数；日期用 yyyy-MM-dd HH:mm:ss；未知字段省略。",
    "用途优先填写支付标题、商品说明、商户、收款方或转账对象；存在这些信息时不得遗漏。",
    "单选、多选若识别值不在候选列表，也如实返回。",
    `仅当截图没有交易时间时才使用分享时间 ${input.sharedAt} 并添加警告。`,
    "只输出 {supported,values,confidence,warnings} JSON。"
  ].join("\n");
  const ai = await fetch(dashscopeBase + "/chat/completions", {
    method: "POST",
    headers: { authorization: `Bearer ${process.env.DASHSCOPE_API_KEY}`, "content-type": "application/json" },
    body: JSON.stringify({ model, enable_thinking: false, max_tokens: 700, response_format: { type: "json_object" }, messages: [
      { role: "system", content: instructions },
      { role: "user", content: [{ type: "image_url", image_url: { url: input.imageDataUrl } }, { type: "text", text: "读取截图并填充字段。" }] }
    ] })
  });
  const body = await ai.json();
  if (!ai.ok) throw Object.assign(new Error(body?.error?.message || "AI 识别失败"), { status: 502 });
  const raw = JSON.parse(body?.choices?.[0]?.message?.content || "{}");
  return normalizeDynamic(raw, fields, input.sharedAt);
}

const server = http.createServer(async (req, res) => {
  const started = Date.now();
  try {
    if (req.url === "/health" && req.method === "GET") return send(res, 200, { ok: true, runtime: "aliyun-fc" });
    const rawBody = await bodyBuffer(req);
    if (req.url === "/parse" && req.method === "POST") return send(res, 200, await parseLocally(req, rawBody));
    return await proxy(req, res, rawBody);
  } catch (error) {
    console.error(JSON.stringify({ path: req.url, durationMs: Date.now() - started, error: error.message }));
    if (error.raw) { res.writeHead(error.status || 500, { "content-type": "application/json; charset=utf-8" }); return res.end(error.message); }
    return send(res, error.status || 500, { error: { code: "gateway_error", message: error.message || "服务暂时不可用" } });
  }
});

server.listen(port, "0.0.0.0", () => console.log(`listening on ${port}`));
