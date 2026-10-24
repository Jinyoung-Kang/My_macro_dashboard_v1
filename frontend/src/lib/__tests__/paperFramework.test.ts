/**
 * 모의 매매 단계 3b(프레임·메모)의 표기 — 지금 판·판 목록·비교, 수동 편집 초안, 제안(평가·변경·승인), 메모, 성과 화면의 판 경계.
 * 렌더링·토큰 어림·예산 초과·유효 메모 판정은 서버 값 그대로이고 화면은 이름만 붙인다(ADR 0004). 모르는 값은 "—", 0으로 채우지 않는다.
 */
import { describe, expect, it } from "vitest";
import {
  approveResultView,
  charCount,
  editFrameworkDraft,
  frameworkBoundaryView,
  frameworkBudgetFrom,
  frameworkCompareDefaults,
  frameworkCurrentView,
  frameworkDraftCheck,
  frameworkDraftFrom,
  frameworkInfoOf,
  frameworkSavePayload,
  frameworkSaveResultView,
  frameworkStatusBadge,
  frameworkVersionLabel,
  frameworkVersionRows,
  insightNotePayload,
  noteArchiveConfirm,
  noteCreateResultView,
  noteDraftCheck,
  noteExpiryHint,
  notePayload,
  noteRows,
  PAPER_TABS,
  proposalView,
  proposeResultView,
  rejectResultView,
  renderedCompare,
} from "../paper";
import type { PaperFrameworkBody, PaperFrameworkProposal, PaperFrameworkVersion } from "../types";

function body(over: Partial<PaperFrameworkBody> = {}): PaperFrameworkBody {
  return {
    schemaVersion: 1,
    title: "임시 v0 — OS 원문 반영 전",
    status: "INTERIM",
    principles: [{ id: "P1", text: "패킷의 숫자에서 출발한다." }, { id: "P2", text: "과매매하지 않는다." }],
    engines: Array.from({ length: 8 }, (_, index) => ({
      id: `E${index + 1}`,
      name: `엔진 ${index + 1}`,
      rules: index === 1
        ? [
          { id: "E2-R1", text: "두 그룹이 겹치는 종목을 우선한다.", addedIn: 0, sourceNoteId: null },
          { id: "E2-R3", text: "13F는 45일 시차를 안고 있다.", addedIn: 0, sourceNoteId: null },
        ]
        : [{ id: `E${index + 1}-R1`, text: `규칙 ${index + 1}`, addedIn: 0, sourceNoteId: null }],
    })),
    guidance: "이 판은 임시다.",
    ...over,
  };
}

function version(over: Partial<PaperFrameworkVersion> = {}): PaperFrameworkVersion {
  return {
    version: 0, status: "INTERIM", title: "임시 v0 — OS 원문 반영 전", body: body(), rendered: "# 투자 OS — v0\n## 원칙\n1. 패킷",
    tokensEst: 4123, overBudget: false, changelog: "임시 v0 — 사용자 OS 원문을 받기 전, 명세·설계의 지침으로 만든 판",
    createdAtKst: "2026-10-25 10:00:00", ...over,
  };
}

function proposal(over: Partial<PaperFrameworkProposal> = {}): PaperFrameworkProposal {
  return {
    id: 3, status: "PENDING", noteId: 12, model: "claude-sonnet-5-5", summary: "원문의 노출 규칙을 E1에 더합니다.",
    operations: [
      { op: "ADD_RULE", target: "E1", text: "VIX 30 이상이면 노출을 줄인다.", why: "원문 3절" },
      { op: "UPDATE_RULE", target: "E2-R1", text: "세 곳 이상 겹치면 우선한다.", why: "원문 5절" },
      { op: "DELETE_PRINCIPLE", target: "P2", text: null, why: "원문과 모순" },
    ],
    assessment: [{ point: "현금 비중은 매일 정한다", verdict: "일치", reason: "P3와 같음" }],
    beforeAfter: "전: 2곳 → 후: 3곳", error: null, createdAtKst: "2026-10-25 11:00", decidedAtKst: null,
    resultVersion: null, baseVersion: 0, ...over,
  };
}

