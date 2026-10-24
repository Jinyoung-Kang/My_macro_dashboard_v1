/**
 * 백엔드 응답 계약 — 🧪 모의 알고리즘 매매(`/api/paper/*`, 운영자 전용·로컬 전용).
 *
 * 값이 없을 수 있는 필드는 전부 `| null`입니다. 프런트는 그 null을 그대로 "—"로 표시하고,
 * 절대 0으로 바꾸지 않습니다. 금액은 백엔드가 소수 2자리로 반올림해 보내고, 수익률·통계도 백엔드가
 * 계산합니다 — 화면은 고르고 이름 붙여 그리기만 합니다(ADR 0004). 가져올 때는 `@/lib/types`(배럴)를 씁니다.
 *
 * 테이블이 아직 없으면(수집기가 V8 스키마를 적용하기 전) 모든 응답이 `{available: false, message}`뿐입니다.
 * 그래서 `available` 밖의 필드는 선택(`?`)입니다.
 */

/** 책 종류 — 규칙 책 · Claude 재량 책 · 벤치마크(SPY 100%, SPY 60%·SHY 40%). */
export type PaperBookKind = "RULES" | "CLAUDE" | "BENCHMARK";

/** 책 상태. NOT_STARTED는 현금만 든 책(규칙·Claude 책은 다음 단계의 초기 결정으로 시작). */
export type PaperBookStatus = "NOT_STARTED" | "ACTIVE" | "PAUSED";

/** 책 통계(PaperStats). 표본(일별 수익률 수)이 짧으면 cumReturnPct만 있고 나머지는 null입니다. */
export interface PaperStats {
  cumReturnPct: number | null;
  cagrPct: number | null;
  volPct: number | null;
  /** 최대 낙폭 — 음수(%). */
  mddPct: number | null;
  sharpe: number | null;
  /** benchmark 책 대비 초과 수익률(%p). 비교 기준이 없으면 null. */
  excessPct: number | null;
  beta: number | null;
  sampleDays: number;
}

/** 책 하나의 요약(BookSummary). 개요·보유 응답과 버튼 응답이 같은 모양을 씁니다. */
export interface PaperBookSummary {
  /** rules · claude · bench_spy · bench_mix */
  code: string;
  kind: PaperBookKind;
  label: string;
  status: PaperBookStatus;
  /** 첫 체결 거래일(미국 동부 세션 날짜). 시작 전이면 null. */
  startedOn: string | null;
  initialCash: number | null;
  cash: number | null;
  nav: number | null;
  equityValue: number | null;
  exposurePct: number | null;
  returnPct: number | null;
  lastObservedAtKst: string | null;
  /** 보유 종목 수. */
  positions: number | null;
  /** 가격을 새로 받지 못한 보유 수. */
  staleCount: number | null;
  stats: PaperStats | null;
  /** 통계 비교 기준 책 코드(rules·claude·bench_mix → bench_spy). bench_spy 자신은 null. */
  benchmark: string | null;
}

/**
 * 장부 관측치(paper.quotes 저장본) 요약 — PaperQueryService.quotesSummary. 저장본이 아직 없으면 이 객체 대신 `quotes: null`입니다.
 * 저장본이 있으면 barDate 밖의 필드는 늘 옵니다(barDate는 쓸 수 있는 봉이 하나도 없을 때만 null).
 */
export interface PaperQuotesSummary {
  collectedAtKst: string;
  /** 수집 뒤 지난 초(0 이상). */
  ageSeconds: number;
  /** 관측치의 최빈 봉 날짜(미국 동부). 장중이면 그날, 장 마감 전후 판단의 근거. */
  barDate: string | null;
  /** 그 봉이 확정 종가인가(봉 날짜 마감 + 15분 뒤에 수집). false면 장중·마감 직후의 지연 시세입니다. */
  finalClose: boolean;
  /** 관측치에 든 티커 수(개수). */
  tickers: number;
  /** 이번 수집에 실패해 직전 저장본의 값을 이어 쓴 티커 이름(목록, 없으면 빈 배열). */
  staleTickers: string[];
  /** 저장본이 늦었는가(수집 3시간 넘게 지남 — 1시간 주기를 두 번 연속 놓침). 신선도 배지를 경고색으로 그립니다. */
  stale: boolean;
}

