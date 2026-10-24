package com.macrodash.feature.paper;

import com.macrodash.Kst;
import com.macrodash.analytics.UsMarketCalendar;
import com.macrodash.feature.paper.PaperLedgerService.Outcome;
import com.macrodash.feature.paper.PaperMarketData.Quotes;
import com.macrodash.read.StoreReader;
import com.macrodash.store.PaperStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 🧪 리스크 점검 정기 작업(설계 §6-3) — 5분마다 새 관측치({@code paper.quotes})가 있으면 장부에 적용합니다(체결·스탑·분할·평가).
 *
 * <p>관측치는 수집기가 1시간마다 저장하므로 대부분의 실행은 "새 저장본 없음"으로 끝나고, 그때는 기록을 남기지 않습니다
 * (5분마다 한 줄씩 쌓이면 작업 기록 200건이 17시간 치뿐입니다). 일했을 때와 오류일 때만 {@code paper_job_runs}에 남기고,
 * detail에 관측치 수집 시각·다수 티커의 봉 날짜·확정 종가 여부를 적습니다 — 장중 저장본의 마지막 봉이 정말 그날 지연 시세인지
 * (명세 §7 첫 항목) 이 기록으로 확인합니다.
 *
 * <p>저장본은 {@link StoreReader#quietly}로만 읽습니다 — 정기 작업이 수집기를 깨우지 않게(ADR 0008). 예외는 삼키고 로그만
 * 남깁니다(RegimeLogService와 같은 관례). 같은 관측치를 두 번 처리해도 결과는 한 번입니다(장부의 멱등 키).
 *
 * <p>한 책이 오류로 되돌려지면 그 관측치는 그 책에 대해 다시 돌리지 않습니다({@link PaperLedgerService} 설명) — 같은 저장본으로
 * 5분마다 같은 오류를 내고 ERROR 기록이 쌓여 장중 봉 확인용 기록을 밀어내지 않게. 새 관측치가 오면 다시 시도하고, 수동 실행은
 * 같은 관측치로 한 번 더 시도합니다.
 *
 * <p>{@code dashboard.paper.enabled=false}면 정기 실행은 아무것도 하지 않습니다(통합 테스트). 수동 실행({@link #runNow})은
 * 설정과 상관없이 한 번 돕니다.
 */
@Service
public class PaperRiskJob {

    /** 작업 기록의 이름(paper_job_runs.job). */
    static final String JOB = "risk";

    private static final Logger log = LoggerFactory.getLogger(PaperRiskJob.class);

    private final PaperStore store;
    private final StoreReader reader;
    private final PaperMarketData marketData;
    private final PaperLedgerService ledger;
    private final PaperConfig config;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public PaperRiskJob(PaperStore store, StoreReader reader, PaperMarketData marketData, PaperLedgerService ledger,
                        PaperConfig config, ObjectMapper mapper) {
        this(store, reader, marketData, ledger, config, mapper, Clock.systemUTC());
    }

    /** 테스트용 — 시계를 움직입니다. */
    PaperRiskJob(PaperStore store, StoreReader reader, PaperMarketData marketData, PaperLedgerService ledger,
                 PaperConfig config, ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.reader = reader;
        this.marketData = marketData;
        this.ledger = ledger;
        this.config = config;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 정기 실행. 꺼져 있으면 아무것도 하지 않습니다. */
    @Scheduled(initialDelayString = "${dashboard.paper.risk-initial-delay-ms:90000}",
            fixedDelayString = "${dashboard.paper.risk-interval-ms:300000}")
    public void scheduledRun() {
        if (!config.enabled()) {
            return;
        }
        try {
            Outcome outcome = run(false);
            if (!outcome.errors().isEmpty()) {
                log.warn("모의 매매 리스크 점검 중 오류 — 그 책은 같은 관측치로 다시 시도하지 않고 새 관측치에서 다시 시도합니다: {}",
                        outcome.errors());
            } else if (outcome.skippedReason() == null) {
                log.info("모의 매매 리스크 점검: 체결 {} · 스탑 {} · 만료 {} · 분할 {} ({})", outcome.filled(),
                        outcome.stopped(), outcome.expired(), outcome.splits(), outcome.booksProcessed());
            }
        } catch (RuntimeException e) {
            log.warn("모의 매매 리스크 점검 실패 — 다음 주기에 다시 시도합니다: {}", e.toString());
        }
    }

    /**
     * 수동 실행(POST /api/paper/run/risk) — {@code dashboard.paper.enabled}와 상관없이 한 번. 오류가 났던 관측치도 한 번 더
     * 시도합니다(사람이 원인을 고친 뒤 누르는 버튼이라). 기록은 누른 만큼만 남습니다.
     */
    public Outcome runNow() {
        ledger.forgetFailures();
        return run(true);
    }

    /** 한 번 실행(정기·수동 공통). 테스트도 부릅니다. */
    Outcome run(boolean manual) {
        if (!store.ready()) {
            return Outcome.skipped("모의 매매 테이블이 아직 없습니다(V8·V9 적용 전) — make up 후 다시 보세요");
        }
        Instant started = clock.instant();
        Optional<Quotes> quotes = reader.quietly(marketData::quotes);
        if (quotes.isEmpty()) {
            return Outcome.skipped("관측치 저장본(paper.quotes)이 아직 없습니다 — 수집기의 paper_quotes가 돈 뒤에 다시 보세요");
        }
        if (!ledger.hasNewObservation(quotes.get())) {
            List<String> failed = ledger.failedBooks(quotes.get());
            if (!failed.isEmpty()) {
                return Outcome.skipped(PaperLedgerService.heldBackReason(quotes.get(), failed));
            }
            return Outcome.skipped("새 관측치가 없습니다(수집 " + Kst.stamp(quotes.get().collectedAt()) + "까지 처리함)");
        }
        Outcome outcome;
        try {
            outcome = ledger.process(quotes.get());
        } catch (RuntimeException e) {
            outcome = Outcome.failed(e.getClass().getSimpleName() + " " + e.getMessage());
        }
        record(started, manual, quotes.get(), outcome);
        return outcome;
    }

    private void record(Instant started, boolean manual, Quotes quotes, Outcome outcome) {
        Map<String, Object> detail = describe(quotes);
        detail.put("manual", manual);
        detail.put("filled", outcome.filled());
        detail.put("stopped", outcome.stopped());
        detail.put("expired", outcome.expired());
        detail.put("splits", outcome.splits());
        detail.put("booksProcessed", outcome.booksProcessed());
        if (outcome.skippedReason() != null) {
            detail.put("skippedReason", outcome.skippedReason());
        }
        if (!outcome.notes().isEmpty()) {
            detail.put("notes", outcome.notes());
        }
        if (!outcome.errors().isEmpty()) {
            detail.put("errors", outcome.errors());
        }
        try {
            store.recordJobRun(JOB, started, clock.instant(), outcome.errors().isEmpty() ? "OK" : "ERROR",
                    mapper.valueToTree(detail));
        } catch (RuntimeException e) {
            // 기록 실패가 처리 결과(이미 커밋됨)를 오류로 바꾸지 않게 로그만 남깁니다.
            log.warn("모의 매매 작업 기록을 남기지 못했습니다: {}", e.toString());
        }
    }

    /**
     * 관측치 요약 — 수집 시각·다수 티커의 봉 날짜·그 시각의 세션·확정 종가 여부. {@code barDate == session}인데
     * {@code finalClose == false}면 장중 봉(지연 시세)입니다.
     */
    static Map<String, Object> describe(Quotes quotes) {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDate barDate = quotes.modalBarDate();
        out.put("collectedAt", quotes.collectedAt().toString());
        out.put("collectedAtKst", Kst.stamp(quotes.collectedAt()));
        out.put("barDate", barDate == null ? null : barDate.toString());
        out.put("session", UsMarketCalendar.currentOrLastSession(quotes.collectedAt()).toString());
        out.put("finalClose", barDate != null && PaperMarketData.isFinal(barDate, quotes.collectedAt()));
        out.put("tickers", quotes.bars().size());
        out.put("staleTickers", quotes.staleTickers().size());
        return out;
    }
}