describe("탭", () => {
  it("프레임·메모는 두 탭으로 열렸다(준비 중 아님)", () => {
    expect(PAPER_TABS.map((tab) => tab.label)).toEqual(["개요", "기관 선정", "보유·체결", "결정 로그", "프레임", "메모", "설정"]);
    expect(PAPER_TABS.every((tab) => tab.opensIn === null)).toBe(true);
  });
});

describe("판 이름·상태", () => {
  it("임시 판은 '임시 v0', 사용자 판은 'v1 · 사용자 판', 번호를 모르면 —", () => {
    expect(frameworkVersionLabel(0, "INTERIM")).toBe("임시 v0");
    expect(frameworkVersionLabel(1, "USER")).toBe("v1");
    expect(frameworkVersionLabel(null, "USER")).toBe("—");
    expect(frameworkStatusBadge("INTERIM", 0)).toMatchObject({ label: "임시 v0" });
    expect(frameworkStatusBadge("INTERIM", 0).className).toContain("text-warn");
    expect(frameworkStatusBadge("USER", 1).label).toBe("v1 · 사용자 판");
    expect(frameworkStatusBadge("DRAFT", 2).label).toBe("v2 · DRAFT");
  });

  it("판 정보 — 3b 모양 {version, status, title}과 3a 결정 기록 {version:null, interim:true, label} 둘 다", () => {
    expect(frameworkInfoOf({ version: 0, status: "INTERIM", title: "임시" })).toEqual({ version: 0, status: "INTERIM", title: "임시" });
    expect(frameworkInfoOf({ version: null, interim: true, label: "프레임 v1 준비 전 임시" }))
      .toEqual({ version: null, status: "INTERIM", title: "프레임 v1 준비 전 임시" });
    expect(frameworkInfoOf(null)).toBeNull();
  });
});

describe("지금 판 카드", () => {
  it("토큰 어림 / 예산(설정 frameworkTokenBudget), 임시 판 안내, 구성 수 — 렌더링은 서버 글 그대로", () => {
    const view = frameworkCurrentView(version(), frameworkBudgetFrom(null, { frameworkTokenBudget: 8000 }));
    expect(view?.tokens).toBe("추정 4,123토큰 / 예산 8,000토큰");
    expect(view?.overBudget).toBeNull();
    expect(view?.badge.label).toBe("임시 v0");
    expect(view?.interimNote).toContain("지금 판은 임시 v0입니다 — 사용자 OS 원문을 받기 전, 명세·설계의 지침으로 만든 판입니다.");
    expect(view?.interimNote).toContain("제안을 승인한 판은 기준 판의 상태를 이어받아 임시로 남습니다");
    expect(view?.counts).toBe("원칙 2 · 엔진 8 · 규칙 9");
    expect(view?.createdAt).toBe("2026-10-25 10:00");
    expect(view?.rendered).toBe("# 투자 OS — v0\n## 원칙\n1. 패킷");
  });

  it("예산 초과는 서버 판정(overBudget)만 쓰고, 판 응답의 예산이 설정보다 우선, 어림을 모르면 —", () => {
    const over = frameworkCurrentView(version({ overBudget: true, tokensEst: 9100, tokenBudget: 9000 }), 8000);
    expect(over?.tokens).toBe("추정 9,100토큰 / 예산 9,000토큰");
    expect(over?.overBudget).toContain("렌더링이 예산을 넘습니다");
    expect(frameworkCurrentView(version({ tokensEst: null }), null)?.tokens).toBe("—");
    expect(frameworkCurrentView(version({ tokensEst: 100 }), null)?.tokens).toBe("추정 100토큰");
    // 서버가 넘었다고 하지 않으면(어림이 예산보다 커 보여도) 경고하지 않습니다 — 화면이 다시 판정하지 않음.
    expect(frameworkCurrentView(version({ tokensEst: 9100, overBudget: false }), 8000)?.overBudget).toBeNull();
    expect(frameworkCurrentView(version({ status: "USER", version: 1 }), 8000)?.interimNote).toBeNull();
    expect(frameworkCurrentView(null)).toBeNull();
    // 예산은 판 응답 맨 위의 tokenBudget이 설정 본문보다 우선, 둘 다 없으면 null
    expect(frameworkBudgetFrom({ tokenBudget: 9000 }, { frameworkTokenBudget: 8000 })).toBe(9000);
    expect(frameworkBudgetFrom({}, null)).toBeNull();
  });
});

