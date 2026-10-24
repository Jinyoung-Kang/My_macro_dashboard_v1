package com.macrodash.feature.paper;

import com.macrodash.feature.paper.PaperMarketData.SourcedClose;
import com.macrodash.store.PaperStore.NavRow;
import com.macrodash.support.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.macrodash.feature.paper.PaperFixtures.FRI;
import static com.macrodash.feature.paper.PaperFixtures.MON;
import static com.macrodash.feature.paper.PaperFixtures.MON_FINAL;
import static com.macrodash.feature.paper.PaperFixtures.bar;
import static com.macrodash.feature.paper.PaperFixtures.quotes;
import static com.macrodash.feature.paper.PaperFixtures.staleBar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 화면 응답 — 준비 전·초기화 전·책 요약·보유 표의 모양과 숫자.
 */
class PaperQueryServiceTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-26T21:00:00Z"), ZoneOffset.UTC);

    private FakePaperStore store;
    private PaperMarketData marketData;
    private PaperQueryService query;

    @BeforeEach
    void setUp() {
        store = new FakePaperStore();
        marketData = mock(PaperMarketData.class);
        when(marketData.history(anyCollection(), any(), any())).thenReturn(Map.of());
        when(marketData.sourcedCloseOn(anyString(), any())).thenReturn(Optional.empty());
        when(marketData.quotes()).thenReturn(Optional.empty());
        query = new PaperQueryService(store, marketData, PaperFixtures.quietReader(), PaperFixtures.settings(store),
                PaperFixtures.config(), NOW);
    }

    private void initWithBenchmarks() {
        when(marketData.sourcedCloseOn("SPY", FRI)).thenReturn(Optional.of(new SourcedClose(500, "equity_close")));
        when(marketData.sourcedCloseOn("SHY", FRI)).thenReturn(Optional.of(new SourcedClose(82.5, "equity_close")));
        PaperLedgerService ledger = new PaperLedgerService(store, marketData, PaperFixtures.settings(store),
                PaperFixtures.MAPPER);
        new PaperAdminService(store, marketData, PaperFixtures.quietReader(), PaperFixtures.settings(store), ledger,
                PaperFixtures.config(), PaperFixtures.MAPPER, NOW).init();
    }

    @Test
    @DisplayName("테이블이 없으면 모든 응답이 available:false와 안내 문구")
    void notReady() {
        store.ready = false;
        for (Map<String, Object> body : List.of(query.overview(), query.positions("rules"), query.orders("rules", 50),
                query.fills("rules", 50), query.nav("rules"))) {
            assertThat(body.get("available")).isEqualTo(false);
            assertThat((String) body.get("message")).contains("V8").contains("make up");
        }
        Map<String, Object> status = query.status();
        assertThat(status.get("schemaReady")).isEqualTo(false);
        assertThat(status.get("enabled")).isEqualTo(true);
    }

    @Test
    @DisplayName("초기화 전: available:true, initialized:false, 책 없음, 한계 문구 여덟 줄")
    void beforeInit() {
        Map<String, Object> overview = query.overview();

        assertThat(overview.get("available")).isEqualTo(true);
        assertThat(overview.get("initialized")).isEqualTo(false);
        assertThat(overview.get("pendingBackfill")).isEqualTo(List.of());
        assertThat(overview.get("startDate")).isEqualTo("2026-10-23");
        assertThat((List<?>) overview.get("books")).isEmpty();
        assertThat(overview.get("quotes")).isNull();
        @SuppressWarnings("unchecked")
        List<String> limits = (List<String>) overview.get("limits");
        assertThat(limits).hasSize(8);
        assertThat(limits.get(0)).isEqualTo("모의 매매입니다. 어떤 주문도 밖으로 나가지 않습니다.");
        assertThat(limits.get(3)).isEqualTo("2026-10-23 체결은 사후 체결입니다.");
        assertThat(query.status().get("settingsVersion")).isNull();
        assertThatThrownBy(() -> query.positions("rules")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("개요: 정의 순서의 네 책, 순자산 곡선(같은 길이·없는 날 null), 시작 전 책은 수익률·통계 null")
    @SuppressWarnings("unchecked")
    void overviewAfterInit() {
        initWithBenchmarks();
        store.upsertNav(new NavRow("bench_spy", MON, 101_000, 450.25, 100_549.75, 99.55, true));
        when(marketData.quotes()).thenReturn(Optional.of(quotes(MON_FINAL, bar("SPY", FRI, 500, MON, 505),
                staleBar("QQQ", FRI, 400))));

        Map<String, Object> overview = query.overview();

        assertThat(overview.get("initialized")).isEqualTo(true);
        assertThat(overview.get("pendingBackfill")).as("벤치마크 둘 다 사후 체결함").isEqualTo(List.of());
        List<Map<String, Object>> books = (List<Map<String, Object>>) overview.get("books");
        assertThat(books).extracting(b -> b.get("code")).containsExactly("rules", "claude", "bench_spy", "bench_mix");
        Map<String, Object> rules = books.get(0);
        assertThat(rules.get("status")).isEqualTo("NOT_STARTED");
        assertThat(rules.get("returnPct")).isNull();
        assertThat(rules.get("stats")).isNull();
        assertThat(rules.get("nav")).isEqualTo(100_000.0);
        assertThat(rules.get("benchmark")).isEqualTo("bench_spy");
        Map<String, Object> spy = books.get(2);
        assertThat(spy.get("benchmark")).isNull();
        assertThat(spy.get("cash")).isEqualTo(450.25);
        assertThat(spy.get("returnPct")).as("상태의 순자산 99,950.25 기준").isEqualTo(-0.05);
        assertThat(spy.get("positions")).isEqualTo(1);
        assertThat(spy.get("staleCount")).isEqualTo(0);
        Map<String, Object> stats = (Map<String, Object>) spy.get("stats");
        assertThat(stats.get("sampleDays")).isEqualTo(1);
        assertThat(stats.get("cumReturnPct")).isEqualTo(1.05);   // 99,950.25 → 101,000
        assertThat(stats.get("cagrPct")).as("표본 20일 미만").isNull();

        Map<String, Object> nav = (Map<String, Object>) overview.get("navSeries");
        assertThat((List<String>) nav.get("dates")).containsExactly("2026-10-23", "2026-10-26");
        Map<String, List<Double>> series = (Map<String, List<Double>>) nav.get("books");
        assertThat(series.get("bench_spy")).containsExactly(99_950.25, 101_000.0);
        assertThat(series.get("bench_mix")).containsExactly(99_950.29, null);   // 520.285 + 119 × 500 + 484 × 82.5
        assertThat(series.get("rules")).containsExactly(null, null);

        Map<String, Object> quotes = (Map<String, Object>) overview.get("quotes");
        assertThat(quotes.get("barDate")).isEqualTo("2026-10-26");
        assertThat(quotes.get("finalClose")).isEqualTo(true);
        assertThat(quotes.get("tickers")).isEqualTo(2);
        assertThat(quotes.get("staleTickers")).isEqualTo(List.of("QQQ"));
        assertThat(quotes.get("ageSeconds")).isEqualTo(40L * 60);
        assertThat(quotes.get("stale")).isEqualTo(false);
        assertThat(quotes.get("collectedAtKst")).isEqualTo("2026-10-27 05:20 KST");
    }

    @Test
    @DisplayName("종가를 받기 전에 초기화했으면 initialized는 true여도 pendingBackfill에 벤치마크가 남고, 다시 초기화하면 빈다")
    void pendingBackfillUntilBenchmarksStart() {
        PaperLedgerService ledger = new PaperLedgerService(store, marketData, PaperFixtures.settings(store),
                PaperFixtures.MAPPER);
        PaperAdminService admin = new PaperAdminService(store, marketData, PaperFixtures.quietReader(),
                PaperFixtures.settings(store), ledger, PaperFixtures.config(), PaperFixtures.MAPPER, NOW);
        admin.init();   // 종가 없음 — 벤치마크는 시작 전으로 남음

        Map<String, Object> before = query.overview();
        assertThat(before.get("initialized")).isEqualTo(true);
        assertThat(before.get("pendingBackfill")).isEqualTo(List.of("bench_spy", "bench_mix"));

        when(marketData.sourcedCloseOn("SPY", FRI)).thenReturn(Optional.of(new SourcedClose(500, "equity_close")));
        admin.init();   // SHY는 아직 없음 — SPY 100%만 시작
        assertThat(query.overview().get("pendingBackfill")).isEqualTo(List.of("bench_mix"));

        when(marketData.sourcedCloseOn("SHY", FRI)).thenReturn(Optional.of(new SourcedClose(82.5, "equity_close")));
        admin.init();
        assertThat(query.overview().get("pendingBackfill")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("보유 표: 평가액·비중·스탑가(벤치마크·현금성은 null)·사후 체결 배지, 주문·체결 목록, limit은 1–500으로 접음")
    @SuppressWarnings("unchecked")
    void positionsAndLists() {
        initWithBenchmarks();
        store.books.put("claude", new com.macrodash.store.PaperStore.Book("claude", "CLAUDE", "Claude 재량 책", 100_000,
                FRI, "ACTIVE", PaperFixtures.FRI_CLOSE));
        store.states.put("claude", new com.macrodash.store.PaperStore.BookState("claude", 1_000, 3_000, 2_000, 66.6667,
                MON_FINAL, MON_FINAL, MON, null));
        PaperFixtures.position(store, "claude", "AAA", 10, 100, 110, 100.0, FRI, 100);
        PaperFixtures.position(store, "claude", "SHY", 10, 100, 100, null, FRI, 100);

        Map<String, Object> claude = query.positions("claude");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) claude.get("positions");
        Map<String, Object> aaa = rows.get(0);
        assertThat(aaa.get("ticker")).isEqualTo("AAA");
        assertThat(aaa.get("marketValue")).isEqualTo(1_000.0);
        assertThat(aaa.get("weightPct")).isEqualTo(33.33);
        assertThat(aaa.get("stopPrice")).isEqualTo(101.2);          // 110 × 0.92
        assertThat(aaa.get("stopDistancePct")).isEqualTo(-1.19);    // 100 / 101.2 − 1
        assertThat(aaa.get("unrealizedPct")).isEqualTo(0.0);
        assertThat(aaa.get("holdingTradingDays")).isEqualTo(1);    // 금 → 월
        assertThat(aaa.get("backfilled")).isEqualTo(false);
        Map<String, Object> shy = rows.get(1);
        assertThat(shy.get("cashLike")).isEqualTo(true);
        assertThat(shy.get("stopPrice")).isNull();
        assertThat(shy.get("lastPrice")).isNull();
        assertThat(shy.get("priceStale")).as("가격을 한 번도 못 받음").isEqualTo(true);
        assertThat(shy.get("marketValue")).as("평단으로 평가").isEqualTo(1_000.0);
        assertThat(claude.get("cash")).isEqualTo(1_000.0);

        Map<String, Object> spy = query.positions("bench_spy");
        Map<String, Object> spyRow = ((List<Map<String, Object>>) spy.get("positions")).get(0);
        assertThat(spyRow.get("stopPrice")).as("벤치마크는 스탑 없음").isNull();
        assertThat(spyRow.get("backfilled")).isEqualTo(true);

        List<Map<String, Object>> fills = (List<Map<String, Object>>) query.fills("bench_mix", 0).get("fills");
        assertThat(fills).hasSize(1);   // limit 0 → 1
        assertThat(fills.get(0).get("observedAtKst")).isEqualTo("2026-10-24 05:00 KST");
        assertThat(fills.get(0).get("backfill")).isEqualTo(true);
        List<Map<String, Object>> orders = (List<Map<String, Object>>) query.orders("bench_mix", 9_999).get("orders");
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).get("asOfTradingDate")).isEqualTo("2026-10-22");
        assertThat(orders.get(0).get("timing")).isEqualTo("BACKFILL");

        Map<String, Object> nav = query.nav("bench_spy");
        assertThat((List<?>) nav.get("series")).hasSize(1);
        assertThat(((Map<String, Object>) nav.get("book")).get("code")).isEqualTo("bench_spy");

        assertThatThrownBy(() -> query.orders("nope", 50)).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("없는 책");
        assertThat(query.status().get("settingsVersion")).isEqualTo(1);
    }
}
