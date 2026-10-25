/**
 * 모의 매매 설정 탭(명세 S1~S3)의 표기 — 키별 줄·입력 읽기·바뀐 값·저장 확인·저장 결과·이력.
 * 형·범위 검사는 서버 몫이고 화면은 입력 글자를 그 키의 형으로 읽기만 한다. 모르는 값은 "—".
 */
import { describe, expect, it } from "vitest";
import {
  PAPER_TABS,
  parseSettingInput,
  RULES_FROZEN_WARNING,
  settingGroups,
  settingRangeText,
  settingsConfirmText,
  settingsDraftView,
  settingsHistoryRows,
  settingsPatch,
  settingsSaveResultView,
  settingText,
  settingValueAt,
} from "../paper";
import type { PaperSettingKey, PaperSettingsResponse } from "../types";

function key(name: string, over: Partial<PaperSettingKey> = {}): PaperSettingKey {
  return { key: name, type: "number", min: null, max: null, affectsRules: false, description: null, ...over };
}

const RESPONSE: PaperSettingsResponse = {
  available: true,
  version: 3,
  body: {
    model: "claude-sonnet-5-5",
    slippageBp: 5,
    maxPositions: 20,
    parkCashInShy: true,
    cashLikeTickers: ["SHY"],
    trackRecord: { windowYears: 5, candidateCiks: ["0001067983", "0001061768"] },
    blend: { groupB: { topN: 4 } },
    gate: { us10yStage1: 4.8 },
    odd: { nested: 1 },
    decisionTimeKst: "07:30",
  },
  defaults: {
    model: "claude-sonnet-5-5",
    slippageBp: 5,
    maxPositions: 20,
    parkCashInShy: true,
    cashLikeTickers: ["SHY"],
    trackRecord: { windowYears: 7, candidateCiks: ["0001067983", "0001061768"] },
    blend: { groupB: { topN: 4 } },
    gate: { us10yStage1: 4.8 },
    decisionTimeKst: "07:30",
  },
  keys: [
    key("model", { type: "string", options: ["claude-sonnet-5-5", "claude-haiku-5-5"], description: "Claude 계좌·제안 모델" }),
    key("slippageBp", { min: 0, max: 100, description: "체결가 슬리피지(bp)" }),
    key("trackRecord.windowYears", { type: "integer", min: 3, max: 10, affectsRules: true }),
    key("maxPositions", { type: "integer", min: 1, max: 50, affectsRules: true }),
    key("blend.groupB.topN", { type: "integer", min: 1, max: 12, affectsRules: true }),
    key("parkCashInShy", { type: "boolean", affectsRules: true }),
    key("cashLikeTickers", { type: "list" }),
    key("trackRecord.candidateCiks", { type: "list", affectsRules: true }),
    key("gate.us10yStage1", { min: null, max: null }),
    key("decisionTimeKst", { type: "time" }),
    key("odd", { type: "mystery" }),
  ],
};

describe("설정 값 찾기·표기", () => {
  it("점 경로로 묶음을 따라 내려가고, 맨 위에 점 이름 그대로 든 키가 있으면 그것 — 없으면 undefined", () => {
    expect(settingValueAt(RESPONSE.body, "blend.groupB.topN")).toBe(4);
    expect(settingValueAt({ "a.b": 1, a: { b: 2 } }, "a.b")).toBe(1);
    expect(settingValueAt(RESPONSE.body, "blend.groupB.missing")).toBeUndefined();
    expect(settingValueAt(null, "model")).toBeUndefined();
  });

  it("표기 — 참·거짓은 예·아니오, 목록은 쉼표, 빈 목록은 (없음), 숫자는 입력과 같은 글자, 모르면 —", () => {
    expect(settingText(true)).toBe("예");
    expect(settingText(["SHY", "TLT"])).toBe("SHY, TLT");
    expect(settingText([])).toBe("(없음)");
    expect(settingText(1500)).toBe("1500");
    expect(settingText(0.5)).toBe("0.5");
    expect(settingText(null)).toBe("—");
    expect(settingText({ a: 1 })).toBe('{"a":1}');
  });

  it("범위 — 서버 값 그대로 '0~20' · 'n 이상' · 'n 이하', 없으면 null", () => {
    expect(settingRangeText(0, 20)).toBe("0~20");
    expect(settingRangeText(1, null)).toBe("1 이상");
    expect(settingRangeText(null, 100)).toBe("100 이하");
    expect(settingRangeText(null, null)).toBeNull();
  });
});