describe("판 목록·비교", () => {
  const VERSIONS = [
    { version: 2, title: "투자 OS", changelog: "제안 #3", createdAtKst: "2026-10-26 09:00", sourceProposalId: 3 },
    { version: 1, title: "투자 OS", changelog: "원문 옮김", createdAtKst: "2026-10-25 12:00", sourceProposalId: null },
    { version: 0, title: "임시 v0", changelog: "시드", createdAtKst: "2026-10-25 10:00", sourceProposalId: null },
  ];

  it("목록은 서버 순서 그대로 — 출처(제안 승인·수동 편집·처음 시드)와 지금 판 표시", () => {
    expect(frameworkVersionRows(VERSIONS, 2).map((row) => [row.version, row.source, row.current])).toEqual([
      ["v2", "제안 #3 승인", true],
      ["v1", "수동 편집", false],
      ["v0", "처음 시드(임시)", false],
    ]);
  });

  it("비교 기본값은 지금 판과 그 바로 앞 판, 판이 하나면 왼쪽 없음", () => {
    expect(frameworkCompareDefaults(VERSIONS, 2)).toEqual({ left: 1, right: 2 });
    expect(frameworkCompareDefaults(VERSIONS, null)).toEqual({ left: 1, right: 2 });
    expect(frameworkCompareDefaults([VERSIONS[2]], 0)).toEqual({ left: null, right: 0 });
  });

  it("나란히 비교 — 다른 쪽에 똑같은 줄이 없는 줄만 표시(빈 줄은 표시 안 함)", () => {
    const compare = renderedCompare("# v0\n\n- [E1-R1] 옛 규칙\n- 같은 줄", "# v1\n\n- 같은 줄\n- [E1-R2] 새 규칙");
    expect(compare.left.map((line) => [line.text, line.changed])).toEqual([
      ["# v0", true], ["", false], ["- [E1-R1] 옛 규칙", true], ["- 같은 줄", false],
    ]);
    expect(compare.right.filter((line) => line.changed).map((line) => line.text)).toEqual(["# v1", "- [E1-R2] 새 규칙"]);
    expect([compare.changedLeft, compare.changedRight]).toEqual([2, 2]);
    expect(renderedCompare(null, undefined)).toEqual({ left: [], right: [], changedLeft: 0, changedRight: 0 });
  });
});

