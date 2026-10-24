/**
 * 🧪 모의 알고리즘 매매 화면의 표기 — React를 모르는 순수 함수(ADR 0004).
 *
 * 숫자는 전부 백엔드가 계산해 보냅니다(순자산·수익률·통계·스탑 가격·비중). 여기서는 받은 값을 고르고,
 * 이름을 붙이고, 차트 행으로 늘어놓기만 합니다 — 화면에서 다시 계산하면 장부와 화면이 다른 숫자를 말하게 됩니다.
 * 모르는 값(null)은 "—"로 둡니다. 0으로 채우지 않습니다.
 */
import { EMPTY, formatKst, formatLatency, formatNumber, formatPercent, formatSigned, formatWithUnit } from "./format";
import type {
  PaperBookResetResult,
  PaperBookStatusResult,
  PaperBookSummary,
  PaperDecisionDetail,
  PaperDecisionSummary,
  PaperGateIndicator,
  PaperGateThresholds,
  PaperInitialDecisionResult,
  PaperInitResult,
  PaperOrder,
  PaperOverviewResponse,
  PaperOverviewSelection,
  PaperPosition,
  PaperQuotesSummary,
  PaperRerankResult,
  PaperRiskOutcome,
  PaperRiskResult,
  PaperRunDecisionResult,
  PaperSelectedFiler,
  PaperSelectionHistoryEntry,
  PaperStats,
  PaperTrackRecordCandidate,
  PaperTrackRecordCollection,
  PaperTrackRecordCriteria,
  PaperTrackRecordGroupB,
  PaperTrackRecordPeriod,
} from "./types";

/**
 * 이 화면 숫자의 출처. 다른 화면은 lib/sources.ts에 모으지만, 모의 매매는 로컬 전용 기능이라 이 파일에 둡니다
 * (sources.ts로 옮길지는 단계 4 마무리에서 정함).
 */
export const PAPER_SOURCES = {
  ledger: "대시보드 모의 장부 (PostgreSQL paper_*) — 체결가는 Yahoo Finance(yfinance) 지연 시세, 사후 체결은 그날 종가",
  quotes: "Yahoo Finance (yfinance) — 모의 매매 유니버스 일봉(장중 마지막 봉 = 지연 시세, 저장본 paper.quotes)",
  jobs: "대시보드 작업 기록 (PostgreSQL paper_job_runs)",
  trackRecord: "SEC EDGAR 13F-HR(후보 기관 분기 공시, 저장본 sec.13f.{cik}.q30) · 수정주가 Yahoo Finance(yfinance, equity_close) — "
    + "공시일 기준 복제 수익률은 대시보드 백엔드 계산(PostgreSQL paper_track_record)",
  groupB: "SEC EDGAR 13F-HR(기존 13F 메뉴 기관 분기 공시, 저장본 sec.13f.{cik}.q30 — 없으면 q8) · 수정주가 Yahoo Finance(yfinance, "
    + "equity_close) — 공시일 기준 복제 수익률은 대시보드 백엔드 계산(PostgreSQL paper_track_record, 그룹 B)",
  selection: "대시보드 선정 기록 (PostgreSQL paper_selection_log)",
  pool: "대시보드 선정 기록 (PostgreSQL paper_selection_log — 신호 풀·기관 가중은 선정 때 값)",
  collection: "대시보드 수집기 (SEC EDGAR 13F-HR, 저장본 sec.13f.track_record_index)",
  decisions: "대시보드 결정 기록 (PostgreSQL paper_decisions·paper_orders) — 13F 신호는 SEC EDGAR 13F-HR"
    + "(신호 풀 기관 저장본 sec.13f.{cik}.q30 — 없으면 q8)",
  gate: "매크로 카드 저장본 macro.collected(미국 10년물·브렌트·WTI·USD/JPY) — 게이트 단계는 대시보드 백엔드 계산(PostgreSQL paper_gate_log)",
} as const;

/**
 * 탭 여섯(명세 U2). opensIn이 있는 탭은 아직 내용이 없고 "다음 단계에서 엽니다"만 보입니다.
 * 탭을 아예 숨기지 않는 이유 — 무엇이 올지 보여야 지금 화면이 전부가 아니라는 것을 압니다.
 */
export const PAPER_TABS = [
  { key: "overview", label: "개요", opensIn: null, upcoming: null },
  { key: "selection", label: "기관 선정", opensIn: null, upcoming: null },
  { key: "holdings", label: "보유·체결", opensIn: null, upcoming: null },
  // 결정 로그는 단계 2에서 규칙 책 기록으로 엽니다. Claude 책 기록(패킷·응답·검증·노출)은 단계 3a에서 같은 탭에 더합니다.
  { key: "decisions", label: "결정 로그", opensIn: null, upcoming: null },
  {
    key: "framework", label: "프레임·메모", opensIn: "단계 3b",
    upcoming: "Claude 책의 판단 프레임 버전(승인 전후)과 시장 메모",
  },
  {
    key: "settings", label: "설정", opensIn: "단계 3a",
    upcoming: "모델·슬리피지·상한·스탑 비율·재진입 금지일 등 설정과 바꾼 기록(버전)",
  },
] as const;

export type PaperTabKey = (typeof PAPER_TABS)[number]["key"];

/** 배지 색 — Banner·Freshness와 같은 팔레트. */
const TONE = {
  ok: "border-ok/40 bg-ok/10 text-ok",
  warn: "border-warn/40 bg-warn/10 text-warn",
  info: "border-accent/40 bg-accent/10 text-accent",
  muted: "border-border bg-surface-hover text-muted",
} as const;

export type BadgeTone = keyof typeof TONE;

export interface Badge {
  key: string;
  label: string;
  className: string;
  /** 마우스를 올리면 보이는 설명. */
  title?: string;
}

const BOOK_STATUS: Record<string, { label: string; tone: BadgeTone }> = {
  ACTIVE: { label: "운용 중", tone: "ok" },
  PAUSED: { label: "일시정지", tone: "warn" },
  NOT_STARTED: { label: "시작 전 · 현금만", tone: "muted" },
};

/** 책 상태 배지. 모르는 상태는 받은 글자 그대로(지어내지 않음). */
export function bookStatusBadge(status: string | null | undefined): Badge {
  const known = status ? BOOK_STATUS[status] : undefined;
  return {
    key: status ?? "unknown",
    label: known?.label ?? status ?? EMPTY,
    className: TONE[known?.tone ?? "muted"],
  };
}

const BOOK_KIND: Record<string, string> = { RULES: "규칙", CLAUDE: "Claude 재량", BENCHMARK: "벤치마크" };

export function bookKindLabel(kind: string | null | undefined): string {
  return kind ? (BOOK_KIND[kind] ?? kind) : EMPTY;
}

/** 달러 금액 — 장부는 책마다 10만 달러 규모라 조·억 단위로 줄이지 않고 센트까지 적습니다. */
export function formatDollars(value: number | null | undefined, digits = 2): string {
  return formatWithUnit(value, digits, " 달러");
}

/**
 * 주당 가격(평단·현재가·최고가·스탑가·체결가·관측가) — 소수 4자리. 백엔드가 4자리로 보내는 까닭이 5bp 슬리피지를 싼
 * 종목에서도 보이게 하려는 것이라, 2자리로 자르면 관측가 9.00과 체결가 9.0045가 둘 다 "9.00"이 됩니다. 총액·비율은 2자리 그대로.
 */
export function formatSharePrice(value: number | null | undefined): string {
  return formatNumber(value, 4);
}

/** 비교 기준 책의 이름(통계의 초과·베타 기준). 기준이 없으면 null, 목록에 없으면 코드 그대로. */
export function benchmarkLabel(book: PaperBookSummary, books: readonly PaperBookSummary[]): string | null {
  if (!book.benchmark) return null;
  return books.find((entry) => entry.code === book.benchmark)?.label ?? book.benchmark;
}

/** 통계 한 칸. */
export interface StatCell {
  key: keyof PaperStats;
  label: string;
  value: string;
}

/**
 * 통계 칸을 표시 순서대로. 통계가 없거나 값이 null이면 "—".
 *
 * @param benchmark 초과·베타의 비교 기준 이름(benchmarkLabel). 없으면 라벨에 적지 않습니다.
 */
export function statCells(stats: PaperStats | null | undefined, benchmark: string | null): StatCell[] {
  const versus = benchmark ? `(${benchmark} 대비)` : "";
  return [
    { key: "cumReturnPct", label: "누적 수익률", value: formatPercent(stats?.cumReturnPct) },
    { key: "cagrPct", label: "연율 수익률", value: formatPercent(stats?.cagrPct) },
    { key: "volPct", label: "변동성(연율)", value: formatWithUnit(stats?.volPct, 2, "%") },
    { key: "mddPct", label: "최대 낙폭", value: formatPercent(stats?.mddPct) },
    { key: "sharpe", label: "샤프", value: formatNumber(stats?.sharpe, 2) },
    {
      key: "excessPct",
      label: `초과 수익률${versus}`,
      value: stats?.excessPct === null || stats?.excessPct === undefined ? EMPTY : `${formatSigned(stats.excessPct, 2)}%p`,
    },
    { key: "beta", label: `베타${versus}`, value: formatNumber(stats?.beta, 2) },
  ];
}

/**
 * 표본 설명 한 줄. 백엔드가 표본이 짧으면 누적 수익률만 내고 나머지를 null로 비우므로, 그때 왜 비었는지 적습니다
 * (기준 일수는 백엔드가 정함 — 화면이 같은 숫자를 따로 들고 있지 않게 null 모양으로만 판단).
 */
export function sampleNote(stats: PaperStats | null | undefined): string {
  if (!stats) return "통계 없음 — 순자산 기록이 아직 없습니다.";
  // sampleDays는 첫·마지막 순자산 기록 사이의 거래일 수입니다(기록이 빠진 날도 셈 — 연율화 기준).
  const base = `표본 ${formatNumber(stats.sampleDays, 0)}거래일`;
  if (stats.cumReturnPct !== null && stats.cagrPct === null) {
    return `${base} — 표본이 짧아 누적 수익률만 냅니다. 몇 달치로는 통계적 결론을 낼 수 없습니다.`;
  }
  if (stats.cagrPct !== null && stats.volPct === null) {
    return `${base} — 하루 간격 기록이 모자라 변동성·샤프·베타는 비웁니다(기록이 빠진 날의 수익률은 쓰지 않음).`;
  }
  return base;
}

/** 책 카드에 적을 값(전부 표시 문자열). */
export function bookCard(book: PaperBookSummary, books: readonly PaperBookSummary[]) {
  const stale = book.staleCount ?? null;
  return {
    status: bookStatusBadge(book.status),
    kind: bookKindLabel(book.kind),
    nav: formatDollars(book.nav),
    returnPct: formatPercent(book.returnPct),
    exposure: formatWithUnit(book.exposurePct, 1, "%"),
    cash: formatDollars(book.cash),
    initialCash: formatDollars(book.initialCash, 0),
    positions: book.positions === null || book.positions === undefined ? EMPTY : `${formatNumber(book.positions, 0)}종목`,
    startedOn: book.startedOn ?? "시작 전",
    lastObserved: formatKst(book.lastObservedAtKst),
    /** 가격을 새로 받지 못한 보유가 있으면 경고 문구, 없거나 모르면 null. */
    staleWarning: stale !== null && stale > 0 ? `가격 없음 ${formatNumber(stale, 0)}종목 — 지난 값으로 평가` : null,
    stats: statCells(book.stats, benchmarkLabel(book, books)),
    sample: sampleNote(book.stats),
  };
}

/** 차트 한 줄(계열)의 키·이름. 색은 화면이 정합니다(components/charts의 팔레트). */
export interface NavSeriesLine {
  key: string;
  name: string;
}

/**
 * 순자산 곡선의 행과 계열.
 *
 * - 계열 순서는 책 목록(서버가 정한 순서)을 따르고, 목록에 없는 책 코드는 뒤에 붙입니다.
 * - 그날 기록이 없는 책은 null 그대로 둡니다 — 차트가 선을 끊어 그립니다(0이나 앞 값으로 채우면 없는 수익률이 생김).
 * - 배열이 dates보다 짧으면 모자란 날도 null입니다.
 */
