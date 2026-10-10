export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

export function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
  });
}

export async function requireJson<T>(request: Request): Promise<T> {
  const type = request.headers.get("content-type") ?? "";
  if (!type.includes("application/json")) throw new ApiError(415, "invalid_content_type", "需要 application/json");
  try {
    return await request.json<T>();
  } catch {
    throw new ApiError(400, "invalid_json", "JSON 格式无效");
  }
}

export function authenticate(request: Request, expected: string): void {
  const actual = request.headers.get("authorization");
  if (!actual || actual !== `Bearer ${expected}`) throw new ApiError(401, "unauthorized", "访问密钥无效");
}

