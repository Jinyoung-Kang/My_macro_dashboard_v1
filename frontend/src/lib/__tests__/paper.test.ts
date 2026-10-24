/**
 * 모의 매매 화면의 표기 — 서버가 준 숫자를 고르고 이름만 붙인다(ADR 0004). 모르는 값은 "—", 0으로 채우지 않는다.
 */
import { describe, expect, it } from "vitest";
import {
  bookCard,
  bookStatusBadge,
  formatSharePrice,
  heldText,
  initPrompt,
  initResultText,
  jsonSummary,
  navChart,
  notStartedNote,
  orderSizeText,
  positionBadges,
  quotesLine,
  resetResultView,
  resolveBookCode,
  riskOutcomeText,
  riskResultView,
  sampleNote,
  statCells,
  statusResultText,
  statusToggle,
  stopText,
  timingLabel,
} from "../paper";
import type { PaperBookSummary, PaperPosition, PaperRiskOutcome, PaperStats } from "../types";

const STATS: PaperStats = {
  cumReturnPct: 1.234, cagrPct: 12.5, volPct: 15.2, mddPct: -3.4, sharpe: 0.81, excessPct: -0.52, beta: 0.97, sampleDays: 42,
};

function book(code: string, over: Partial<PaperBookSummary> = {}): PaperBookSummary {
  return {
    code, kind: "BENCHMARK", label: code, status: "ACTIVE", startedOn: "2026-10-23", initialCash: 100000,
    cash: 12.34, nav: 100123.45, equityValue: 100111.11, exposurePct: 99.99, returnPct: 0.12345,
    lastObservedAtKst: "2026-10-24 05:15", positions: 1, staleCount: 0, stats: STATS, benchmark: null, ...over,
  };
}

const BOOKS = [
  book("rules", { kind: "RULES", label: "규칙 책", status: "NOT_STARTED", benchmark: "bench_spy" }),
  book("claude", { kind: "CLAUDE", label: "Claude 재량 책", status: "NOT_STARTED", benchmark: "bench_spy" }),
  book("bench_spy", { label: "SPY 100%" }),
  book("bench_mix", { label: "SPY 60% · SHY 40%", benchmark: "bench_spy" }),
];

function position(over: Partial<PaperPosition> = {}): PaperPosition {
  return {
    ticker: "SPY", qty: 150, avgCost: 660.33, lastPrice: 662.1, lastPriceAtKst: "2026-10-24 05:15", marketValue: 99315,
    weightPct: 99.2, unrealizedPct: 0.27, highWatermark: 662.1, stopPrice: 609.13, stopDistancePct: 8.0,
    openedOn: "2026-10-23", holdingTradingDays: 1, priceStale: false, splitNote: null, cashLike: false, backfilled: false,
    ...over,
  };
}

function outcome(over: Partial<PaperRiskOutcome> = {}): PaperRiskOutcome {
  return {
    filled: 0, stopped: 0, expired: 0, splits: 0, booksProcessed: [], skippedReason: null, notes: [], errors: [], ...over,
  };
}

