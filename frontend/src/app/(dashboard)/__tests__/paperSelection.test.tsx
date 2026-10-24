/**
 * 🧪 모의 매매 — 기관 선정 탭(13F 트랙레코드). 서버가 계산한 값을 그대로 보이고, 지어내지 않는다.
 *
 *  - 계산 전(computed:false)이면 수집 진행률만 — 후보 표·수익률 칸은 없다
 *  - 수집 중·자격 없음 행은 서버 사유를 그대로 보인다(순위 없음 "—")
 *  - 커버리지 칸이 수익률 칸보다 먼저(왼쪽) — 후보 표와 펼친 구간 표 둘 다
 *  - 숫자 null은 "—"(0.00으로 채우지 않음)
 *  - "지금 재계산"은 POST /api/paper/run/rerank → 경과 초 → 결과(선정은 그대로) + 표 다시 읽기
 */
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => {
  class UnauthorizedError extends Error {}
  return { apiGet: vi.fn(), apiPost: vi.fn(), apiGetShared: vi.fn(), UnauthorizedError };
});
vi.mock("@/lib/api", () => api);
// 매번 같은 객체를 돌려줘야 합니다 — 새 함수면 그것을 의존성으로 쓰는 effect가 끝없이 다시 돕니다.
const signal = vi.hoisted(() => ({ token: 0, reloadAll: () => {}, notifyUnauthorized: () => {} }));
vi.mock("@/hooks/useRefreshSignal", () => ({ useRefreshSignal: () => signal }));
// 개요 탭이 먼저 그려지므로 차트도 바꿔 끼웁니다(jsdom에서는 recharts가 크기 0이라 아무것도 그리지 않음).
vi.mock("@/components/charts", () => ({
  SERIES_COLORS: { blue: "#3987e5", orange: "#d95926", green: "#199e70" },
  MultiLineSeries: () => null,
}));

import PaperPage from "../paper/page";

const OVERVIEW = { available: true, initialized: true, startDate: "2026-10-23", limits: [], books: [], jobs: [], pendingBackfill: [] };

const COLLECTION = { filers: 27, completeCount: 20, storedFilings: 700, targetFilings: 810, updatedAtKst: "2026-10-24 03:10" };

const SCION_REASON = "최신 분기(2026-06-30) 공시 없음";
const TUDOR_REASON = "수집 중(저장 분기 12/30)";

function candidate(cik: string, name: string, over: Record<string, unknown> = {}) {
  return {
    cik, name, style: "가치·집중", rank: null, eligible: false, ineligibleReason: null, collecting: false,
    cagrPct: 14.21, cumReturnPct: 152.3, spyCumReturnPct: 120.4, excessVsSpyPct: 31.9, volPct: 17.85, mddPct: -24.6, sharpe: 0.83,
    periodsUsed: 27, periodsTotal: 28, coverageMin: 0.613, coverageAvg: 0.874, firstFilingDate: "2019-11-14", lastFilingDate: "2026-08-14",
    storedQuarters: 30, complete: true, selected: false, periods: [], ...over,
  };
}

const COMPUTED = {
  available: true,
  computed: true,
  asOfDate: "2026-10-23",
  windowYears: 7,
  metric: "CAGR",
  criteria: { windowYears: 7, minPeriods: 24, minCoverage: 0.5, avgCoverage: 0.7 },
  candidates: [
    candidate("0001067983", "버크셔 해서웨이 (Berkshire Hathaway)", {
      rank: 1, eligible: true, selected: true,
      periods: [
        {
          filingDate: "2026-05-15", reportDate: "2026-03-31", start: "2026-05-18", end: "2026-08-17", returnPct: 3.42, spyReturnPct: 2.1,
          coverage: 0.912, coveredTickers: 38, holdings: 42,
        },
        {
          filingDate: "2026-08-14", reportDate: "2026-06-30", start: "2026-08-17", end: "2026-10-23", returnPct: null, spyReturnPct: 1.05,
          coverage: 0, coveredTickers: 0, holdings: 40,
        },
      ],
    }),
    candidate("0001649339", "사이언 (Scion Asset Management)", { ineligibleReason: SCION_REASON, lastFilingDate: "2025-11-14" }),
    // 30분기를 다 받지 못한 기관 — 수치는 전부 null(지어내지 않음)
    candidate("0000923093", "튜더 (Tudor Investment)", {
      style: "매크로", collecting: true, complete: false, storedQuarters: 12, ineligibleReason: TUDOR_REASON,
      cagrPct: null, cumReturnPct: null, spyCumReturnPct: null, excessVsSpyPct: null, volPct: null, mddPct: null, sharpe: null,
      periodsUsed: null, periodsTotal: null, coverageMin: null, coverageAvg: null, firstFilingDate: null, lastFilingDate: null,
    }),
  ],
  selected: ["0001067983"],
  selectionHistory: [{ asOfDate: "2026-10-23", selectedCiks: ["0001067983", "0009999999"] }],
  collection: COLLECTION,
  limits: [
    "트랙레코드는 공시일 다음 거래일부터 복제한 값이라 실제 기관 수익률이 아닙니다.",
    "후보 기관 목록은 오늘 알려진 운용사라 생존 편향이 있습니다.",
    "커버리지(티커 붙은 비중)가 100% 미만이면 '커버 보유분 기준'입니다.",
  ],
};

