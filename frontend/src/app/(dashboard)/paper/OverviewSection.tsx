"use client";

import { useState } from "react";
import { MultiLineSeries, SERIES_COLORS } from "@/components/charts";
import { Banner, Button, Card, Freshness, Table } from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { useAsyncAction } from "@/hooks/useAsyncAction";
import { useElapsedSeconds } from "@/hooks/useElapsedSeconds";
import { apiPost } from "@/lib/api";
import { endpoints } from "@/lib/endpoints";
import { deltaColor, EMPTY, formatKst } from "@/lib/format";
import {
  apiUsageView,
  type Badge,
  bookCard,
  claudeExposureChart,
  initialDecisionResultView,
  initPrompt,
  initResultText,
  jobOutcomeClass,
  jsonSummary,
  jsonTitle,
  navChart,
  PAPER_SOURCES,
  type PoolRow,
  quotesLine,
  riskResultView,
  rulesStartPrompt,
  type SelectedFilerRow,
  selectedFilersView,
} from "@/lib/paper";
import type {
  PaperBookSummary,
  PaperInitialDecisionResult,
  PaperInitResult,
  PaperJobRun,
  PaperOverviewResponse,
  PaperRiskResult,
  PaperStatusResponse,
} from "@/lib/types";
import { ClaudeStartSection } from "./ClaudeStartSection";
import { GateIndicatorBody } from "./GateIndicatorBody";

/**
 * 책 코드별 선 색. 앞 셋은 charts의 검증된 팔레트, 혼합 벤치마크는 COT 화면의 보조 계열과 같은 회색입니다
 * (벤치마크 둘이 같은 계열색이면 어느 선이 SPY인지 범례를 다시 봐야 했음). 모르는 책은 남는 색을 돌려 씁니다.
 */
const BOOK_COLORS: Record<string, string> = {
  rules: SERIES_COLORS.blue,
  claude: SERIES_COLORS.orange,
  bench_spy: SERIES_COLORS.green,
  bench_mix: "#8B949E",
};
const SPARE_COLORS = ["#A371F7", "#39C5CF", "#DB6D28"];

/**
 * Claude 노출 추이의 두 선 — 목표는 순자산 곡선의 Claude 책 색(주황), 실제는 검증된 팔레트의 다음 색(파랑). 두 색은 charts의
 * 팔레트 검증을 통과한 이웃 쌍이라 색약에서도 갈립니다. 한 축(%)이고, 범례가 이름을 함께 적어 색만으로 구분하지 않습니다.
 */
const EXPOSURE_COLORS: Record<string, string> = { target: SERIES_COLORS.orange, actual: SERIES_COLORS.blue };

/**
 * 개요 탭 — 책 카드 네 개, 순자산 곡선, Claude 노출 추이(목표 vs 실제), Claude 호출 사용량(오늘·이달 — 상태 응답), 신호 기관(최근
 * 선정), OS 게이트 지표(참고), 장부 관측치 신선도, 작업 기록. 초기화 전이거나 벤치마크가 아직 사후 체결을 기다리면(pendingBackfill) "초기화" 버튼, 규칙 책이 시작 전이면
 * "규칙 책 시작" 버튼, Claude 책이 시작 전이면 "미리보기"·"Claude 재량 책 시작" 버튼(ClaudeStartSection).
 *
 * 숫자는 개요 응답 그대로 그립니다. 버튼이 성공하면 onChanged(개요 다시 읽기)를 불러 카드·곡선을 새 장부로 바꿉니다.
 */
