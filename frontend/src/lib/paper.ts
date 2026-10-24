/**
 * 🧪 모의 알고리즘 매매 화면의 표기 — React를 모르는 순수 함수(ADR 0004).
 *
 * 숫자는 전부 백엔드가 계산해 보냅니다(순자산·수익률·통계·스탑 가격·비중). 여기서는 받은 값을 고르고,
 * 이름을 붙이고, 차트 행으로 늘어놓기만 합니다 — 화면에서 다시 계산하면 장부와 화면이 다른 숫자를 말하게 됩니다.
 * 모르는 값(null)은 "—"로 둡니다. 0으로 채우지 않습니다.
 */
import { EMPTY, formatKst, formatNumber, formatPercent, formatSigned, formatWithUnit } from "./format";
import type {
  PaperBookResetResult,
  PaperBookStatusResult,
  PaperBookSummary,
  PaperInitResult,
  PaperOrder,
  PaperOverviewResponse,
  PaperPosition,
  PaperQuotesSummary,
  PaperRiskOutcome,
  PaperRiskResult,
  PaperStats,
} from "./types";

/**
 * 이 화면 숫자의 출처. 다른 화면은 lib/sources.ts에 모으지만, 모의 매매는 로컬 전용 기능이라 이 파일에 둡니다
 * (sources.ts로 옮길지는 단계 4 마무리에서 정함).
 */
export const PAPER_SOURCES = {
  ledger: "대시보드 모의 장부 (PostgreSQL paper_*) — 체결가는 Yahoo Finance(yfinance) 지연 시세, 사후 체결은 그날 종가",
  quotes: "Yahoo Finance (yfinance) — 모의 매매 유니버스 일봉(장중 마지막 봉 = 지연 시세, 저장본 paper.quotes)",
  jobs: "대시보드 작업 기록 (PostgreSQL paper_job_runs)",
} as const;

/**
 * 탭 여섯(명세 U2). opensIn이 있는 탭은 아직 내용이 없고 "다음 단계에서 엽니다"만 보입니다.
 * 탭을 아예 숨기지 않는 이유 — 무엇이 올지 보여야 지금 화면이 전부가 아니라는 것을 압니다.
 */
export const PAPER_TABS = [
  { key: "overview", label: "개요", opensIn: null, upcoming: null },
  {
    key: "selection", label: "기관 선정", opensIn: "단계 1b(13F 트랙레코드)",
    upcoming: "후보 기관의 7년 트랙레코드 표(순위·CAGR·초과·변동성·MDD·샤프·구간 수·커버리지·자격·선정 여부), 선정 이력, 수집 진행률",
  },
  { key: "holdings", label: "보유·체결", opensIn: null, upcoming: null },
  {
    key: "decisions", label: "결정 로그", opensIn: "단계 2(규칙 책)·3a(Claude 책)",
    upcoming: "책마다 하루 한 번의 결정 기록 — 근거가 된 신호·입력 패킷·응답·검증 결과·목표 노출 비율",
  },
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
 * 주문 크기 — 수량이 있으면 수량, 없으면 목표 비중(매수) 또는 매도 비율. 매도에 셋 다 없으면 전량입니다
 * (백엔드 규칙: sellFraction이 null이면 전량). 매수에 셋 다 없으면 모름(—).
 */
export function orderSizeText(order: Pick<PaperOrder, "side" | "qty" | "targetWeightPct" | "sellFraction">): string {
  if (order.qty !== null && order.qty !== undefined) return `${formatNumber(order.qty, 0)}주`;
  if (order.targetWeightPct !== null && order.targetWeightPct !== undefined) {
    return `목표 비중 ${formatNumber(order.targetWeightPct, 2)}%`;
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
 * 일시정지·재개 버튼. 시작 전인 책은 null — 시작은 다음 단계의 초기 결정이 합니다(백엔드도 거절).
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
        + "규칙 책·Claude 책은 현금만 든 채 '시작 전'으로 두고 다음 단계의 초기 결정으로 시작합니다. "
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