/** 정기·수동 작업 한 건(paper_job_runs). detail은 JSON 그대로입니다. */
export interface PaperJobRun {
  job: string;
  startedAtKst: string | null;
  finishedAtKst: string | null;
  outcome: string;
  detail: unknown;
}

/** GET /api/paper/overview */
export interface PaperOverviewResponse {
  available: boolean;
  message?: string;
  initialized?: boolean;
  /** 네 책의 출발(사후 체결) 거래일 — 설정 dashboard.paper.start-date. */
  startDate?: string | null;
  books?: PaperBookSummary[];
  /** 거래일별 순자산. books[code]는 dates와 같은 길이, 그날 기록이 없는 책은 null(곡선을 끊어 그림). */
  navSeries?: { dates: string[]; books: Record<string, (number | null)[]> } | null;
  quotes?: PaperQuotesSummary | null;
  /** 명세 §6 "화면에 반드시 적을 한계" 여덟 문장(순서대로). */
  limits?: string[];
  /** 최근 작업 기록(10건). */
  jobs?: PaperJobRun[];
  /**
   * 출발일 종가를 아직 받지 못해 사후 체결하지 못한(시작 전으로 남은) 벤치마크 책 코드. 비어 있지 않으면 화면은 초기화 카드를
   * 계속 보입니다 — `initialized`는 책 네 개가 있기만 하면 true라, 그것만 보면 다시 누를 버튼이 사라집니다.
   * 이 필드를 아직 보내지 않는 백엔드에서는 빈 목록으로 봅니다.
   */
  pendingBackfill?: string[];
}

/** GET /api/paper/status */
export interface PaperStatusResponse {
  available: boolean;
  message?: string;
  schemaReady?: boolean;
  initialized?: boolean;
  /** dashboard.paper.enabled — false면 정기 작업(리스크·결정)이 일하지 않습니다. 테이블이 없을 때도 옵니다. */
  enabled?: boolean;
  /** 네 책의 출발(사후 체결) 거래일 — 설정 dashboard.paper.start-date. */
  startDate?: string;
  /** 책마다의 처음 현금(달러) — 설정 dashboard.paper.initial-cash. */
  initialCash?: number | null;
  quotes?: PaperQuotesSummary | null;
  /** 최근 작업 기록(30건). */
  jobs?: PaperJobRun[];
  /** 설정 행(paper_settings)의 버전. 행이 없으면(초기화 전) null. */
  settingsVersion?: number | null;
  /** 설정 행에서 키가 없거나 값이 틀려 기본값으로 대신 읽은 키(예: "slippageBp", "gate.brentMax"). 비어 있으면 모두 설정대로. */
  settingsFallbacks?: string[];
}

/**
 * 보유 한 줄. 가격을 한 번도 받지 못했으면 lastPrice가 null이고 평가액은 백엔드가 평단으로 낸 값입니다.
 * 주당 가격(avgCost·lastPrice·highWatermark·stopPrice)은 소수 4자리로 옵니다 — 5bp 슬리피지가 싼 종목에서도 보이게.
 */
export interface PaperPosition {
  ticker: string;
  qty: number;
  avgCost: number | null;
  lastPrice: number | null;
  lastPriceAtKst: string | null;
  marketValue: number | null;
  weightPct: number | null;
  unrealizedPct: number | null;
  highWatermark: number | null;
  stopPrice: number | null;
  stopDistancePct: number | null;
  openedOn: string | null;
  holdingTradingDays: number | null;
  priceStale: boolean;
  splitNote: string | null;
  /** 현금성 ETF(SHY 등) — 트레일링 스탑을 적용하지 않습니다. */
  cashLike: boolean;
  /** 사후 체결(출발일 종가)로 연 보유인가 — 보유를 연 시각이 그 종목의 사후 매수 체결 시각과 같으면 true. */
  backfilled: boolean;
}