describe("수동 편집(F3)", () => {
  it("지금 판에서 시작 — 새 원칙·규칙 번호는 지운 id를 다시 쓰지 않고, 새 규칙의 addedIn은 비워 보낸다(서버가 새 판 번호로 채움)", () => {
    const draft = frameworkDraftFrom(version());
    expect(draft).not.toBeNull();
    if (!draft) return;
    // P2를 지우고 원칙을 더하면 P3(지운 P2를 다시 쓰지 않음)
    const principles = editFrameworkDraft(editFrameworkDraft(draft, { type: "removePrinciple", index: 1 }), { type: "addPrinciple" });
    expect(principles.principles.map((entry) => entry.id)).toEqual(["P1", "P3"]);
    // E2는 R1·R3이 있으니 다음은 R4, addedIn은 null(판 번호는 서버가 정함)
    const rules = editFrameworkDraft(draft, { type: "addRule", engine: 1 });
    expect(rules.engines[1].rules.at(-1)).toEqual({ id: "E2-R4", text: "", addedIn: null, sourceNoteId: null });
    // 원래 객체는 그대로(불변)
    expect(draft.engines[1].rules).toHaveLength(2);
    const renamed = editFrameworkDraft(draft, { type: "engineName", engine: 0, value: "매크로 축" });
    expect(renamed.engines[0].name).toBe("매크로 축");
    const edited = editFrameworkDraft(draft, { type: "rule", engine: 1, index: 0, text: "고친 규칙" });
    expect(edited.engines[1].rules[0]).toMatchObject({ id: "E2-R1", text: "고친 규칙", addedIn: 0 });
  });

  it("검사 — 300자 초과·빈 본문·바꾼 내용 없음·엔진 수, 사용자 판에서 바뀐 것이 없으면 저장 못 함", () => {
    const draft = frameworkDraftFrom(version({ status: "USER", version: 1, body: body({ status: "USER" }) }));
    if (!draft) throw new Error("draft");
    const unchanged = frameworkDraftCheck({ ...draft, changelog: "x" }, version({ status: "USER", version: 1, body: body({ status: "USER" }) }));
    expect(unchanged.canSave).toBe(false);
    expect(unchanged.errors).toContain("기준 판과 바뀐 내용이 없습니다.");

    const long = editFrameworkDraft(draft, { type: "rule", engine: 0, index: 0, text: "가".repeat(301) });
    const empty = editFrameworkDraft(long, { type: "addPrinciple" });
    const check = frameworkDraftCheck(empty, version({ status: "USER", version: 1 }));
    expect(check.errors).toEqual([
      "원칙 P3: 본문이 비었습니다.",
      "규칙 E1-R1: 300자를 넘습니다(301자).",
      "바꾼 내용(changelog)을 적으세요 — 판 목록에 남습니다.",
    ]);
    const fewer = frameworkDraftCheck({ ...draft, engines: draft.engines.slice(0, 7), changelog: "x" }, null);
    expect(fewer.errors).toContain("엔진은 8개여야 합니다(지금 7개).");
  });

  it("임시 판은 내용을 그대로 두고 사용자 판으로 확정하는 저장도 새 판 — 본문은 status USER, 글은 앞뒤 공백을 뗀다", () => {
    const draft = frameworkDraftFrom(version());
    if (!draft) throw new Error("draft");
    const ready = editFrameworkDraft(editFrameworkDraft(draft, { type: "changelog", value: " 사용자 판으로 확정 " }), {
      type: "principle", index: 0, text: "  패킷의 숫자에서 출발한다.  ",
    });
    const check = frameworkDraftCheck(ready, version());
    expect(check).toEqual({ errors: [], changed: true, canSave: true });
    const payload = frameworkSavePayload(ready);
    expect(payload.changelog).toBe("사용자 판으로 확정");
    expect(payload.body).toMatchObject({ schemaVersion: 1, status: "USER", title: "임시 v0 — OS 원문 반영 전", guidance: "이 판은 임시다." });
    expect((payload.body.principles as { text: string }[])[0].text).toBe("패킷의 숫자에서 출발한다.");
    expect((payload.body.engines as unknown[]).length).toBe(8);
  });

  it("본문이 없는 판은 편집할 수 없다(null), 저장 결과는 새 판 번호", () => {
    expect(frameworkDraftFrom(version({ body: null }))).toBeNull();
    expect(frameworkSaveResultView({ ok: true, version: 1 }).text).toContain("새 판 v1(사용자 판)을 저장했습니다");
    expect(frameworkSaveResultView({ ok: true, version: 2, overBudget: true, warning: "렌더링이 예산을 넘습니다 — 저장은 했습니다" }).warning)
      .toBe("렌더링이 예산을 넘습니다 — 저장은 했습니다");
    expect(frameworkSaveResultView({ ok: false, message: "엔진은 8개여야 합니다" }))
      .toEqual({ tone: "warn", text: "엔진은 8개여야 합니다", warning: null });
  });

  it("글자 수 — 서버와 같은 코드 포인트 단위(이모지 하나가 1자)", () => {
    expect(charCount("가나다", 300)).toEqual({ text: "3/300자", over: false });
    expect(charCount("📈".repeat(300), 300)).toEqual({ text: "300/300자", over: false });
    expect(charCount("a".repeat(4001), 4000)).toEqual({ text: "4,001/4,000자", over: true });
  });
});