describe("책 카드", () => {
  it("서버 값을 그대로 표기한다 — 금액은 센트까지, 비교 기준 이름을 통계 라벨에", () => {
    const card = bookCard(BOOKS[3], BOOKS);
    expect(card.nav).toBe("100,123.45 달러");
    expect(card.returnPct).toBe("+0.12%");
    expect(card.exposure).toBe("100.0%");
    expect(card.initialCash).toBe("100,000 달러");
    expect(card.status.label).toBe("운용 중");
    expect(card.staleWarning).toBeNull();
    expect(card.stats.find((s) => s.key === "excessPct")).toEqual({
      key: "excessPct", label: "초과 수익률(SPY 100% 대비)", value: "-0.52%p",
    });
  });

  it("값을 모르면 0이 아니라 — (통계가 통째로 없어도)", () => {
    const empty = book("x", {
      nav: null, cash: null, exposurePct: null, returnPct: null, positions: null, staleCount: null, stats: null,
      startedOn: null, lastObservedAtKst: null,
    });
    const card = bookCard(empty, [empty]);
    expect([card.nav, card.cash, card.exposure, card.returnPct, card.positions, card.lastObserved]).toEqual(
      ["—", "—", "—", "—", "—", "—"],
    );
    expect(card.startedOn).toBe("시작 전");
    expect(card.stats.every((s) => s.value === "—")).toBe(true);
    expect(card.sample).toContain("통계 없음");
  });

  it("가격을 새로 받지 못한 보유가 있으면 경고 문구", () => {
    expect(bookCard(book("b", { staleCount: 2 }), []).staleWarning).toBe("가격 없음 2종목 — 지난 값으로 평가");
  });

  it("표본이 짧아 연율 통계가 비었으면 그 이유를 적는다(기준 일수는 서버가 정함)", () => {
    const short: PaperStats = { ...STATS, cagrPct: null, volPct: null, mddPct: null, sharpe: null, excessPct: null, beta: null, sampleDays: 1 };
    expect(sampleNote(short)).toContain("누적 수익률만");
    expect(statCells(short, null).map((s) => s.value)).toEqual(["+1.23%", "—", "—", "—", "—", "—", "—"]);
    expect(sampleNote(STATS)).toBe("표본 42거래일");
    // 구간은 충분한데 하루 간격 기록이 모자라면 변동성 계열만 비고, 그 이유를 적는다
    expect(sampleNote({ ...STATS, volPct: null, sharpe: null, beta: null })).toContain("하루 간격 기록이 모자라");
    // 비교 기준이 없으면(SPY 책 자신) 라벨에 기준을 적지 않는다
    expect(statCells(STATS, null).find((s) => s.key === "beta")?.label).toBe("베타");
  });

  it("상태 배지 — 모르는 상태는 받은 글자 그대로", () => {
    expect(bookStatusBadge("PAUSED").label).toBe("일시정지");
    expect(bookStatusBadge("NOT_STARTED").label).toBe("시작 전 · 현금만");
    expect(bookStatusBadge("CLOSED").label).toBe("CLOSED");
    expect(bookStatusBadge(null).label).toBe("—");
  });
});

describe("순자산 곡선", () => {
  it("책 순서대로 계열을 만들고, 빠진 날은 null 그대로(선을 끊음), 모자란 배열도 null", () => {
    const { rows, series } = navChart(
      {
        dates: ["2026-10-23", "2026-10-26", "2026-10-27"],
        books: { bench_mix: [100000, 100050.5, null], bench_spy: [100000, 100210], rules: [null, null, null], ghost: [1, 2, 3] },
      },
      BOOKS,
    );
    expect(series).toEqual([
      { key: "rules", name: "규칙 책" },
      { key: "bench_spy", name: "SPY 100%" },
      { key: "bench_mix", name: "SPY 60% · SHY 40%" },
      { key: "ghost", name: "ghost" },
    ]);
    expect(rows).toEqual([
      { date: "2026-10-23", rules: null, bench_spy: 100000, bench_mix: 100000, ghost: 1 },
      { date: "2026-10-26", rules: null, bench_spy: 100210, bench_mix: 100050.5, ghost: 2 },
      { date: "2026-10-27", rules: null, bench_spy: null, bench_mix: null, ghost: 3 },
    ]);
  });

  it("곡선이 없으면 빈 차트", () => {
    expect(navChart(null, BOOKS)).toEqual({ rows: [], series: [] });
    expect(navChart(undefined, BOOKS)).toEqual({ rows: [], series: [] });
  });
});

describe("장부 관측치", () => {
  const QUOTES = {
    collectedAtKst: "2026-10-24 05:10", ageSeconds: 60, barDate: "2026-10-23", finalClose: true, tickers: 157,
    staleTickers: ["XLB"], stale: false,
  };

  it("tickers는 개수, staleTickers는 이름 목록(백엔드 계약) — 이어받은 수는 목록 길이", () => {
    expect(quotesLine(QUOTES)).toBe("봉 날짜 2026-10-23(미국 동부) · 티커 157개 · 지난 값 이어받음 1개");
    expect(quotesLine({ ...QUOTES, staleTickers: [] })).toBe("봉 날짜 2026-10-23(미국 동부) · 티커 157개 · 지난 값 이어받음 0개");
  });

  it("봉이 하나도 없으면 봉 날짜만 —, 저장본이 없으면 그렇다고 적는다", () => {
    expect(quotesLine({ ...QUOTES, barDate: null, tickers: 0, staleTickers: [] }))
      .toBe("봉 날짜 —(미국 동부) · 티커 0개 · 지난 값 이어받음 0개");
    expect(quotesLine(null)).toContain("저장본이 아직 없습니다");
  });
});