export function OverviewSection({ overview, onChanged }: { overview: PaperOverviewResponse; onChanged: () => void }) {
  // 정기 작업이 꺼져 있는지·설정 버전·Claude 호출 사용량은 개요 응답에 없어 상태 응답에서 읽습니다(실패해도 개요는 그대로).
  const status = useApi<PaperStatusResponse>(endpoints.paper.status, 60_000);

  const init = useAsyncAction(() => apiPost<PaperInitResult>(endpoints.paper.init), "초기화하지 못했습니다.");
  const risk = useAsyncAction(() => apiPost<PaperRiskResult>(endpoints.paper.runRisk), "리스크 작업을 실행하지 못했습니다.");
  const initElapsed = useElapsedSeconds(init.busy);
  const riskElapsed = useElapsedSeconds(risk.busy);
  // 규칙 책 시작(초기 결정) — 선정·트랙레코드 계산과 사후 체결을 한 번에 하므로 오래 걸릴 수 있어 경과 초를 셉니다.
  const start = useAsyncAction(
    (book: string) => apiPost<PaperInitialDecisionResult>(endpoints.paper.runInitialDecision, { book }),
    "규칙 책을 시작하지 못했습니다.",
  );
  const startElapsed = useElapsedSeconds(start.busy);
  const [confirmingStart, setConfirmingStart] = useState(false);

  const runInit = async () => {
    if (await init.run()) onChanged();
  };
  const runRisk = async () => {
    if (await risk.run()) onChanged();
  };
  const runStart = async (book: string) => {
    const ok = await start.run(book);
    // 성공·실패 모두 확인 단계를 닫습니다 — 실패 사유(예: 수집 중)는 아래 경고로 남고, 다시 누르려면 확인을 한 번 더 거칩니다.
    setConfirmingStart(false);
    if (ok) onChanged();
  };

  const books = overview.books ?? [];
  const chart = navChart(overview.navSeries, books);
  const series = chart.series.map((line, index) => ({
    ...line,
    color: BOOK_COLORS[line.key] ?? SPARE_COLORS[index % SPARE_COLORS.length],
  }));
  const quotes = overview.quotes ?? null;
  const staleNames = quotes?.staleTickers ?? [];
  const prompt = initPrompt(overview);
  const initResult = init.result && (
    <Banner tone={init.result.skipped?.length ? "warn" : "info"}>초기화 — {initResultText(init.result)}</Banner>
  );
  const startPrompt = rulesStartPrompt(overview);
  const startView = start.result ? initialDecisionResultView(start.result) : null;
  const startResult = startView && <Banner tone={startView.tone}>{startView.text}</Banner>;
  const selection = selectedFilersView(overview.selection);
  const hasClaude = books.some((book) => book.kind === "CLAUDE");
  const exposure = claudeExposureChart(overview.claudeExposureSeries);
  const exposureSeries = exposure.series.map((line) => ({ ...line, color: EXPOSURE_COLORS[line.key] ?? SPARE_COLORS[0] }));
  const usage = status.data?.available ? apiUsageView(status.data.apiUsage) : null;

  return (
    <>
      {status.data?.available && status.data.enabled === false && (
        <Banner tone="warn">
          정기 작업이 꺼져 있습니다(<code>PAPER_ENABLED=false</code>) — 리스크 작업(체결·스탑·평가)은 아래 &quot;리스크 지금 실행&quot;을
          누를 때만 돕니다.
        </Banner>
      )}

      {prompt && (
        <Card title={prompt.title} source={PAPER_SOURCES.ledger}>
          <p className="text-sm leading-relaxed text-body">{prompt.body}</p>
          <div className="mt-3 flex flex-wrap items-center gap-3">
            <Button variant="primary" onClick={() => void runInit()} disabled={init.busy}>
              {init.busy ? `초기화 중… ${initElapsed}초` : "초기화"}
            </Button>
          </div>
          {init.error && <div className="mt-3"><Banner tone="warn">{init.error}</Banner></div>}
          {/* 종가가 없어 건너뛴 벤치마크의 서버 사유는 다시 누를 버튼 바로 아래에 둡니다. */}
          {initResult && <div className="mt-3">{initResult}</div>}
        </Card>
      )}
      {/* 카드가 사라진 뒤(모두 시작함)에도 초기화 결과는 남겨 둡니다 — 무엇을 만들고 체결했는지 읽을 수 있게. */}
      {!prompt && initResult}

      {startPrompt && (
        <Card title={startPrompt.title} subtitle="초기 결정 — 출발일 종가 사후 체결" source={PAPER_SOURCES.decisions}>
          <p className="text-sm leading-relaxed text-body">{startPrompt.body}</p>
          {confirmingStart ? (
            <div className="mt-3 flex flex-col gap-3 rounded-md border border-warn/40 bg-warn/10 px-3 py-3">
              <p className="text-sm leading-relaxed text-warn">{startPrompt.confirm}</p>
              <div className="flex flex-wrap items-center gap-2">
                <Button variant="primary" onClick={() => void runStart(startPrompt.bookCode)} disabled={start.busy}>
                  {start.busy ? `시작 중… ${startElapsed}초` : "시작 확인"}
                </Button>
                <Button onClick={() => setConfirmingStart(false)} disabled={start.busy}>취소</Button>
              </div>
            </div>
          ) : (
            <div className="mt-3">
              <Button variant="primary" onClick={() => setConfirmingStart(true)} disabled={start.busy}>{startPrompt.title}</Button>
            </div>
          )}
          {/* 서버의 400 문구(트랙레코드 수집 중·이미 시작함 등)를 그대로 — 무엇을 기다려야 하는지 서버가 적어 보냅니다. */}
          {start.error && <div className="mt-3"><Banner tone="warn">{start.error}</Banner></div>}
          {startResult && <div className="mt-3">{startResult}</div>}
        </Card>
      )}
      {/* 시작한 뒤(개요를 다시 읽어 카드가 사라짐)에도 결과는 남깁니다 — 주문·사후 체결 수를 읽을 수 있게. */}
      {!startPrompt && startResult}

      <ClaudeStartSection overview={overview} onChanged={onChanged} />

      <Card
        title="책"
        subtitle={`순자산·수익률·노출은 마지막 관측치 기준 · 통계는 거래일별 순자산 기준${
          status.data?.settingsVersion ? ` · 설정 v${status.data.settingsVersion}` : ""
        }`}
        source={PAPER_SOURCES.ledger}
        actions={
          overview.initialized !== false && (
            <Button onClick={() => void runRisk()} disabled={risk.busy}>
              {risk.busy ? `실행 중… ${riskElapsed}초` : "리스크 지금 실행"}
            </Button>
          )
        }
      >
        {risk.error && <div className="mb-3"><Banner tone="warn">{risk.error}</Banner></div>}
        {risk.result && <div className="mb-3"><RiskResult result={risk.result} /></div>}
        {books.length === 0 ? (
          <p className="text-sm text-muted">책이 없습니다. 초기화하면 생깁니다.</p>
        ) : (
          <div className="grid gap-3 md:grid-cols-2">
            {books.map((book) => (
              <BookCard key={book.code} book={book} books={books} />
            ))}
          </div>
        )}
      </Card>

      <Card
        title="순자산 곡선"
        subtitle="거래일별 순자산(달러). 그날 기록이 없는 책은 선을 끊어 그립니다 — 시작 전인 책은 선이 없습니다."
        source={PAPER_SOURCES.ledger}
      >
        <MultiLineSeries data={chart.rows} series={series} precision={0} height={300} />
      </Card>

      {hasClaude && (
        <Card
          title="Claude 노출 추이"
          subtitle="목표 = Claude가 결정마다 정한 주식 노출(기록·표시용 — 집행은 주문 기준) · 실제 = 그 거래일 장부의 주식 노출. 기록이 없는 날은 선을 끊어 그립니다."
          source={PAPER_SOURCES.exposure}
        >
          {exposure.rows.length === 0 ? (
            <p className="text-sm text-muted">
              아직 Claude 결정 기록이 없습니다 — Claude 책을 시작하면 결정마다 정한 목표 노출과 그 거래일의 실제 노출을 그립니다.
            </p>
          ) : (
            <>
              <MultiLineSeries data={exposure.rows} series={exposureSeries} unit="%" precision={0} height={240} domain={[0, 100]} />
              {exposure.latest && <p className="mt-2 text-xs text-muted">마지막 {exposure.latest}</p>}
            </>
          )}
        </Card>
      )}

      {hasClaude && usage && (
        <Card
          title="Claude 호출 사용량"
          subtitle="결정·미리보기·시작·재시도의 호출 수와 토큰(KST 날짜 기준) — 하루 상한에 닿으면 그날은 Claude를 부르지 않습니다"
          source={PAPER_SOURCES.apiUsage}
        >
          <div className="grid gap-4 sm:grid-cols-2">
            <UsageCells label={usage.todayLabel} cells={usage.today} />
            <UsageCells label={usage.monthLabel} cells={usage.month} />
          </div>
          {usage.capReached && (
            <p className="mt-2 text-xs text-warn">오늘 호출이 하루 상한에 닿았습니다 — 오늘 남은 결정은 Claude를 부르지 않고 건너뜁니다.</p>
          )}
          <p className="mt-2 text-xs text-muted">{usage.note}</p>
        </Card>
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        <Card
          title="신호 기관"
          subtitle={selection
            ? `${selection.asOfDate} 선정 — 규칙 책이 두 그룹을 합친 신호 풀의 13F 변화를 신호로 씁니다(다음 재선정일까지)`
            : "규칙 책이 13F 신호를 읽는 기관(그룹 A: 후보 트랙레코드 상위 · 그룹 B: 기존 13F 메뉴 최근 상위)"}
          source={PAPER_SOURCES.selection}
        >
          {!selection ? (
            <p className="text-sm text-muted">아직 기관 선정이 없습니다 — 규칙 책을 시작하면 출발일 기준으로 선정하고, 그 뒤는 재선정일마다 다시 뽑습니다.</p>
          ) : selection.groupA.length === 0 && selection.pool.length === 0 ? (
            <p className="text-sm text-muted">선정이 비어 있습니다 — 자격 있는 기관이 없었습니다(규칙 책은 매수 없이 SHY만 듭니다).</p>
          ) : (
            <div className="flex flex-col gap-4">
              <div className="grid gap-3 sm:grid-cols-2">
                <GroupList
                  title="그룹 A — 후보 기관 트랙레코드 상위"
                  rows={selection.groupA}
                  empty="자격 있는 기관이 없습니다."
                />
                <GroupList
                  title="그룹 B — 기존 13F 메뉴 최근 상위"
                  rows={selection.groupB}
                  empty="자격 있는 기관이 없습니다 — 신호는 그룹 A만 냅니다(가중은 그룹 A가 전부)."
                />
              </div>
              {selection.pool.length > 0 && (
                <section aria-label="신호 풀" className="flex flex-col gap-1">
                  <h3 className="text-xs font-semibold text-bright">신호 풀 · 기관 가중</h3>
                  <Table<PoolRow>
                    rows={selection.pool}
                    rowKey={(row) => row.key}
                    columns={[
                      { key: "name", header: "기관", render: (row) => <span className="font-semibold text-bright">{row.name}</span> },
                      {
                        key: "group",
                        header: "그룹",
                        render: (row) => (row.group ? <GroupBadge badge={row.group} /> : <span className="text-muted">{EMPTY}</span>),
                      },
                      { key: "ranks", header: "순위", render: (row) => <span className="whitespace-nowrap">{row.ranks}</span> },
                      { key: "weight", header: "기관 가중", align: "right", render: (row) => row.weight },
                    ]}
                  />
                </section>
              )}
            </div>
          )}
        </Card>

        <Card title="OS 게이트 지표" subtitle="참고 — 구속하지 않음 · 과거 OS가 쓴 매크로 트리거 수준" source={PAPER_SOURCES.gate}>
          <GateIndicatorBody gate={overview.gate} />
        </Card>
      </div>

      <Card
        title="장부 관측치"
        subtitle="체결·스탑·평가에 쓰는 저장본입니다. 장중이면 마지막 봉이 지연 시세입니다."
        source={PAPER_SOURCES.quotes}
        actions={
          quotes && (
            <Freshness collectedAt={quotes.collectedAtKst} ageSeconds={quotes.ageSeconds ?? undefined} stale={quotes.stale} />
          )
        }
      >
        <p className="text-sm text-body">{quotesLine(quotes)}</p>
        {staleNames.length > 0 && (
          <p className="mt-1 text-xs text-warn">지난 값을 이어 쓴 티커: {staleNames.join(", ")}</p>
        )}
      </Card>

      <Card title="작업 기록" subtitle="정기·수동 작업이 일했을 때·오류일 때만 남깁니다(최근 10건). 상세에는 처리한 관측치의 수집 시각·봉 날짜·티커 수가 있습니다." source={PAPER_SOURCES.jobs}>
        <Table<PaperJobRun>
          rows={overview.jobs ?? []}
          rowKey={(row, index) => `${row.job}-${row.startedAtKst ?? index}-${index}`}
          emptyMessage="아직 작업 기록이 없습니다. 리스크 작업이 새 관측치를 처리하면 생깁니다."
          columns={[
            { key: "job", header: "작업", render: (row) => row.job },
            { key: "startedAt", header: "시작", render: (row) => formatKst(row.startedAtKst) },
            { key: "finishedAt", header: "끝", render: (row) => formatKst(row.finishedAtKst) },
            {
              key: "outcome",
              header: "결과",
              render: (row) => <span className={jobOutcomeClass(row.outcome)}>{row.outcome || EMPTY}</span>,
            },
            {
              key: "detail",
              header: "상세",
              render: (row) => (
                <span className="text-xs text-muted" title={jsonTitle(row.detail)}>
                  {jsonSummary(row.detail)}
                </span>
              ),
            },
          ]}
        />
      </Card>
    </>
  );
}

/**
 * 리스크 작업 결과. ok:false(한 책이 오류로 되돌려짐)면 경고 배너에 오류를, 성공이든 아니든 처리 메모를 목록으로 보입니다 —
 * HTTP 200이라 버튼은 성공으로 끝나므로, 응답의 ok를 보지 않으면 실패가 정상 완료 문구로 보였습니다.
 */
function RiskResult({ result }: { result: PaperRiskResult }) {
  const view = riskResultView(result);
  return (
    <Banner tone={view.tone}>
      {view.warning && <p className="font-semibold">{view.warning}</p>}
      <p className="text-xs">리스크 작업 — {view.summary}</p>
      {view.errors.length > 0 && (
        <div className="mt-2">
          <p className="text-xs font-semibold">오류</p>
          <ul aria-label="리스크 작업 오류" className="list-disc pl-5 text-xs">
            {view.errors.map((error, index) => <li key={`${index}-${error}`}>{error}</li>)}
          </ul>
        </div>
      )}
      {view.notes.length > 0 && (
        <div className="mt-2">
          <p className="text-xs font-semibold">처리 메모</p>
          <ul aria-label="리스크 작업 메모" className="list-disc pl-5 text-xs">
            {view.notes.map((note, index) => <li key={`${index}-${note}`}>{note}</li>)}
          </ul>
        </div>
      )}
    </Banner>
  );
}

/** 사용량 칸 묶음 하나(오늘·이달). */
function UsageCells({ label, cells }: { label: string; cells: readonly { key: string; label: string; value: string }[] }) {
  return (
    <section aria-label={`Claude 호출 사용량 ${label}`} className="flex flex-col gap-1">
      <h3 className="text-xs font-semibold text-bright">{label}</h3>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 sm:grid-cols-3">
        {cells.map((cell) => (
          <Cell key={cell.key} label={cell.label} value={cell.value} small />
        ))}
      </dl>
    </section>
  );
}

/** 선정 그룹 하나의 목록 — 그룹 안 순위 순(서버 순서), 이름을 모르면 CIK. */
function GroupList({ title, rows, empty }: { title: string; rows: SelectedFilerRow[]; empty: string }) {
  return (
    <section aria-label={title} className="flex flex-col gap-1">
      <h3 className="text-xs font-semibold text-bright">{title}</h3>
      {rows.length === 0 ? (
        <p className="text-xs text-muted">{empty}</p>
      ) : (
        <ol className="space-y-0.5 text-xs text-body">
          {rows.map((row) => (
            <li key={row.key} title={`CIK ${row.cik}`}>
              <span className="tabular-nums text-muted">{row.rank}</span> <span>{row.name}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

function GroupBadge({ badge }: { badge: Badge }) {
  return (
    <span title={badge.title} className={`whitespace-nowrap rounded-sm border px-1.5 py-px text-[10px] ${badge.className}`}>
      {badge.label}
    </span>
  );
}

/** 책 하나의 카드 — 상태 배지, 순자산·수익률·노출·현금, 통계. */
function BookCard({ book, books }: { book: PaperBookSummary; books: readonly PaperBookSummary[] }) {
  const card = bookCard(book, books);
  return (
    <article className="rounded-lg border border-border bg-canvas px-4 py-3" aria-label={book.label}>
      <header className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="text-sm font-semibold text-bright">{book.label}</h3>
          <p className="text-[11px] text-muted">
            {card.kind} · 출발 {card.startedOn} · 처음 {card.initialCash}
          </p>
        </div>
        <span className={`rounded-sm border px-2 py-0.5 text-[11px] ${card.status.className}`}>{card.status.label}</span>
      </header>

      <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 sm:grid-cols-4">
        <Cell label="순자산" value={card.nav} />
        <Cell label="수익률" value={card.returnPct} tone={deltaColor(book.returnPct)} />
        <Cell label="주식 노출" value={card.exposure} />
        <Cell label="현금" value={card.cash} />
      </dl>
      <p className="mt-2 text-[11px] text-muted">
        보유 {card.positions} · 마지막 관측 {card.lastObserved}
      </p>
      {card.staleWarning && <p className="mt-1 text-[11px] text-warn">⚠️ {card.staleWarning}</p>}

      <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1 border-t border-border pt-2 sm:grid-cols-4">
        {card.stats.map((stat) => (
          <Cell key={stat.key} label={stat.label} value={stat.value} small />
        ))}
      </dl>
      <p className="mt-2 text-[11px] text-muted">{card.sample}</p>
    </article>
  );
}

function Cell({ label, value, tone, small }: { label: string; value: string; tone?: string; small?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-[11px] text-muted">{label}</dt>
      <dd className={`tabular-nums ${small ? "text-xs" : "text-sm font-semibold"} ${tone ?? "text-bright"}`}>{value}</dd>
    </div>
  );
}
