import { ApiError } from "./http";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

export async function sha256(value: string): Promise<string> {
  return bytesToBase64(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value))));
}

async function encryptionKey(secret: string): Promise<CryptoKey> {
  if (!secret) throw new ApiError(500, "encryption_not_configured", "服务端加密密钥未配置");
  const raw = await crypto.subtle.digest("SHA-256", encoder.encode(secret));
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}

export async function encryptText(value: string, secret: string): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const cipher = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await encryptionKey(secret), encoder.encode(value));
  return `${bytesToBase64(iv)}.${bytesToBase64(new Uint8Array(cipher))}`;
}

export async function decryptText(value: string, secret: string): Promise<string> {
  try {
    const [iv, cipher] = value.split(".");
    const ivBytes = base64ToBytes(iv);
    const cipherBytes = base64ToBytes(cipher);
    const clear = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: ivBytes.buffer as ArrayBuffer },
      await encryptionKey(secret),
      cipherBytes.buffer as ArrayBuffer,
    );
    return decoder.decode(clear);
  } catch {
    throw new ApiError(500, "credential_decryption_failed", "授权信息无法读取");
  }
}

export function randomToken(bytes = 32): string {
  return bytesToBase64(crypto.getRandomValues(new Uint8Array(bytes))).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

export async function loginChallenge(verifier: string): Promise<string> {
  return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(verifier))), byte => byte.toString(16).padStart(2,"0")).join("");
}