describe("보유 표", () => {
  it("가격을 한 번도 못 받았으면 '가격 없음 · 평단 평가', 이번에 못 받았으면 '가격 없음 · 지난 값'", () => {
    expect(positionBadges(position({ lastPrice: null, priceStale: true })).map((b) => b.label)).toEqual(["가격 없음 · 평단 평가"]);
    expect(positionBadges(position({ priceStale: true })).map((b) => b.label)).toEqual(["가격 없음 · 지난 값"]);
    expect(positionBadges(position())).toEqual([]);
  });

  it("사후 체결·분할·현금성 배지", () => {
    const labels = positionBadges(position({ backfilled: true, splitNote: "2:1 분할 (2026-11-02)", cashLike: true })).map((b) => b.label);
    expect(labels).toEqual(["사후 체결", "분할 조정", "현금성"]);
  });

  it("스탑 칸 — 스탑 가격은 주당 가격이라 4자리, 현금성은 적용 안 함, 값이 없으면 —", () => {
    expect(stopText(position())).toBe("609.1300 · 거리 8.0%");
    expect(stopText(position({ stopPrice: 9.2046 }))).toBe("9.2046 · 거리 8.0%");
    expect(stopText(position({ cashLike: true }))).toBe("적용 안 함");
    expect(stopText(position({ stopPrice: null }))).toBe("—");
    expect(stopText(position({ stopDistancePct: null }))).toBe("609.1300");
  });

  it("주당 가격은 4자리 — 싼 종목의 5bp 슬리피지(9.00 → 9.0045)가 보인다, 모르면 —", () => {
    expect(formatSharePrice(9)).toBe("9.0000");
    expect(formatSharePrice(9.0045)).toBe("9.0045");
    expect(formatSharePrice(662.0808)).toBe("662.0808");
    expect(formatSharePrice(1234.5)).toBe("1,234.5000");
    expect(formatSharePrice(null)).toBe("—");
  });

  it("보유 기간 — 모르는 부분은 —", () => {
    expect(heldText(position())).toBe("2026-10-23부터 · 1거래일");
    expect(heldText(position({ openedOn: null, holdingTradingDays: null }))).toBe("— · —");
  });
});

describe("주문 표", () => {
  it("크기 — 수량 > 목표 비중 > 매도 비율, 매도에 셋 다 없으면 전량, 매수는 —", () => {
    const base = { qty: null, targetWeightPct: null, sellFraction: null };
    expect(orderSizeText({ ...base, side: "BUY", qty: 151 })).toBe("151주");
    expect(orderSizeText({ ...base, side: "BUY", targetWeightPct: 20 })).toBe("목표 비중 20.00%");
    expect(orderSizeText({ ...base, side: "SELL", sellFraction: 0.5 })).toBe("보유의 50%");
    expect(orderSizeText({ ...base, side: "SELL" })).toBe("전량");
    expect(orderSizeText({ ...base, side: "BUY" })).toBe("—");
  });

  it("체결 시점 이름 — 모르는 값은 그대로", () => {
    expect(timingLabel("BACKFILL")).toBe("사후 체결");
    expect(timingLabel("SOMEDAY")).toBe("SOMEDAY");
    expect(timingLabel(null)).toBe("—");
  });

  it("근거 JSON은 '키: 값'으로 한 줄, 비었으면 —, 길면 자른다", () => {
    expect(jsonSummary({ cashLimited: true, note: "현금 부족" })).toBe("cashLimited: true · note: 현금 부족");
    expect(jsonSummary({ nested: { a: 1 }, list: ["SPY", "SHY"], none: null })).toBe('nested: {"a":1} · list: SPY, SHY · none: —');
    expect(jsonSummary({})).toBe("—");
    expect(jsonSummary(null)).toBe("—");
    expect(jsonSummary("x".repeat(200), 10)).toBe(`${"x".repeat(9)}…`);
  });
});

