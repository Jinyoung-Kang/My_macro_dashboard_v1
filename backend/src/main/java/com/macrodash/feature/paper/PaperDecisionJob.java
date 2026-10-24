package com.macrodash.feature.paper;

import com.macrodash.Kst;
import com.macrodash.analytics.GateIndicator;
import com.macrodash.analytics.UsMarketCalendar;
import com.macrodash.feature.paper.PaperLedgerService.Outcome;
import com.macrodash.feature.paper.PaperMarketData.Quotes;
import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.PaperStore;
import com.macrodash.store.PaperStore.Book;
import com.macrodash.store.PaperStore.Decision;
import com.macrodash.store.PaperStore.GateRow;
import com.macrodash.store.Snapshot;
import com.macrodash.support.InvalidRequestException;
import com.macrodash.support.Json;
import com.macrodash.support.Params;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 🧪 하루 한 번의 결정 작업(설계 §6-2) — 10분마다 돌며, 미국 세션이 끝난 다음 날 아침(KST)에 그 거래일의 결정을 냅니다.
 *
 * <pre>
 *  d = 마감이 끝난 가장 최근 세션
 *  d 다음 날 decisionTimeKst(07:30) 전           → TOO_EARLY(기록 안 함)
 *  그날 할 일(게이트 지표·규칙 책 결정)이 다 됨    → ALREADY_DECIDED(기록 안 함)
 *  관측치가 d의 확정 종가가 아님 && decisionDeadlineKst(12:00) 전 → PRICES_NOT_FINAL(기록 안 함 — 다음 주기에 다시)
 *  관측치 처리 → (확정이 아니면) d의 미확정 순자산 줄 → 게이트 지표 → 재선정일이면 선정 → 규칙 책 결정 → 작업 기록
 * </pre>
 *
 * <p>확정 종가 = 관측치의 다수 티커 봉 날짜가 d이고 마감 + 15분 뒤에 수집함. 기한을 넘기면 확정 종가 없이 진행하고 결정·순자산
 * 줄에 {@code prices_final = false}를 남깁니다(그날 결정을 아예 건너뛰지 않게 — 늦은 확정 관측치가 순자산 줄을 바로잡음).
 * 결정·게이트는 거래일마다 한 건이라(유일 제약) 같은 날 여러 번 돌아도 한 번입니다.
 *
 * <p>재선정일(공시 기한 다음 거래일, R5)이면 먼저 그날 기준으로 기관을 다시 선정하고 규칙 책은 REBALANCE(매수 포함), 그 외 날은
 * SCHEDULED(매도 신호와 남는 현금 SHY만)입니다. <b>재선정일을 놓친 경우</b>(그날 결정 창 동안 작업이 한 번도 끝나지 못함 — 맥 꺼짐·오류 계속):
 * 규칙 책이 그 분기 재선정일 r 전에 시작했는데 <b>r 선정으로 낸</b> 매수 결정(REBALANCE·INITIAL, {@code selection_as_of = r})이
 * 없으면, 다음 결정 날에 r 기준으로 선정하고({@code select(r)} — d가 아니라 r이라 언제 따라잡아도 같은 선정) 그날 REBALANCE를
 * 냅니다. 그러지 않으면 그 분기 매수가 통째로 사라져 다음 재선정일까지 지난 분기 신호로 갑니다. r에 선정을 건너뛴 경우(수집 중·후보
 * 목록 없음 — {@link PaperSelectionService#select})도 같습니다: 그날 REBALANCE는 지난 선정이라 매수 없이 끝나므로 세지 않고, 다음
 * 결정 날마다 {@code select(r)}를 다시 불러 수집이 끝난 뒤 그 분기 매수를 냅니다. Claude 책 결정은 단계 3a에서 규칙 책 다음 자리에
 * 들어갑니다. 시작 전 책은 결정을 내지 않고, 초기화 전(책이 없음)이면 아무것도 하지 않습니다.
 *
 * <p>저장본은 {@link StoreReader#quietly}로만 읽습니다(정기 작업이 수집을 깨우지 않게). 같은 거래일에 오류가 {@value #MAX_ATTEMPTS}번
 * 나면 그날은 더 시도하지 않습니다 — 10분마다 같은 오류가 작업 기록 200건을 밀어내지 않게(수동 실행·재기동은 다시 시도).
 * 정기 실행은 {@code dashboard.paper.enabled=false}면 아무것도 하지 않고, 수동 실행({@link #runNow})은 설정과 상관없이 돕니다.
 */
@Service
public class PaperDecisionJob {

    private static final Logger log = LoggerFactory.getLogger(PaperDecisionJob.class);

    /** 작업 기록의 이름(paper_job_runs.job). */
    static final String JOB = "decision";
    static final LocalTime DEFAULT_DECISION_TIME = LocalTime.of(7, 30);
    static final LocalTime DEFAULT_DEADLINE = LocalTime.of(12, 0);
    /** 같은 거래일에 오류가 이만큼 나면 그날은 더 시도하지 않습니다(클래스 설명). */
    static final int MAX_ATTEMPTS = 3;

    public static final String NOT_READY = "NOT_READY";
    public static final String NOT_INITIALIZED = "NOT_INITIALIZED";
    public static final String TOO_EARLY = "TOO_EARLY";
    public static final String ALREADY_DECIDED = "ALREADY_DECIDED";
    public static final String PRICES_NOT_FINAL = "PRICES_NOT_FINAL";
    public static final String GAVE_UP = "GAVE_UP";
    public static final String DECIDED = "DECIDED";
    public static final String ERROR = "ERROR";

    /**
     * 한 번 실행의 결과.
     *
     * @param outcome     위 상수 중 하나. DECIDED·ERROR만 작업 기록을 남깁니다
     * @param tradingDate 결정 대상 거래일(테이블이 없으면 null)
     * @param pricesFinal 확정 종가로 결정했나(결정 전에 끝났으면 null)
     * @param rules       규칙 책 결정(내지 않았으면 null)
     */
    public record Result(String outcome, LocalDate tradingDate, Boolean pricesFinal, Decision rules, String message) {
    }

    /** 설정의 결정 시각과 마감 기한(KST). */
    record Times(LocalTime decision, LocalTime deadline) {
    }

    private final PaperStore store;
    private final StoreReader reader;
    private final PaperMarketData marketData;
    private final PaperLedgerService ledger;
    private final PaperSelectionService selection;
    private final RuleBookService rules;
    private final PaperSettingsService settings;
    private final PaperConfig config;
    private final ObjectMapper mapper;
    private final Clock clock;
    /** 정기 실행과 수동 실행이 겹치지 않게(같은 거래일 결정을 동시에 만들지 않게 — 유일 제약이 마지막 방어선). */
    private final ReentrantLock running = new ReentrantLock();
    /** 거래일 → 오류 난 횟수(클래스 설명). 재기동하면 사라집니다. */
    private final Map<LocalDate, Integer> failures = new ConcurrentHashMap<>();

    @Autowired
    public PaperDecisionJob(PaperStore store, StoreReader reader, PaperMarketData marketData, PaperLedgerService ledger,
                            PaperSelectionService selection, RuleBookService rules, PaperSettingsService settings,
                            PaperConfig config, ObjectMapper mapper) {
        this(store, reader, marketData, ledger, selection, rules, settings, config, mapper, Clock.systemUTC());
    }

    /** 테스트용 — 시계를 움직입니다. */
    PaperDecisionJob(PaperStore store, StoreReader reader, PaperMarketData marketData, PaperLedgerService ledger,
                     PaperSelectionService selection, RuleBookService rules, PaperSettingsService settings,
                     PaperConfig config, ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.reader = reader;
        this.marketData = marketData;
        this.ledger = ledger;
        this.selection = selection;
        this.rules = rules;
        this.settings = settings;
        this.config = config;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 정기 실행. 꺼져 있으면 아무것도 하지 않습니다. 예외는 삼키고 로그만(RegimeLogService 관례). */
    @Scheduled(initialDelayString = "${dashboard.paper.decision-initial-delay-ms:120000}",
            fixedDelayString = "${dashboard.paper.decision-interval-ms:600000}")
    public void scheduledRun() {
        if (!config.enabled()) {
            return;
        }
        try {
            Result result = run();
            if (ERROR.equals(result.outcome())) {
                log.warn("모의 매매 결정 작업 오류({}) — {}", result.tradingDate(), result.message());
            } else if (DECIDED.equals(result.outcome())) {
                log.info("모의 매매 결정 작업: {} 규칙 책 {}", result.tradingDate(),
                        result.rules() == null ? "결정 없음" : result.rules().status());
            }
        } catch (RuntimeException e) {
            log.warn("모의 매매 결정 작업 실패 — 다음 주기에 다시 시도합니다: {}", e.toString());
        }
    }

    /** 정기 실행 한 번(클래스 설명의 순서). 테스트도 부릅니다. */
    Result run() {
        running.lock();
        try {
            return runLocked();
        } finally {
            running.unlock();
        }
    }

    private Result runLocked() {
        if (!store.ready()) {
            return new Result(NOT_READY, null, null, null, PaperQueryService.NOT_READY_MESSAGE);
        }
        Instant now = clock.instant();
        LocalDate d = UsMarketCalendar.lastCompletedSession(now);
        if (store.books().isEmpty()) {
            return new Result(NOT_INITIALIZED, d, null, null, "초기화 전입니다 — 책이 없습니다");
        }
        Times times = times();
        Instant decisionAt = kst(d.plusDays(1), times.decision());
        if (now.isBefore(decisionAt)) {
            return new Result(TOO_EARLY, d, null, null, "결정 시각 " + Kst.stamp(decisionAt) + " 전");
        }
        if (!pendingWork(d)) {
            return new Result(ALREADY_DECIDED, d, null, null, d + " 결정·게이트 지표를 이미 남겼습니다");
        }
        if (failures.getOrDefault(d, 0) >= MAX_ATTEMPTS) {
            return new Result(GAVE_UP, d, null, null, d + " 오류가 " + MAX_ATTEMPTS + "번 나 오늘은 더 시도하지 않습니다 — "
                    + "수동 실행(POST /api/paper/run/decision)은 다시 시도합니다");
        }
        Optional<Quotes> quotes = reader.quietly(marketData::quotes);
        boolean pricesFinal = quotes.map(q -> finalFor(q, d)).orElse(false);
        Instant deadline = kst(d.plusDays(1), times.deadline());
        if (!pricesFinal && now.isBefore(deadline)) {
            return new Result(PRICES_NOT_FINAL, d, false, null, d + " 확정 종가 관측치를 기다립니다(기한 "
                    + Kst.stamp(deadline) + ")");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tradingDate", d.toString());
        detail.put("pricesFinal", pricesFinal);
        detail.put("pastDeadline", !now.isBefore(deadline));
        quotes.ifPresent(q -> detail.put("quotes", PaperRiskJob.describe(q)));
        try {
            quotes.ifPresent(q -> detail.put("ledger", ledgerDetail(ledger.process(q))));
            // 다음 세션 봉이 섞인 관측치(기한을 한참 넘겨 장중에 돈 경우)로는 d의 줄을 쓰지 않습니다 — 그날 값이 아닙니다.
            boolean sameSession = quotes.map(q -> q.modalBarDate() == null || !q.modalBarDate().isAfter(d))
                    .orElse(true);
            if (sameSession) {
                detail.put("navSettled", ledger.settleNav(d, pricesFinal));
            }
            GateRow gate = recordGate(d);
            detail.put("gateLevel", gate.level());

            // 이 거래일 시점에 재선정이 지난 최신 분기의 재선정일 r — 오늘이 r이면 재선정, r을 놓쳤으면 따라잡기(클래스 설명).
            LocalDate reselectionDay = UsMarketCalendar.reselectionDay(
                    UsMarketCalendar.latestQuarterEndReselectedBy(d));
            boolean reselection = reselectionDay.equals(d);
            Optional<Book> book = store.book(PaperBooks.RULES);
            boolean rulesStarted = book.isPresent() && !PaperBooks.NOT_STARTED.equals(book.get().status());
            boolean rebalance = rulesStarted && rebalanceMissing(book.get(), reselectionDay);
            detail.put("reselectionDay", reselection);
            if (rebalance && !reselection) {
                detail.put("catchUpReselection", reselectionDay.toString());
            }
            if (reselection || rebalance) {
                PaperSelectionService.SelectionOutcome outcome = selection.select(reselectionDay);
                Map<String, Object> picked = new LinkedHashMap<>();
                picked.put("created", outcome.created());
                picked.put("asOf", outcome.selection() == null ? null : outcome.selection().asOfDate().toString());
                picked.put("ciks", outcome.selection() == null ? null : outcome.selection().selectedCiks());
                picked.put("skippedReason", outcome.skippedReason());
                detail.put("selection", picked);
            }
            Decision decision = null;
            if (rulesStarted) {
                decision = rules.decide(d, rebalance ? RuleBookService.REBALANCE : RuleBookService.SCHEDULED,
                        pricesFinal);
                detail.put("rules", decisionDetail(decision));
            } else {
                detail.put("rules", "시작 전 — 결정을 내지 않습니다");
            }
            // 단계 3a: Claude 책 결정(claude.decide(d, SCHEDULED))이 이 자리에 들어갑니다.
            failures.remove(d);
            record(now, DECIDED, detail);
            return new Result(DECIDED, d, pricesFinal, decision, null);
        } catch (RuntimeException e) {
            failures.merge(d, 1, Integer::sum);
            String message = e.getClass().getSimpleName() + " " + e.getMessage();
            detail.put("error", message);
            detail.put("attempt", failures.get(d));
            record(now, ERROR, detail);
            return new Result(ERROR, d, pricesFinal, null, message);
        }
    }

    /**
     * 수동 결정(POST /api/paper/run/decision) — 그 책만 종류 MANUAL로 한 번. 결정 시각·확정 종가를 기다리지 않고(결정의
     * {@code prices_final}에 그때 관측치가 확정 종가였는지 남김), 먼저 최신 관측치를 장부에 적용합니다. MANUAL도 거래일마다 한
     * 건이라 같은 날 다시 누르면 처음 결정을 돌려줍니다. 매수는 재선정일·시작에만이라 수동 결정은 매도 신호와 남는 현금 SHY만 냅니다.
     *
     * @throws InvalidRequestException 책 코드가 rules·claude가 아님, Claude 책(단계 3a), 규칙 책이 시작 전
     */
    public Decision runNow(String book) {
        String code = Params.oneOf("book", book, List.of(PaperBooks.RULES, PaperBooks.CLAUDE));
        if (PaperBooks.CLAUDE.equals(code)) {
            throw new InvalidRequestException("Claude 책의 결정은 다음 단계(3a)에서 엽니다.");
        }
        running.lock();
        try {
            failures.clear();
            Instant now = clock.instant();
            LocalDate d = UsMarketCalendar.lastCompletedSession(now);
            Optional<Quotes> quotes = reader.quietly(marketData::quotes);
            boolean pricesFinal = quotes.map(q -> finalFor(q, d)).orElse(false);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("manual", true);
            detail.put("book", code);
            detail.put("tradingDate", d.toString());
            detail.put("pricesFinal", pricesFinal);
            quotes.ifPresent(q -> detail.put("ledger", ledgerDetail(ledger.process(q))));
            Decision decision = rules.decide(d, RuleBookService.MANUAL, pricesFinal);
            detail.put("rules", decisionDetail(decision));
            record(now, DECIDED, detail);
            return decision;
        } finally {
            running.unlock();
        }
    }

    // ------------------------------------------------------------------ 판정
    /** 그 거래일에 아직 할 일이 있나 — 게이트 지표가 없거나, 시작한 규칙 책의 그날 정기 결정(SCHEDULED·REBALANCE)이 없음. */
    private boolean pendingWork(LocalDate d) {
        if (store.gate(d).isEmpty()) {
            return true;
        }
        Optional<Book> book = store.book(PaperBooks.RULES);
        if (book.isPresent() && !PaperBooks.NOT_STARTED.equals(book.get().status())) {
            boolean decided = store.decisionFor(PaperBooks.RULES, d, RuleBookService.SCHEDULED).isPresent()
                    || store.decisionFor(PaperBooks.RULES, d, RuleBookService.REBALANCE).isPresent();
            if (!decided) {
                return true;
            }
        }
        // 단계 3a: Claude 책의 그날 결정도 여기서 봅니다.
        return false;
    }

    /**
     * 그 분기의 매수 결정을 아직 내지 않았나 — 책이 재선정일 r 전에 시작했고, r 선정({@code selection_as_of = r})으로 낸
     * REBALANCE·INITIAL 결정이 없음. r 당일에는 늘 참이고(그날 처음 내는 결정), r을 놓쳤으면 다음 결정 날에도 참입니다. r에 선정을
     * 건너뛰어 지난 선정으로 낸 REBALANCE(매수 없음)는 세지 않습니다 — 날짜만 보면 그 분기 재선정을 다시 시도하지 않게 됩니다.
     * r 뒤에 시작한 책은 초기 결정이 그 분기 매수입니다.
     */
    private boolean rebalanceMissing(Book book, LocalDate reselectionDay) {
        if (book.startedOn() == null || !book.startedOn().isBefore(reselectionDay)) {
            return false;
        }
        return !store.hasDecisionWithSelection(PaperBooks.RULES,
                List.of(RuleBookService.REBALANCE, RuleBookService.INITIAL), reselectionDay);
    }

    /** d의 확정 종가 관측치인가 — 다수 티커의 봉 날짜가 d이고 마감 + 15분 뒤에 수집함(설계 §6-1). */
    static boolean finalFor(Quotes quotes, LocalDate d) {
        return d.equals(quotes.modalBarDate()) && PaperMarketData.isFinal(d, quotes.collectedAt());
    }

    /**
     * 설정의 결정 시각·마감 기한({@code decisionTimeKst}·{@code decisionDeadlineKst}, "HH:mm"). 없거나 틀리면 07:30·12:00,
     * 기한이 결정 시각보다 앞이면 기한을 결정 시각으로 봅니다(기다리지 않고 바로 진행).
     */
    Times times() {
        var body = store.settings().map(PaperStore.SettingsRow::body).orElse(null);
        LocalTime decision = time(Json.asText(body, "decisionTimeKst"), DEFAULT_DECISION_TIME);
        LocalTime deadline = time(Json.asText(body, "decisionDeadlineKst"), DEFAULT_DEADLINE);
        return new Times(decision, deadline.isBefore(decision) ? decision : deadline);
    }

    private static LocalTime time(String text, LocalTime fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return LocalTime.parse(text.trim());
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private static Instant kst(LocalDate day, LocalTime time) {
        return ZonedDateTime.of(day, time, Kst.ZONE).toInstant();
    }

    // ------------------------------------------------------------------ 게이트 지표
    /**
     * 그 거래일의 게이트 지표를 한 번 남깁니다(이미 있으면 그대로). 입력은 매크로 카드 저장본의 지금 값({@link PaperGate}),
     * 연속 일수는 <b>바로 전 거래일</b>의 기록에서 잇습니다 — 그 사이 기록이 빠졌으면(작업이 멈춘 날) 처음부터 셉니다. 빠진 날을
     * 참으로 이어 세면 확인되지 않은 '3일 연속'이 생기기 때문입니다. 같은 이유로 이어 쓴(isStale) 값·카드 시각이 그 거래일보다
     * 앞선 값도 그날 값으로 세지 않습니다({@link PaperGate#counted}).
     */
    GateRow recordGate(LocalDate d) {
        Optional<GateRow> existing = store.gate(d);
        if (existing.isPresent()) {
            return existing.get();
        }
        Optional<Snapshot> macro = reader.quietly(() -> reader.read(Datasets.SNAP_MACRO_COLLECTED,
                Datasets.MAX_AGE_REALTIME, "macro_collected"));
        Map<String, PaperGate.Input> read = PaperGate.read(macro.orElse(null));
        Map<String, Integer> previous = store.latestGateBefore(d)
                .filter(row -> row.tradingDate().equals(UsMarketCalendar.previousTradingDay(d)))
                .map(row -> PaperGate.streaks(row.streaks())).orElse(null);
        GateIndicator.Thresholds thresholds = settings.current().settings().gate();
        // 이어 쓴 값·그 거래일보다 앞선 값은 연속 일수에 넣지 않습니다(PaperGate.counted) — 기록에는 그대로 남김.
        GateIndicator.Result result = GateIndicator.evaluate(PaperGate.inputs(read, d), previous, thresholds);
        store.insertGateIfAbsent(new GateRow(d,
                mapper.valueToTree(PaperGate.inputsBody(read, d, result, thresholds, macro.orElse(null))),
                result.level(), mapper.valueToTree(result.streaks()), null));
        return store.gate(d).orElseThrow(() -> new IllegalStateException("게이트 지표를 남겼는데 다시 읽지 못했습니다: " + d));
    }

    // ------------------------------------------------------------------ 기록
    private void record(Instant started, String outcome, Map<String, Object> detail) {
        try {
            store.recordJobRun(JOB, started, clock.instant(), outcome, mapper.valueToTree(detail));
        } catch (RuntimeException e) {
            // 기록 실패가 결정(이미 커밋됨)을 오류로 바꾸지 않게 로그만 남깁니다.
            log.warn("모의 매매 결정 작업 기록을 남기지 못했습니다: {}", e.toString());
        }
    }

    private static Map<String, Object> ledgerDetail(Outcome outcome) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filled", outcome.filled());
        out.put("stopped", outcome.stopped());
        out.put("expired", outcome.expired());
        out.put("splits", outcome.splits());
        out.put("booksProcessed", outcome.booksProcessed());
        out.put("skippedReason", outcome.skippedReason());
        if (!outcome.errors().isEmpty()) {
            out.put("errors", outcome.errors());
        }
        return out;
    }

    private static Map<String, Object> decisionDetail(Decision decision) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", decision.id());
        out.put("kind", decision.kind());
        out.put("status", decision.status());
        out.put("summary", Json.asText(decision.response(), "summary"));
        return out;
    }
}