/** 재진입 금지(트레일링 스탑 뒤 N거래일). */
export interface PaperLockout {
  ticker: string;
  untilTradingDate: string;
  reason: string;
}

/** GET /api/paper/books/{code}/positions */
export interface PaperPositionsResponse {
  available: boolean;
  message?: string;
  book?: PaperBookSummary;
  positions?: PaperPosition[];
  lockouts?: PaperLockout[];
  cash?: number | null;
}

export type PaperOrderStatus = "PROPOSED" | "REJECTED" | "PENDING" | "FILLED" | "EXPIRED" | "CANCELLED";

/** 주문 한 줄. qty·targetWeightPct·sellFraction 중 하나로 크기를 정합니다(없으면 null). */
export interface PaperOrder {
  id: number;
  ticker: string;
  side: "BUY" | "SELL";
  qty: number | null;
  targetWeightPct: number | null;
  sellFraction: number | null;
  timing: "NEXT_OBSERVATION" | "NEXT_CLOSE" | "IMMEDIATE" | "BACKFILL";
  source: "RULE" | "CLAUDE" | "STOP" | "BENCHMARK" | "INITIAL";
  status: PaperOrderStatus;
  rejectReason: string | null;
  /** 주문 근거(JSON 그대로 — 예: `{"cashLimited": true}`). */
  reason: unknown;
  asOfTradingDate: string | null;
  createdAtKst: string | null;
  expiresOn: string | null;
  filledAtKst: string | null;
}

/** GET /api/paper/books/{code}/orders */
export interface PaperOrdersResponse {
  available: boolean;
  message?: string;
  orders?: PaperOrder[];
}

/** 체결 한 줄. price는 슬리피지를 얹은 값, rawPrice는 관측치 그대로(둘 다 주당 가격이라 소수 4자리, gross는 2자리). */
export interface PaperFill {
  id: number;
  orderId: number;
  ticker: string;
  side: "BUY" | "SELL";
  qty: number;
  price: number | null;
  rawPrice: number | null;
  slippageBp: number | null;
  gross: number | null;
  observedAtKst: string | null;
  observationDate: string | null;
  priceSource: string | null;
  /** 사후 체결(그날 종가로 나중에 기록). */
  backfill: boolean;
}

/** GET /api/paper/books/{code}/fills */
export interface PaperFillsResponse {
  available: boolean;
  message?: string;
  fills?: PaperFill[];
}

/** 거래일 하나의 순자산 기록. pricesFinal이 false면 장 마감 확정 전 값. */
export interface PaperNavRow {
  tradingDate: string;
  nav: number | null;
  cash: number | null;
  equityValue: number | null;
  exposurePct: number | null;
  pricesFinal: boolean;
}

/** GET /api/paper/books/{code}/nav */
export interface PaperNavResponse {
  available: boolean;
  message?: string;
  book?: PaperBookSummary;
  series?: PaperNavRow[];
}

/**
 * POST /api/paper/init — 멱등. 이미 있는 책은 created에 들지 않습니다.
 * 출발일 종가가 아직 없으면 오류가 아니라 200 + `skipped`(사유)이고, 그 벤치마크는 시작 전으로 남습니다(개요 pendingBackfill).
 */
export interface PaperInitResult {
  ok: boolean;
  created: string[];
  backfilled: string[];
  skipped: { code: string; reason: string }[];
  /** 이번에 설정 행(paper_settings v1)을 새로 만들었는가. */
  settingsCreated: boolean;
  /** 이번에 새로 얼린 출발일 종가 종목 수(paper_start_prices — 이미 얼린 종목은 세지 않음). */
  startPricesCaptured: number;
}

/** POST /api/paper/books/{code}/status */
export interface PaperBookStatusResult {
  ok: boolean;
  book: PaperBookSummary;
  /** 일시정지하며 취소한 대기 주문 수. */
  cancelledOrders: number | null;
}