describe("버튼", () => {
  it("일시정지·재개 — 시작 전인 책은 버튼이 없다(시작은 초기 결정이 함)", () => {
    expect(statusToggle("ACTIVE")).toEqual({ next: "PAUSED", label: "일시정지" });
    expect(statusToggle("PAUSED")).toEqual({ next: "ACTIVE", label: "재개" });
    expect(statusToggle("NOT_STARTED")).toBeNull();
    expect(statusToggle(undefined)).toBeNull();
  });

  it("시작 전 안내 — 벤치마크는 종가를 받은 뒤 초기화를 다시 누르라고, 규칙·Claude 책은 초기 결정을 기다린다고", () => {
    expect(notStartedNote("BENCHMARK")).toContain("종가를 받은 뒤 초기화를 다시 누르세요");
    expect(notStartedNote("BENCHMARK")).not.toContain("초기 결정");
    expect(notStartedNote("RULES")).toContain("다음 단계의 초기 결정으로 시작합니다");
    expect(notStartedNote("CLAUDE")).not.toContain("종가를 받은 뒤");
  });

  it("기본 책 — 고른 책이 있으면 그것, 아니면 처음 시작한 책, 없으면 첫 책, 책이 없으면 null", () => {
    expect(resolveBookCode("claude", BOOKS)).toBe("claude");
    expect(resolveBookCode("", BOOKS)).toBe("bench_spy");
    expect(resolveBookCode("gone", BOOKS.slice(0, 2))).toBe("rules");
    expect(resolveBookCode("", [])).toBeNull();
  });

  it("결과 문구", () => {
    expect(initResultText({ ok: true, created: ["rules", "claude", "bench_spy", "bench_mix"], backfilled: ["bench_spy"],
      skipped: [{ code: "bench_mix", reason: "2026-10-23 SHY 종가 없음" }], settingsCreated: true, startPricesCaptured: 153 }))
      .toBe("만든 책 rules, claude, bench_spy, bench_mix · 사후 체결 bench_spy · bench_mix 시작하지 않음 — 2026-10-23 SHY 종가 없음");
    expect(initResultText({ ok: true, created: [], backfilled: [], skipped: [], settingsCreated: false, startPricesCaptured: 0 })).toBe("새로 만든 책 없음(이미 있음)");
    expect(riskOutcomeText(outcome({ filled: 1, expired: 2, booksProcessed: ["bench_spy"] })))
      .toBe("체결 1 · 스탑 0 · 만료 2 · 분할 0 · 처리한 책 bench_spy");
    expect(riskOutcomeText(outcome({ skippedReason: "새 저장본 없음" })))
      .toBe("체결 0 · 스탑 0 · 만료 0 · 분할 0 · 처리한 책 없음 — 새 저장본 없음");
    expect(riskOutcomeText(null)).toBe("결과를 받지 못했습니다.");
    expect(statusResultText({ ok: true, book: book("bench_spy", { label: "SPY 100%", status: "PAUSED" }), cancelledOrders: 3 }))
      .toBe("SPY 100%: 일시정지 · 대기 주문 3건 취소");
    expect(statusResultText({ ok: true, book: book("bench_spy", { label: "SPY 100%" }), cancelledOrders: null }))
      .toBe("SPY 100%: 운용 중");
  });
});

describe("초기화 카드", () => {
  const PENDING_BOOKS = BOOKS.map((entry) => (entry.kind === "BENCHMARK" ? { ...entry, status: "NOT_STARTED" as const } : entry));

  it("초기화 전이면 '장부 초기화'", () => {
    expect(initPrompt({ initialized: false, startDate: "2026-10-23", books: [] })?.title).toBe("장부 초기화");
  });

  it("책 네 개가 있어도 벤치마크가 사후 체결을 기다리면(pendingBackfill) 카드를 계속 보이고 다시 누르라고 적는다", () => {
    const prompt = initPrompt({
      initialized: true, startDate: "2026-10-23", books: PENDING_BOOKS, pendingBackfill: ["bench_spy", "bench_mix"],
    });
    expect(prompt?.title).toBe("벤치마크 사후 체결 대기");
    expect(prompt?.body).toContain("SPY 100%, SPY 60% · SHY 40%: 출발일(2026-10-23) 종가를 아직 받지 못해");
    expect(prompt?.body).toContain("종가를 받은 뒤 초기화를 다시 누르세요");
  });

  it("모두 시작했으면(pendingBackfill 비었거나 이 필드를 아직 안 보내는 백엔드) 카드 없음", () => {
    expect(initPrompt({ initialized: true, startDate: "2026-10-23", books: BOOKS, pendingBackfill: [] })).toBeNull();
    expect(initPrompt({ initialized: true, startDate: "2026-10-23", books: BOOKS })).toBeNull();
  });

  it("목록에 없는 책 코드는 코드 그대로", () => {
    expect(initPrompt({ initialized: true, startDate: null, books: [], pendingBackfill: ["bench_x"] })?.body)
      .toContain("bench_x: 출발일(—)");
  });
});

