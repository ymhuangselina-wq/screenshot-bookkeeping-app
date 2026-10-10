import { describe, expect, it } from "vitest";
import { decryptText, encryptText, sha256 } from "../src/crypto";

describe("credential crypto", () => {
  it("round trips tokens without storing plaintext", async () => {
    const encrypted = await encryptText("u-sensitive-token", "test-only-secret");
    expect(encrypted).not.toContain("u-sensitive-token");
    expect(await decryptText(encrypted, "test-only-secret")).toBe("u-sensitive-token");
  });

  it("hashes invite codes deterministically", async () => {
    expect(await sha256("INVITE")).toBe(await sha256("INVITE"));
    expect(await sha256("INVITE")).not.toBe(await sha256("OTHER"));
  });
});