export function navChart(
  navSeries: PaperOverviewResponse["navSeries"],
  books: readonly PaperBookSummary[],
): { rows: Record<string, string | number | null>[]; series: NavSeriesLine[] } {
  if (!navSeries || !Array.isArray(navSeries.dates) || !navSeries.books) return { rows: [], series: [] };
  const known = books.map((book) => book.code).filter((code) => code in navSeries.books);
  const extra = Object.keys(navSeries.books).filter((code) => !known.includes(code));
  const codes = [...known, ...extra];

  const rows = navSeries.dates.map((date, index) => {
    const row: Record<string, string | number | null> = { date };
    for (const code of codes) {
      const value = navSeries.books[code]?.[index];
      row[code] = typeof value === "number" && Number.isFinite(value) ? value : null;
    }
    return row;
  });
  const series = codes.map((code) => ({ key: code, name: books.find((book) => book.code === code)?.label ?? code }));
  return { rows, series };
}

/**
 * 장부 관측치 한 줄 — 봉 날짜·티커 수·이어받은 값 수. 저장본이 없으면 그렇다고 적습니다.
 * tickers는 개수, staleTickers는 티커 이름 목록으로 옵니다(PaperQueryService.quotesSummary).
 */
export function quotesLine(quotes: PaperQuotesSummary | null | undefined): string {
  if (!quotes) {
    return "장부 관측치(paper.quotes) 저장본이 아직 없습니다 — 수집기의 paper_quotes 태스크가 한 번 돌면 생깁니다.";
  }
  return [
    `봉 날짜 ${quotes.barDate ?? EMPTY}(미국 동부)`,
    `티커 ${formatWithUnit(quotes.tickers, 0, "개")}`,
    `지난 값 이어받음 ${formatNumber(quotes.staleTickers.length, 0)}개`,
  ].join(" · ");
}

/**
 * JSON(주문 근거·작업 detail)을 한 줄로 — `키: 값 · 키: 값`. 비었거나 없으면 "—".
 * 길면 maxLength에서 자릅니다(전체는 jsonTitle로 마우스를 올려 봄).
 */
export function jsonSummary(value: unknown, maxLength = 160): string {
  const text = summarize(value);
  if (!text) return EMPTY;
  return text.length > maxLength ? `${text.slice(0, maxLength - 1)}…` : text;
}

function summarize(value: unknown): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (Array.isArray(value)) return value.map(summarize).filter(Boolean).join(", ");
  if (typeof value === "object") {
    return Object.entries(value as Record<string, unknown>)
      .map(([key, entry]) => {
        const shown = entry !== null && typeof entry === "object" && !Array.isArray(entry)
          ? JSON.stringify(entry)
          : summarize(entry);
        return `${key}: ${shown || EMPTY}`;
      })
      .join(" · ");
  }
  return "";
}

/**
 * 주문 근거 한 줄 — 서버가 사람이 읽을 문장(`reason.text`, 예: "2곳 동시 매수(순위 합 3): 1위 …, 2위 …")을 남겼으면 그 문장을
 * 자르지 않고 그대로, 없으면 {@link jsonSummary}. 규칙 책 근거는 `rule · signal · kind`가 앞에 오고 `text`가 뒤(SHY 주문은 아홉째)라,
 * 키 순서대로 이어 붙여 자르면 둘째 기관부터 잘리거나 문장이 아예 보이지 않습니다.
 */
export function orderReasonText(reason: unknown, maxLength = 120): string {
  if (isRecord(reason)) {
    const text = reason.text;
    if (typeof text === "string" && text.trim()) return text;
    const contributed = contributionReason(reason);
    if (contributed) return contributed;
  }
  return jsonSummary(reason, maxLength);
}

/**
 * 문장(text)이 없는 매수 근거를 점수 기여로 한 줄 — "점수 0.367: 듀케인(AB) 신규 편입 · 가중 36.7% × 확신도 100.0%, …".
 * 그룹 섞기(D27) 뒤 매수 근거는 `{score, contributions:[{cik, name, groups, filerWeight, conviction, points, action, weightPct}]}`라
 * 키: 값으로 이어 붙이면 기관 줄이 JSON 덩어리로 보입니다. 기여가 없으면 null(호출한 쪽이 키: 값 요약으로).
 */
function contributionReason(reason: Record<string, unknown>): string | null {
  const contributions = listOf(reason.contributions).filter(isRecord);
  if (contributions.length === 0) return null;
  const parts = contributions.map((entry) => {
    const name = textOf(entry.name) ?? `CIK ${textOf(entry.cik) ?? EMPTY}`;
    const groups = groupsOf(entry.groups).join("");
    const action = textOf(entry.action);
    return `${name}${groups ? `(${groups})` : ""}${action ? ` ${signalActionLabel(action)}` : ""} · 가중 `
      + `${formatRatioPercent(numberOf(entry.filerWeight))} × 확신도 ${formatRatioPercent(numberOf(entry.conviction))}`;
  });
  const score = numberOf(reason.score);
  return `${score === null ? "" : `점수 ${formatScore(score)}: `}${parts.join(", ")}`;
}

/** 마우스를 올리면 볼 JSON 전체. 없으면 undefined(title을 달지 않음). */
export function jsonTitle(value: unknown): string | undefined {
  if (value === null || value === undefined) return undefined;
  if (typeof value === "string") return value || undefined;
  try {
    return JSON.stringify(value);
  } catch {
    return undefined;
  }
}

/** 작업 결과 글자색 — 오류·실패만 경고색. 모르는 값은 본문색. */
export function jobOutcomeClass(outcome: string | null | undefined): string {
  return outcome && /error|fail|오류|실패/i.test(outcome) ? "text-warn" : "text-body";
}

const SIDE: Record<string, string> = { BUY: "매수", SELL: "매도" };
const ORDER_STATUS: Record<string, { label: string; tone: BadgeTone }> = {
  PROPOSED: { label: "제안", tone: "muted" },
  PENDING: { label: "대기", tone: "info" },
  FILLED: { label: "체결", tone: "ok" },
  EXPIRED: { label: "만료", tone: "muted" },
  CANCELLED: { label: "취소", tone: "muted" },
  REJECTED: { label: "거절", tone: "warn" },
};
const TIMING: Record<string, string> = {
  NEXT_OBSERVATION: "다음 관측치",
  NEXT_CLOSE: "다음 종가",
  IMMEDIATE: "즉시",
  BACKFILL: "사후 체결",
};
const SOURCE: Record<string, string> = {
  RULE: "규칙",
  CLAUDE: "Claude",
  STOP: "트레일링 스탑",
  BENCHMARK: "벤치마크",
  INITIAL: "초기 결정",
};

const labelOf = (table: Record<string, string>, value: string | null | undefined) =>
  value ? (table[value] ?? value) : EMPTY;

export const sideLabel = (side: string | null | undefined) => labelOf(SIDE, side);
export const timingLabel = (timing: string | null | undefined) => labelOf(TIMING, timing);
export const sourceLabel = (source: string | null | undefined) => labelOf(SOURCE, source);

/** 매수는 상승색, 매도는 하락색(한국 관행, format.deltaColor와 같음). */
export function sideClass(side: string | null | undefined): string {
  return side === "BUY" ? "text-up" : side === "SELL" ? "text-down" : "text-body";
}

export function orderStatusBadge(status: string | null | undefined): Badge {
  const known = status ? ORDER_STATUS[status] : undefined;
  return { key: status ?? "unknown", label: known?.label ?? status ?? EMPTY, className: TONE[known?.tone ?? "muted"] };
}

/**
 * 주문 크기 — 수량이 있으면 수량, 없으면 살 금액(매수) 또는 매도 비율. 매도에 셋 다 없으면 전량입니다
 * (백엔드 규칙: sellFraction이 null이면 전량). 매수에 셋 다 없으면 모름(—).
 *
 * 주문의 `targetWeightPct`는 이름과 달리 "체결 시점 순자산 대비 살 금액(%)"입니다(장부가 그렇게 읽음). 규칙 책은 이미 든 종목이면
 * 모자란 만큼(목표 − 현재), SHY면 늘릴 만큼만 넣고 진짜 목표 비중은 근거 `reason.targetWeightPct`에 남기므로, 근거의 목표가
 * 주문 값과 다르면 함께 적습니다.
 */
export function orderSizeText(
  order: Pick<PaperOrder, "side" | "qty" | "targetWeightPct" | "sellFraction"> & { reason?: unknown },
): string {
  if (order.qty !== null && order.qty !== undefined) return `${formatNumber(order.qty, 0)}주`;
  if (order.targetWeightPct !== null && order.targetWeightPct !== undefined) {
    const amount = formatNumber(order.targetWeightPct, 2);
    const target = isRecord(order.reason) ? numberOf(order.reason.targetWeightPct) : null;
    const targetText = target === null ? null : formatNumber(target, 2);
    return targetText === null || targetText === amount
      ? `순자산의 ${amount}% 매수`
      : `순자산의 ${amount}% 매수 (목표 ${targetText}%)`;
  }
  if (order.sellFraction !== null && order.sellFraction !== undefined) {
    return `보유의 ${formatNumber(order.sellFraction * 100, 0)}%`;
  }
  return order.side === "SELL" ? "전량" : EMPTY;
}

/** 보유 한 줄의 배지 — 가격 없음 · 사후 체결 · 분할 조정 · 현금성. */
export function positionBadges(position: PaperPosition): Badge[] {
  const badges: Badge[] = [];
  if (position.lastPrice === null || position.lastPrice === undefined) {
    badges.push({
      key: "noPrice", label: "가격 없음 · 평단 평가", className: TONE.warn,
      title: "시세를 한 번도 받지 못해 평단으로 평가했습니다. 트레일링 스탑 판정은 건너뜁니다.",
    });
  } else if (position.priceStale) {
    badges.push({
      key: "stale", label: "가격 없음 · 지난 값", className: TONE.warn,
      title: `이번 관측치에 시세가 없어 ${formatKst(position.lastPriceAtKst)}의 가격으로 평가했습니다. 트레일링 스탑 판정은 건너뜁니다.`,
    });
  }
  if (position.backfilled) {
    badges.push({ key: "backfill", label: "사후 체결", className: TONE.info, title: "출발일 종가로 나중에 기록한 체결입니다." });
  }
  if (position.splitNote) {
    badges.push({ key: "split", label: "분할 조정", className: TONE.info, title: position.splitNote });
  }
  if (position.cashLike) {
    badges.push({ key: "cashLike", label: "현금성", className: TONE.muted, title: "현금성 ETF — 트레일링 스탑을 적용하지 않습니다." });
  }
  return badges;
}

/** 체결 배지 — 사후 체결이면 하나. */
export function fillBadges(fill: { backfill: boolean }): Badge[] {
  return fill.backfill
    ? [{ key: "backfill", label: "사후 체결", className: TONE.info, title: "그날 종가로 나중에 기록한 체결입니다(관측 시각 = 그날 마감)." }]
    : [];
}

/** 스탑 칸 — 현금성은 적용 안 함, 값이 없으면 "—". */
export function stopText(position: Pick<PaperPosition, "cashLike" | "stopPrice" | "stopDistancePct">): string {
  if (position.cashLike) return "적용 안 함";
  if (position.stopPrice === null || position.stopPrice === undefined) return EMPTY;
  const distance = position.stopDistancePct === null || position.stopDistancePct === undefined
    ? ""
    : ` · 거리 ${formatWithUnit(position.stopDistancePct, 1, "%")}`;
  return `${formatSharePrice(position.stopPrice)}${distance}`;
}

/** 보유 기간 칸 — "2026-10-23부터 · 3거래일". 모르는 부분은 "—". */
export function heldText(position: Pick<PaperPosition, "openedOn" | "holdingTradingDays">): string {
  const since = position.openedOn ? `${position.openedOn}부터` : EMPTY;
  const days = position.holdingTradingDays === null || position.holdingTradingDays === undefined
    ? EMPTY
    : `${formatNumber(position.holdingTradingDays, 0)}거래일`;
  return `${since} · ${days}`;
}

/**
 * 일시정지·재개 버튼. 시작 전인 책은 null — 시작은 초기 결정이 합니다(규칙 책은 개요의 '규칙 책 시작', 백엔드도 거절).
 */
export function statusToggle(status: string | null | undefined): { next: "ACTIVE" | "PAUSED"; label: string } | null {
  if (status === "ACTIVE") return { next: "PAUSED", label: "일시정지" };
  if (status === "PAUSED") return { next: "ACTIVE", label: "재개" };
  return null;
}

/**
 * 시작 전인 책에 버튼 대신 적는 안내. 책 종류마다 시작하는 길이 다릅니다 — 벤치마크는 초기 결정이 아니라 출발일 종가의
 * 사후 체결로 시작하므로, 종가가 없어 시작 전에 남았다면 할 일은 "기다렸다가 초기화를 다시 누르기"입니다.
 */
