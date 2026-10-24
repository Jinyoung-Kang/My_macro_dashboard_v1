package com.macrodash.feature.paper;

import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.PaperStore;
import com.macrodash.store.SchemaCheck;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static com.macrodash.feature.paper.PaperFixtures.FRI;
import static com.macrodash.feature.paper.PaperFixtures.MAPPER;
import static com.macrodash.feature.paper.PaperFixtures.MON;
import static com.macrodash.feature.paper.PaperFixtures.MON_INTRADAY;
import static com.macrodash.feature.paper.PaperFixtures.SAT;
import static com.macrodash.feature.paper.PaperFixtures.TUE_FINAL;
import static com.macrodash.feature.paper.PaperFixtures.TUE_INTRADAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 장부 흐름을 실제 테스트 DB(macrodash_test)의 V8 테이블로 — 초기화(사후 체결) → 장중 체결 → 스탑·재진입 금지 → 같은 관측치
 * 다시 처리. 가짜 저장소로는 CHECK 제약(주문 출처·시점·상태 값)·NUMERIC 자리·외래키가 실제로 받아 주는지 알 수 없습니다.
 *
 * <p>관측치 저장본은 {@link StoreReader}를 바꿔 끼워 넣습니다(수집기 테이블 snapshots에는 쓰지 않음). 책은 실제 코드
 * (rules·claude·bench_spy·bench_mix)라 통합 테스트(ApiIntegrationTest)와 같은 행을 쓰므로 시작·끝에 그 네 책만 지웁니다 —
 * 책을 지우면 장부가 외래키로 함께 지워집니다. 설정 행은 이 테스트가 만들었을 때만 지웁니다. 초기화가 얼리는 출발일 종가
 * ({@code paper_start_prices})도 이 테스트가 쓰는 종목만 지웁니다 — 남겨 두면 다른 테스트의 초기화가 그 값으로 사후 체결합니다.
 */
class PaperLedgerDbTest {

    private static final Clock AFTER_START = Clock.fixed(Instant.parse("2026-10-24T03:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private PaperStore store;
    private StoreReader reader;
    private PaperMarketData marketData;
    private PaperLedgerService ledger;
    private PaperAdminService admin;
    private boolean settingsExisted;

    @BeforeEach
    void setUp() {
        String url = System.getenv().getOrDefault("TEST_DATABASE_URL",
                "jdbc:postgresql://localhost:5432/macrodash_test");
        assertThat(url).as("테스트 DB만 씁니다").endsWith("_test");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv().getOrDefault("DATABASE_USER", "macro"),
                System.getenv().getOrDefault("DATABASE_PASSWORD", "macro")));
        store = new PaperStore(jdbc, MAPPER, new SchemaCheck(jdbc));
        assumeTrue(store.ready(), "테스트 DB에 V8(paper_*)이 없습니다 — make db-test");
        settingsExisted = store.settings().isPresent();
        deleteBooks();

        reader = PaperFixtures.quietReader();
        marketData = new PaperMarketData(reader, new StoreRepository(jdbc, MAPPER), AFTER_START);
        PaperSettingsService settings = new PaperSettingsService(store, MAPPER);
        ledger = new PaperLedgerService(store, marketData, settings, MAPPER);
        admin = new PaperAdminService(store, marketData, reader, settings, ledger, PaperFixtures.config(), MAPPER,
                AFTER_START);
    }

    @AfterEach
    void tearDown() {
        if (jdbc == null || store == null || !store.ready()) {
            return;
        }
        deleteBooks();
        if (!settingsExisted) {
            jdbc.update("DELETE FROM paper_settings");
            jdbc.update("DELETE FROM paper_settings_history WHERE version = 1");
        }
    }

    private void deleteBooks() {
        jdbc.update("DELETE FROM paper_books WHERE code IN ('rules', 'claude', 'bench_spy', 'bench_mix')");
        jdbc.update("DELETE FROM paper_start_prices WHERE ticker IN ('SPY', 'SHY', 'AAA')");
    }