describe("리스크 작업 결과", () => {
  it("ok:false면 경고색 + 오류 목록 — HTTP 200이어도 실패를 성공처럼 보이지 않는다", () => {
    const view = riskResultView({
      ok: false,
      outcome: outcome({
        filled: 1, booksProcessed: ["bench_spy"],
        notes: ["bench_spy: 주문 7 BUY SPY 현금 부족으로 10주 중 9주"],
        errors: ["claude: IllegalStateException 상태 저장 실패"],
      }),
    });
    expect(view.tone).toBe("warn");
    expect(view.warning).toContain("오류가 난 책이 있습니다");
    expect(view.errors).toEqual(["claude: IllegalStateException 상태 저장 실패"]);
    expect(view.notes).toEqual(["bench_spy: 주문 7 BUY SPY 현금 부족으로 10주 중 9주"]);
    expect(view.summary).toBe("체결 1 · 스탑 0 · 만료 0 · 분할 0 · 처리한 책 bench_spy");
  });

  it("ok:true면 안내색, 경고 없음, 처리 메모는 그대로 보인다", () => {
    const view = riskResultView({ ok: true, outcome: outcome({ notes: ["rules: 주문 3 SELL XLE 취소 — 재진입 금지"] }) });
    expect(view.tone).toBe("info");
    expect(view.warning).toBeNull();
    expect(view.errors).toEqual([]);
    expect(view.notes).toEqual(["rules: 주문 3 SELL XLE 취소 — 재진입 금지"]);
  });

  it("테이블이 없어 outcome 없이 ok:false면 서버 문구를 요약으로", () => {
    const view = riskResultView({ ok: false, message: "모의 매매 테이블이 아직 없습니다." });
    expect(view.tone).toBe("warn");
    expect(view.warning).toBe("리스크 작업이 돌지 않았습니다.");
    expect(view.summary).toBe("모의 매매 테이블이 아직 없습니다.");
    expect(view.notes).toEqual([]);
  });
});

describe("책 초기화 결과", () => {
  const spy = book("bench_spy", { label: "SPY 100%" });

  it("벤치마크를 다시 사후 체결하지 못했으면 서버 사유를 경고로", () => {
    const reason = "2026-10-23 SPY 종가가 아직 없습니다 — 수집기가 받은 뒤 초기화를 다시 누르세요.";
    const view = resetResultView({ ok: true, book: { ...spy, status: "NOT_STARTED" }, backfilled: false, skippedReason: reason });
    expect(view.tone).toBe("warn");
    expect(view.text).toBe(`SPY 100%을(를) 초기화했습니다 — 지금 시작 전 · 현금만. 출발일 종가로 다시 사후 체결하지 못했습니다 — ${reason}`);
  });

  it("다시 사후 체결했으면 그렇다고, 규칙·Claude 책은 상태만", () => {
    expect(resetResultView({ ok: true, book: spy, backfilled: true, skippedReason: null }))
      .toEqual({ tone: "info", text: "SPY 100%을(를) 초기화했습니다 — 지금 운용 중 · 출발일 종가로 다시 사후 체결했습니다" });
    expect(resetResultView({
      ok: true, book: book("rules", { label: "규칙 책", status: "NOT_STARTED" }), backfilled: false, skippedReason: null,
    })).toEqual({ tone: "info", text: "규칙 책을(를) 초기화했습니다 — 지금 시작 전 · 현금만" });
  });
});