export function notStartedNote(kind: string | null | undefined): string {
  if (kind === "BENCHMARK") {
    return "시작 전인 벤치마크 책은 출발일 종가로 사후 체결해 시작합니다 — 종가를 받은 뒤 초기화를 다시 누르세요"
      + "(개요 탭의 '초기화' 또는 아래 '책 초기화'). 지금은 현금만 듭니다.";
  }
  if (kind === "RULES") {
    return "시작 전인 규칙 책은 개요 탭의 '규칙 책 시작'(초기 결정)으로 시작합니다 — 출발일 기준 선정·신호로 고른 종목을 "
      + "출발일 종가에 사후 체결합니다. 지금은 현금만 듭니다.";
  }
  return "시작 전인 책은 다음 단계의 초기 결정으로 시작합니다 — 지금은 현금만 듭니다.";
}

/** 보유·체결 탭의 기본 책 — 고른 책이 목록에 있으면 그것, 아니면 처음으로 시작한 책, 그것도 없으면 첫 책. */
export function resolveBookCode(picked: string, books: readonly PaperBookSummary[]): string | null {
  if (books.some((book) => book.code === picked)) return picked;
  return books.find((book) => book.status !== "NOT_STARTED")?.code ?? books[0]?.code ?? null;
}

/** 책 선택 상자의 선택지. */
export function bookOptions(books: readonly PaperBookSummary[]) {
  return books.map((book) => ({ value: book.code, label: `${book.label} · ${bookStatusBadge(book.status).label}` }));
}

/**
 * 초기화 카드 — 보일 때만 제목·본문, 아니면 null.
 *
 * 초기화 전(`initialized:false`)뿐 아니라, 책 네 개는 있어도 벤치마크가 출발일 종가를 못 받아 시작 전으로 남았으면
 * (`pendingBackfill`) 카드를 계속 보입니다. init은 그때 오류가 아니라 200 + skipped를 돌려주고 책은 만들어 두므로,
 * `initialized`만 보면 다시 누를 버튼이 사라져 보유 탭의 책 초기화라는 우회로밖에 남지 않았습니다.
 */
export function initPrompt(
  overview: Pick<PaperOverviewResponse, "initialized" | "pendingBackfill" | "startDate" | "books">,
): { title: string; body: string } | null {
  const start = overview.startDate ?? EMPTY;
  if (overview.initialized === false) {
    return {
      title: "장부 초기화",
      body: `아직 장부가 없습니다. 초기화하면 책 네 개를 만들고, 두 벤치마크 책을 출발일(${start}) 종가로 사후 체결합니다. `
        + "규칙 책·Claude 책은 현금만 든 채 '시작 전'으로 둡니다 — 규칙 책은 초기화 뒤 '규칙 책 시작'으로, "
        + "Claude 책은 다음 단계의 초기 결정으로 시작합니다. "
        + "여러 번 눌러도 이미 있는 책은 다시 만들지 않습니다.",
    };
  }
  const pending = overview.pendingBackfill ?? [];
  if (pending.length === 0) return null;
  const books = overview.books ?? [];
  const names = pending.map((code) => books.find((book) => book.code === code)?.label ?? code).join(", ");
  return {
    title: "벤치마크 사후 체결 대기",
    body: `${names}: 출발일(${start}) 종가를 아직 받지 못해 시작하지 않았습니다(현금만). 종가를 받은 뒤 초기화를 다시 누르세요 — `
      + "이미 있는 책은 다시 만들지 않고, 시작 전인 벤치마크만 그 종가로 사후 체결합니다.",
  };
}

/** 초기화 결과 한 줄. */
export function initResultText(result: PaperInitResult): string {
  const created = result.created ?? [];
  const backfilled = result.backfilled ?? [];
  const parts = [created.length > 0 ? `만든 책 ${created.join(", ")}` : "새로 만든 책 없음(이미 있음)"];
  if (backfilled.length > 0) parts.push(`사후 체결 ${backfilled.join(", ")}`);
  for (const skip of result.skipped ?? []) parts.push(`${skip.code} 시작하지 않음 — ${skip.reason}`);
  return parts.join(" · ");
}

/** 리스크 작업 결과 한 줄. 건너뛴 이유가 있으면 덧붙입니다. */
export function riskOutcomeText(outcome: PaperRiskOutcome | null | undefined): string {
  if (!outcome) return "결과를 받지 못했습니다.";
  const books = outcome.booksProcessed ?? [];
  const text = `체결 ${outcome.filled} · 스탑 ${outcome.stopped} · 만료 ${outcome.expired} · 분할 ${outcome.splits}` +
    ` · 처리한 책 ${books.length > 0 ? books.join(", ") : "없음"}`;
  return outcome.skippedReason ? `${text} — ${outcome.skippedReason}` : text;
}

/** 리스크 작업 결과의 표시 — 배너 색·요약 줄·오류·메모. */
export interface RiskResultView {
  tone: "warn" | "info";
  /** ok:false일 때 맨 앞에 적을 경고 한 줄, 성공이면 null. */
  warning: string | null;
  summary: string;
  errors: string[];
  notes: string[];
}

/**
 * 리스크 작업 결과(POST run/risk)를 화면에 그릴 모양으로.
 *
 * 한 책이 오류로 되돌려져도 HTTP는 200이라 useAsyncAction은 성공으로 칩니다 — 그래서 응답의 ok를 따로 봐야 합니다.
 * ok:false면 경고색 + errors를, 성공이든 아니든 notes(현금 부족으로 줄인 매수·취소한 주문·분할)를 함께 보입니다.
 * 버리면 사용자는 "체결 0"만 보고 왜 그런지(또는 실패했는지) 알 수 없었습니다.
 */
export function riskResultView(result: PaperRiskResult): RiskResultView {
  const outcome = result.outcome ?? null;
  const errors = outcome?.errors ?? [];
  const failed = result.ok === false;
  return {
    tone: failed ? "warn" : "info",
    warning: !failed
      ? null
      : errors.length > 0
        ? "오류가 난 책이 있습니다 — 그 책은 이번 관측치 처리를 되돌렸습니다(장부는 그대로)."
        : "리스크 작업이 돌지 않았습니다.",
    // 테이블이 없을 때는 outcome 없이 서버 문구만 옵니다.
    summary: outcome ? riskOutcomeText(outcome) : (result.message ?? riskOutcomeText(null)),
    errors,
    notes: outcome?.notes ?? [],
  };
}

/** 일시정지·재개 결과 한 줄. */
export function statusResultText(result: PaperBookStatusResult): string {
  const cancelled = result.cancelledOrders;
  const tail = cancelled !== null && cancelled !== undefined && cancelled > 0 ? ` · 대기 주문 ${cancelled}건 취소` : "";
  return `${result.book.label}: ${bookStatusBadge(result.book.status).label}${tail}`;
}

/**
 * 책 초기화 결과 — 배너 색과 한 줄. 벤치마크를 다시 사후 체결하지 못했으면(종가 없음) 서버 사유를 경고로 붙입니다.
 * 사유를 버리면 "초기화했습니다 — 지금 시작 전"만 남아, 왜 벤치마크가 현금만 들고 있는지 알 수 없었습니다.
 */
export function resetResultView(result: PaperBookResetResult): { tone: "warn" | "info"; text: string } {
  const base = `${result.book.label}을(를) 초기화했습니다 — 지금 ${bookStatusBadge(result.book.status).label}`;
  if (result.skippedReason) {
    return { tone: "warn", text: `${base}. 출발일 종가로 다시 사후 체결하지 못했습니다 — ${result.skippedReason}` };
  }
  return { tone: "info", text: result.backfilled ? `${base} · 출발일 종가로 다시 사후 체결했습니다` : base };
}

// ---------------------------------------------------------------- 기관 선정(13F 트랙레코드)

const TRACK_METRIC: Record<string, string> = {
  CAGR: "연율 수익률(CAGR)",
  EXCESS_VS_SPY: "SPY 대비 초과 수익률",
  SHARPE: "샤프",
};

/** 순위 기준 이름. 모르는 값은 받은 글자 그대로. */
export const trackMetricLabel = (metric: string | null | undefined) => labelOf(TRACK_METRIC, metric);

const isNumber = (value: unknown): value is number => typeof value === "number" && Number.isFinite(value);
// 결정 기록·선정 기록은 JSON 그대로 오므로 모양을 믿지 않고 하나씩 확인해 읽습니다.
const isRecord = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === "object" && !Array.isArray(value);
const textOf = (value: unknown): string | null => (typeof value === "string" && value ? value : null);
const numberOf = (value: unknown): number | null => (isNumber(value) ? value : null);
const listOf = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);

/**
 * 0~1 비율을 %로 — 커버리지 표기. 백엔드는 커버리지를 자격 기준(0.5·0.7)과 같은 비율로 보내므로 표기할 때만 100을 곱합니다
 * (주문의 sellFraction 표기와 같음). 소수 1자리 — 0자리면 49.7%가 "50%"로 보여 기준(50%)을 넘은 것처럼 읽힙니다.
 */
export function formatCoverage(value: number | null | undefined): string {
  return isNumber(value) ? formatWithUnit(value * 100, 1, "%") : EMPTY;
}

/** SPY 대비 초과(%p). 수익률(%)끼리의 차이라 단위가 %p입니다. */
export function formatExcessPoints(value: number | null | undefined): string {
  return isNumber(value) ? `${formatSigned(value, 2)}%p` : EMPTY;
}

/** 자격 기준 한 줄 — "창 7년 · 구간 ≥ 24개 · 최소 커버리지 ≥ 50% · 평균 커버리지 ≥ 70%". 기준을 모르면 null. */
export function criteriaText(criteria: PaperTrackRecordCriteria | null | undefined): string | null {
  if (!criteria) return null;
  const threshold = (ratio: number) => (isNumber(ratio) ? formatWithUnit(ratio * 100, 0, "%") : EMPTY);
  return [
    `창 ${formatWithUnit(criteria.windowYears, 0, "년")}`,
    `구간 ≥ ${formatWithUnit(criteria.minPeriods, 0, "개")}`,
    `최소 커버리지 ≥ ${threshold(criteria.minCoverage)}`,
    `평균 커버리지 ≥ ${threshold(criteria.avgCoverage)}`,
  ].join(" · ");
}

/**
 * 자격 배지 — 자격 · 수집 중 · 자격 없음. 사유 문구는 서버가 보낸 그대로 배지 옆에 적습니다(화면이 사유를 짓지 않음).
 * 수집 중(30분기를 아직 다 받지 못함)은 기준 미달과 성격이 달라 — 기다리면 풀림 — 경고색이 아니라 흐린 색입니다.
 *
 * 그룹 B는 30분기 수집이 끝났는지와 상관없이 있는 저장본(q30, 없으면 q8)으로 재므로(D27), complete=false를 수집 중으로 보지 않고
 * 서버가 collecting을 참으로 보낼 때만 수집 중입니다 — 기존 메뉴 기관의 q8 저장본은 complete가 늘 거짓이라 전부 "수집 중"으로 보였을 것입니다.
 */
export function eligibilityBadge(
  candidate: Pick<PaperTrackRecordCandidate, "eligible" | "collecting" | "complete">,
  group: "A" | "B" = "A",
): Badge {
  if (candidate.eligible) {
    return { key: "eligible", label: "자격", className: TONE.ok, title: "자격 기준(구간 수·최소/평균 커버리지)을 모두 넘었습니다." };
  }
  if (candidate.collecting === true || (group === "A" && candidate.complete === false)) {
    return {
      key: "collecting", label: "수집 중", className: TONE.muted,
      title: "13F 분기 공시를 아직 다 받지 못해 자격 판정에서 뺐습니다 — 수집기가 다음 실행에 이어 받습니다.",
    };
  }
  return { key: "ineligible", label: "자격 없음", className: TONE.warn, title: "자격 기준에 못 미쳐 순위에서 뺐습니다." };
}

/** 선정 배지 — 그 기준일의 선정에 든 기관만. */
export function selectedBadge(candidate: Pick<PaperTrackRecordCandidate, "selected">): Badge | null {
  return candidate.selected
    ? { key: "selected", label: "선정", className: TONE.info, title: "이 기준일의 선정(상위 기관)에 든 기관 — 규칙 책이 이 기관들의 13F 신호를 씁니다." }
    : null;
}