    /** paper.quotes 저장본 하나를 관측치로 내놓습니다(수집기 모양 그대로의 JSON). */
    private void observe(Instant collectedAt, String tickersJson) throws Exception {
        Snapshot snapshot = new Snapshot(Datasets.SNAP_PAPER_QUOTES,
                MAPPER.readTree("{\"period\":\"3mo\",\"tickers\":" + tickersJson + "}"), "json", "ok", null, collectedAt);
        when(reader.read(eq(Datasets.SNAP_PAPER_QUOTES), anyLong(), any())).thenReturn(Optional.of(snapshot));
    }

    private PaperLedgerService.Outcome processLatest() {
        return ledger.process(reader.quietly(marketData::quotes).orElseThrow());
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("초기화 → 장중 체결 → 스탑·재진입 금지 → 같은 관측치 다시: DB 제약을 통과하고 결과는 한 번")
    void ledgerFlowOnRealTables() throws Exception {
        // 1. 출발일 종가가 든 저장본(토요일 수집) — 10년 이력이 없으니 관측치의 그날 봉으로 사후 체결
        observe(SAT, """
                {"SPY":{"dates":["2026-10-22","2026-10-23"],"close":[495.0,500.0]},
                 "SHY":{"dates":["2026-10-22","2026-10-23"],"close":[82.4,82.5]},
                 "AAA":{"dates":["2026-10-22","2026-10-23"],"close":[99.0,100.0]}}""");
        PaperAdminService.InitResult init = admin.init();
        assertThat(init.backfilled()).containsExactly("bench_spy", "bench_mix");
        assertThat(count("SELECT COUNT(*) FROM paper_fills WHERE book_code = 'bench_spy' AND is_backfill "
                + "AND price_source = 'paper.quotes' AND qty = 199")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM paper_nav_daily WHERE book_code = 'bench_mix' "
                + "AND trading_date = DATE '2026-10-23' AND prices_final")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM paper_books WHERE code = 'bench_spy'", String.class))
                .isEqualTo("ACTIVE");
        assertThat(init.startPricesCaptured()).as("관측치 유니버스 전체(SPY·SHY·AAA)를 얼림").isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT ticker || '=' || close::text || ' ' || source FROM paper_start_prices "
                + "WHERE ticker IN ('SPY', 'SHY', 'AAA') AND trading_date = DATE '2026-10-23' ORDER BY ticker",
                String.class)).containsExactly("AAA=100.000000 paper.quotes", "SHY=82.500000 paper.quotes",
                "SPY=500.000000 paper.quotes");