/** POST /api/paper/books/{code}/reset */
export interface PaperBookResetResult {
  ok: boolean;
  book: PaperBookSummary;
  /** 벤치마크 책을 출발일 종가로 다시 사후 체결했는가. 규칙·Claude 책은 늘 false(현금만 든 시작 전). */
  backfilled: boolean;
  /** 벤치마크를 다시 사후 체결하지 못한 사유(종가가 아직 없음 등). 체결했거나 규칙·Claude 책이면 null. */
  skippedReason: string | null;
}

/** 리스크 작업 한 번의 결과(PaperLedgerService.Outcome). */
export interface PaperRiskOutcome {
  filled: number;
  stopped: number;
  expired: number;
  splits: number;
  booksProcessed: string[];
  skippedReason: string | null;
  /** 책 코드를 앞에 붙인 처리 메모 — 현금 부족으로 줄인 매수, 취소한 주문, 분할 조정 등. */
  notes: string[];
  /** 오류로 되돌린 책(`"claude: IllegalStateException …"`). 하나라도 있으면 응답의 ok가 false입니다. */
  errors: string[];
}

/**
 * POST /api/paper/run/risk. 한 책이 오류로 되돌려져도 HTTP는 200이고 `ok:false`·`outcome.errors`로 알립니다 — 화면은
 * ok를 봐야 실패를 압니다. 테이블이 없으면 outcome 없이 `{available:false, message, ok:false}`입니다.
 */
export interface PaperRiskResult {
  ok: boolean;
  outcome?: PaperRiskOutcome | null;
  message?: string;
}

/** 트랙레코드 순위 기준(설정 `trackRecord.metric`, 명세 R10·D20). 기본 CAGR. */
export type PaperTrackRecordMetric = "CAGR" | "EXCESS_VS_SPY" | "SHARPE";

/**
 * 트랙레코드 구간 하나(TrackRecord13F.Period, 명세 R7) — 공시 F_i의 보유를 공시 다음 거래일(start) 종가에 사서
 * 다음 공시의 다음 거래일(end, 마지막 구간은 기준일) 종가까지 든 평가액 가중 수익률. 첫 구간의 start는 7년 창 시작으로 자릅니다.
 * 커버된 평가액이 0이면 returnPct가 null이고, 그 구간은 누적·통계와 periodsUsed에서 빠집니다.
 */
export interface PaperTrackRecordPeriod {
  filingDate: string;
  /** 그 공시가 보고한 분기 말일. */
  reportDate: string;
  start: string;
  end: string;
  returnPct: number | null;
  spyReturnPct: number | null;
  /**
   * 시작·끝 가격이 모두 있는 보유의 평가액 / 그 공시 총평가액(티커 없는 보유 포함) — **0~1 비율**(%가 아님).
   * 자격 기준 `criteria.minCoverage`(0.5)와 같은 단위입니다.
   */
  coverage: number;
  /** 시작·끝 가격이 다 있어 수익률에 들어간 티커 수. */
  coveredTickers: number;
  /** 그 공시의 보유 줄 수(티커 없는 줄 포함). */
  holdings: number;
}

/**
 * 후보 기관 하나의 트랙레코드(TrackRecord13F.Result + 후보 목록 정보). 수익률·통계는 `%`(×100) 값이고, 커버리지는 0~1 비율입니다.
 * 표본이 없으면 수치는 null — 0으로 채우지 않습니다. 자격이 없으면 rank가 null이고 ineligibleReason에 사유(여럿이면 "; ")가 옵니다.
 */
export interface PaperTrackRecordCandidate {
  cik: string;
  name: string;
  /** 가치·집중 · 성장·테크 · 매크로(후보 목록의 분류). */
  style: string | null;
  rank: number | null;
  eligible: boolean;
  ineligibleReason: string | null;
  /** 30분기 수집이 아직 끝나지 않음(complete=false) — 자격 없음, 사유 "수집 중(저장 분기 n/30)". */
  collecting?: boolean;
  cagrPct: number | null;
  cumReturnPct: number | null;
  spyCumReturnPct: number | null;
  /** 누적 − SPY 누적(%p). */
  excessVsSpyPct: number | null;
  volPct: number | null;
  /** 최대 낙폭 — 음수(%). */
  mddPct: number | null;
  sharpe: number | null;
  /** 창 안에서 수익률이 있는 구간 수(자격 기준 minPeriods와 비교). */
  periodsUsed: number | null;
  /** 창 안의 구간 수(수익률이 null인 구간 포함). */
  periodsTotal: number | null;
  coverageMin: number | null;
  coverageAvg: number | null;
  firstFilingDate: string | null;
  lastFilingDate: string | null;
  /** 저장본(sec.13f.{cik}.q30)에 든 분기 수. */
  storedQuarters: number | null;
  complete: boolean;
  /** 그 기준일의 선정(paper_selection_log)에 든 기관인가. */
  selected: boolean;
  periods: PaperTrackRecordPeriod[];
}