/**
 * 후보 표의 행 순서 — 순위가 있는(자격 있는) 기관을 순위대로, 그 뒤에 순위가 없는 기관을 서버가 보낸 순서대로.
 * 순위는 서버가 매긴 값 그대로이고 화면은 다시 매기지 않습니다. 서버가 이미 이 순서로 보내면 바뀌는 것이 없습니다(안정 정렬).
 */
export function trackRecordRows(candidates: readonly PaperTrackRecordCandidate[] | null | undefined): PaperTrackRecordCandidate[] {
  const order = (candidate: PaperTrackRecordCandidate) => (isNumber(candidate.rank) ? candidate.rank : Number.POSITIVE_INFINITY);
  return [...(candidates ?? [])].sort((a, b) => {
    const left = order(a);
    const right = order(b);
    return left === right ? 0 : left < right ? -1 : 1;
  });
}

/** 구간 칸 — "수익률이 있는 구간 / 창 안 구간"(예: "26/27"). 둘 다 모르면 "—". */
export function periodsText(candidate: Pick<PaperTrackRecordCandidate, "periodsUsed" | "periodsTotal">): string {
  if (!isNumber(candidate.periodsUsed) && !isNumber(candidate.periodsTotal)) return EMPTY;
  return `${formatNumber(candidate.periodsUsed, 0)}/${formatNumber(candidate.periodsTotal, 0)}`;
}

/** 공시 기간 — "2019-11-14 ~ 2026-08-14". 모르는 쪽은 "—". */
export function filingSpanText(candidate: Pick<PaperTrackRecordCandidate, "firstFilingDate" | "lastFilingDate">): string {
  if (!candidate.firstFilingDate && !candidate.lastFilingDate) return EMPTY;
  return `${candidate.firstFilingDate ?? EMPTY} ~ ${candidate.lastFilingDate ?? EMPTY}`;
}

/**
 * 수집 칸 — "30/30 · 완료", "18/30 · 수집 중". 목표 분기 수를 모르면 "18분기 · 수집 중".
 * complete는 30분기를 다 받았거나 EDGAR에 더 오래된 원본 공시가 없다는 뜻이라, 30에 못 미쳐도 "완료"일 수 있습니다(신생 운용사).
 */
export function quartersText(
  candidate: Pick<PaperTrackRecordCandidate, "storedQuarters" | "complete">,
  targetQuarters: number | null,
): string {
  const stored = candidate.storedQuarters;
  const count = !isNumber(stored)
    ? EMPTY
    : targetQuarters === null
      ? `${formatNumber(stored, 0)}분기`
      : `${formatNumber(stored, 0)}/${formatNumber(targetQuarters, 0)}`;
  return `${count} · ${candidate.complete ? "완료" : "수집 중"}`;
}

/** 개수 — 정수면 그대로, 목록이면 길이(계약상 개수지만 목록으로 와도 "[object Object]"를 그리지 않게), 아니면 null. */
function countOf(value: unknown): number | null {
  if (isNumber(value)) return value;
  return Array.isArray(value) ? value.length : null;
}

/** 수집 진행률 카드의 표시. */
export interface CollectionView {
  /** 기관마다의 목표 분기 수(30). 모르면 null — 수집 칸이 "n분기"로만 적습니다. */
  targetQuarters: number | null;
  line: string;
}

/**
 * 수집 진행률 — "후보 27곳 중 수집 완료 20곳 · 저장 공시 700/810건 · 마지막 수집 …".
 *
 * 기관마다의 목표 분기 수는 서버가 보내면(targetQuarters) 그것을, 아니면 목표 공시 수 ÷ 기관 수가 나누어떨어질 때만 그 몫을
 * 씁니다 — 목표 공시 수가 기관 수 × 30으로 정의돼 있어서입니다. 나누어떨어지지 않으면 정의가 다르다는 뜻이라 짐작하지 않습니다(null).
 */
export function collectionView(collection: PaperTrackRecordCollection | null | undefined): CollectionView {
  if (!collection) {
    return {
      targetQuarters: null,
      line: "수집 진행률 저장본(sec.13f.track_record_index)이 아직 없습니다 — 수집기의 13F 트랙레코드 태스크가 한 번 돌면 생깁니다.",
    };
  }
  const filers = countOf(collection.filers);
  const sent = collection.targetQuarters;
  const derived = filers !== null && filers > 0 && isNumber(collection.targetFilings) && collection.targetFilings % filers === 0
    ? collection.targetFilings / filers
    : null;
  return {
    targetQuarters: isNumber(sent) ? sent : derived,
    line: [
      `후보 ${formatWithUnit(filers, 0, "곳")} 중 수집 완료 ${formatWithUnit(collection.completeCount, 0, "곳")}`,
      `저장 공시 ${formatNumber(collection.storedFilings, 0)}/${formatNumber(collection.targetFilings, 0)}건`,
      `마지막 수집 ${formatKst(collection.updatedAtKst)}`,
    ].join(" · "),
  };
}

/** 구간의 커버리지 칸 — "82.4% (티커 31개 · 보유 40줄)". 보유 줄에는 티커가 없는 줄도 듭니다. */
export function periodCoverageText(period: Pick<PaperTrackRecordPeriod, "coverage" | "coveredTickers" | "holdings">): string {
  return `${formatCoverage(period.coverage)} (티커 ${formatWithUnit(period.coveredTickers, 0, "개")} · 보유 ${formatWithUnit(period.holdings, 0, "줄")})`;
}

/** 선정 이력 한 줄 — 기준일과 선정 기관 이름(후보 목록에 없는 CIK는 CIK 그대로). */
export interface SelectionHistoryRow {
  asOfDate: string;
  names: string;
}

/**
 * 선정 이력 줄. 그룹 섞기(D27) 뒤의 선정은 신호 풀(A 순서 뒤에 B에만 있는 기관)이라 이름은 그룹 A·B 후보 목록 둘 다에서 찾습니다 —
 * 앞에 둔 목록(그룹 A)의 이름이 먼저입니다(두 그룹에 모두 있는 기관의 이름이 줄마다 바뀌지 않게).
 */
export function selectionHistoryRows(
  history: readonly PaperSelectionHistoryEntry[] | null | undefined,
  candidates: readonly PaperTrackRecordCandidate[] | null | undefined,
): SelectionHistoryRow[] {
  const names = new Map<string, string>();
  for (const candidate of candidates ?? []) {
    if (!names.has(candidate.cik)) names.set(candidate.cik, candidate.name);
  }
  return (history ?? []).map((entry) => {
    const ciks = entry.selectedCiks ?? [];
    return {
      asOfDate: entry.asOfDate,
      // 자격 있는 기관이 0곳이면 선정도 비어 있습니다 — 그때 규칙 책은 매수 없이 SHY만 듭니다(설계 §4-0 ⑤).
      names: ciks.length > 0 ? ciks.map((cik) => names.get(cik) ?? cik).join(", ") : "선정 없음 — 자격 있는 기관이 없었습니다",
    };
  });
}

/**
 * 수동 재계산 결과 — 배너 색과 한 줄. ok:false(테이블 없음 등)면 서버 문구를 경고로.
 * 서버가 계산하지 못한 이유(skippedReason)를 보내면 그 문구 그대로 — 원인(후보 목록 저장본 없음, 설정 후보가 수집 목록과
 * 겹치지 않음)마다 할 일이 달라 화면이 한 가지 안내로 뭉뚱그리지 않습니다(resetResultView와 같은 방식).
 * computed는 계산한 후보 수(정수)나 참·거짓으로 옵니다(타입 주석). 선정은 바꾸지 않는다는 것을 결과에도 적습니다 —
 * 순위가 바뀌어 보여도 규칙 책이 쓰는 선정은 그대로라는 것을 놓치기 쉽습니다.
 */
export function rerankResultView(result: PaperRerankResult): { tone: "warn" | "info"; text: string } {
  if (result.ok === false) return { tone: "warn", text: result.message ?? "다시 계산하지 못했습니다." };
  const asOf = result.asOfDate ?? EMPTY;
  if (result.skippedReason) {
    return { tone: "warn", text: `${asOf} 기준 — 계산하지 못했습니다: ${result.skippedReason}` };
  }
  if (result.computed === false || result.computed === 0) {
    return { tone: "warn", text: `${asOf} 기준 — 계산할 후보가 없었습니다. 수집 진행률을 보세요.` };
  }
  const count = isNumber(result.computed) ? ` · 후보 ${formatNumber(result.computed, 0)}곳` : "";
  const unchanged = result.selectedUnchanged === false
    ? ""
    : " — 선정은 바꾸지 않았습니다(선정은 재선정일·초기 선정 때 한 번만 남깁니다)";
  return { tone: "info", text: `${asOf} 기준으로 다시 계산했습니다${count}${unchanged}.` };
}

// ---------------------------------------------------------------- 두 기관 그룹·신호 풀(D27)

/** 창 이름 — "7년 창". 연수를 모르면 "창"(숫자를 지어내지 않음). */
export function windowLabel(years: number | null | undefined): string {
  return isNumber(years) ? `${formatNumber(years, 0)}년 창` : "창";
}

/**
 * 그룹 B 자격 기준. 서버가 기준을 통째로 보내면 그것을, 아니면 그룹 B의 창·구간 수에 그룹 A의 커버리지 기준을 붙입니다 —
 * 계약상 그룹 B의 커버리지 기준은 그룹 A(trackRecord.minCoverage·avgCoverage)와 같습니다. 창·구간 수를 모르면 null.
 */
export function groupBCriteria(
  groupB: Pick<PaperTrackRecordGroupB, "windowYears" | "minPeriods" | "criteria"> | null | undefined,
  criteriaA: PaperTrackRecordCriteria | null | undefined,
): PaperTrackRecordCriteria | null {
  if (!groupB) return null;
  if (groupB.criteria) return groupB.criteria;
  if (!isNumber(groupB.windowYears) || !isNumber(groupB.minPeriods) || !criteriaA) return null;
  return {
    windowYears: groupB.windowYears,
    minPeriods: groupB.minPeriods,
    minCoverage: criteriaA.minCoverage,
    avgCoverage: criteriaA.avgCoverage,
  };
}

/**
 * 그룹 B의 한계 한 줄. 창이 짧으면 구간이 몇 개뿐이라 한 국면을 크게 탄 기관이 순위를 차지합니다 — 7년 창의 그룹 A와 같은 무게로
 * 읽으면 안 된다는 것을 표 옆에 적습니다. 연수는 응답 값(설정 blend.groupB.windowYears)이고, 모르면 숫자 없이 적습니다.
 */
export function groupBLimit(windowYearsB: number | null | undefined, windowYearsA: number | null | undefined): string {
  const longer = isNumber(windowYearsA) ? `${windowLabel(windowYearsA)}의 그룹 A` : "그룹 A";
  return `그룹 B는 ${isNumber(windowYearsB) ? windowLabel(windowYearsB) : "짧은 창"} — 표본이 짧다. 분기 공시 구간이 몇 개뿐이라 `
    + `한 국면을 크게 탄 기관이 순위를 차지하기 쉽고, ${longer}보다 우연의 몫이 큽니다. 기존 13F 메뉴의 저장본(q8)이 8분기라 더 길게 재지 못합니다.`;
}

/**
 * 한계 카드의 줄 — 서버 문구 그대로, 그룹 B가 있으면 그룹 B 줄을 덧붙입니다. 서버가 이미 그룹 B 줄을 보내면(문구에 "그룹 B")
 * 덧붙이지 않습니다 — 같은 한계를 두 번 적지 않게.
 */
export function trackRecordLimits(
  limits: readonly string[] | null | undefined,
  groupB: Pick<PaperTrackRecordGroupB, "windowYears"> | null | undefined,
  windowYearsA: number | null | undefined,
): string[] {
  const lines = [...(limits ?? [])];
  if (groupB && !lines.some((line) => line.includes("그룹 B"))) lines.push(groupBLimit(groupB.windowYears, windowYearsA));
  return lines;
}

const TRACK_SOURCE: Record<string, string> = {
  q30: "후보 수집 저장본(sec.13f.{cik}.q30) — 같은 분기 JSON의 CUSIP 매핑 티커",
  q8: "기존 13F 메뉴 저장본(sec.13f.{cik}.q8, 8분기) — 이름·클래스 매핑 티커",
};