/** 경로 앞부분 → 응답. 없는 경로는 끝나지 않는 요청. 긴 경로부터 맞춥니다. */
let reads: Record<string, () => Promise<unknown>> = {};

beforeEach(() => {
  reads = { "/api/paper/overview": () => Promise.resolve(OVERVIEW) };
  api.apiPost.mockReset();
  api.apiGetShared.mockReset();
  api.apiGetShared.mockImplementation((path: string) => {
    const key = Object.keys(reads).sort((a, b) => b.length - a.length).find((prefix) => path.startsWith(prefix));
    return key ? reads[key]() : new Promise(() => {});
  });
});

afterEach(() => cleanup());

async function openSelectionTab() {
  render(<PaperPage />);
  fireEvent.click(await screen.findByRole("tab", { name: "기관 선정" }));
}

const headersOf = (container: HTMLElement) => within(container).getAllByRole("columnheader").map((th) => th.textContent);
const cellsOf = (name: string) => within(screen.getByRole("row", { name })).getAllByRole("cell").map((td) => td.textContent);

describe("계산 전(computed:false)", () => {
  it("수집 진행률만 보이고, 후보 표·수익률 칸은 그리지 않는다 — 다음 단계 안내도 없다", async () => {
    reads["/api/paper/track-record"] = () => Promise.resolve({ available: true, computed: false, collection: COLLECTION });

    await openSelectionTab();

    expect(await screen.findByText("후보 27곳 중 수집 완료 20곳 · 저장 공시 700/810건 · 마지막 수집 2026-10-24 03:10")).toBeTruthy();
    expect(screen.getByText(/트랙레코드를 아직 계산하지 않았습니다/)).toBeTruthy();
    expect(screen.queryByRole("region", { name: /후보 기관 트랙레코드 표/ })).toBeNull();
    expect(screen.queryByRole("columnheader", { name: "CAGR" })).toBeNull();
    expect(screen.queryByText("선정 이력")).toBeNull();
    // 준비 중 자리 문구는 이제 없습니다.
    expect(screen.queryByText(/탭은 다음 단계에서 엽니다/)).toBeNull();
    expect(screen.getByRole("button", { name: "지금 재계산" })).toBeTruthy();
  });

  it("테이블이 없으면(available:false) 서버 문구 그대로", async () => {
    const message = "모의 매매 테이블이 아직 없습니다. 수집기가 스키마(V8)를 적용하면 생깁니다 — make up 후 다시 보세요.";
    reads["/api/paper/track-record"] = () => Promise.resolve({ available: false, message });

    await openSelectionTab();

    expect(await screen.findByText(message)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "지금 재계산" })).toBeNull();
  });
});