/** 자격 기준(TrackRecord13F.Criteria, 명세 R9·D22). 커버리지는 0~1 비율. */
export interface PaperTrackRecordCriteria {
  windowYears: number;
  minPeriods: number;
  minCoverage: number;
  avgCoverage: number;
}

/** 선정 한 번(paper_selection_log). 선정은 재선정일·초기 선정 때 한 번 남고, 수동 재계산은 바꾸지 않습니다. */
export interface PaperSelectionHistoryEntry {
  asOfDate: string;
  selectedCiks: string[];
}

/**
 * 13F 트랙레코드 수집 진행률(수집기 저장본 sec.13f.track_record_index). 개수는 모두 정수.
 * - filers: 후보 기관 수 · completeCount: 30분기를 다 받은(또는 EDGAR에 더 오래된 공시가 없는) 기관 수
 * - storedFilings / targetFilings: 저장된 공시 수 / 목표(기관 수 × 30)
 */
export interface PaperTrackRecordCollection {
  filers: number;
  completeCount: number;
  storedFilings: number;
  targetFilings: number;
  /** 기관마다의 목표 분기 수(30). 아직 보내지 않는 백엔드에서는 없습니다 — 화면은 targetFilings ÷ filers로 대신합니다. */
  targetQuarters?: number | null;
  updatedAtKst: string | null;
}

/**
 * GET /api/paper/track-record?asOf= (asOf가 없으면 가장 최근 계산).
 * 계산이 아직 없으면 `computed:false`와 수집 진행률(collection)만 옵니다 — 그때 candidates는 없거나 비어 있습니다.
 */
export interface PaperTrackRecordResponse {
  available: boolean;
  message?: string;
  computed?: boolean;
  asOfDate?: string | null;
  windowYears?: number | null;
  metric?: PaperTrackRecordMetric | null;
  criteria?: PaperTrackRecordCriteria | null;
  candidates?: PaperTrackRecordCandidate[];
  /** 그 기준일에 유효한 선정의 CIK(순서는 선정 순위). */
  selected?: string[];
  selectionHistory?: PaperSelectionHistoryEntry[];
  collection?: PaperTrackRecordCollection | null;
  /** 트랙레코드의 한계 — 복제 수익률·생존 편향·커버리지 문구(순서대로). */
  limits?: string[];
}

/**
 * POST /api/paper/run/rerank — 수동 재계산. 같은 기준일의 트랙레코드를 덮어쓰고 **선정은 바꾸지 않습니다**(selectedUnchanged).
 * computed는 계산한 후보 수(정수) 또는 계산했는지(참·거짓)입니다 — 계약에 둘 중 무엇인지 적혀 있지 않아 화면은 둘 다 받습니다.
 * 테이블이 없으면 `{available:false, message, ok:false}`입니다.
 */
export interface PaperRerankResult {
  ok: boolean;
  asOfDate?: string | null;
  computed?: number | boolean | null;
  /** 자격 있는 기관 수. */
  eligible?: number | null;
  /** 30분기 수집이 끝나지 않아 자격에서 뺀 기관 수. */
  collecting?: number | null;
  selectedUnchanged?: boolean;
  /** 계산하지 못한 이유(후보 목록 저장본 없음·설정 후보가 수집 목록과 겹치지 않음 등). 계산했으면 null. */
  skippedReason?: string | null;
  message?: string;
}