/** 그룹 B 출처 칸 — "q30 · 30분기", "q8 · 8분기". 저장 분기 수를 모르면 출처만, 출처를 모르면 "—". */
export function trackSourceText(
  candidate: Pick<PaperTrackRecordCandidate, "source" | "storedQuarters">,
): { label: string; title?: string } {
  const source = textOf(candidate.source);
  if (!source) return { label: EMPTY };
  const stored = isNumber(candidate.storedQuarters) ? ` · ${formatNumber(candidate.storedQuarters, 0)}분기` : "";
  return { label: `${source}${stored}`, title: TRACK_SOURCE[source] };
}

/** 그룹 이름 목록을 정리 — 글자만, 대문자, 중복 없이, A·B 먼저(그 밖의 값은 받은 순서대로 뒤에). */
export function groupsOf(value: unknown): string[] {
  const seen: string[] = [];
  for (const entry of listOf(value)) {
    const group = typeof entry === "string" ? entry.trim().toUpperCase() : "";
    if (group && !seen.includes(group)) seen.push(group);
  }
  const rank = (group: string) => (group === "A" ? 0 : group === "B" ? 1 : 2);
  return seen.sort((a, b) => rank(a) - rank(b));
}

const GROUP_TITLE: Record<string, { title: string; tone: BadgeTone }> = {
  A: { title: "그룹 A — 후보 기관 트랙레코드(긴 창) 상위", tone: "info" },
  B: { title: "그룹 B — 기존 13F 메뉴 기관의 최근(짧은 창) 상위", tone: "muted" },
  AB: { title: "두 그룹 모두 — 기관 가중을 두 그룹에서 다 받습니다", tone: "ok" },
};

/** 그룹 배지 — "A" · "B" · "AB"(두 그룹 모두). 그룹을 모르면 null. 모르는 그룹 이름은 받은 글자 그대로 붙입니다. */
export function groupBadge(groups: unknown): Badge | null {
  const list = groupsOf(groups);
  if (list.length === 0) return null;
  const label = list.join("");
  const known = GROUP_TITLE[label];
  return { key: `group-${label}`, label, className: TONE[known?.tone ?? "muted"], title: known?.title };
}

/** 그룹 순위 — "A 1위 · B 1위", "A 3위", "B 2위". 둘 다 모르면 "—". */
export function groupRankText(rankA: number | null | undefined, rankB: number | null | undefined): string {
  const parts = [
    isNumber(rankA) ? `A ${formatNumber(rankA, 0)}위` : null,
    isNumber(rankB) ? `B ${formatNumber(rankB, 0)}위` : null,
  ].filter((part): part is string => part !== null);
  return parts.length > 0 ? parts.join(" · ") : EMPTY;
}

/**
 * 0~1 비율을 %로(소수 1자리) — 기관 가중·확신도. 서버는 둘 다 비율로 보내고(합 1·상한 1) 표기할 때만 100을 곱합니다
 * (커버리지 표기와 같음). 모르면 "—".
 */
export function formatRatioPercent(value: number | null | undefined): string {
  return isNumber(value) ? formatWithUnit(value * 100, 1, "%") : EMPTY;
}

/** 후보 점수·점수 기여(Σ 기관 가중 × 확신도, 0~1) — 소수 3자리. 모르면 "—". */
export function formatScore(value: number | null | undefined): string {
  return formatNumber(numberOf(value), 3);
}

/** 신호 풀 한 줄(표시 문자열). */
export interface PoolRow {
  key: string;
  cik: string;
  name: string;
  group: Badge | null;
  rankA: string;
  rankB: string;
  /** 두 그룹 순위를 한 칸에 — "A 1위 · B 1위". */
  ranks: string;
  weight: string;
}

/**
 * 신호 풀(선정 행의 ranking.pool, 결정 기록의 selection.pool)을 표 줄로 — 서버 순서 그대로(A 순서 뒤에 B에만 있는 기관).
 * 가중은 서버가 낸 기관 가중(0~1) 그대로이고 화면은 다시 계산하지 않습니다. JSON이라 줄마다 모양을 확인하고, CIK가 없는 줄은 뺍니다.
 */
export function signalPoolRows(pool: unknown): PoolRow[] {
  return listOf(pool).filter(isRecord).flatMap((entry, index) => {
    const cik = textOf(entry.cik);
    if (!cik) return [];
    const rankA = numberOf(entry.rankA);
    const rankB = numberOf(entry.rankB);
    return [{
      key: `${index}-${cik}`,
      cik,
      name: textOf(entry.name) ?? `CIK ${cik}`,
      group: groupBadge(entry.groups),
      rankA: rankA === null ? EMPTY : `${formatNumber(rankA, 0)}위`,
      rankB: rankB === null ? EMPTY : `${formatNumber(rankB, 0)}위`,
      ranks: groupRankText(rankA, rankB),
      weight: formatRatioPercent(numberOf(entry.weight)),
    }];
  });
}

/**
 * 신호 풀 표 위의 한 줄 — 기관 가중을 어떻게 나눴는지. 그룹 A 몫 g를 서버가 보내면 숫자로(50% · 50%), 아니면 g로 적습니다.
 */
export function poolWeightNote(groupWeightA: number | null | undefined): string {
  const share = isNumber(groupWeightA)
    ? `그룹 A ${formatRatioPercent(groupWeightA)} · 그룹 B ${formatRatioPercent(1 - groupWeightA)}`
    : "그룹 A 몫 g · 그룹 B 몫 1 − g";
  return `신호 풀 = 그룹 A ∪ 그룹 B(겹치는 기관은 한 번). 기관 가중 = ${share}을 각 그룹 안의 순위 가중(1위가 가장 큼, 그룹 안 합 1)으로 `
    + "나눈 값이고, 두 그룹에 모두 든 기관은 두 몫을 다 받습니다. 한 그룹이 비면 다른 그룹이 전부 받습니다(풀 합 100%).";
}

// ---------------------------------------------------------------- 규칙 책 시작(초기 결정)

/** 개요의 "규칙 책 시작" 카드. */
export interface RulesStartPrompt {
  /** 초기 결정을 보낼 책 코드(본문 `{book}`). */
  bookCode: string;
  title: string;
  body: string;
  /** 확인 단계에서 보일 문구. */
  confirm: string;
}

/**
 * 규칙 책 시작 카드 — 규칙 책이 시작 전(NOT_STARTED)일 때만, 아니면 null.
 *
 * 시작은 되돌리기 어려운 동작이라(되돌리려면 책 초기화로 장부를 지워야 함) 카드에 무엇을 하는지 적고, 버튼은 확인 단계를 한 번 거칩니다.
 * 규칙은 두 기관 그룹을 섞는 방식(D27 — 그룹 A·B 선정, 기관 가중 × 확신도 점수, 확신도 비례 배분)이라 그 순서대로 적습니다.
 * 기준 숫자(7년·5곳·2년·4곳·2곳·+30%·10%·10종목·1%)는 설정의 기본값이라 "기본값"이라고 적습니다 — 지금 설정값은 설정 탭(단계 3a)이 보입니다.
 * 트랙레코드 수집이 끝났는지는 화면이 판단하지 않습니다 — 서버가 "수집 중"(400)으로 거절하고, 화면은 그 문구를 그대로 보입니다.
 */
export function rulesStartPrompt(
  overview: Pick<PaperOverviewResponse, "initialized" | "startDate" | "books">,
): RulesStartPrompt | null {
  if (overview.initialized === false) return null;
  const rules = (overview.books ?? []).find((book) => book.kind === "RULES");
  if (!rules || rules.status !== "NOT_STARTED") return null;
  const start = overview.startDate ?? EMPTY;
  return {
    bookCode: rules.code,
    title: `${rules.label} 시작`,
    body: `${rules.label}은 아직 현금만 들고 있습니다. 시작하면 출발일(${start}) 기준으로(그날 이전 공시·가격만) 신호 기관 두 그룹을 `
      + "뽑습니다 — 그룹 A는 후보 기관의 7년 트랙레코드 상위 5곳, 그룹 B는 기존 '기관 13F 포트폴리오 분석' 메뉴 기관의 최근 2년 상위 "
      + "4곳입니다. 두 그룹을 합친 신호 풀의 최신 분기 13F에서 2곳 이상이 신규 편입하거나 주식 수를 30% 이상 늘린 종목마다 점수"
      + "(기관 가중 × 확신도 — 그 종목이 그 기관 13F에서 차지하는 비중, 10%에서 상한)를 매기고, 점수 순 상위 10종목을 확신도 비례 "
      + "비중(가장 높은 점수가 종목 상한 10%, 목표 1% 미만은 뺌)으로 출발일 종가에 사후 체결합니다. 남는 현금은 SHY에 둡니다. "
      + "숫자는 모두 설정의 기본값입니다. 매크로 게이트는 쓰지 않습니다(참고 지표로 기록만).",
    confirm: `정말 시작할까요? 출발일(${start}) 종가로 사후 체결하고 책을 '운용 중'으로 바꿉니다. 되돌리려면 보유·체결 탭의 `
      + "'책 초기화'로 이 책의 장부를 모두 지워야 합니다. 후보 기관의 13F 수집이 끝나지 않았으면 서버가 거절합니다.",
  };
}

/**
 * 초기 결정("시작") 결과 — 배너 색과 한 줄. ok:false면 서버 문구를 경고로.
 * 주문이 하나도 없으면(NO_TRADE) 시작은 했지만 현금만 든다는 것을 경고색으로 적습니다 — 정보색이면 "종목을 샀다"로 읽힙니다.
 */
export function initialDecisionResultView(result: PaperInitialDecisionResult): { tone: "warn" | "info"; text: string } {
  if (result.ok === false) return { tone: "warn", text: result.message ?? "규칙 책을 시작하지 못했습니다." };
  const decision = result.decision ?? null;
  const noTrade = decision?.status === "NO_TRADE";
  const parts = [
    `${decision?.asOfTradingDate ?? EMPTY} ${decisionKindLabel(decision?.kind ?? "INITIAL")}`,
    `주문 ${formatWithUnit(decision?.orderCount, 0, "건")}`,
    `사후 체결 ${formatWithUnit(countOf(result.fills), 0, "건")}`,
  ];
  const head = noTrade ? "규칙 책을 시작했지만 주문이 없습니다 — 현금만 듭니다" : "규칙 책을 시작했습니다";
  const summary = decision?.summary ? ` — ${decision.summary}` : "";
  return { tone: noTrade ? "warn" : "info", text: `${head} · ${parts.join(" · ")}${summary}` };
}

// ---------------------------------------------------------------- 결정 로그

const DECISION_KIND: Record<string, string> = {
  SCHEDULED: "정기",
  MANUAL: "수동",
  REBALANCE: "재선정일",
  INITIAL: "초기 결정",
};

/** 결정 종류 이름. 모르는 값은 받은 글자 그대로. */
export const decisionKindLabel = (kind: string | null | undefined) => labelOf(DECISION_KIND, kind);

const DECISION_STATUS: Record<string, { label: string; tone: BadgeTone }> = {
  OK: { label: "주문 냄", tone: "ok" },
  NO_TRADE: { label: "주문 없음", tone: "muted" },
  REFUSED: { label: "모델 거절", tone: "warn" },
  FORMAT_ERROR: { label: "응답 형식 오류", tone: "warn" },
  API_ERROR: { label: "API 오류", tone: "warn" },
  SKIPPED: { label: "건너뜀", tone: "muted" },
};

/** 결정 상태 배지. 모르는 상태는 받은 글자 그대로(지어내지 않음). */
export function decisionStatusBadge(status: string | null | undefined): Badge {
  const known = status ? DECISION_STATUS[status] : undefined;
  return { key: status ?? "unknown", label: known?.label ?? status ?? EMPTY, className: TONE[known?.tone ?? "muted"] };
}

/** 주문 칸 — "3건", 거절이 있으면 "3건 · 거절 1". 모르면 "—". */
export function decisionOrdersText(decision: Pick<PaperDecisionSummary, "orderCount" | "rejectedCount">): string {
  if (!isNumber(decision.orderCount)) return EMPTY;
  const rejected = isNumber(decision.rejectedCount) && decision.rejectedCount > 0
    ? ` · 거절 ${formatNumber(decision.rejectedCount, 0)}`
    : "";
  return `${formatNumber(decision.orderCount, 0)}건${rejected}`;
}

/** 가격 칸 — 확정 종가로 냈는지. false는 경고색(마감 확정 전 값으로 낸 결정), 모르면 "—". */
export function pricesFinalText(value: boolean | null | undefined): { label: string; className: string } {
  if (value === true) return { label: "확정 종가", className: "text-body" };
  if (value === false) return { label: "확정 전 값", className: "text-warn" };
  return { label: EMPTY, className: "text-muted" };
}