describe("계산된 트랙레코드", () => {
  beforeEach(() => {
    reads["/api/paper/track-record"] = () => Promise.resolve(COMPUTED);
  });

  it("커버리지 칸이 수익률 칸보다 먼저 — 기준·순위 기준을 제목 아래에", async () => {
    await openSelectionTab();

    const table = await screen.findByRole("region", { name: /후보 기관 트랙레코드 표/ });
    const headers = headersOf(table);
    expect(headers).toEqual([
      "순위", "기관", "자격", "선정", "수집", "구간", "커버리지 최소", "커버리지 평균",
      "CAGR", "누적", "SPY 누적", "초과(SPY 대비)", "변동성(연율)", "MDD", "샤프",
    ]);
    expect(headers.indexOf("커버리지 평균")).toBeLessThan(headers.indexOf("CAGR"));
    expect(screen.getByText(/2026-10-23 기준 · 순위 기준 연율 수익률\(CAGR\) · 자격: 창 7년 · 구간 ≥ 24개 · 최소 커버리지 ≥ 50% · 평균 커버리지 ≥ 70%/))
      .toBeTruthy();
  });

  it("자격 있는 기관은 순위·선정 배지와 서버 값 그대로, 커버리지는 % 표기", async () => {
    await openSelectionTab();

    await screen.findByRole("row", { name: "버크셔 해서웨이 (Berkshire Hathaway)" });
    const cells = cellsOf("버크셔 해서웨이 (Berkshire Hathaway)");
    expect(cells[0]).toBe("1");
    expect(cells[2]).toBe("자격");
    expect(cells[3]).toBe("선정");
    expect(cells[4]).toBe("30/30 · 완료");
    expect(cells[5]).toBe("27/282019-11-14 ~ 2026-08-14");
    expect(cells[6]).toBe("61.3%");
    expect(cells[7]).toBe("87.4%");
    expect(cells[8]).toBe("+14.21%");
    expect(cells[11]).toBe("+31.90%p");
    expect(cells[12]).toBe("17.85%");
    expect(cells[13]).toBe("-24.60%");
    expect(cells[14]).toBe("0.83");
  });

  it("자격 없음·수집 중인 행은 서버 사유를 보이고 순위는 —, 모르는 숫자는 전부 —", async () => {
    await openSelectionTab();

    await screen.findByRole("row", { name: "사이언 (Scion Asset Management)" });
    const scion = cellsOf("사이언 (Scion Asset Management)");
    expect(scion[0]).toBe("—");
    expect(scion[2]).toBe(`자격 없음${SCION_REASON}`);
    expect(scion[3]).toBe("—");

    const tudor = cellsOf("튜더 (Tudor Investment)");
    expect(tudor[0]).toBe("—");
    expect(tudor[2]).toBe(`수집 중${TUDOR_REASON}`);
    expect(tudor[4]).toBe("12/30 · 수집 중");
    expect(tudor.slice(6)).toEqual(Array(9).fill("—"));
    expect(within(screen.getByRole("row", { name: "튜더 (Tudor Investment)" })).queryByText(/0\.00/)).toBeNull();
  });

  it("행을 펼치면 구간 표 — 커버리지가 수익률보다 먼저, 수익률이 없는 구간은 —", async () => {
    await openSelectionTab();

    const row = await screen.findByRole("row", { name: "버크셔 해서웨이 (Berkshire Hathaway)" });
    const toggle = within(row).getByRole("button", { name: "구간 펼치기" });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    fireEvent.click(toggle);

    const periods = screen.getByRole("region", { name: "버크셔 해서웨이 (Berkshire Hathaway) 구간" });
    expect(headersOf(periods)).toEqual(["공시일", "보고 분기", "시작", "끝", "커버리지", "수익률", "SPY"]);
    const first = within(periods).getByText("2026-05-15").closest("tr") as HTMLElement;
    expect(within(first).getAllByRole("cell").map((td) => td.textContent))
      .toEqual(["2026-05-15", "2026-03-31", "2026-05-18", "2026-08-17", "91.2% (티커 38개 · 보유 42줄)", "+3.42%", "+2.10%"]);
    const last = within(periods).getByText("2026-08-14").closest("tr") as HTMLElement;
    const lastCells = within(last).getAllByRole("cell").map((td) => td.textContent);
    expect(lastCells[4]).toBe("0.0% (티커 0개 · 보유 40줄)");
    expect(lastCells[5]).toBe("—");

    // 다시 누르면 접습니다.
    fireEvent.click(within(row).getByRole("button", { name: "구간 접기" }));
    expect(screen.queryByRole("region", { name: "버크셔 해서웨이 (Berkshire Hathaway) 구간" })).toBeNull();
  });

  it("한계 문구 세 개, 선정 이력(후보에 없는 CIK는 그대로), 수집 진행률", async () => {
    await openSelectionTab();

    for (const limit of COMPUTED.limits) expect(await screen.findByText(limit)).toBeTruthy();
    expect(screen.getByText("버크셔 해서웨이 (Berkshire Hathaway), 0009999999")).toBeTruthy();
    expect(screen.getByText("후보 27곳 중 수집 완료 20곳 · 저장 공시 700/810건 · 마지막 수집 2026-10-24 03:10")).toBeTruthy();
  });
});

describe("지금 재계산", () => {
  it("POST /api/paper/run/rerank → 경과 초 → 결과(선정은 그대로)와 표 다시 읽기", async () => {
    let trackReads = 0;
    reads["/api/paper/track-record"] = () => {
      trackReads += 1;
      return Promise.resolve(COMPUTED);
    };
    let finish: (value: unknown) => void = () => {};
    api.apiPost.mockImplementation(() => new Promise((resolve) => {
      finish = resolve;
    }));

    await openSelectionTab();
    fireEvent.click(await screen.findByRole("button", { name: "지금 재계산" }));

    const busy = await screen.findByRole("button", { name: /계산 중… \d+초/ });
    expect((busy as HTMLButtonElement).disabled).toBe(true);
    expect(api.apiPost).toHaveBeenCalledWith("/api/paper/run/rerank", {});

    finish({ ok: true, asOfDate: "2026-10-23", computed: 27, selectedUnchanged: true });

    expect(await screen.findByText(/2026-10-23 기준으로 다시 계산했습니다 · 후보 27곳 — 선정은 바꾸지 않았습니다/)).toBeTruthy();
    await waitFor(() => expect(trackReads).toBeGreaterThanOrEqual(2));
    expect((screen.getByRole("button", { name: "지금 재계산" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("실패하면 서버 문구를 보이고 다시 누를 수 있다", async () => {
    reads["/api/paper/track-record"] = () => Promise.resolve({ available: true, computed: false, collection: COLLECTION });
    api.apiPost.mockRejectedValue(new Error("2026-10-23 SPY 이력이 없습니다."));

    await openSelectionTab();
    fireEvent.click(await screen.findByRole("button", { name: "지금 재계산" }));

    expect(await screen.findByText("2026-10-23 SPY 이력이 없습니다.")).toBeTruthy();
    expect((screen.getByRole("button", { name: "지금 재계산" }) as HTMLButtonElement).disabled).toBe(false);
  });
});
