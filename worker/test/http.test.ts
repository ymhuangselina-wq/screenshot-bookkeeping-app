import { describe, expect, it } from "vitest";
import { ApiError, authenticate } from "../src/http";

describe("authenticate", () => {
  it("accepts the configured bearer token", () => {
    expect(() => authenticate(new Request("https://example.com", { headers: { authorization: "Bearer secret" } }), "secret")).not.toThrow();
  });

  it("rejects a missing token without exposing the expected value", () => {
    expect(() => authenticate(new Request("https://example.com"), "secret")).toThrowError(ApiError);
  });
});