/** 결정을 내는 책(규칙·Claude) — 벤치마크는 결정을 내지 않습니다. 순서는 서버가 준 책 순서. */
export function decisionBooks(books: readonly PaperBookSummary[]): PaperBookSummary[] {
  return books.filter((book) => book.kind === "RULES" || book.kind === "CLAUDE");
}

/** 결정 로그 탭의 기본 책 — 고른 책이 목록에 있으면 그것, 아니면 규칙 책, 그것도 없으면 첫 책. 결정을 내는 책이 없으면 null. */
export function resolveDecisionBook(picked: string, books: readonly PaperBookSummary[]): string | null {
  const choices = decisionBooks(books);
  if (choices.some((book) => book.code === picked)) return picked;
  return choices.find((book) => book.kind === "RULES")?.code ?? choices[0]?.code ?? null;
}

/** 책마다 결정이 언제·무엇을 하는지 한 문단. */
export function decisionBookNote(kind: string | null | undefined): string {
  if (kind === "CLAUDE") {
    return "Claude 책의 결정(입력 패킷·모델 응답·검증 결과·목표 주식 노출)은 다음 단계(3a)에서 엽니다 — 지금은 기록이 없습니다.";
  }
  return "규칙 책은 거래일마다 한 번(KST 07:30 이후, 확정 종가를 확인한 뒤) 결정을 남깁니다. 매수는 재선정일(분기 13F 공시 기한 "
    + "다음 거래일)과 초기 결정 때만 내고(재선정일 결정을 놓쳤거나 그날 선정을 건너뛰었으면 다음 결정 날에 그 재선정일 기준 선정으로 "
    + "한 번 따라잡습니다), 그 밖의 날은 보유 종목의 매도 신호(신호 풀 기관 여러 곳의 전량 매도)와 남는 현금만 봅니다(남는 현금이 "
    + "순자산의 기준(기본 2%, 설정 rules.parkCashThresholdPct)을 넘으면 SHY에 둠 — 신호 매수가 아닌 현금 관리). "
    + "신호는 선정 기준일까지 공시된 13F만 씁니다. 매크로 게이트는 쓰지 않고 참고로 기록만 합니다. "
    + "'지금 결정 실행'은 시각 조건 없이 이 책만 한 번 결정하고(종류 '수동' — 매도 신호와 남는 현금만), 확정 종가가 아니면 '확정 전 값'으로 남깁니다.";
}

/**
 * 수동 결정 버튼을 막는 이유 — 시작 전인 책이면 문구, 아니면 null. 시작 전 책은 장부가 체결하지 않으므로(현금만) 결정을 내도
 * 주문이 쌓이기만 합니다. 서버도 다시 판단하고, 그 문구는 버튼 결과로 그대로 보입니다.
 */
export function decisionRunBlocked(book: Pick<PaperBookSummary, "status" | "kind"> | null | undefined): string | null {
  if (!book) return "책이 없습니다.";
  if (book.status !== "NOT_STARTED") return null;
  return book.kind === "RULES"
    ? "시작 전인 책은 결정을 내지 않습니다 — 개요 탭의 '규칙 책 시작'으로 먼저 시작하세요."
    : "시작 전인 책은 결정을 내지 않습니다 — 다음 단계(3a)의 초기 결정으로 시작합니다.";
}

/** 수동 결정 결과 — 배너 색과 한 줄. ok:false면 서버 문구를, 결정에 오류가 있으면 경고색으로 오류를 붙입니다. */
export function runDecisionResultView(result: PaperRunDecisionResult): { tone: "warn" | "info"; text: string } {
  if (result.ok === false) return { tone: "warn", text: result.message ?? "결정을 내지 못했습니다." };
  const decision = result.decision ?? null;
  if (!decision) return { tone: "warn", text: result.message ?? "결정 기록을 받지 못했습니다." };
  const text = `${decision.asOfTradingDate} ${decisionKindLabel(decision.kind)} 결정 — ${decisionStatusBadge(decision.status).label}`
    + ` · 주문 ${decisionOrdersText(decision)}${decision.summary ? ` · ${decision.summary}` : ""}`;
  return decision.error ? { tone: "warn", text: `${text} — 오류: ${decision.error}` } : { tone: "info", text };
}

/** 결정 상세 머리의 한 칸. */
export interface DecisionMetaCell {
  key: string;
  label: string;
  value: string;
}

/**
 * 결정 상세 머리 — 결정 시각·선정 기준일·설정 버전·가격, 그리고 있을 때만 붙는 값: 소요(규칙 책도 늘 채움 — 결정 계산 시간)와
 * Claude 책만 채우는 값(모델·프레임·목표 노출·재시도·패킷 해시·메모). 규칙 책 기록에 늘 빈 칸("—") 여섯 개가 늘어서면 무엇이
 * 빠졌는지 오히려 읽기 어렵습니다.
 */
export function decisionMeta(decision: PaperDecisionDetail): DecisionMetaCell[] {
  const cells: DecisionMetaCell[] = [
    { key: "decidedAt", label: "결정 시각", value: formatKst(decision.decidedAtKst) },
    { key: "selectionAsOf", label: "선정 기준일", value: decision.selectionAsOf ?? EMPTY },
    { key: "settingsVersion", label: "설정 버전", value: isNumber(decision.settingsVersion) ? `v${decision.settingsVersion}` : EMPTY },
    { key: "pricesFinal", label: "가격", value: pricesFinalText(decision.pricesFinal).label },
  ];
  if (decision.model) cells.push({ key: "model", label: "모델", value: decision.model });
  if (isNumber(decision.frameworkVersion)) {
    cells.push({ key: "frameworkVersion", label: "프레임 버전", value: `v${decision.frameworkVersion}` });
  }
  if (isNumber(decision.targetExposurePct)) {
    cells.push({ key: "targetExposurePct", label: "목표 주식 노출", value: formatWithUnit(decision.targetExposurePct, 1, "%") });
  }
  if (isNumber(decision.latencyMs)) cells.push({ key: "latencyMs", label: "소요", value: formatLatency(decision.latencyMs) });
  if (isNumber(decision.retryCount) && decision.retryCount > 0) {
    cells.push({ key: "retryCount", label: "재시도", value: `${formatNumber(decision.retryCount, 0)}회` });
  }
  if (decision.packetHash) {
    const hash = decision.packetHash;
    cells.push({ key: "packetHash", label: "패킷 해시", value: hash.length > 12 ? `${hash.slice(0, 12)}…` : hash });
  }
  if (Array.isArray(decision.noteIds) && decision.noteIds.length > 0) {
    cells.push({ key: "noteIds", label: "참조 메모", value: decision.noteIds.map((id) => `#${id}`).join(", ") });
  }
  return cells;
}

/**
 * 매수 후보 표 위의 한 줄 — 그 결정이 매수하는 날이었는지. 규칙 책은 재선정일·초기 결정에만 사고(명세 R5) 그 밖의 날은
 * 후보를 기록만 하므로, 후보가 있는데 주문이 없는 정기 결정이 "놓친 매수"로 읽히지 않게 적습니다.
 */
export function candidatesNote(kind: string | null | undefined): string {
  return kind === "REBALANCE" || kind === "INITIAL"
    ? "매수하는 결정입니다 — 후보 상위부터 배분했고, 고르지 못한 후보는 아래에 사유를 남겼습니다."
    : "매수하지 않는 날의 결정입니다(매수는 재선정일·초기 결정에만) — 후보는 참고로 남깁니다.";
}

const SIGNAL_ACTION: Record<string, string> = { NEW: "신규 편입", ADDED: "확대", CLOSED: "전량 매도" };

/** 13F 신호 행동 이름. 모르는 값은 받은 글자 그대로. */
export const signalActionLabel = (action: string | null | undefined) => labelOf(SIGNAL_ACTION, action);

/** 선정 순위표 한 줄(표시 문자열). */
export interface RankingRow {
  cik: string;
  name: string;
  rank: string;
  cagr: string;
  excess: string;
  sharpe: string;
  selected: boolean;
}

/** 신호 이벤트 한 줄(표시 문자열). */
export interface SignalEventRow {
  key: string;
  filer: string;
  ticker: string;
  action: string;
  /** NEW · ADDED · CLOSED — 글자색을 고를 때. */
  actionKey: string;
  sharesChange: string;
  weight: string;
  previousWeight: string;
}

/** 매수 후보 한 줄(표시 문자열). 섞은 뒤 기록은 score, 섞기 전 기록은 rankSum이 채워집니다(없는 쪽은 "—"). */
export interface SignalCandidateRow {
  ticker: string;
  buyers: string;
  buyerCount: string;
  score: string;
  rankSum: string;
  combinedWeight: string;
}

/** 후보 점수에 기관 한 곳이 보탠 몫 한 줄(표시 문자열) — 점수 기여 = 기관 가중 × 확신도. */
export interface ContributionRow {
  key: string;
  ticker: string;
  filer: string;
  group: Badge | null;
  filerWeight: string;
  conviction: string;
  points: string;
}

/** 매도 신호(전량 매도한 신호 기관) 한 줄. */
export interface ClosedSignalRow {
  ticker: string;
  filers: string;
  count: string;
}

/** 고르지 못한 후보 한 줄. */
export interface SkippedRow {
  key: string;
  ticker: string;
  reason: string;
}

/**
 * 결정 기록의 선정 — 섞기 전 기록은 순위표(ranking), 섞은 뒤 기록은 신호 풀(pool)·그룹별 순위표(rankingA·rankingB, 선정 때의 상위 10)·
 * 그룹별 선정 이름.
 */
export interface RuleDecisionSelection {
  asOf: string;
  ranking: RankingRow[];
  rankingA: RankingRow[];
  rankingB: RankingRow[];
  /** 신호를 낸 기관(섞은 뒤면 신호 풀) 이름표. */
  selectedNames: string;
  pool: PoolRow[];
  /** 그룹 A·B 선정 이름표("듀케인 · 1위, …"). 섞기 전 기록이면 null, 그룹이 비었으면 그렇다고. */
  groupANames: string | null;
  groupBNames: string | null;
  /** 기관 가중의 그룹 A 몫(0~1, 선정 때 값). 기록에 없으면 null. */
  groupWeightA: number | null;
}

/** 규칙 책 결정 기록(response)을 화면에 그릴 모양으로. */
export interface RuleDecisionView {
  /** 규칙 책 기록 모양(gate·selection·signals 중 하나라도 있음)인가. 아니면 화면은 원본 JSON만 보입니다. */
  recognized: boolean;
  gate: PaperGateIndicator | null;
  selection: RuleDecisionSelection | null;
  events: SignalEventRow[];
  candidates: SignalCandidateRow[];
  /** 후보가 점수(섞은 뒤 기록)로 정렬됐는가 — 화면은 점수 칸과 기여 표를, 아니면 순위 합 칸을 그립니다. */
  scored: boolean;
  contributions: ContributionRow[];
  /** 배분 방식 한 줄(확신도 비례·동일 비중). 기록에 없으면 null — 그 줄을 그리지 않습니다. */
  allocation: string | null;
  /** 확신도 설명 한 줄(상한을 기록에서 읽으면 숫자로). 점수 기록이 아니면 null. */
  convictionNote: string | null;
  closed: ClosedSignalRow[];
  skipped: SkippedRow[];
  notes: string[];
}

/** 결정 기록 안에서 CIK로 찾는 기관 정보 — 섞기 전 순위표(rank)와 섞은 뒤 그룹·신호 풀(rankA·rankB·groups)을 합친 것. */
interface FilerInfo {
  name: string | null;
  rank: number | null;
  rankA: number | null;
  rankB: number | null;
  groups: string[];
}

/** 이벤트·기여 줄이 스스로 가진 순위(있으면 기록의 순위표보다 먼저). */
interface OwnRanks {
  rank?: number | null;
  rankA?: number | null;
  rankB?: number | null;
}

/**
 * 기관 이름표 — 섞은 뒤 기록은 "듀케인 · A 1위 · B 1위", 섞기 전 기록은 "버크셔 해서웨이 · 1위". 이름을 모르면 줄이 가진 이름,
 * 그것도 없으면 CIK(지어내지 않음), 순위를 모르면 이름만.
 */
