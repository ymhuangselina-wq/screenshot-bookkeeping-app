import { describe, expect, it } from "vitest";
import { ApiError } from "../src/http";
import { validateDraft } from "../src/qwen";

const options = {
  paymentPlatforms: ["支付宝", "微信支付"],
  tags: ["餐饮", "工作"],
  projects: ["日常生活"],
};

describe("validateDraft", () => {
  it("keeps only configured select options and clamps confidence", () => {
    const draft = validateDraft({
      supported: true,
      date: "2026-09-15T10:00:00+08:00",
      purpose: "咖啡",
      amount: 18,
      paymentPlatform: "不存在的平台",
      tags: ["餐饮", "模型编造", "餐饮"],
      note: "  测试备注  ",
      project: "模型编造的项目",
      confidence: { date: 2, purpose: -1, amount: 0.9, paymentPlatform: "0.8", tags: null, project: 0.5 },
      warnings: ["请核对", 123],
    }, options);

    expect(draft.paymentPlatform).toBeNull();
    expect(draft.tags).toEqual(["餐饮"]);
    expect(draft.project).toBeNull();
    expect(draft.note).toBe("测试备注");
    expect(draft.confidence.date).toBe(1);
    expect(draft.confidence.purpose).toBe(0);
    expect(draft.warnings).toEqual(["请核对"]);
  });

  it("rejects a non-object model response", () => {
    expect(() => validateDraft(null, options)).toThrowError(ApiError);
  });

  it("turns invalid amounts into null", () => {
    expect(validateDraft({ supported: true, amount: -2 }, options).amount).toBeNull();
  });
});