        // 2. Claude 책을 운용 중으로 두고(시작 기능은 다음 단계) 대기 매수 하나 — 월요일 장중 관측치로 체결
        jdbc.update("UPDATE paper_books SET status = 'ACTIVE', started_on = DATE '2026-10-23' WHERE code = 'claude'");
        long buy = store.insertOrder(new PaperStore.Order(0, "claude", null, "AAA", "BUY", null, 10.0, null,
                "NEXT_OBSERVATION", "CLAUDE", "PENDING", null, null, FRI, SAT.plusSeconds(60), MON.plusDays(3), null));
        observe(MON_INTRADAY, """
                {"SPY":{"dates":["2026-10-23","2026-10-26"],"close":[500.0,505.0]},
                 "SHY":{"dates":["2026-10-23","2026-10-26"],"close":[82.5,82.5]},
                 "AAA":{"dates":["2026-10-23","2026-10-26"],"close":[100.0,100.0]}}""");
        PaperLedgerService.Outcome first = processLatest();
        assertThat(first.filled()).isEqualTo(1);
        assertThat(first.errors()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM paper_orders WHERE id = ?", String.class, buy))
                .isEqualTo("FILLED");
        assertThat(jdbc.queryForObject("SELECT qty FROM paper_positions WHERE book_code = 'claude' AND ticker = 'AAA'",
                Integer.class)).isEqualTo(99);   // 10,000 / 100.05 → 99주

        // 3. 화요일 장중 −10% → 스탑(STOP·IMMEDIATE·FILLED) + 재진입 금지
        observe(TUE_INTRADAY, """
                {"SPY":{"dates":["2026-10-23","2026-10-26","2026-10-27"],"close":[500.0,505.0,506.0]},
                 "AAA":{"dates":["2026-10-23","2026-10-26","2026-10-27"],"close":[100.0,100.0,90.0]}}""");
        PaperLedgerService.Outcome stop = processLatest();
        assertThat(stop.stopped()).isEqualTo(1);
        Map<String, Object> stopOrder = jdbc.queryForMap("SELECT source, timing, status, qty, reason->>'trigger' AS trig "
                + "FROM paper_orders WHERE book_code = 'claude' AND source = 'STOP'");
        assertThat(stopOrder).containsEntry("timing", "IMMEDIATE").containsEntry("status", "FILLED")
                .containsEntry("qty", 99).containsEntry("trig", "TRAILING_STOP");
        assertThat(count("SELECT COUNT(*) FROM paper_positions WHERE book_code = 'claude'")).isZero();
        assertThat(jdbc.queryForObject("SELECT until_trading_date::text FROM paper_lockouts WHERE book_code = 'claude' "
                + "AND ticker = 'AAA'", String.class)).isEqualTo("2026-11-24");
        assertThat(count("SELECT COUNT(*) FROM paper_positions WHERE book_code = 'bench_mix' AND price_stale"))
                .as("SHY 봉이 빠진 관측치 — 가격 없음").isEqualTo(1);

        // 4. 같은 관측치를 다시 — 아무 책도 처리하지 않음
        long fillsBefore = count("SELECT COUNT(*) FROM paper_fills WHERE book_code IN "
                + "('rules', 'claude', 'bench_spy', 'bench_mix')");
        PaperLedgerService.Outcome again = processLatest();
        assertThat(again.booksProcessed()).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM paper_fills WHERE book_code IN "
                + "('rules', 'claude', 'bench_spy', 'bench_mix')")).isEqualTo(fillsBefore);

        // 5. 순자산 줄은 확정 종가 관측치에서만 — 장중 관측치 둘로는 없고, 화요일 마감 뒤 관측치로 화요일 줄(확정)
        assertThat(count("SELECT COUNT(*) FROM paper_nav_daily WHERE book_code = 'claude'")).isZero();
        observe(TUE_FINAL, """
                {"SPY":{"dates":["2026-10-23","2026-10-26","2026-10-27"],"close":[500.0,505.0,507.0]},
                 "SHY":{"dates":["2026-10-23","2026-10-26","2026-10-27"],"close":[82.5,82.5,82.6]},
                 "AAA":{"dates":["2026-10-23","2026-10-26","2026-10-27"],"close":[100.0,100.0,91.0]}}""");
        processLatest();
        assertThat(jdbc.queryForList("SELECT book_code FROM paper_nav_daily WHERE trading_date = DATE '2026-10-27' "
                + "AND prices_final ORDER BY book_code", String.class))
                .containsExactly("bench_mix", "bench_spy", "claude");   // 시작 전 rules는 남기지 않음
        assertThat(jdbc.queryForObject("SELECT last_nav_trading_date::text FROM paper_book_state "
                + "WHERE book_code = 'claude'", String.class)).isEqualTo("2026-10-27");
    }

    @Test
    @DisplayName("리셋은 얼려 둔 출발일 종가로 다시 사후 체결한다 — 배당락으로 수정 종가가 낮아진 뒤에도 처음과 같은 가격·수량")
    void resetReusesFrozenStartPrice() throws Exception {
        observe(SAT, """
                {"SPY":{"dates":["2026-10-22","2026-10-23"],"close":[495.0,500.0]},
                 "SHY":{"dates":["2026-10-22","2026-10-23"],"close":[82.4,82.5]}}""");
        admin.init();
        // 몇 주 뒤: 수집기가 배당 조정으로 출발일 종가를 낮게 다시 씀(SHY 82.5 → 82.0, SPY 500 → 497).
        observe(TUE_FINAL, """
                {"SPY":{"dates":["2026-10-23","2026-10-26","2026-10-27"],"close":[497.0,505.0,507.0]},
                 "SHY":{"dates":["2026-10-23","2026-10-26","2026-10-27"],"close":[82.0,82.1,82.2]}}""");

        PaperAdminService.ResetResult reset = admin.reset("bench_mix", "bench_mix");

        assertThat(reset.backfilled()).isTrue();
        assertThat(jdbc.queryForList("SELECT ticker || ' ' || qty || ' ' || raw_price::text FROM paper_fills "
                + "WHERE book_code = 'bench_mix' ORDER BY ticker", String.class))
                .containsExactly("SHY 484 82.500000", "SPY 119 500.000000");
    }
}
