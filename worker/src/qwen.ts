import { ApiError } from "./http";
import type { Draft, DynamicDraft, Env, PersonalBookField, SelectOptions } from "./types";

type QwenResponse = {
  choices?: Array<{ message?: { content?: string } }>;
  error?: { message?: string };
};

const numericConfidenceKeys = ["date", "purpose", "amount", "paymentPlatform", "tags", "project"] as const;

function nullableString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

export function validateDraft(value: unknown, options: SelectOptions): Draft {
  if (!value || typeof value !== "object") throw new ApiError(502, "qwen_invalid_output", "AI 返回格式无效");
  const raw = value as Record<string, unknown>;
  const rawConfidence = raw.confidence && typeof raw.confidence === "object" ? raw.confidence as Record<string, unknown> : {};
  const confidence = Object.fromEntries(numericConfidenceKeys.map((key) => {
    const number = Number(rawConfidence[key]);
    return [key, Number.isFinite(number) ? Math.max(0, Math.min(1, number)) : 0];
  })) as Draft["confidence"];
  const paymentPlatform = nullableString(raw.paymentPlatform);
  const project = nullableString(raw.project);
  const tags = Array.isArray(raw.tags)
    ? [...new Set(raw.tags.filter((tag): tag is string => typeof tag === "string" && options.tags.includes(tag)))]
    : [];
  return {
    supported: raw.supported === true,
    date: nullableString(raw.date),
    purpose: nullableString(raw.purpose),
    amount: typeof raw.amount === "number" && Number.isFinite(raw.amount) && raw.amount > 0 ? raw.amount : null,
    paymentPlatform: paymentPlatform && options.paymentPlatforms.includes(paymentPlatform) ? paymentPlatform : null,
    tags,
    note: nullableString(raw.note),
    project: project && options.projects.includes(project) ? project : null,
    confidence,
    warnings: Array.isArray(raw.warnings) ? raw.warnings.filter((item): item is string => typeof item === "string").slice(0, 8) : [],
  };
}

export async function parseScreenshot(env: Env, dataUrl: string, sharedAt: string, options: SelectOptions): Promise<Draft> {
  if (!/^data:image\/(jpeg|png|webp);base64,/.test(dataUrl)) throw new ApiError(400, "invalid_image", "图片格式仅支持 JPEG、PNG 或 WebP");
  if (dataUrl.length > 20_000_000) throw new ApiError(413, "image_too_large", "图片过大");
  const instructions = [
    "你是支付结果截图记账提取器。只读取截图中有明确依据的信息，不得猜测商家、项目或标签。",
    "仅支持单笔支出支付结果；收入、退款、账单列表、订单列表、聊天或普通商品页均设置 supported=false。",
    "金额为正数，不含货币符号。用途用简短中文概括收款方或商品用途。备注只放对核账有帮助且截图明确出现的简短信息。",
    `支付平台必须为以下值之一或 null：${JSON.stringify(options.paymentPlatforms)}。`,
    `标签只能从以下值选择：${JSON.stringify(options.tags)}。`,
    `归属项目必须为以下值之一或 null：${JSON.stringify(options.projects)}。`,
    `截图没有交易时间时，date 使用分享时间 ${sharedAt}，并将 date 置信度设为 0.5、添加警告。时区固定 +08:00。`,
    "无法确定的字段使用 null；不要把不确定内容编造成确定值。",
    "请只输出 JSON，必须包含 supported、date、purpose、amount、paymentPlatform、tags、note、project、confidence、warnings。",
    "confidence 必须包含 date、purpose、amount、paymentPlatform、tags、project，值为 0 到 1。",
  ].join("\n");
  const baseUrl = (env.DASHSCOPE_BASE_URL ?? "https://dashscope.aliyuncs.com/compatible-mode/v1").replace(/\/$/, "");
  const response = await fetch(`${baseUrl}/chat/completions`, {
    method: "POST",
    headers: { authorization: `Bearer ${env.DASHSCOPE_API_KEY}`, "content-type": "application/json" },
    body: JSON.stringify({
      model: env.DASHSCOPE_MODEL ?? "qwen3-vl-flash",
      enable_thinking: false,
      max_tokens: 700,
      messages: [
        { role: "system", content: instructions },
        { role: "user", content: [
          { type: "image_url", image_url: { url: dataUrl } },
          { type: "text", text: "提取这张截图中的一笔支出，并按照 JSON 格式输出。" },
        ] },
      ],
      response_format: { type: "json_object" },
    }),
  });
  const body = await response.json<QwenResponse>();
  if (!response.ok) throw new ApiError(502, "qwen_failed", body.error?.message ?? "AI 识别失败");
  const outputText = body.choices?.[0]?.message?.content;
  if (!outputText) throw new ApiError(502, "qwen_empty_output", "AI 未返回识别结果");
  try {
    return validateDraft(JSON.parse(outputText), options);
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(502, "qwen_invalid_output", "AI 返回格式无效");
  }
}