function filerLabel(cik: string, own: OwnRanks | null, names: Map<string, FilerInfo>, fallbackName: string | null = null): string {
  const known = names.get(cik);
  const name = known?.name ?? fallbackName ?? `CIK ${cik}`;
  const rankA = own?.rankA ?? known?.rankA ?? null;
  const rankB = own?.rankB ?? known?.rankB ?? null;
  if (rankA !== null || rankB !== null) return `${name} · ${groupRankText(rankA, rankB)}`;
  const rank = own?.rank ?? known?.rank ?? null;
  return rank === null ? name : `${name} · ${formatNumber(rank, 0)}위`;
}

/**
 * 결정 기록의 기관 정보 표 — 섞기 전 순위표(ranking)·그룹 A·그룹 B·신호 풀을 CIK로 합칩니다. 이름은 먼저 본 값을 씁니다
 * (두 그룹에 모두 있는 기관의 이름이 표마다 달라지지 않게). 그룹은 신호 풀이 적은 값, 없으면 그룹 목록에 든 것으로.
 */
function filerInfo(selection: Record<string, unknown> | null): Map<string, FilerInfo> {
  const names = new Map<string, FilerInfo>();
  const merge = (entry: Record<string, unknown>, patch: Partial<FilerInfo>) => {
    const cik = textOf(entry.cik);
    if (!cik) return;
    const current = names.get(cik) ?? { name: null, rank: null, rankA: null, rankB: null, groups: [] };
    names.set(cik, {
      name: current.name ?? textOf(entry.name),
      rank: current.rank ?? patch.rank ?? null,
      rankA: current.rankA ?? patch.rankA ?? null,
      rankB: current.rankB ?? patch.rankB ?? null,
      groups: groupsOf([...current.groups, ...(patch.groups ?? [])]),
    });
  };
  for (const entry of listOf(selection?.ranking).filter(isRecord)) merge(entry, { rank: numberOf(entry.rank) });
  const fromPool = (entry: Record<string, unknown>) =>
    merge(entry, { rankA: numberOf(entry.rankA), rankB: numberOf(entry.rankB), groups: groupsOf(entry.groups) });
  listOf(selection?.pool).filter(isRecord).forEach(fromPool);
  // selection.filers는 신호를 대조한 기관마다의 줄 — 섞기 전 기록은 rank, 섞은 뒤 기록은 그룹·그룹 순위를 씁니다.
  for (const entry of listOf(selection?.filers).filter(isRecord)) {
    merge(entry, { rank: numberOf(entry.rank), rankA: numberOf(entry.rankA), rankB: numberOf(entry.rankB), groups: groupsOf(entry.groups) });
  }
  // 그룹 순위표에서는 선정된 줄만 그룹·순위를 보탭니다 — 그룹 A에서 뽑히고 그룹 B 순위표 6위에만 있는 기관이 "AB"·"B 6위"가 되지 않게.
  for (const entry of selectedEntries(selection?.groupA)) merge(entry, { rankA: numberOf(entry.rank), groups: ["A"] });
  for (const entry of selectedEntries(selection?.groupB)) merge(entry, { rankB: numberOf(entry.rank), groups: ["B"] });
  return names;
}

/**
 * 그룹 목록에서 선정된 줄만. 결정 기록의 groupA·groupB는 선정 때의 순위표(상위 10, 줄마다 selected)이고 개요·계약 초안의 모양은
 * 선정된 기관만이라, selected 표시가 하나라도 있으면 참인 줄만, 없으면 모두를 선정으로 봅니다.
 */
function selectedEntries(value: unknown): Record<string, unknown>[] {
  const entries = listOf(value).filter(isRecord);
  return entries.some((entry) => typeof entry.selected === "boolean") ? entries.filter((entry) => entry.selected === true) : entries;
}

/** 줄이 스스로 가진 순위 — 섞기 전 rank, 섞은 뒤 rankA·rankB. 숫자가 아니면 없는 것으로. */
const ownRanks = (entry: Record<string, unknown>): OwnRanks => ({
  rank: numberOf(entry.rank),
  rankA: numberOf(entry.rankA),
  rankB: numberOf(entry.rankB),
});

/**
 * 고르지 못한 후보와 사유. 서버가 `{ticker, reason}`으로 보내든, 자바 Map.Entry를 그대로 직렬화한 `{"티커": "사유"}`나
 * `{key, value}`로 보내든 같은 줄로 읽습니다 — 계약에 직렬화 모양이 정해져 있지 않아, 모양이 달라 표가 비면 사유가 사라집니다.
 */
function skippedRows(value: unknown): SkippedRow[] {
  const rows: Omit<SkippedRow, "key">[] = [];
  for (const entry of listOf(value)) {
    if (typeof entry === "string") {
      rows.push({ ticker: EMPTY, reason: entry });
    } else if (isRecord(entry)) {
      if (typeof entry.ticker === "string") {
        rows.push({ ticker: entry.ticker, reason: textOf(entry.reason) ?? EMPTY });
      } else if (typeof entry.key === "string" && "value" in entry) {
        rows.push({ ticker: entry.key, reason: textOf(entry.value) ?? EMPTY });
      } else {
        for (const [ticker, reason] of Object.entries(entry)) rows.push({ ticker, reason: textOf(reason) ?? EMPTY });
      }
    }
  }
  return rows.map((row, index) => ({ ...row, key: `${index}-${row.ticker}` }));
}

/** 그 결정에 쓴 설정값 하나 — `settings.키`, 점 이름(`settings["blend.키"]`), 묶음(`settings.blend.키`) 어느 모양이든. */
function settingOf(settings: Record<string, unknown> | null, key: string): unknown {
  if (!settings) return undefined;
  if (key in settings) return settings[key];
  if (`blend.${key}` in settings) return settings[`blend.${key}`];
  return isRecord(settings.blend) ? settings.blend[key] : undefined;
}

/**
 * 배분 방식 한 줄(설계 §4-2, 설정 blend.allocation). 그 결정에 쓴 설정값(settings)에서 읽고, 없으면 기록 맨 위·신호 묶음의 allocation을
 * 봅니다(문자열이든 `{mode}`든). 기록에 없으면 null — 섞기 전 기록은 늘 동일 비중이었지만, 없는 값을 화면이 채우지 않습니다.
 * 하한(blend.minTargetPct)을 기록에서 읽으면 숫자로 적습니다.
 */
function allocationText(record: Record<string, unknown>, settings: Record<string, unknown> | null): string | null {
  const signals = isRecord(record.signals) ? record.signals : null;
  const raw = settingOf(settings, "allocation") ?? record.allocation ?? signals?.allocation;
  const mode = textOf(isRecord(raw) ? raw.mode : raw);
  if (!mode) return null;
  const min = numberOf(settingOf(settings, "minTargetPct"));
  const floor = min === null ? "하한" : `${formatNumber(min, 1)}%`;
  if (mode === "CONVICTION") {
    return `배분: 확신도 비례(CONVICTION) — 목표 비중 = 종목 상한 × 점수 ÷ 최고 점수(가장 강한 후보가 상한), 목표가 ${floor} `
      + "미만이면 '확신도 낮음'으로 뺍니다.";
  }
  if (mode === "EQUAL") return "배분: 동일 비중(EQUAL) — 목표 비중 = min(100% ÷ 최대 종목 수, 종목 상한).";
  return `배분: ${mode}`;
}

/** 그룹 선정 이름표 — "듀케인 · 1위, 아팔루사 · 2위"(선정된 줄만). 그룹 목록이 없으면(섞기 전 기록) null, 비었으면 그렇다고. */
function groupNames(value: unknown, names: Map<string, FilerInfo>): string | null {
  if (!Array.isArray(value)) return null;
  const rows = selectedEntries(value).flatMap((entry) => {
    const cik = textOf(entry.cik);
    if (!cik) return [];
    const name = names.get(cik)?.name ?? textOf(entry.name) ?? `CIK ${cik}`;
    const rank = numberOf(entry.rank);
    return [rank === null ? name : `${name} · ${formatNumber(rank, 0)}위`];
  });
  return rows.length > 0 ? rows.join(", ") : "자격 있는 기관 없음";
}

/**
 * 규칙 책 결정 기록(paper_decisions.response, JSON)을 표(선정·신호 이벤트·매수 후보·점수 기여·매도 신호)와 건너뛴 후보·메모로.
 *
 * 숫자는 서버가 계산한 값을 표기만 합니다(비중·변화율·점수·기관 가중·확신도·순위 합). 기관 이름·그룹·순위는 같은 기록의
 * 선정(섞은 뒤면 그룹 A·B와 신호 풀, 섞기 전이면 순위표)에서 CIK로 찾아 붙이고, 없는 CIK는 CIK 그대로 둡니다(지어내지 않음).
 * 결정 기록은 바뀌지 않는 이력이라 그룹 섞기(D27) 전·후 모양을 다 읽습니다. JSON이라 모양을 믿지 않고 하나씩 확인해 읽습니다 —
 * 모양이 다른 칸은 "—".
 */
export function ruleDecisionView(response: unknown): RuleDecisionView {
  const record = isRecord(response) ? response : {};
  const selection = isRecord(record.selection) ? record.selection : null;
  const signals = isRecord(record.signals) ? record.signals : null;
  const settings = isRecord(record.settings) ? record.settings : null;
  const names = filerInfo(selection);

  const poolRows = signalPoolRows(selection?.pool);
  const ciks = listOf(selection?.ciks).filter((cik): cik is string => typeof cik === "string");
  // 신호를 낸 기관 — 서버의 ciks(섞은 뒤면 신호 풀과 같음), 없으면 신호 풀 순서.
  const selectedCiks = ciks.length > 0 ? ciks : poolRows.map((row) => row.cik);
  // 순위표 줄 — 섞기 전 순위표는 신호 기관 목록(ciks)으로도 선정을 표시하고, 그룹 순위표는 줄의 selected만 봅니다
  // (두 그룹에 모두 든 기관이 그룹 B 순위표에서 선정되지 않았는데 신호 풀에 있다고 "선정"이 붙지 않게).
  const rankingRows = (value: unknown, byCiks: boolean): RankingRow[] => listOf(value).filter(isRecord).flatMap((entry) => {
    const cik = textOf(entry.cik);
    if (!cik) return [];
    const rank = numberOf(entry.rank);
    return [{
      cik,
      name: textOf(entry.name) ?? `CIK ${cik}`,
      rank: rank === null ? EMPTY : formatNumber(rank, 0),
      cagr: formatPercent(numberOf(entry.cagrPct)),
      excess: formatExcessPoints(numberOf(entry.excessVsSpyPct)),
      sharpe: formatNumber(numberOf(entry.sharpe), 2),
      selected: entry.selected === true || (byCiks && selectedCiks.includes(cik)),
    }];
  });
  const ranking = rankingRows(selection?.ranking, true);

  const events: SignalEventRow[] = listOf(signals?.events).filter(isRecord).flatMap((event, index) => {
    const cik = textOf(event.cik);
    const ticker = textOf(event.ticker);
    if (!cik || !ticker) return [];
    const action = textOf(event.action);
    return [{
      key: `${index}-${cik}-${ticker}`,
      filer: filerLabel(cik, ownRanks(event), names, textOf(event.name)),
      ticker,
      action: signalActionLabel(action),
      actionKey: action ?? "",
      sharesChange: formatPercent(numberOf(event.sharesChangePct)),
      weight: formatWithUnit(numberOf(event.weightPct), 2, "%"),
      previousWeight: formatWithUnit(numberOf(event.previousWeightPct), 2, "%"),
    }];
  });

  const candidateEntries = listOf(signals?.candidates).filter(isRecord).filter((candidate) => textOf(candidate.ticker) !== null);
  const candidates: SignalCandidateRow[] = candidateEntries.map((candidate) => {
    const buyers = listOf(candidate.buyerCiks).filter((cik): cik is string => typeof cik === "string");
    return {
      ticker: textOf(candidate.ticker) ?? EMPTY,
      buyers: buyers.length > 0 ? buyers.map((cik) => filerLabel(cik, null, names)).join(", ") : EMPTY,
      buyerCount: `${formatNumber(buyers.length, 0)}곳`,
      score: formatScore(numberOf(candidate.score)),
      rankSum: formatNumber(numberOf(candidate.rankSum), 0),
      combinedWeight: formatWithUnit(numberOf(candidate.combinedWeightPct), 2, "%"),
    };
  });
  const scored = candidateEntries.some((candidate) => isNumber(candidate.score) || Array.isArray(candidate.contributions));

  // 기여 줄은 후보 순서(서버 정렬) 그대로, 후보 안에서는 서버가 보낸 순서 그대로입니다.
  const contributions: ContributionRow[] = candidateEntries.flatMap((candidate, candidateIndex) => {
    const ticker = textOf(candidate.ticker) ?? EMPTY;
    return listOf(candidate.contributions).filter(isRecord).flatMap((entry, index) => {
      const cik = textOf(entry.cik);
      if (!cik) return [];
      const groups = groupsOf(entry.groups);
      return [{
        key: `${candidateIndex}-${index}-${ticker}-${cik}`,
        ticker,
        filer: filerLabel(cik, ownRanks(entry), names, textOf(entry.name)),
        group: groupBadge(groups.length > 0 ? groups : names.get(cik)?.groups),
        filerWeight: formatRatioPercent(numberOf(entry.filerWeight)),
        conviction: formatRatioPercent(numberOf(entry.conviction)),
        points: formatScore(numberOf(entry.points)),
      }];
    });
  });

  const closedBy = isRecord(signals?.closedBy) ? signals.closedBy : {};
  const closed: ClosedSignalRow[] = Object.entries(closedBy).map(([ticker, value]) => {
    const filers = listOf(value).filter((cik): cik is string => typeof cik === "string");
    return {
      ticker,
      filers: filers.length > 0 ? filers.map((cik) => filerLabel(cik, null, names)).join(", ") : EMPTY,
      count: `${formatNumber(filers.length, 0)}곳`,
    };
  });

  const cap = numberOf(settingOf(settings, "convictionCapPct"));
  const capText = cap === null ? "상한" : `${formatNumber(cap, cap % 1 === 0 ? 0 : 1)}%`;
  const selectionAsOf = textOf(selection?.asOf) ?? textOf(selection?.asOfDate);
  return {
    recognized: isRecord(record.gate) || selection !== null || signals !== null,
    gate: isRecord(record.gate) ? (record.gate as unknown as PaperGateIndicator) : null,
    selection: selection
      ? {
        asOf: selectionAsOf ?? EMPTY,
        ranking,
        selectedNames: selectedCiks.length > 0
          ? selectedCiks.map((cik) => filerLabel(cik, null, names)).join(", ")
          : "선정 없음 — 자격 있는 기관이 없었습니다",
        rankingA: rankingRows(selection.groupA, false),
        rankingB: rankingRows(selection.groupB, false),
        pool: poolRows,
        groupANames: groupNames(selection.groupA, names),
        // 섞기 전 선정(legacy)으로 낸 결정은 그룹 B가 아예 없었습니다 — "자격 있는 기관 없음"과 다릅니다.
        groupBNames: selection.legacy === true ? "없음 — 두 그룹을 섞기 전의 선정입니다" : groupNames(selection.groupB, names),
        groupWeightA: numberOf(selection.groupWeightA),
      }
      : null,
    events,
    candidates,
    scored,
    contributions,
    allocation: allocationText(record, settings),
    convictionNote: scored
      ? `점수 = Σ 매수 기관의 기관 가중 × 확신도. 확신도 = min(그 종목의 기관 내 비중, ${capText}) ÷ ${capText} — `
        + "그 기관 13F에서 크게 든 종목일수록 1에 가깝습니다."
      : null,
    closed,
    skipped: skippedRows(record.skipped),
    notes: listOf(record.notes).filter((note): note is string => typeof note === "string" && note.length > 0),
  };
}