describe("제안(F2) — 평가·변경·승인", () => {
  it("변경마다 지금 판의 글을 옆에 — 추가는 엔진 이름, 수정은 지금 규칙, 삭제는 '(삭제)'", () => {
    const view = proposalView(proposal(), version());
    expect(view.title).toBe("제안 #3");
    expect(view.status.label).toBe("승인 대기");
    expect(view.pending).toBe(true);
    expect(view.assessment.map((row) => [row.point, row.verdict.label, row.reason])).toEqual([["현금 비중은 매일 정한다", "일치", "P3와 같음"]]);
    expect(view.operations.map((row) => [row.op, row.target, row.before, row.after, row.warning])).toEqual([
      ["규칙 추가", "E1", "E1. 엔진 1 — 새 규칙", "VIX 30 이상이면 노출을 줄인다.", null],
      ["규칙 수정", "E2-R1", "두 그룹이 겹치는 종목을 우선한다.", "세 곳 이상 겹치면 우선한다.", null],
      ["원칙 삭제", "P2", "과매매하지 않는다.", "(삭제)", null],
    ]);
    expect(view.meta.map((cell) => [cell.label, cell.value])).toEqual([
      ["요청 시각", "2026-10-25 11:00"], ["모델", "claude-sonnet-5-5"], ["근거 메모", "#12"], ["기준 판", "v0"],
    ]);
    expect(view.staleBase).toBeNull();
    // 승인은 비용 없음, 임시 판에 승인하면 임시를 이어받는다는 안내
    expect(view.confirmApprove).toContain("변경 3건을 지금 판(임시 v0)에 적용해 새 판을");
    expect(view.confirmApprove).toContain("Claude를 부르지 않습니다(비용 없음)");
    expect(view.confirmApprove).toContain("새 판은 기준 판의 상태(임시)를 이어받습니다");
    expect(view.confirmReject).toContain("판은 바뀌지 않고");
  });

  it("대상 없음·본문 없음·300자 초과는 그 줄에 경고, 기준 판이 지금 판과 다르면 승인 불가 안내", () => {
    const view = proposalView(proposal({
      baseVersion: 0,
      operations: [
        { op: "UPDATE_RULE", target: "E9-R1", text: "x", why: "y" },
        { op: "ADD_PRINCIPLE", target: "", text: null, why: "y" },
        { op: "UPDATE_PRINCIPLE", target: "P1", text: "가".repeat(301), why: "y" },
      ],
    }), version({ version: 1, status: "USER", body: body({ status: "USER" }) }));
    expect(view.operations.map((row) => row.warning)).toEqual([
      "지금 판에 대상이 없습니다 — 승인하면 서버가 거절합니다(아무것도 바꾸지 않음).",
      "새 본문이 없습니다 — 승인하면 서버가 거절합니다(아무것도 바꾸지 않음).",
      "300자를 넘습니다(301자) — 승인하면 서버가 거절합니다(아무것도 바꾸지 않음).",
    ]);
    expect(view.operations[1].target).toBe("(새 번호)");
    expect(view.staleBase).toContain("이 제안의 기준 판(v0)이 지금 판(v1)과 다릅니다");
    expect(view.confirmApprove).not.toContain("이어받습니다");
  });

  it("승인·거절·실패한 제안은 버튼이 없고, 실패는 오류를 보인다", () => {
    const failed = proposalView(proposal({ status: "FAILED", summary: null, operations: null, assessment: null, error: "API_ERROR 429" }), version());
    expect(failed.pending).toBe(false);
    expect(failed.status.label).toBe("실패");
    expect(failed.error).toBe("API_ERROR 429");
    expect(failed.operations).toEqual([]);
    const approved = proposalView(proposal({ status: "APPROVED", resultVersion: 1, decidedAtKst: "2026-10-25 12:00" }), version());
    expect(approved.meta.at(-1)).toEqual({ key: "resultVersion", label: "만든 판", value: "v1" });
    expect(approved.staleBase).toBeNull();
  });

  it("요청·승인·거절 결과 문구 — 실패(FAILED 행)는 경고와 서버 사유, 자동 반영 없음", () => {
    expect(proposeResultView({ ok: true, proposal: proposal() })).toEqual({
      tone: "info", text: "제안(#3)을 받았습니다 — 평가와 변경을 보고 승인하거나 거절하세요. 자동 반영은 없습니다(승인해야 새 판).",
    });
    // 실패한 호출은 ok:false와 FAILED 행이 함께 옵니다 — 행의 사유를 씁니다.
    expect(proposeResultView({ ok: false, proposal: proposal({ status: "FAILED", error: "응답 형식 오류" }) }))
      .toMatchObject({ tone: "warn", text: expect.stringContaining("제안 요청이 실패했습니다(#3) — 응답 형식 오류. 판은 바뀌지 않았습니다.") });
    expect(proposeResultView({ ok: false, message: "하루 호출 상한" })).toEqual({ tone: "warn", text: "하루 호출 상한" });
    expect(approveResultView({ ok: true, version: 1 }).text).toBe("승인했습니다 — 새 판 v1. 다음 Claude 결정부터 이 판으로 판단합니다.");
    expect(approveResultView({ ok: true, version: 1 }).warning).toBeNull();
    expect(approveResultView({ ok: true, version: 2, overBudget: true, warning: "렌더링이 약 9,000토큰으로 예산을 넘습니다" }).warning)
      .toBe("렌더링이 약 9,000토큰으로 예산을 넘습니다");
    expect(rejectResultView({ ok: true }).text).toBe("거절했습니다 — 판은 그대로입니다.");
    expect(insightNotePayload(" ", " 원문 ")).toEqual({ kind: "INSIGHT", title: "투자 OS 원문", body: "원문" });
  });
});