export async function parseDynamicScreenshot(env: Env, dataUrl: string, sharedAt: string, fields: PersonalBookField[]): Promise<DynamicDraft> {
  if (!/^data:image\/(jpeg|png|webp);base64,/.test(dataUrl)) throw new ApiError(400, "invalid_image", "图片格式仅支持 JPEG、PNG 或 WebP");
  if (dataUrl.length > 20_000_000) throw new ApiError(413, "image_too_large", "图片过大");
  const definitions = fields.map((field) => ({ id: field.fieldId, name: field.displayName, type: field.fieldType, options: field.options }));
  const instructions = [
    "你是支付结果截图的数据录入助手。只提取截图中明确出现或可直接判断的信息，不得编造。",
    "仅支持单笔支出支付结果；收入、退款、列表、聊天或普通商品页设置 supported=false。",
    `目标飞书字段如下：${JSON.stringify(definitions)}。`,
    "values 必须以字段 id 为键、字符串为值。文本直接填写；数字只填数字且金额使用绝对值；日期使用 yyyy-MM-dd HH:mm:ss；单选填一个值，多选用顿号连接。截图中明确出现但候选列表里没有的选项也要如实返回，系统会询问用户是否新增。",
    "字段名包含“用途、消费用途”时，优先提取支付标题、商品说明、商户、收款方或转账对象。例如截图出现“转账-转给张三”，用途应填写“转账-转给张三”。只要截图存在这些信息，用途字段不得遗漏。",
    "如果同时存在“名称”和“用途”等多个文本字段，必须分别按字段名称理解，不要因为填写了其中一个就遗漏用途字段。",
    "字段名包含“金额、支出金额、交易金额、费用”时，填写正数绝对值，并保留两位小数。",
    `请特别检查“交易时间、支付时间、转账时间、创建时间”等截图文字。只有截图确实没有任何交易时间时，日期字段才使用分享时间 ${sharedAt} 并添加警告；识别到截图时间时不得添加“使用分享时间”的警告。`,
    "无法判断的字段不要放入 values。confidence 只包含已填写字段，值为 0 到 1。",
    "只输出 JSON，格式为 {supported,values,confidence,warnings}。",
  ].join("\n");
  const baseUrl = (env.DASHSCOPE_BASE_URL ?? "https://dashscope.aliyuncs.com/compatible-mode/v1").replace(/\/$/, "");
  const response = await fetch(`${baseUrl}/chat/completions`, {
    method: "POST", headers: { authorization: `Bearer ${env.DASHSCOPE_API_KEY}`, "content-type": "application/json" },
    body: JSON.stringify({ model: env.DASHSCOPE_MODEL ?? "qwen3-vl-flash", enable_thinking: false, max_tokens: 700,
      messages: [{ role: "system", content: instructions }, { role: "user", content: [{ type: "image_url", image_url: { url: dataUrl } }, { type: "text", text: "读取截图并填充目标字段。" }] }],
      response_format: { type: "json_object" } }),
  });
  const body = await response.json<QwenResponse>();
  if (!response.ok) throw new ApiError(502, "qwen_failed", body.error?.message ?? "AI 识别失败");
  const raw = JSON.parse(body.choices?.[0]?.message?.content ?? "{}");
  const allowed = new Map(fields.map((field) => [field.fieldId, field]));
  const values: Record<string, string> = {};
  const suggestedOptions: Record<string, string[]> = {};
  for (const [id, value] of Object.entries(raw.values ?? {})) {
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
  const purposeFallback = fields.find((field) => /名称|商户|收款方|转账对象/.test(field.displayName) && values[field.fieldId])?.fieldId;
  if (purposeFallback) {
    for (const field of purposeFields) if (!values[field.fieldId]) values[field.fieldId] = values[purposeFallback];
  }
  const confidence = Object.fromEntries(Object.keys(values).map((id) => [id, Math.max(0, Math.min(1, Number(raw.confidence?.[id]) || 0))]));
  let warnings = Array.isArray(raw.warnings) ? raw.warnings.filter((it: unknown): it is string => typeof it === "string").slice(0, 8) : [];
  const sharedMs = Date.parse(sharedAt);
  const screenshotDateFound = fields.filter((field) => field.fieldType === 5).some((field) => {
    const rawDate = values[field.fieldId];
    if (!rawDate) return false;
    const parsed = Date.parse(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(rawDate) ? `${rawDate.replace(" ", "T")}+08:00` : rawDate);
    return Number.isFinite(parsed) && Number.isFinite(sharedMs) && Math.abs(parsed - sharedMs) > 60_000;
  });
  if (screenshotDateFound) warnings = warnings.filter((warning: string) => !/分享时间|无交易时间|没有交易时间/.test(warning));
  return { supported: raw.supported === true, values, confidence, suggestedOptions, warnings };
}