describe("설정 줄", () => {
  const groups = settingGroups(RESPONSE);
  const row = (name: string) => groups.flatMap((group) => group.rows).find((entry) => entry.key === name)!;

  it("묶음은 점 경로의 첫 마디 — 서버 키 순서대로 처음 나온 묶음 순, 이름표는 알려진 묶음만", () => {
    expect(groups.map((group) => [group.key, group.title])).toEqual([
      ["", "일반 — Claude 계좌 · 장부 · 검증기 · 규칙 계좌 · 메모"],
      ["trackRecord", "기관 선정 — 트랙레코드(그룹 A)"],
      ["blend", "두 기관 그룹 섞기(D27)"],
      ["gate", "OS 게이트 지표 — 참고 임계치(구속하지 않음)"],
    ]);
    expect(groups[0].rows.map((entry) => entry.key)).toEqual([
      "model", "slippageBp", "maxPositions", "parkCashInShy", "cashLikeTickers", "decisionTimeKst", "odd",
    ]);
  });

  it("입력 종류 — 선택지가 있으면 선택, 서버 형 이름대로, 모르는 형은 값 모양으로(객체는 바꾸지 않음)", () => {
    expect(row("model").kind).toBe("choice");
    expect(row("slippageBp").kind).toBe("number");
    expect(row("trackRecord.windowYears").kind).toBe("integer");
    expect(row("parkCashInShy").kind).toBe("boolean");
    expect(row("cashLikeTickers").kind).toBe("list");
    expect(row("decisionTimeKst").kind).toBe("text");
    expect(row("odd").kind).toBe("json");
  });

  it("지금 값·기본값·범위·규칙 책 영향 — 기본값과 다르면 isDefault:false, 기본값을 모르면 null", () => {
    const window = row("trackRecord.windowYears");
    expect(window).toMatchObject({
      initial: "5", current: "5", defaultText: "7", isDefault: false, range: "3~10", affectsRules: true,
    });
    expect(row("slippageBp")).toMatchObject({ initial: "5", isDefault: true, range: "0~100", description: "체결가 슬리피지(bp)" });
    expect(row("parkCashInShy")).toMatchObject({ initial: "true", current: "예" });
    expect(row("trackRecord.candidateCiks").initial).toBe("0001067983, 0001061768");
    expect(row("gate.us10yStage1").range).toBeNull();
    expect(row("odd")).toMatchObject({ defaultText: "—", isDefault: null });
  });

  it("지금 값이 선택지에 없으면 뒤에 붙인다 — 선택 상자가 다른 값을 가리키지 않게", () => {
    const odd = settingGroups({ ...RESPONSE, body: { ...RESPONSE.body, model: "claude-old" } });
    expect(odd[0].rows[0].options).toEqual(["claude-sonnet-5-5", "claude-haiku-5-5", "claude-old"]);
  });

  it("응답이 없거나 키가 없으면 빈 목록", () => {
    expect(settingGroups(null)).toEqual([]);
    expect(settingGroups({ available: true, version: null, body: {}, keys: [] })).toEqual([]);
  });
});