describe("메모(F6)", () => {
  const NOTES = [
    { id: 12, kind: "MARKET", asOfDate: "2026-10-23", expiresOn: "2026-10-28", title: "유가", body: "브렌트 급등", archived: false, active: true, createdAtKst: "2026-10-24 09:00:00" },
    { id: 11, kind: "MARKET", asOfDate: "2026-10-20", expiresOn: "2026-10-22", title: "옛 메모", body: "x", archived: false, active: false, createdAtKst: null },
    { id: 10, kind: "INSIGHT", asOfDate: "2026-10-20", expiresOn: null, title: "투자 OS 원문", body: "원문", archived: false, active: true, createdAtKst: null },
    { id: 9, kind: "MARKET", asOfDate: "2026-10-20", expiresOn: "2026-10-23", title: "보관", body: "y", archived: true, active: false, createdAtKst: null },
    { id: null, kind: "MARKET", title: "번호 없음" },
  ];

  it("목록 — 서버 순서, 상태는 서버 판정(active·archived) 그대로, 방법론 메모는 만료 없음", () => {
    expect(noteRows(NOTES).map((row) => [row.idText, row.kind.label, row.state.label, row.expires, row.length, row.createdAt])).toEqual([
      ["#12", "시황", "유효 — 패킷에 실음", "2026-10-28", "6자", "2026-10-24 09:00"],
      ["#11", "시황", "패킷 밖 — 만료·기준일 전", "2026-10-22", "1자", "—"],
      ["#10", "방법론", "프레임 제안용", "만료 없음", "2자", "—"],
      ["#9", "시황", "보관", "2026-10-23", "1자", "—"],
    ]);
    expect(noteRows(undefined)).toEqual([]);
  });

  it("쓰기 검사 — 제목·본문, 시황 메모 4,000자, 날짜 형식, 만료일 ≥ 기준일. 방법론 메모는 한도 없음", () => {
    const market = { kind: "MARKET" as const, asOfDate: "2026-10-24", expiresOn: "2026-10-23", title: "", body: "가".repeat(4001) };
    expect(noteDraftCheck(market).errors).toEqual([
      "제목을 넣으세요.",
      "시황 메모 본문은 4,000자 이내입니다(지금 4,001자).",
      "만료일이 기준일보다 이릅니다.",
    ]);
    expect(noteDraftCheck(market).bodyCount).toEqual({ text: "4,001/4,000자", over: true });
    const insight = { ...market, kind: "INSIGHT" as const, title: "원문", expiresOn: "2026-01-01" };
    expect(noteDraftCheck(insight)).toMatchObject({ errors: [], canSave: true, bodyCount: { text: "4,001자", over: false } });
    expect(noteDraftCheck({ ...market, title: "t", body: "b", asOfDate: "10/24", expiresOn: "" }).errors).toEqual(["기준일은 YYYY-MM-DD 형식입니다."]);
  });

  it("저장 본문 — 비운 날짜는 보내지 않고(서버 기본값), 방법론 메모는 만료일을 보내지 않는다", () => {
    expect(notePayload({ kind: "MARKET", asOfDate: "", expiresOn: "", title: " 유가 ", body: " 급등 " }))
      .toEqual({ kind: "MARKET", title: "유가", body: "급등" });
    expect(notePayload({ kind: "MARKET", asOfDate: "2026-10-23", expiresOn: "2026-10-30", title: "t", body: "b" }))
      .toEqual({ kind: "MARKET", title: "t", body: "b", asOfDate: "2026-10-23", expiresOn: "2026-10-30" });
    expect(notePayload({ kind: "INSIGHT", asOfDate: "", expiresOn: "2026-10-30", title: "t", body: "b" }))
      .toEqual({ kind: "INSIGHT", title: "t", body: "b" });
  });

  it("만료일 안내는 설정값(못 읽으면 기본 +3거래일이라고), 저장 결과는 서버가 채운 날짜", () => {
    expect(noteExpiryHint({ notesDefaultExpiryTradingDays: 5 })).toBe("비우면 기본 +5거래일(기준일부터 — 설정 notesDefaultExpiryTradingDays)");
    expect(noteExpiryHint(null)).toBe("비우면 기본 +3거래일(기준일부터 — 설정 notesDefaultExpiryTradingDays의 기본값)");
    expect(noteCreateResultView({ ok: true, note: { ...NOTES[0], id: 12 } as never }).text)
      .toBe("시황 메모(#12)를 저장했습니다 — 기준일 2026-10-23 · 만료 2026-10-28. 유효한 동안 Claude 결정 패킷에 자료로 실립니다.");
    expect(noteCreateResultView({ ok: true, note: NOTES[2] as never }).text).toContain("결정 패킷에는 싣지 않고 프레임 제안에만");
    expect(noteArchiveConfirm({ id: 12, title: "유가" })).toContain("이 메모(#12 유가)를 보관할까요? 지우지 않고");
  });
});

describe("성과 화면의 판 경계(F5)", () => {
  it("순자산 기록이 있는 날만 세로선('프레임 vN'), 한 줄에는 모든 경계와 임시 여부", () => {
    const view = frameworkBoundaryView([
      { tradingDate: "2026-10-23", version: 0, status: "INTERIM" },
      { tradingDate: "2026-11-02", version: 1, status: "USER" },
      { tradingDate: null, version: 2, status: "USER" },
    ], ["2026-10-23", "2026-10-26"]);
    expect(view.markers).toEqual([{ x: "2026-10-23", label: "프레임 v0" }]);
    expect(view.text).toBe(
      "프레임 경계(Claude 책 결정이 그 판을 처음 쓴 거래일) — 2026-10-23부터 임시 v0 · 2026-11-02부터 v1(그날 순자산 기록 없음 — 선 없음)",
    );
    expect(frameworkBoundaryView(undefined, [])).toEqual({ markers: [], text: null });
    expect(frameworkBoundaryView([], ["2026-10-23"])).toEqual({ markers: [], text: null });
  });
});