/** JSON 원본을 들여 써서(펼쳐 보기용). 없으면 null — 그 칸을 그리지 않습니다. */
export function prettyJson(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------- OS 게이트 지표(참고)

const GATE_LEVEL: Record<string, { label: string; tone: BadgeTone }> = {
  L0: { label: "L0 · 동결", tone: "muted" },
  L1: { label: "L1 · 1단계 해제", tone: "info" },
  L2: { label: "L2 · 전면 해제", tone: "ok" },
  UNKNOWN: { label: "판정 없음 · 입력 빠짐", tone: "warn" },
};

/** 게이트 단계 배지. 모르는 단계는 받은 글자 그대로. */
export function gateLevelBadge(level: string | null | undefined): Badge {
  const known = level ? GATE_LEVEL[level] : undefined;
  return { key: level ?? "unknown", label: known?.label ?? level ?? EMPTY, className: TONE[known?.tone ?? "muted"] };
}

const GATE_INPUTS = [
  { key: "us10y", label: "미국 10년물 금리", unit: "%" },
  { key: "brent", label: "브렌트유", unit: " 달러" },
  { key: "wti", label: "WTI", unit: " 달러" },
  { key: "usdjpy", label: "USD/JPY", unit: " 엔" },
] as const;

const GATE_CONDITIONS = ["G1", "G2", "G3", "G4", "G5"] as const;

/** 임계치 표기 — 정수면 소수 없이(95 달러), 아니면 2자리(4.80%). 10년물은 늘 2자리(OS 원문 표기 4.80·4.75). */
function threshold(value: number | null | undefined, unit: string, alwaysTwo = false): string | null {
  if (!isNumber(value)) return null;
  return `${formatNumber(value, alwaysTwo || !Number.isInteger(value) ? 2 : 0)}${unit}`;
}

/** 조건 이름. 임계치를 받으면 숫자를, 아니면 기준 이름만 적습니다(화면이 설정값을 따로 들고 있지 않게). */
function gateConditionLabel(key: string, t: PaperGateThresholds | null | undefined): string {
  switch (key) {
    case "G1": return `10년물 < ${threshold(t?.us10yStage1, "%", true) ?? "1단계 기준"}`;
    case "G2": return `10년물 ≤ ${threshold(t?.us10yFull, "%", true) ?? "전면 기준"}`;
    case "G3": return `브렌트 ≤ ${threshold(t?.brentMax, " 달러") ?? "상한"}`;
    case "G4": return `WTI ≤ ${threshold(t?.wtiMax, " 달러") ?? "상한"}`;
    case "G5": return `USD/JPY ≥ ${threshold(t?.usdjpyMin, " 엔") ?? "기준"}`;
    default: return key;
  }
}

/** 게이트 조건 한 줄. */
export interface GateConditionRow {
  key: string;
  label: string;
  state: string;
  stateClass: string;
  streak: string;
  /** 단계에 쓰지 않는 참고 조건(G5)인가. */
  reference: boolean;
}

/** 게이트 입력 한 줄. */
export interface GateInputRow {
  key: string;
  label: string;
  value: string;
  lastTs: string;
  stale: boolean;
}

/** 게이트 지표 카드의 표시. */
export interface GateView {
  tradingDate: string;
  level: Badge;
  levelNote: string;
  conditions: GateConditionRow[];
  inputs: GateInputRow[];
  note: string;
}

/**
 * 게이트 지표(참고)를 카드 모양으로. 기록이 없으면 null.
 *
 * 단계와 조건 참·거짓·연속 일수는 서버(GateIndicator)가 낸 값 그대로입니다 — 화면은 다시 판정하지 않습니다.
 * 입력이 없는 조건은 "—"(거짓으로 보지 않음), 입력이 하나라도 없으면 단계는 "판정 없음"(L0로 보지 않음).
 */
export function gateView(gate: PaperGateIndicator | null | undefined): GateView | null {
  if (!gate) return null;
  const t = gate.thresholds ?? null;
  const days = isNumber(t?.confirmDays) ? `확인 일수(${formatNumber(t.confirmDays, 0)}거래일)` : "확인 일수";
  const missingLabels = listOf(gate.missing)
    .filter((key): key is string => typeof key === "string")
    .map((key) => GATE_INPUTS.find((input) => input.key === key)?.label ?? key);
  const levelNote: Record<string, string> = {
    L0: `해제 조건이 ${days}만큼 이어지지 않았습니다 — 동결 단계.`,
    L1: `10년물 1단계 조건(G1)이 ${days} 이상 이어졌습니다.`,
    L2: `10년물·브렌트·WTI 세 조건(G2·G3·G4)이 모두 ${days} 이상 이어졌습니다.`,
    UNKNOWN: `입력이 빠져 단계를 판정하지 않았습니다(L0로 보지 않음)${missingLabels.length > 0 ? ` — 없는 입력: ${missingLabels.join(", ")}` : ""}.`,
  };

  // 결정 기록의 게이트는 JSON 그대로라 모양을 믿지 않습니다 — 객체가 아니면 빈 것으로(조건·연속은 "—").
  const conditions: Record<string, unknown> = isRecord(gate.conditions) ? gate.conditions : {};
  const streaks: Record<string, unknown> = isRecord(gate.streaks) ? gate.streaks : {};
  const keys = [...GATE_CONDITIONS, ...Object.keys(conditions).filter((key) => !(GATE_CONDITIONS as readonly string[]).includes(key))];
  const conditionRows: GateConditionRow[] = keys.map((key) => {
    const holds = conditions[key];
    const streak = streaks[key];
    const reference = key === "G5";
    return {
      key,
      label: `${key} ${gateConditionLabel(key, t)}${reference ? " (참고 — 단계에 쓰지 않음)" : ""}`,
      state: holds === true ? "충족" : holds === false ? "미충족" : EMPTY,
      stateClass: holds === true ? "text-ok" : "text-muted",
      streak: isNumber(streak) ? `${formatNumber(streak, 0)}거래일 연속` : EMPTY,
      reference,
    };
  });

  const inputs: Record<string, unknown> = isRecord(gate.inputs) ? gate.inputs : {};
  const inputRows: GateInputRow[] = GATE_INPUTS.map(({ key, label, unit }) => {
    const raw = inputs[key];
    const input = isRecord(raw) ? raw : null;
    const value = isNumber(raw) ? raw : numberOf(input?.value);
    return {
      key,
      label,
      value: formatWithUnit(value, 2, unit),
      lastTs: formatKst(textOf(input?.lastTs)),
      stale: input?.isStale === true,
    };
  });

  return {
    tradingDate: textOf(gate.tradingDate) ?? EMPTY,
    level: gateLevelBadge(gate.level),
    levelNote: (typeof gate.level === "string" && Object.hasOwn(levelNote, gate.level)) ? levelNote[gate.level] : "",
    conditions: conditionRows,
    inputs: inputRows,
    // 화면이 뒤에 문장을 잇기 때문에 서버 문구 끝의 마침표는 뗍니다("…않음.." 방지).
    note: (textOf(gate.note) ?? "과거 OS가 쓴 트리거 수준 — 구속하지 않음").replace(/[.。]\s*$/, ""),
  };
}

// ---------------------------------------------------------------- 개요의 선정 기관

/** 개요의 선정 기관 한 줄. */
export interface SelectedFilerRow {
  key: string;
  rank: string;
  name: string;
  cik: string;
}

/** 개요의 선정 카드 — 기준일, 두 그룹(그룹 안 순위 순), 신호 풀(풀 순서). */
export interface SelectedFilersView {
  asOfDate: string;
  groupA: SelectedFilerRow[];
  groupB: SelectedFilerRow[];
  pool: PoolRow[];
}

const filerRows = (filers: readonly PaperSelectedFiler[]): SelectedFilerRow[] =>
  filers.map((filer, index) => ({
    key: `${index}-${filer.cik}`,
    rank: isNumber(filer.rank) ? `${formatNumber(filer.rank, 0)}위` : EMPTY,
    name: filer.name ?? `CIK ${filer.cik}`,
    cik: filer.cik,
  }));

/**
 * 가장 최근 선정 — 기준일, 그룹 A·B 줄(서버 순서 그대로), 신호 풀 줄. 선정이 없으면 null.
 *
 * 개요의 풀 줄에는 순위가 없어(`{cik, name, groups, weight}`) 같은 응답의 그룹 A·B 목록에서 CIK로 순위를 찾아 붙입니다 —
 * 순위를 매기지 않고 찾기만 합니다. 그룹 섞기(D27) 전 모양(`selected`만)이 오면 그것을 그룹 A로 읽고 풀은 비웁니다.
 */
export function selectedFilersView(selection: PaperOverviewSelection | null | undefined): SelectedFilersView | null {
  if (!selection) return null;
  const groupA = selection.groupA ?? selection.selected ?? [];
  const groupB = selection.groupB ?? [];
  const rankIn = (filers: readonly PaperSelectedFiler[], cik: string) => filers.find((filer) => filer.cik === cik)?.rank ?? null;
  return {
    asOfDate: selection.asOfDate ?? EMPTY,
    groupA: filerRows(groupA),
    groupB: filerRows(groupB),
    pool: signalPoolRows((selection.pool ?? []).map((entry) => ({
      ...entry,
      rankA: rankIn(groupA, entry.cik),
      rankB: rankIn(groupB, entry.cik),
    }))),
  };
}