describe("입력 읽기·바뀐 값", () => {
  const groups = settingGroups(RESPONSE);

  it("형으로 읽기 — 숫자·정수·참거짓·목록(쉼표·공백·줄바꿈)·글자, 범위는 검사하지 않는다", () => {
    expect(parseSettingInput({ kind: "number" }, " 7.5 ")).toEqual({ ok: true, value: 7.5 });
    expect(parseSettingInput({ kind: "number" }, "abc")).toEqual({ ok: false, error: "숫자가 아닙니다." });
    expect(parseSettingInput({ kind: "number" }, "")).toEqual({ ok: false, error: "값을 넣으세요." });
    expect(parseSettingInput({ kind: "integer" }, "2.5")).toEqual({ ok: false, error: "정수를 넣으세요." });
    // 범위(3~10) 밖이어도 읽기는 됩니다 — 범위는 저장할 때 서버가 봅니다.
    expect(parseSettingInput({ kind: "integer" }, "99")).toEqual({ ok: true, value: 99 });
    expect(parseSettingInput({ kind: "boolean" }, "false")).toEqual({ ok: true, value: false });
    expect(parseSettingInput({ kind: "list" }, "SHY, TLT\nGLD  ,")).toEqual({ ok: true, value: ["SHY", "TLT", "GLD"] });
    expect(parseSettingInput({ kind: "list" }, "")).toEqual({ ok: true, value: [] });
    expect(parseSettingInput({ kind: "text" }, "  ")).toEqual({ ok: false, error: "값을 넣으세요." });
    expect(parseSettingInput({ kind: "json" }, "{}").ok).toBe(false);
  });

  it("바뀐 값 — 손댄 키만, 읽은 값이 지금 값과 같으면(되돌림·표기만 다름) 바뀐 것이 아님, 못 읽은 키는 오류", () => {
    const view = settingsDraftView(groups, {
      slippageBp: "5",                      // 같은 값
      cashLikeTickers: "SHY,",               // 표기만 다름
      "trackRecord.windowYears": "6",
      model: "claude-haiku-5-5",
      maxPositions: "스물",
      parkCashInShy: "false",
    });
    expect(view.changes).toEqual([
      { key: "model", from: "claude-sonnet-5-5", to: "claude-haiku-5-5", value: "claude-haiku-5-5", affectsRules: false },
      { key: "parkCashInShy", from: "예", to: "아니오", value: false, affectsRules: true },
      { key: "trackRecord.windowYears", from: "5", to: "6", value: 6, affectsRules: true },
    ]);
    expect(view.errors).toEqual({ maxPositions: "숫자가 아닙니다." });
  });

  it("저장 본문은 바뀐 키만, 키는 점 경로 그대로", () => {
    const { changes } = settingsDraftView(groups, { "blend.groupB.topN": "3", cashLikeTickers: "SHY TLT" });
    expect(settingsPatch(changes)).toEqual({ "blend.groupB.topN": 3, cashLikeTickers: ["SHY", "TLT"] });
    expect(settingsPatch([])).toEqual({});
  });

  it("저장 전 확인 — 규칙 책 영향 키가 있을 때만 경고와 키 목록, 없으면 null(바로 저장)", () => {
    const rules = settingsDraftView(groups, { "trackRecord.windowYears": "6", slippageBp: "10" }).changes;
    const text = settingsConfirmText(rules);
    expect(text).toContain(RULES_FROZEN_WARNING);
    expect(text).toContain("바꾸는 규칙 계좌 영향 키: trackRecord.windowYears.");
    expect(text).not.toContain("slippageBp");
    expect(settingsConfirmText(settingsDraftView(groups, { slippageBp: "10" }).changes)).toBeNull();
  });
});

describe("저장 결과·이력", () => {
  it("저장 결과 — 새 버전과 바뀐 키, 서버 경고는 따로, 바뀐 키가 없으면 그대로라고, ok:false는 경고", () => {
    expect(settingsSaveResultView({ version: 4, changedKeys: ["trackRecord.windowYears"], warning: `${RULES_FROZEN_WARNING}` })).toEqual({
      tone: "info",
      text: "설정 v4(으)로 저장했습니다 — 바뀐 키 trackRecord.windowYears. 재기동 없이 다음 작업부터 씁니다.",
      warning: RULES_FROZEN_WARNING,
    });
    expect(settingsSaveResultView({ version: 3, changedKeys: [], warning: null }))
      .toEqual({ tone: "info", text: "바뀐 값이 없습니다 — 설정 v3 그대로입니다.", warning: null });
    expect(settingsSaveResultView({ ok: false, version: null, changedKeys: [], warning: null, message: "테이블 없음" }))
      .toEqual({ tone: "warn", text: "테이블 없음", warning: null });
  });

  it("이력 — 서버 순서 그대로, 바뀐 키는 그 버전의 값과 함께(본문이 없으면 키만), 처음 버전은 '처음 값', 규칙 책 영향 표시", () => {
    const rows = settingsHistoryRows([
      {
        version: 3, changedKeys: ["trackRecord.windowYears", "slippageBp"], createdAtKst: "2026-10-25 10:02",
        body: { slippageBp: 7, trackRecord: { windowYears: 5 } },
      },
      { version: 2, changedKeys: ["model"], createdAtKst: null },
      { version: 1, changedKeys: [], createdAtKst: "2026-10-24 09:00" },
      "깨진 줄",
    ], RESPONSE.keys);
    expect(rows.map((row) => [row.version, row.createdAt, row.changes, row.rulesAffected])).toEqual([
      ["v3", "2026-10-25 10:02", "trackRecord.windowYears = 5 · slippageBp = 7", true],
      ["v2", "—", "model", false],
      ["v1", "2026-10-24 09:00", "처음 값(초기화)", false],
    ]);
    expect(settingsHistoryRows(undefined, undefined)).toEqual([]);
  });

  it("탭 — 설정은 이제 내용이 있다(준비 중 아님), 프레임·메모도 단계 3b에서 열렸다", () => {
    expect(PAPER_TABS.find((tab) => tab.key === "settings")?.opensIn).toBeNull();
    expect(PAPER_TABS.find((tab) => tab.key === "framework")?.opensIn).toBeNull();
    expect(PAPER_TABS.find((tab) => tab.key === "notes")?.opensIn).toBeNull();
  });
});
