"use client";

import { MultiLineSeries, SERIES_COLORS } from "@/components/charts";
import { Banner, Button, Card, Freshness, Table } from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { useAsyncAction } from "@/hooks/useAsyncAction";
import { useElapsedSeconds } from "@/hooks/useElapsedSeconds";
import { apiPost } from "@/lib/api";
import { endpoints } from "@/lib/endpoints";
import { deltaColor, EMPTY, formatKst } from "@/lib/format";
import {
  bookCard,
  initPrompt,
  initResultText,
  jobOutcomeClass,
  jsonSummary,
  jsonTitle,
  navChart,
  PAPER_SOURCES,
  quotesLine,
  riskResultView,
} from "@/lib/paper";
import type {
  PaperBookSummary,
  PaperInitResult,
  PaperJobRun,
  PaperOverviewResponse,
  PaperRiskResult,
  PaperStatusResponse,
} from "@/lib/types";

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
 * 개요 탭 — 책 카드 네 개, 순자산 곡선, 장부 관측치 신선도, 작업 기록. 초기화 전이거나 벤치마크가 아직 사후 체결을
 * 기다리면(pendingBackfill) "초기화" 버튼.
 *
 * 숫자는 개요 응답 그대로 그립니다. 버튼이 성공하면 onChanged(개요 다시 읽기)를 불러 카드·곡선을 새 장부로 바꿉니다.
 */
export function OverviewSection({ overview, onChanged }: { overview: PaperOverviewResponse; onChanged: () => void }) {
  // 정기 작업이 꺼져 있는지·설정 버전은 개요 응답에 없어 상태 응답에서 읽습니다(실패해도 개요는 그대로).
  const status = useApi<PaperStatusResponse>(endpoints.paper.status, 60_000);

  const init = useAsyncAction(() => apiPost<PaperInitResult>(endpoints.paper.init), "초기화하지 못했습니다.");
  const risk = useAsyncAction(() => apiPost<PaperRiskResult>(endpoints.paper.runRisk), "리스크 작업을 실행하지 못했습니다.");
  const initElapsed = useElapsedSeconds(init.busy);
  const riskElapsed = useElapsedSeconds(risk.busy);

  const runInit = async () => {
    if (await init.run()) onChanged();
  };
  const runRisk = async () => {
    if (await risk.run()) onChanged();
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
