package com.macrodash.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 모의 매매 장부 테이블(V8)과 여러 시계열 읽기 — 실제 테스트 DB(macrodash_test)에서 유일 제약·덮어쓰기 규칙·트랜잭션을
 * 확인합니다. 가짜 저장소로는 ON CONFLICT·외래키·커서가 실제로 어떻게 도는지 알 수 없습니다.
 *
 * <p>운영 DB로 돌지 않도록 이름이 {@code _test}로 끝나는지 먼저 확인합니다. 다른 테스트(통합 테스트의 초기화 등)와
 * 겹치지 않게 책 코드·작업 이름은 {@code t_}로, 출발일 종가는 티커 {@code T_}로 시작하는 것만, 시계열은 데이터셋
 * {@code t_equity_close}만 쓰고 시작·끝에 그 행만 지웁니다. 설정은 한 행(id = 1)뿐이라, 설정 시험은 끝에 되돌리는
 * 트랜잭션 안에서만 합니다.
 *
 * <p>{@code timeseries}는 수집기 테이블입니다(ADR 0008). 백엔드 코드는 쓰지 않고, 여기서는 읽기를 시험할 행을 만들려고
 * 테스트에서만 씁니다(ArchitectureRulesTest는 main 소스만 검사).
 */
class PaperStoreDbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BOOK = "t_rules";
    private static final String OTHER_BOOK = "t_bench";
    private static final String TS_DATASET = "t_equity_close";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 23);
    private static final Instant CLOSE = Instant.parse("2026-10-23T20:00:00Z");

    private JdbcTemplate jdbc;
    private PaperStore store;
    private StoreRepository repository;

    @BeforeEach
    void setUp() {
        String url = System.getenv().getOrDefault("TEST_DATABASE_URL",
                "jdbc:postgresql://localhost:5432/macrodash_test");
        assertThat(url).as("테스트 DB만 씁니다").endsWith("_test");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("DATABASE_USER", "macro"),
                System.getenv().getOrDefault("DATABASE_PASSWORD", "macro"));
        jdbc = new JdbcTemplate(dataSource);
        store = new PaperStore(jdbc, MAPPER, new SchemaCheck(jdbc));
        repository = new StoreRepository(jdbc, MAPPER);
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        // 책을 지우면 상태·보유·재진입 금지·결정·주문·체결·순자산이 외래키로 함께 지워집니다.
        jdbc.update("DELETE FROM paper_books WHERE code LIKE 't\\_%'");
        jdbc.update("DELETE FROM paper_job_runs WHERE job LIKE 't\\_%'");
        jdbc.update("DELETE FROM paper_start_prices WHERE ticker LIKE 'T\\_%'");
        jdbc.update("DELETE FROM timeseries WHERE dataset = ?", TS_DATASET);
        // Claude 호출 사용량은 날짜가 키라, 시험은 쓰일 일이 없는 1999년 날짜만 씁니다.
        jdbc.update("DELETE FROM paper_api_usage WHERE day < DATE '2000-01-01'");
    }

    private static PaperStore.Book book(String code, String label) {
        return new PaperStore.Book(code, "RULES", label, 100_000, null, "NOT_STARTED", null);
    }

    private static PaperStore.Order pendingBuy(String code, String ticker, Instant createdAt) {
        return new PaperStore.Order(0, code, null, ticker, "BUY", null, 10.0, null, "NEXT_OBSERVATION", "RULE",
                "PENDING", null, null, DAY, createdAt, DAY.plusDays(5), null);
    }

    private static PaperStore.Fill fill(long orderId, String code) {
        return new PaperStore.Fill(0, orderId, code, "SPY", "BUY", 147, 680.34, 680.0, 5, 100_009.98, CLOSE, DAY,
                "equity_close", true, null);
    }

    private static PaperStore.NavRow nav(LocalDate day, double value, boolean pricesFinal) {
        return new PaperStore.NavRow(BOOK, day, value, 10.0, value - 10.0, 99.99, pricesFinal);
    }

    @Test
    @DisplayName("테이블이 있다 — V8이 마지막에 만드는 paper_api_usage와 V9·V10이 더하는 열로 준비 여부를 본다")
    void ready() {
        assertThat(store.ready()).as("make db-test로 V8~V10을 적용했는가").isTrue();
        assertThat(new SchemaCheck(jdbc).columnExists("paper_decisions", "packet_text")).isTrue();
    }

    @Test
    @DisplayName("V8만 적용된 DB(paper_track_record.data_source 열 없음)는 준비 안 됨 — 트랙레코드 SQL 오류(500) 대신 안내 문구, 시험 뒤 되돌림")
    void readyRequiresV9() {
        class Rollback extends RuntimeException {
        }
        assertThatThrownBy(() -> store.atomically(() -> {
            jdbc.execute("ALTER TABLE paper_track_record DROP COLUMN data_source");
            SchemaCheck schema = new SchemaCheck(jdbc);
            assertThat(schema.tableExists("paper_api_usage")).as("V8 테이블은 있음").isTrue();
            assertThat(schema.columnExists("paper_track_record", "data_source")).as("지운 열").isFalse();
            assertThat(new PaperStore(jdbc, MAPPER, schema).ready()).as("V9 전이면 준비 안 됨").isFalse();
            throw new Rollback();
        })).isInstanceOf(Rollback.class);

        SchemaCheck schema = new SchemaCheck(jdbc);
        assertThat(schema.columnExists("paper_track_record", "group_code")).isTrue();
        assertThat(schema.columnExists("paper_track_record", "data_source")).as("되돌린 뒤").isTrue();
        assertThat(schema.columnExists("paper_track_record", "no_such_column")).isFalse();
        assertThat(schema.columnExists("no_such_table", "data_source")).as("테이블이 없으면 false").isFalse();
        assertThat(new PaperStore(jdbc, MAPPER, schema).ready()).isTrue();
    }

    @Test
    @DisplayName("책은 한 번만 생긴다 — 두 번째 넣기는 false이고 처음 값이 남는다")
    void insertBookIfAbsentIsIdempotent() {
        assertThat(store.insertBookIfAbsent(book(BOOK, "규칙 책"))).isTrue();
        assertThat(store.insertBookIfAbsent(book(BOOK, "다른 이름"))).isFalse();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paper_books WHERE code = ?", Integer.class, BOOK))
                .isEqualTo(1);
        PaperStore.Book saved = store.book(BOOK).orElseThrow();
        assertThat(saved.label()).isEqualTo("규칙 책");
        assertThat(saved.initialCash()).isEqualTo(100_000);
        assertThat(saved.startedOn()).as("시작 전이면 출발일 없음").isNull();
        assertThat(saved.createdAt()).as("비우면 DB가 채움").isNotNull();
        assertThat(store.book("t_none")).isEmpty();

        store.updateBookStatus(BOOK, "ACTIVE");
        store.updateBookStartedOn(BOOK, DAY);
        assertThat(store.book(BOOK).orElseThrow())
                .extracting(PaperStore.Book::status, PaperStore.Book::startedOn)
                .containsExactly("ACTIVE", DAY);
        store.updateBookStartedOn(BOOK, null);
        assertThat(store.book(BOOK).orElseThrow().startedOn()).isNull();
    }

    @Test
    @DisplayName("체결은 주문당 하나 — 같은 주문의 두 번째 체결은 DuplicateKeyException이고 행은 하나")
    void oneFillPerOrder() {
        store.insertBookIfAbsent(book(BOOK, "규칙 책"));
        long orderId = store.insertOrder(pendingBuy(BOOK, "SPY", null));
        long fillId = store.insertFill(fill(orderId, BOOK));
        assertThat(fillId).isPositive();

        assertThatThrownBy(() -> store.insertFill(fill(orderId, BOOK))).isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paper_fills WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);

        PaperStore.Fill saved = store.recentFills(BOOK, 10).get(0);
        assertThat(saved.orderId()).isEqualTo(orderId);
        assertThat(saved.price()).isEqualTo(680.34);
        assertThat(saved.gross()).isEqualTo(100_009.98);
        assertThat(saved.observedAt()).isEqualTo(CLOSE);
        assertThat(saved.observationDate()).isEqualTo(DAY);
        assertThat(saved.backfill()).isTrue();
    }

    @Test
    @DisplayName("확정 순자산은 미확정 값으로 덮이지 않는다 — 미확정은 확정으로, 확정은 새 확정으로 바뀐다")
    void finalNavIsNotOverwrittenByProvisional() {
        store.insertBookIfAbsent(book(BOOK, "규칙 책"));
        LocalDate next = DAY.plusDays(3);

        store.upsertNav(nav(DAY, 100_000, true));
        store.upsertNav(nav(DAY, 99_000, false));              // 다음 날 새벽의 묵은 관측치
        store.upsertNav(nav(next, 101_000, false));
        store.upsertNav(nav(next, 101_500, true));             // 장 마감 뒤 확정

        List<PaperStore.NavRow> series = store.navSeries(BOOK);
        assertThat(series).extracting(PaperStore.NavRow::tradingDate).containsExactly(DAY, next);
        assertThat(series.get(0).nav()).as("확정값이 남음").isEqualTo(100_000);
        assertThat(series.get(0).pricesFinal()).isTrue();
        assertThat(series.get(1).nav()).isEqualTo(101_500);
        assertThat(series.get(1).pricesFinal()).isTrue();

        store.upsertNav(nav(DAY, 100_100, true));              // 확정끼리는 새 값
        assertThat(store.navSeries(BOOK).get(0).nav()).isEqualTo(100_100);
        assertThat(store.navSeriesAll()).filteredOn(r -> r.bookCode().equals(BOOK)).hasSize(2);
    }

    @Test
    @DisplayName("atomically 안에서 예외가 나면 그 안의 쓰기가 모두 되돌려진다")
    void atomicallyRollsBackOnException() {
        assertThatThrownBy(() -> store.atomically(() -> {
            store.insertBookIfAbsent(book(BOOK, "규칙 책"));
            store.upsertState(new PaperStore.BookState(BOOK, 100_000, 100_000, 0, 0, null, null, null, null));
            throw new IllegalStateException("중간에 실패");
        })).isInstanceOf(IllegalStateException.class).hasMessage("중간에 실패");

        assertThat(store.book(BOOK)).isEmpty();
        assertThat(store.state(BOOK)).isEmpty();
    }

    @Test
    @DisplayName("트랜잭션 안의 중복 체결은 앞선 쓰기까지 함께 되돌린다 — 반쯤 적용된 관측치가 남지 않는다")
    void duplicateFillInsideTransactionRollsBackEverything() {
        store.insertBookIfAbsent(book(BOOK, "규칙 책"));
        long orderId = store.insertOrder(pendingBuy(BOOK, "SPY", null));
        store.insertFill(fill(orderId, BOOK));

        assertThatThrownBy(() -> store.atomically(() -> {
            store.markOrderFilled(orderId, CLOSE);
            return store.insertFill(fill(orderId, BOOK));
        })).isInstanceOf(DuplicateKeyException.class);

        assertThat(store.ordersByStatus(BOOK, "PENDING")).as("상태 변경도 되돌려짐").hasSize(1);
    }

    @Test
    @DisplayName("시계열 여러 개를 한 번에 — 범위(양끝 포함)·날짜 오름차순·NULL 건너뜀·없는 시계열은 키 없음")
    void readTimeseriesColumns() {
        // 일부러 날짜를 섞어 넣습니다(정렬은 DB가 해야 함).
        insertPoint("T_A", "2026-10-21", 10.5);
        insertPoint("T_A", "2026-10-19", 10.0);
        insertPoint("T_A", "2026-10-23", 11.0);
        insertPoint("T_A", "2026-10-22", null);                // 건너뜀
        insertPoint("T_A", "2026-10-26", 12.0);                // 범위 밖
        insertPoint("T_B", "2026-10-20", 200.0);
        insertPoint("T_C", "2026-10-20", null);                // 값이 하나도 없는 시계열

        Map<String, StoreRepository.TimeseriesColumns> got = repository.readTimeseriesColumns(TS_DATASET,
                List.of("T_B", "T_A", "T_C", "T_MISSING", "T_A"), LocalDate.of(2026, 10, 19), DAY);

        assertThat(got).containsOnlyKeys("T_A", "T_B");
        assertThat(got.keySet()).as("series_id 순").containsExactly("T_A", "T_B");
        StoreRepository.TimeseriesColumns a = got.get("T_A");
        assertThat(a.epochDays()).containsExactly(epoch("2026-10-19"), epoch("2026-10-21"), epoch("2026-10-23"));
        assertThat(a.values()).containsExactly(10.0, 10.5, 11.0);
        assertThat(a.size()).isEqualTo(3);
        assertThat(LocalDate.ofEpochDay(a.epochDays()[0])).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(got.get("T_B").values()).containsExactly(200.0);

        // 범위를 비우면 제한 없음, 한쪽만 주면 그쪽만.
        assertThat(repository.readTimeseriesColumns(TS_DATASET, List.of("T_A"), null, null).get("T_A").values())
                .containsExactly(10.0, 10.5, 11.0, 12.0);
        assertThat(repository.readTimeseriesColumns(TS_DATASET, List.of("T_A"), LocalDate.of(2026, 10, 23), null)
                .get("T_A").values()).containsExactly(11.0, 12.0);
        assertThat(repository.readTimeseriesColumns(TS_DATASET, List.of("T_A"), null, LocalDate.of(2026, 10, 19))
                .get("T_A").values()).containsExactly(10.0);
        assertThat(repository.readTimeseriesColumns(TS_DATASET, List.of(), null, null)).isEmpty();
        assertThat(repository.readTimeseriesColumns("t_other_dataset", List.of("T_A"), null, null)).isEmpty();
    }

    @Test
    @DisplayName("버퍼보다 긴 시계열도 빠짐없이 — 10년치(2,600점)가 그대로 온다")
    void readTimeseriesColumnsLongSeries() {
        LocalDate start = LocalDate.of(2016, 10, 24);
        int points = 2_600;
        jdbc.update("INSERT INTO timeseries (dataset, series_id, obs_date, value) "
                + "SELECT ?, 'T_LONG', ?::date + g, g FROM generate_series(0, ?) AS g", TS_DATASET,
                java.sql.Date.valueOf(start), points - 1);

        StoreRepository.TimeseriesColumns got = repository
                .readTimeseriesColumns(TS_DATASET, List.of("T_LONG"), null, null).get("T_LONG");

        assertThat(got.size()).isEqualTo(points);
        assertThat(got.epochDays()[0]).isEqualTo((int) start.toEpochDay());
        assertThat(got.epochDays()[points - 1]).isEqualTo((int) start.plusDays(points - 1).toEpochDay());
        assertThat(got.values()[points - 1]).isEqualTo(points - 1);
    }

    @Test
    @DisplayName("장부 트랜잭션 안에서 시계열을 읽고 이어서 써도 된다 — 읽기 전용 트랜잭션이 바깥 쓰기를 막지 않는다")
    void readTimeseriesColumnsJoinsOuterTransaction() {
        insertPoint("T_A", "2026-10-23", 11.0);

        store.atomically(() -> {
            store.insertBookIfAbsent(book(BOOK, "규칙 책"));
            assertThat(repository.readTimeseriesColumns(TS_DATASET, List.of("T_A"), DAY, DAY)).containsOnlyKeys("T_A");
            store.upsertState(new PaperStore.BookState(BOOK, 1, 1, 0, 0, null, CLOSE, null, null));
            return null;
        });

        assertThat(store.state(BOOK).orElseThrow().lastProcessedCollectedAt()).isEqualTo(CLOSE);
    }

    @Test
    @DisplayName("작업 기록은 작업마다 최근 200건만 남는다 — 다른 작업의 기록은 건드리지 않는다")
    void recordJobRunKeepsRecent200PerJob() {
        // 다른 테스트가 지금 시각으로 남긴 기록보다 뒤에 오도록 먼 미래 시각을 씁니다(최근 순 조회 검사).
        Instant base = Instant.parse("2099-01-01T00:00:00Z");
        JsonNode detail = MAPPER.valueToTree(Map.of("tickers", 572));
        for (int i = 0; i < 3; i++) {
            store.recordJobRun("t_other", base.plusSeconds(i), base.plusSeconds(i + 1), "ok", detail);
        }
        for (int i = 0; i < 205; i++) {
            store.recordJobRun("t_risk", base.plusSeconds(60L * i), base.plusSeconds(60L * i + 5), "ok",
                    i == 204 ? detail : null);
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paper_job_runs WHERE job = 't_risk'", Integer.class))
                .isEqualTo(PaperStore.JOB_RUNS_KEPT);
        assertThat(jdbc.queryForObject("SELECT MIN(started_at) FROM paper_job_runs WHERE job = 't_risk'",
                Timestamp.class).toInstant()).as("가장 오래된 5건이 지워짐").isEqualTo(base.plusSeconds(60L * 5));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paper_job_runs WHERE job = 't_other'", Integer.class))
                .isEqualTo(3);

        List<PaperStore.JobRun> recent = store.recentJobRuns(2);
        assertThat(recent).extracting(PaperStore.JobRun::startedAt)
                .containsExactly(base.plusSeconds(60L * 204), base.plusSeconds(60L * 203));
        assertThat(recent.get(0).job()).isEqualTo("t_risk");
        assertThat(recent.get(0).finishedAt()).isEqualTo(base.plusSeconds(60L * 204 + 5));
        assertThat(recent.get(0).detail().path("tickers").asInt()).isEqualTo(572);
        assertThat(recent.get(1).detail().isObject() && recent.get(1).detail().isEmpty())
                .as("detail이 null이면 {}").isTrue();
    }

    @Test
    @DisplayName("주문: 넣은 값이 그대로 돌아오고, 만료·취소·체결 표시는 조건에 맞는 것만 바꾼다")
    void ordersLifecycle() {
        store.insertBookIfAbsent(book(BOOK, "규칙 책"));
        store.insertBookIfAbsent(book(OTHER_BOOK, "벤치마크"));
        // 사후 체결 주문은 시각을 직접 줍니다. 순서 검사가 DB 시계에 기대지 않게 지금보다 확실히 앞선 시각을 씁니다.
        Instant backfillAt = Instant.parse("2001-01-01T00:00:00Z");
        long backfill = store.insertOrder(new PaperStore.Order(0, BOOK, null, "SPY", "BUY", 147, null, null,
                "BACKFILL", "BENCHMARK", "PENDING", null, MAPPER.valueToTree(Map.of("note", "사후 체결")),
                DAY.minusDays(1), backfillAt, null, null));
        long first = store.insertOrder(pendingBuy(BOOK, "XLK", null));
        long proposed = store.insertOrder(new PaperStore.Order(0, BOOK, null, "XLE", "SELL", null, null, 0.5,
                "NEXT_CLOSE", "CLAUDE", "PROPOSED", null, null, DAY, null, DAY.plusDays(5), null));
        long other = store.insertOrder(pendingBuy(OTHER_BOOK, "SPY", null));

        PaperStore.Order savedBackfill = store.ordersByStatus(BOOK, "PENDING").get(0);
        assertThat(savedBackfill.id()).as("created_at 순 — 사후 체결 주문이 앞").isEqualTo(backfill);
        assertThat(savedBackfill.createdAt()).as("준 시각을 씀").isEqualTo(backfillAt);
        assertThat(savedBackfill.qty()).isEqualTo(147);
        assertThat(savedBackfill.targetWeightPct()).as("없는 값은 null").isNull();
        assertThat(savedBackfill.decisionId()).isNull();
        assertThat(savedBackfill.expiresOn()).isNull();
        assertThat(savedBackfill.reason().path("note").asText()).isEqualTo("사후 체결");
        PaperStore.Order savedFirst = store.ordersByStatus(BOOK, "PENDING").get(1);
        assertThat(savedFirst.id()).isEqualTo(first);
        assertThat(savedFirst.qty()).isNull();
        assertThat(savedFirst.targetWeightPct()).isEqualTo(10.0);
        assertThat(savedFirst.reason().isObject() && savedFirst.reason().isEmpty()).as("reason이 null이면 {}").isTrue();
        assertThat(store.ordersByStatus(BOOK, "PROPOSED").get(0).sellFraction()).isEqualTo(0.5);

        store.markOrderFilled(backfill, CLOSE);
        assertThat(store.expireOrders(BOOK, DAY.plusDays(6)))
                .as("PENDING이고 기한이 지난 것만(기한 없는 주문·PROPOSED·다른 책 제외)").isEqualTo(1);
        assertThat(store.cancelPendingOrders(BOOK)).as("PROPOSED만 남아 있음").isEqualTo(1);
        store.markOrderStatus(other, "REJECTED", "OVER_ALLOCATED");

        List<PaperStore.Order> recent = store.recentOrders(BOOK, 10);
        assertThat(recent).extracting(PaperStore.Order::id).containsExactly(proposed, first, backfill);
        assertThat(recent).extracting(PaperStore.Order::status).containsExactly("CANCELLED", "EXPIRED", "FILLED");
        assertThat(recent.get(2).filledAt()).isEqualTo(CLOSE);
        assertThat(store.recentOrders(BOOK, 1)).hasSize(1);
        PaperStore.Order rejected = store.recentOrders(OTHER_BOOK, 10).get(0);
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectReason()).isEqualTo("OVER_ALLOCATED");
    }

    @Test
    @DisplayName("상태·보유·재진입 금지: 넣고 바꾸고 지운 값이 그대로 읽힌다(빈 값은 null)")
    void stateAndPositionsRoundTrip() {
        store.insertBookIfAbsent(book(BOOK, "규칙 책"));
        store.upsertState(new PaperStore.BookState(BOOK, 100_000, 100_000, 0, 0, null, null, null, null));
        assertThat(store.state(BOOK).orElseThrow().lastProcessedCollectedAt()).isNull();
        store.upsertState(new PaperStore.BookState(BOOK, 50_000.1234, 100_500, 50_499.8766, 50.2486, CLOSE, CLOSE, DAY,
                null));
        PaperStore.BookState state = store.state(BOOK).orElseThrow();
        assertThat(state.cash()).isEqualTo(50_000.1234);
        assertThat(state.lastProcessedCollectedAt()).isEqualTo(CLOSE);
        assertThat(state.lastNavTradingDate()).isEqualTo(DAY);
        assertThat(state.updatedAt()).isNotNull();

        store.upsertPosition(new PaperStore.Position(BOOK, "XLK", 10, 250.5, DAY, CLOSE, 252, CLOSE, null, null, true,
                DAY, 250.123456, null));
        store.upsertPosition(new PaperStore.Position(BOOK, "AAPL", 3, 230, DAY, CLOSE, 230, CLOSE, 231.5, CLOSE, false,
                DAY, 230, "2:1 분할 반영"));
        List<PaperStore.Position> positions = store.positions(BOOK);
        assertThat(positions).extracting(PaperStore.Position::ticker).containsExactly("AAPL", "XLK");
        PaperStore.Position xlk = positions.get(1);
        assertThat(xlk.lastPrice()).as("가격을 못 받았으면 null").isNull();
        assertThat(xlk.lastPriceAt()).isNull();
        assertThat(xlk.priceStale()).isTrue();
        assertThat(xlk.fillRefClose()).isEqualTo(250.123456);
        assertThat(positions.get(0).splitNote()).isEqualTo("2:1 분할 반영");

        store.upsertPosition(new PaperStore.Position(BOOK, "XLK", 12, 251, DAY, CLOSE, 260, CLOSE, 259.9, CLOSE, false,
                DAY, 250.123456, null));
        assertThat(store.positions(BOOK).get(1)).extracting(PaperStore.Position::qty, PaperStore.Position::lastPrice)
                .containsExactly(12, 259.9);
        store.deletePosition(BOOK, "AAPL");
        assertThat(store.positions(BOOK)).extracting(PaperStore.Position::ticker).containsExactly("XLK");

        store.upsertLockout(new PaperStore.Lockout(BOOK, "XLE", DAY.plusDays(10), "트레일링 스탑"));
        store.upsertLockout(new PaperStore.Lockout(BOOK, "XLE", DAY.plusDays(30), "트레일링 스탑(다시)"));
        store.upsertLockout(new PaperStore.Lockout(BOOK, "XLB", DAY, "트레일링 스탑"));
        assertThat(store.lockouts(BOOK)).extracting(PaperStore.Lockout::ticker).containsExactly("XLB", "XLE");
        assertThat(store.lockouts(BOOK).get(1).untilTradingDate()).as("새 기한으로 덮음").isEqualTo(DAY.plusDays(30));
        assertThat(store.deleteLockoutsBefore(BOOK, DAY)).as("기한 당일까지는 남김").isZero();
        assertThat(store.deleteLockoutsBefore(BOOK, DAY.plusDays(1))).isEqualTo(1);
        assertThat(store.lockouts(BOOK)).extracting(PaperStore.Lockout::ticker).containsExactly("XLE");
    }

    @Test
    @DisplayName("출발일 종가는 같은 거래일이면 처음 값이 남고(배당락 뒤 다시 넣어도), 다른 거래일 행만 바뀐다")
    void startPriceIsFrozenPerTradingDate() {
        Instant first = Instant.parse("2026-10-24T03:00:00Z");
        assertThat(store.insertStartPriceIfAbsent(new PaperStore.StartPrice("T_SPY", DAY, 680.123456, "equity_close",
                first))).isTrue();
        assertThat(store.insertStartPriceIfAbsent(new PaperStore.StartPrice("T_SPY", DAY, 676.5, "equity_close",
                null))).as("배당락으로 낮아진 수정 종가 — 덮지 않음").isFalse();
        assertThat(store.insertStartPriceIfAbsent(new PaperStore.StartPrice("T_SHY", DAY, 82.5, "paper.quotes",
                null))).isTrue();

        PaperStore.StartPrice spy = store.startPrice("T_SPY").orElseThrow();
        assertThat(spy.close()).isEqualTo(680.123456);
        assertThat(spy.tradingDate()).isEqualTo(DAY);
        assertThat(spy.source()).isEqualTo("equity_close");
        assertThat(spy.capturedAt()).isEqualTo(first);
        assertThat(store.startPrice("T_SHY").orElseThrow().capturedAt()).as("비우면 DB가 채움").isNotNull();
        assertThat(store.startPrice("T_NONE")).isEmpty();
        assertThat(store.startPrices()).filteredOn(p -> p.ticker().startsWith("T_"))
                .extracting(PaperStore.StartPrice::ticker).containsExactly("T_SHY", "T_SPY");

        // 출발일 설정을 바꾼 경우 — 옛 날짜의 값은 새 날짜에 쓸 수 없어 바꿉니다.
        LocalDate other = DAY.plusDays(3);
        assertThat(store.insertStartPriceIfAbsent(new PaperStore.StartPrice("T_SPY", other, 690, "paper.quotes",
                null))).isTrue();
        assertThat(store.startPrice("T_SPY").orElseThrow())
                .extracting(PaperStore.StartPrice::tradingDate, PaperStore.StartPrice::close)
                .containsExactly(other, 690.0);
    }

    @Test
    @DisplayName("주문 근거를 통째로 바꾼다 — 현금 부족으로 줄인 매수의 cashLimited, null이면 {}")
    void updateOrderReason() {
        store.insertBookIfAbsent(book(BOOK, "규칙 책"));
        long id = store.insertOrder(new PaperStore.Order(0, BOOK, null, "XLK", "BUY", 100, null, null,
                "NEXT_OBSERVATION", "RULE", "PENDING", null, MAPPER.valueToTree(Map.of("note", "신호")), DAY, null,
                DAY.plusDays(5), null));

        store.updateOrderReason(id, MAPPER.valueToTree(Map.of("note", "신호", "cashLimited", true,
                "requestedQty", 100, "filledQty", 40)));
        JsonNode reason = store.recentOrders(BOOK, 1).get(0).reason();
        assertThat(reason.path("cashLimited").asBoolean()).isTrue();
        assertThat(reason.path("requestedQty").asInt()).isEqualTo(100);
        assertThat(reason.path("filledQty").asInt()).isEqualTo(40);
        assertThat(reason.path("note").asString()).isEqualTo("신호");

        store.updateOrderReason(id, null);
        JsonNode cleared = store.recentOrders(BOOK, 1).get(0).reason();
        assertThat(cleared.isObject() && cleared.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("NaN 금액은 저장하지 않는다 — NUMERIC이 받아 주는 계산 오류를 입구에서 막음")
    void nonFiniteMoneyIsRejected() {
        store.insertBookIfAbsent(book(BOOK, "규칙 책"));
        assertThatThrownBy(() -> store.upsertNav(nav(DAY, Double.NaN, true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nav");
        assertThat(store.navSeries(BOOK)).isEmpty();
    }

    @Test
    @DisplayName("책 초기화는 그 책의 장부만 비우고 책 행과 다른 책은 남긴다")
    void resetBookClearsOnlyThatLedger() {
        for (String code : List.of(BOOK, OTHER_BOOK)) {
            store.insertBookIfAbsent(book(code, code));
            store.upsertState(new PaperStore.BookState(code, 1, 1, 0, 0, null, null, null, null));
            long orderId = store.insertOrder(pendingBuy(code, "SPY", null));
            store.insertFill(fill(orderId, code));
            store.upsertPosition(new PaperStore.Position(code, "SPY", 1, 1, DAY, CLOSE, 1, CLOSE, null, null, false,
                    DAY, 1, null));
            store.upsertLockout(new PaperStore.Lockout(code, "XLE", DAY, "스탑"));
            store.upsertNav(new PaperStore.NavRow(code, DAY, 1, 1, 0, 0, true));
        }
        store.updateBookStatus(BOOK, "ACTIVE");

        store.resetBook(BOOK);

        assertThat(store.book(BOOK).orElseThrow().status()).as("책 행·상태는 호출자가 맞춤").isEqualTo("ACTIVE");
        assertThat(store.state(BOOK)).isEmpty();
        assertThat(store.recentOrders(BOOK, 10)).isEmpty();
        assertThat(store.recentFills(BOOK, 10)).isEmpty();
        assertThat(store.positions(BOOK)).isEmpty();
        assertThat(store.lockouts(BOOK)).isEmpty();
        assertThat(store.navSeries(BOOK)).isEmpty();

        assertThat(store.state(OTHER_BOOK)).isPresent();
        assertThat(store.recentFills(OTHER_BOOK, 10)).hasSize(1);
        assertThat(store.navSeries(OTHER_BOOK)).hasSize(1);
    }

    @Test
    @DisplayName("설정: 초기화는 한 번(버전 1·이력 1), 저장은 버전 +1과 이력 — 시험 뒤 되돌림")
    void settingsVersioning() {
        // 설정은 한 행뿐이라 다른 테스트가 남긴 행을 건드리지 않도록 되돌리는 트랜잭션 안에서만 시험합니다.
        class Rollback extends RuntimeException {
        }
        assertThatThrownBy(() -> store.atomically(() -> {
            jdbc.update("DELETE FROM paper_settings_history");
            jdbc.update("DELETE FROM paper_settings");
            assertThat(store.settings()).isEmpty();

            JsonNode body = MAPPER.valueToTree(Map.of("slippageBp", 5, "trailingStopPct", 8));
            assertThat(store.initSettingsIfAbsent(body)).isTrue();
            assertThat(store.initSettingsIfAbsent(MAPPER.valueToTree(Map.of("slippageBp", 99)))).isFalse();
            PaperStore.SettingsRow first = store.settings().orElseThrow();
            assertThat(first.version()).isEqualTo(1);
            assertThat(first.body().path("slippageBp").asInt()).isEqualTo(5);

            int version = store.saveSettings(MAPPER.valueToTree(Map.of("slippageBp", 7, "trailingStopPct", 8)),
                    List.of("slippageBp"));
            assertThat(version).isEqualTo(2);
            assertThat(store.settings().orElseThrow().body().path("slippageBp").asInt()).isEqualTo(7);
            assertThat(jdbc.queryForList("SELECT version, array_to_string(changed_keys, ',') AS keys "
                    + "FROM paper_settings_history ORDER BY version"))
                    .extracting(row -> row.get("version") + ":" + row.get("keys"))
                    .containsExactly("1:", "2:slippageBp");
            throw new Rollback();
        })).isInstanceOf(Rollback.class);
    }

    @Test
    @DisplayName("설정 이력: 최근 버전 순으로 본문·바뀐 키·시각 — 시험 뒤 되돌림")
    void settingsHistoryIsNewestFirst() {
        class Rollback extends RuntimeException {
        }
        assertThatThrownBy(() -> store.atomically(() -> {
            jdbc.update("DELETE FROM paper_settings_history");
            jdbc.update("DELETE FROM paper_settings");
            store.initSettingsIfAbsent(MAPPER.valueToTree(Map.of("slippageBp", 5)));
            store.saveSettings(MAPPER.valueToTree(Map.of("slippageBp", 6)), List.of("slippageBp"));
            store.saveSettings(MAPPER.valueToTree(Map.of("slippageBp", 6, "model", "claude-haiku-5-5")),
                    List.of("model", "effort"));

            List<PaperStore.SettingsHistoryRow> history = store.settingsHistory(10);
            assertThat(history).extracting(PaperStore.SettingsHistoryRow::version).containsExactly(3, 2, 1);
            assertThat(history.get(0).changedKeys()).containsExactly("model", "effort");
            assertThat(history.get(0).body().path("model").asString()).isEqualTo("claude-haiku-5-5");
            assertThat(history.get(2).changedKeys()).as("처음 값").isEmpty();
            assertThat(history.get(2).createdAt()).isNotNull();
            assertThat(store.settingsHistory(1)).hasSize(1);
            throw new Rollback();
        })).isInstanceOf(Rollback.class);
    }

    @Test
    @DisplayName("Claude 호출 사용량: 상한 안에서만 호출을 잡고(한 문장), 되돌리기는 0 아래로 안 가고, 토큰은 아는 값만 더하고, 기간 합계")
    void apiUsageReservesWithinCap() {
        LocalDate day = LocalDate.of(1999, 1, 4);
        LocalDate next = LocalDate.of(1999, 1, 5);
        assertThat(store.apiUsage(day)).isEmpty();
        assertThat(store.reserveApiCall(day, 0)).as("상한 0").isFalse();
        assertThat(store.apiUsage(day)).as("상한 0이면 행도 만들지 않음").isEmpty();

        assertThat(store.reserveApiCall(day, 2)).isTrue();
        assertThat(store.reserveApiCall(day, 2)).isTrue();
        assertThat(store.reserveApiCall(day, 2)).as("세 번째는 상한").isFalse();
        assertThat(store.apiUsage(day).orElseThrow().calls()).isEqualTo(2);

        store.addApiTokens(day, 21_000L, 3_100L, null, 0L);
        store.addApiTokens(day, 1_000L, null, 7_800L, null);
        PaperStore.ApiUsage usage = store.apiUsage(day).orElseThrow();
        assertThat(usage.calls()).as("토큰 더하기는 호출 수를 바꾸지 않음").isEqualTo(2);
        assertThat(usage.inputTokens()).isEqualTo(22_000);
        assertThat(usage.outputTokens()).isEqualTo(3_100);
        assertThat(usage.cacheReadTokens()).isEqualTo(7_800);
        assertThat(usage.cacheWriteTokens()).isZero();

        store.releaseApiCall(day);
        store.releaseApiCall(day);
        store.releaseApiCall(day);
        assertThat(store.apiUsage(day).orElseThrow().calls()).as("0 아래로는 내리지 않음").isZero();

        store.addApiTokens(next, 5L, 6L, null, null);   // 행이 없으면 호출 0으로 만듦
        assertThat(store.apiUsage(next).orElseThrow().calls()).isZero();
        assertThat(store.reserveApiCall(next, 1)).isTrue();
        PaperStore.ApiUsageTotal total = store.apiUsageBetween(LocalDate.of(1999, 1, 1), LocalDate.of(1999, 1, 31));
        assertThat(total.days()).isEqualTo(2);
        assertThat(total.calls()).isEqualTo(1);
        assertThat(total.inputTokens()).isEqualTo(22_005);
        assertThat(total.outputTokens()).isEqualTo(3_106);
        PaperStore.ApiUsageTotal empty = store.apiUsageBetween(LocalDate.of(1998, 1, 1), LocalDate.of(1998, 1, 31));
        assertThat(empty.days()).isZero();
        assertThat(empty.calls()).isZero();
    }

    @Test
    @DisplayName("결정 재시도: API 오류·재시도 전 행만 통째로 바꾸고 재시도 수 1 — 두 번째·다른 상태는 바꾸지 않음")
    void decisionRetryUpdatesOnlyOnce() throws Exception {
        store.insertBookIfAbsent(book(BOOK, "Claude 책 시험"));
        long id = store.insertDecisionIfAbsent(new PaperStore.Decision(0, BOOK, DAY, "SCHEDULED", null, "API_ERROR",
                "claude-sonnet-5-5", null, 1, null, null, MAPPER.readTree("{\"p\":1}"), "h1", null, null, null,
                "[RATE_LIMIT] 429", List.of(), true, 12L, 0)).orElseThrow();
        long ok = store.insertDecisionIfAbsent(new PaperStore.Decision(0, BOOK, DAY, "MANUAL", null, "OK", null, null, 1,
                null, null, null, null, null, null, null, null, null, true, 1L, 0)).orElseThrow();

        PaperStore.Decision retry = new PaperStore.Decision(id, BOOK, DAY, "SCHEDULED", null, "OK",
                "claude-haiku-5-5", 3, 2, DAY, 42.5, MAPPER.readTree("{\"p\":2}"), "h2", "{\"raw\":true}",
                MAPPER.readTree("{\"summary\":\"두 번째\"}"), MAPPER.readTree("{\"inputTokens\":9}"), null,
                List.of(4L), false, 99L, 0);
        assertThat(store.updateDecisionRetry(retry)).isTrue();
        PaperStore.Decision saved = store.decision(id).orElseThrow();
        assertThat(saved.status()).isEqualTo("OK");
        assertThat(saved.retryCount()).isEqualTo(1);
        assertThat(saved.model()).isEqualTo("claude-haiku-5-5");
        assertThat(saved.frameworkVersion()).isEqualTo(3);
        assertThat(saved.settingsVersion()).isEqualTo(2);
        assertThat(saved.selectionAsOf()).isEqualTo(DAY);
        assertThat(saved.targetExposurePct()).isEqualTo(42.5);
        assertThat(saved.packet().path("p").asInt()).isEqualTo(2);
        assertThat(saved.packetHash()).isEqualTo("h2");
        assertThat(saved.responseRaw()).isEqualTo("{\"raw\":true}");
        assertThat(saved.response().path("summary").asString()).isEqualTo("두 번째");
        assertThat(saved.usage().path("inputTokens").asInt()).isEqualTo(9);
        assertThat(saved.error()).isNull();
        assertThat(saved.noteIds()).containsExactly(4L);
        assertThat(saved.pricesFinal()).isFalse();
        assertThat(saved.latencyMs()).isEqualTo(99L);
        assertThat(saved.kind()).isEqualTo("SCHEDULED");

        assertThat(store.updateDecisionRetry(retry)).as("이미 재시도함").isFalse();
        assertThat(store.updateDecisionRetry(new PaperStore.Decision(ok, BOOK, DAY, "MANUAL", null, "API_ERROR", null,
                null, 1, null, null, null, null, null, null, null, null, null, true, 1L, 0))).as("API 오류가 아님")
                .isFalse();
        assertThat(store.decision(ok).orElseThrow().status()).isEqualTo("OK");
    }

    @Test
    @DisplayName("패킷 문자열(V10): 보낸 글자 그대로 남아 SHA-256을 기록만으로 다시 만들고, 패킷은 보낸 섹션 순서로 읽힌다 — JSONB는 키를 다시 정렬함")
    void packetTextKeepsSentBytes() throws Exception {
        store.insertBookIfAbsent(book(BOOK, "Claude 책 시험"));
        // JSONB는 키를 길이·바이트 순으로 정렬합니다 — "0_instructions"(14자)가 "1_basis"(7자) 뒤로 갑니다.
        String sent = "{\"0_instructions\":{\"lines\":[\"가\"]},\"1_basis\":{\"asOf\":\"2026-10-23\"},\"12_hardRules\":1.50}";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(sent.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        long id = store.insertDecisionIfAbsent(new PaperStore.Decision(0, BOOK, DAY, "SCHEDULED", null, "API_ERROR",
                "claude-sonnet-5-5", null, 1, null, null, MAPPER.readTree(sent), hash, null, null, null, "[RATE_LIMIT] 429",
                List.of(), true, 12L, 0, sent)).orElseThrow();

        PaperStore.Decision saved = store.decision(id).orElseThrow();
        assertThat(saved.packetText()).isEqualTo(sent);
        assertThat(jdbc.queryForObject("SELECT encode(sha256(convert_to(packet_text, 'UTF8')), 'hex') FROM "
                + "paper_decisions WHERE id = ?", String.class, id)).as("기록만으로 해시 재현").isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT encode(sha256(convert_to(packet::text, 'UTF8')), 'hex') FROM "
                + "paper_decisions WHERE id = ?", String.class, id)).as("JSONB로는 재현되지 않음").isNotEqualTo(hash);
        List<String> sections = new java.util.ArrayList<>();
        saved.packet().propertyNames().forEach(sections::add);
        assertThat(sections).as("보낸 섹션 순서").containsExactly("0_instructions", "1_basis", "12_hardRules");

        // 재시도도 패킷 문자열을 바꿔 씁니다.
        String resent = "{\"0_instructions\":2}";
        assertThat(store.updateDecisionRetry(new PaperStore.Decision(id, BOOK, DAY, "SCHEDULED", null, "OK",
                "claude-sonnet-5-5", null, 1, null, null, MAPPER.readTree(resent), "h2", null, null, null, null,
                List.of(), true, 5L, 0, resent))).isTrue();
        assertThat(store.decision(id).orElseThrow().packetText()).isEqualTo(resent);

        // 문자열이 없는 기록(규칙 책·V10 전)은 JSONB에서 읽고 packetText는 null.
        long legacy = store.insertDecisionIfAbsent(new PaperStore.Decision(0, BOOK, DAY, "MANUAL", null, "OK", null,
                null, 1, null, null, MAPPER.readTree(sent), "h3", null, null, null, null, null, true, 1L, 0))
                .orElseThrow();
        PaperStore.Decision old = store.decision(legacy).orElseThrow();
        assertThat(old.packetText()).isNull();
        assertThat(old.packet().path("1_basis").path("asOf").asString()).isEqualTo("2026-10-23");
    }

    private static PaperStore.TrackRecordRow trackRow(LocalDate asOf, String cik, Integer rank, Double cagr,
                                                      JsonNode periods) {
        boolean eligible = rank != null;
        return new PaperStore.TrackRecordRow(asOf, cik, 7, "CAGR", eligible ? 26 : 0, eligible ? 27 : 0,
                eligible ? LocalDate.of(2019, 8, 14) : null, eligible ? LocalDate.of(2026, 8, 14) : null,
                cagr == null ? null : cagr * 2, cagr, eligible ? 18.5 : null, eligible ? -22.25 : null,
                eligible ? 0.71 : null, eligible ? 3.5 : null, eligible ? 0.62 : null, eligible ? 0.81 : null,
                eligible, eligible ? null : "수집 중(저장 분기 0/30); 공시 없음", rank, periods, 1, null);
    }

    @Test
    @DisplayName("트랙레코드·선정: (기준일, CIK) 덮어쓰기, 없는 수치는 NULL 그대로, 순위 순, 선정은 한 번 — 시험 뒤 되돌림")
    void trackRecordAndSelection() {
        // 선정은 기준일이 키이고 '가장 최근 기준일'을 묻는 메서드가 있어, 다른 테스트의 행과 섞이지 않도록 지운 뒤
        // 되돌리는 트랜잭션 안에서만 시험합니다(설정 시험과 같은 방식).
        class Rollback extends RuntimeException {
        }
        LocalDate first = LocalDate.of(2026, 8, 17);
        LocalDate second = LocalDate.of(2026, 11, 17);
        assertThatThrownBy(() -> store.atomically(() -> {
            jdbc.update("DELETE FROM paper_selection_log");
            jdbc.update("DELETE FROM paper_track_record");
            assertThat(store.latestTrackRecordAsOf()).isEmpty();
            assertThat(store.selections()).isEmpty();

            JsonNode periods = MAPPER.readTree("[{\"filingDate\":\"2026-08-14\",\"start\":\"2026-08-17\","
                    + "\"returnPct\":1.5,\"spyReturnPct\":null,\"coverage\":0.8,\"coveredTickers\":12}]");
            store.upsertTrackRecord(trackRow(first, "t_b", 2, 10.0, periods));
            store.upsertTrackRecord(trackRow(first, "t_a", 1, 99.0, null));
            store.upsertTrackRecord(trackRow(first, "t_c", null, null, null));
            store.upsertTrackRecord(trackRow(first, "t_a", 1, 12.0, null));   // 같은 기준일 재계산 → 덮어씀

            List<PaperStore.TrackRecordRow> rows = store.trackRecords(first);
            assertThat(rows).extracting(PaperStore.TrackRecordRow::cik).as("순위 순, 순위 없는 기관은 뒤")
                    .containsExactly("t_a", "t_b", "t_c");
            assertThat(rows.get(0).cagrPct()).isEqualTo(12.0);
            assertThat(rows.get(0).cumReturnPct()).isEqualTo(24.0);
            assertThat(rows.get(0).mddPct()).isEqualTo(-22.25);
            assertThat(rows.get(0).periods().isArray() && rows.get(0).periods().isEmpty()).as("null → []").isTrue();
            assertThat(rows.get(0).computedAt()).as("비우면 DB가 채움").isNotNull();
            assertThat(rows.get(1).periods().get(0).path("coverage").asDouble()).isEqualTo(0.8);
            assertThat(rows.get(1).periods().get(0).path("spyReturnPct").isNull()).isTrue();
            PaperStore.TrackRecordRow c = rows.get(2);
            assertThat(c.rank()).isNull();
            assertThat(c.eligible()).isFalse();
            assertThat(c.cagrPct()).as("0이 아니라 NULL").isNull();
            assertThat(c.coverageMin()).isNull();
            assertThat(c.firstFilingDate()).isNull();
            assertThat(c.ineligibleReason()).isEqualTo("수집 중(저장 분기 0/30); 공시 없음");

            store.upsertTrackRecord(trackRow(second, "t_a", 1, 11.0, null));
            assertThat(store.latestTrackRecordAsOf()).contains(second);
            assertThat(store.deleteTrackRecords(first)).isEqualTo(3);
            assertThat(store.trackRecords(first)).isEmpty();
            assertThat(store.trackRecords(second)).hasSize(1);
            assertThatThrownBy(() -> store.upsertTrackRecord(trackRow(second, "t_d", 2, Double.POSITIVE_INFINITY,
                    null))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("유한한 수가 아닙니다");

            JsonNode ranking = MAPPER.readTree("[{\"cik\":\"t_b\",\"rank\":1},{\"cik\":\"t_a\",\"rank\":2}]");
            assertThat(store.insertSelectionIfAbsent(new PaperStore.Selection(first, List.of("t_b", "t_a"), ranking,
                    1, null))).isTrue();
            assertThat(store.insertSelectionIfAbsent(new PaperStore.Selection(first, List.of("t_c"), null, 2, null)))
                    .as("같은 기준일의 선정은 바꾸지 않음").isFalse();
            assertThat(store.insertSelectionIfAbsent(new PaperStore.Selection(second, List.of(), null, 1, null)))
                    .as("자격 기관 0곳 = 빈 선정").isTrue();

            assertThat(store.selections()).extracting(PaperStore.Selection::asOfDate).containsExactly(second, first);
            PaperStore.Selection saved = store.selectionOnOrBefore(second.minusDays(1)).orElseThrow();
            assertThat(saved.asOfDate()).isEqualTo(first);
            assertThat(saved.selectedCiks()).as("선정 순위 순 그대로").containsExactly("t_b", "t_a");
            assertThat(saved.ranking().get(0).path("cik").asString()).isEqualTo("t_b");
            assertThat(saved.settingsVersion()).isEqualTo(1);
            assertThat(saved.createdAt()).isNotNull();
            assertThat(store.selectionOnOrBefore(second).orElseThrow().selectedCiks()).isEmpty();
            assertThat(store.selectionOnOrBefore(second).orElseThrow().ranking().isArray()).isTrue();
            assertThat(store.selectionOnOrBefore(first.minusDays(1))).isEmpty();

            // 설정 이력 — 그 버전의 본문(트랙레코드가 어떤 기준으로 계산됐나).
            jdbc.update("DELETE FROM paper_settings_history");
            jdbc.update("DELETE FROM paper_settings");
            store.initSettingsIfAbsent(MAPPER.valueToTree(Map.of("trackRecord", Map.of("windowYears", 7))));
            store.saveSettings(MAPPER.valueToTree(Map.of("trackRecord", Map.of("windowYears", 5))),
                    List.of("trackRecord.windowYears"));
            assertThat(store.settingsAt(1).orElseThrow().body().path("trackRecord").path("windowYears").asInt())
                    .isEqualTo(7);
            assertThat(store.settingsAt(2).orElseThrow().body().path("trackRecord").path("windowYears").asInt())
                    .isEqualTo(5);
            assertThat(store.settingsAt(3)).isEmpty();
            throw new Rollback();
        })).isInstanceOf(Rollback.class);
    }

    /** trackRow를 그 그룹·창·출처로. */
    private static PaperStore.TrackRecordRow groupRow(LocalDate asOf, String cik, String group, int windowYears,
                                                      Integer rank, Double cagr, String source) {
        PaperStore.TrackRecordRow r = trackRow(asOf, cik, rank, cagr, null);
        return new PaperStore.TrackRecordRow(r.asOfDate(), r.cik(), windowYears, r.metric(), r.periodsUsed(),
                r.periodsTotal(), r.firstFilingDate(), r.lastFilingDate(), r.cumReturnPct(), r.cagrPct(), r.volPct(),
                r.mddPct(), r.sharpe(), r.excessVsSpyPct(), r.coverageMin(), r.coverageAvg(), r.eligible(),
                r.ineligibleReason(), r.rank(), r.periods(), r.settingsVersion(), r.computedAt(), group, source);
    }

    @Test
    @DisplayName("트랙레코드 그룹(V9): 같은 기준일·같은 기관도 그룹 A·B 행이 따로 — 그룹마다 덮어쓰고·읽고·지움, 그룹 없는 호출은 A")
    void trackRecordGroups() {
        class Rollback extends RuntimeException {
        }
        LocalDate asOf = LocalDate.of(2026, 8, 17);
        assertThatThrownBy(() -> store.atomically(() -> {
            jdbc.update("DELETE FROM paper_track_record");
            // 두 그룹에 모두 있는 기관(Duquesne처럼) — 창이 달라 수치가 다른 두 행
            store.upsertTrackRecord(trackRow(asOf, "t_duq", 1, 27.5, null));   // 그룹 없는 생성자 → A
            store.upsertTrackRecord(groupRow(asOf, "t_duq", "B", 2, 1, 26.8, "q8"));
            store.upsertTrackRecord(groupRow(asOf, "t_fis", "B", 2, 2, 15.4, "q8"));
            store.upsertTrackRecord(groupRow(asOf, "t_duq", "B", 2, 1, 26.9, "q30"));   // 같은 그룹 재계산 → 덮어씀

            assertThat(store.trackRecords(asOf)).as("그룹 없는 읽기 = A").singleElement().satisfies(r -> {
                assertThat(r.groupCode()).isEqualTo("A");
                assertThat(r.cagrPct()).isEqualTo(27.5);
                assertThat(r.windowYears()).isEqualTo(7);
                assertThat(r.dataSource()).as("단계 1b 모양 — 출처 모름").isNull();
            });
            List<PaperStore.TrackRecordRow> b = store.trackRecords(asOf, "B");
            assertThat(b).extracting(PaperStore.TrackRecordRow::cik).containsExactly("t_duq", "t_fis");
            assertThat(b.get(0).cagrPct()).isEqualTo(26.9);
            assertThat(b.get(0).windowYears()).isEqualTo(2);
            assertThat(b.get(0).dataSource()).isEqualTo("q30");
            assertThat(b.get(1).dataSource()).isEqualTo("q8");
            assertThat(b).allSatisfy(r -> assertThat(r.groupCode()).isEqualTo("B"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paper_track_record WHERE as_of_date = ?",
                    Integer.class, java.sql.Date.valueOf(asOf))).isEqualTo(3);

            store.upsertTrackRecord(groupRow(asOf.plusDays(92), "t_fis", "B", 2, 1, 14.0, "q8"));
            assertThat(store.latestTrackRecordAsOf()).as("A는 8-17까지").contains(asOf);
            assertThat(store.latestTrackRecordAsOf("B")).contains(asOf.plusDays(92));

            assertThat(store.deleteTrackRecords(asOf, "B")).isEqualTo(2);
            assertThat(store.trackRecords(asOf)).as("다른 그룹은 그대로").hasSize(1);
            assertThat(store.deleteTrackRecords(asOf)).isEqualTo(1);
            assertThat(store.trackRecords(asOf, "B")).isEmpty();

            assertThatThrownBy(() -> store.trackRecords(asOf, "C")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> groupRow(asOf, "t_x", "b", 2, 1, 1.0, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> groupRow(asOf, "t_x", "B", 2, 1, 1.0, "q1"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO paper_track_record (as_of_date, cik, window_years, metric, "
                    + "periods_used, periods_total, eligible, settings_version, group_code) "
                    + "VALUES (?, 't_x', 2, 'CAGR', 0, 0, false, 1, 'C')", java.sql.Date.valueOf(asOf)))
                    .as("DB CHECK도 A·B만").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            throw new Rollback();
        })).isInstanceOf(Rollback.class);
    }

    private static PaperStore.Decision decision(LocalDate day, String kind, String status, JsonNode response) {
        return new PaperStore.Decision(0, BOOK, day, kind, null, status, null, null, 3, DAY, null, null, null, null,
                response, null, null, null, false, 42L, 0);
    }

    @Test
    @DisplayName("결정: (책, 거래일, 종류)마다 한 건 — 두 번째 넣기는 empty, 목록은 요약·주문 수·거절 수, 상세는 JSON 그대로, 갱신은 상태·응답·오류")
    void decisionsAreUniquePerBookDayKind() throws Exception {
        store.insertBookIfAbsent(book(BOOK, "규칙 책"));
        JsonNode response = MAPPER.readTree("{\"summary\":\"매도 1 (선정 2026-10-23 · 5곳)\",\"orders\":[{\"ticker\":\"SPY\"}]}");
        long id = store.insertDecisionIfAbsent(decision(DAY, "SCHEDULED", "OK", response)).orElseThrow();
        assertThat(store.insertDecisionIfAbsent(decision(DAY, "SCHEDULED", "NO_TRADE", null))).as("같은 날·같은 종류")
                .isEmpty();
        long manual = store.insertDecisionIfAbsent(decision(DAY, "MANUAL", "NO_TRADE", null)).orElseThrow();
        long later = store.insertDecisionIfAbsent(new PaperStore.Decision(0, BOOK, DAY.plusDays(3), "REBALANCE", null,
                "OK", "claude-sonnet-5-5", 2, 3, null, 55.5, MAPPER.readTree("{\"a\":1}"), "abc", "raw", null,
                MAPPER.readTree("{\"inputTokens\":10}"), "경고", List.of(7L, 9L), true, null, 1)).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paper_decisions WHERE book_code = ?", Integer.class, BOOK))
                .isEqualTo(3);

        PaperStore.Order pending = pendingBuy(BOOK, "SPY", null);
        store.insertOrder(new PaperStore.Order(0, BOOK, id, "SPY", "SELL", null, null, null, "NEXT_OBSERVATION", "RULE",
                "PENDING", null, null, DAY, null, DAY.plusDays(5), null));
        store.insertOrder(new PaperStore.Order(0, BOOK, id, "QQQ", "BUY", null, 5.0, null, "NEXT_OBSERVATION", "RULE",
                "REJECTED", "상한 초과", null, DAY, null, DAY.plusDays(5), null));
        store.insertOrder(pending);   // 결정 없는 주문은 세지 않음

        List<PaperStore.DecisionSummary> list = store.decisions(BOOK, 10);
        assertThat(list).extracting(PaperStore.DecisionSummary::id).as("최근 거래일 순, 같은 날은 나중 것 먼저")
                .containsExactly(later, manual, id);
        PaperStore.DecisionSummary scheduled = list.get(2);
        assertThat(scheduled.summary()).isEqualTo("매도 1 (선정 2026-10-23 · 5곳)");
        assertThat(scheduled.orderCount()).isEqualTo(2);
        assertThat(scheduled.rejectedCount()).isEqualTo(1);
        assertThat(scheduled.settingsVersion()).isEqualTo(3);
        assertThat(scheduled.selectionAsOf()).isEqualTo(DAY);
        assertThat(scheduled.decidedAt()).as("비우면 DB가 채움").isNotNull();
        assertThat(list.get(1).summary()).as("응답 없음 → null").isNull();
        assertThat(store.decisions(BOOK, 1)).hasSize(1);
        assertThat(store.ordersByDecision(id)).extracting(PaperStore.Order::ticker).containsExactly("SPY", "QQQ");

        PaperStore.Decision full = store.decision(later).orElseThrow();
        assertThat(full.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(full.frameworkVersion()).isEqualTo(2);
        assertThat(full.targetExposurePct()).isEqualTo(55.5);
        assertThat(full.packet().path("a").asInt()).isEqualTo(1);
        assertThat(full.usage().path("inputTokens").asInt()).isEqualTo(10);
        assertThat(full.noteIds()).containsExactly(7L, 9L);
        assertThat(full.pricesFinal()).isTrue();
        assertThat(full.latencyMs()).isNull();
        assertThat(full.retryCount()).isEqualTo(1);
        PaperStore.Decision bare = store.decision(manual).orElseThrow();
        assertThat(bare.noteIds()).isNull();
        assertThat(bare.response()).isNull();
        assertThat(bare.latencyMs()).isEqualTo(42L);
        assertThat(store.decisionFor(BOOK, DAY, "SCHEDULED").orElseThrow().id()).isEqualTo(id);
        assertThat(store.decisionFor(BOOK, DAY, "INITIAL")).isEmpty();
        assertThat(store.decision(-1)).isEmpty();
        // 선정 기준일로 찾기 — SCHEDULED·MANUAL은 선정 DAY, 나중 REBALANCE는 선정 없음(null)
        assertThat(store.hasDecisionWithSelection(BOOK, List.of("SCHEDULED", "MANUAL"), DAY)).isTrue();
        assertThat(store.hasDecisionWithSelection(BOOK, List.of("REBALANCE", "INITIAL"), DAY))
                .as("REBALANCE는 있지만 선정 DAY로 낸 것이 아님").isFalse();
        assertThat(store.hasDecisionWithSelection(BOOK, List.of("SCHEDULED"), DAY.plusDays(1))).isFalse();
        assertThat(store.hasDecisionWithSelection(BOOK, List.of("SCHEDULED"), null)).isFalse();
        assertThat(store.hasDecisionWithSelection(BOOK, List.of(), DAY)).isFalse();
        long rebalanced = store.insertDecisionIfAbsent(new PaperStore.Decision(0, BOOK, DAY.plusDays(4), "REBALANCE",
                null, "OK", null, null, 3, DAY.plusDays(4), null, null, null, null, null, null, null, null, true, 1L,
                0)).orElseThrow();
        assertThat(store.hasDecisionWithSelection(BOOK, List.of("REBALANCE", "INITIAL"), DAY.plusDays(4))).isTrue();
        assertThat(store.hasDecisionWithSelection("bench_spy", List.of("REBALANCE"), DAY.plusDays(4)))
                .as("다른 책").isFalse();
        jdbc.update("DELETE FROM paper_decisions WHERE id = ?", rebalanced);

        store.updateDecision(id, "NO_TRADE", MAPPER.readTree("{\"summary\":\"바뀜\"}"), "오류 한 줄");
        assertThat(store.decision(id).orElseThrow()).extracting(PaperStore.Decision::status,
                d -> d.response().path("summary").asString(), PaperStore.Decision::error)
                .containsExactly("NO_TRADE", "바뀜", "오류 한 줄");
        store.updateDecision(id, "OK", null, null);
        assertThat(store.decision(id).orElseThrow().response().path("summary").asString()).as("null이면 응답 그대로")
                .isEqualTo("바뀜");

        store.resetBook(BOOK);
        assertThat(store.decisions(BOOK, 10)).as("리셋이 결정도 지움").isEmpty();
        assertThat(store.hasDecisionWithSelection(BOOK, List.of("SCHEDULED"), DAY)).isFalse();
    }

    @Test
    @DisplayName("게이트 지표: 거래일마다 한 번(두 번째 넣기는 처음 값 그대로), 직전 기록·최근 순 — 시험 뒤 되돌림")
    void gateLogOncePerTradingDay() {
        class Rollback extends RuntimeException {
        }
        LocalDate thu = LocalDate.of(2026, 10, 22);
        assertThatThrownBy(() -> store.atomically(() -> {
            jdbc.update("DELETE FROM paper_gate_log");
            JsonNode inputs = MAPPER.readTree("{\"us10y\":{\"value\":4.5,\"source\":\"macro.collected\"}}");
            JsonNode streaks = MAPPER.readTree("{\"G1\":3,\"G2\":null}");
            assertThat(store.insertGateIfAbsent(new PaperStore.GateRow(thu, inputs, "L1", streaks, null))).isTrue();
            assertThat(store.insertGateIfAbsent(new PaperStore.GateRow(thu, MAPPER.createObjectNode(), "L0", null,
                    null))).as("같은 거래일").isFalse();
            assertThat(store.insertGateIfAbsent(new PaperStore.GateRow(DAY, MAPPER.createObjectNode(), "UNKNOWN", null,
                    null))).isTrue();

            PaperStore.GateRow saved = store.gate(thu).orElseThrow();
            assertThat(saved.level()).isEqualTo("L1");
            assertThat(saved.inputs().path("us10y").path("value").asDouble()).isEqualTo(4.5);
            assertThat(saved.streaks().path("G1").asInt()).isEqualTo(3);
            assertThat(saved.streaks().path("G2").isNull()).as("모르는 연속은 null 그대로").isTrue();
            assertThat(saved.createdAt()).isNotNull();
            assertThat(store.gate(DAY).orElseThrow().streaks().isEmpty()).as("null → {}").isTrue();
            assertThat(store.latestGateBefore(DAY).orElseThrow().tradingDate()).isEqualTo(thu);
            assertThat(store.latestGateBefore(thu)).isEmpty();
            assertThat(store.recentGate(5)).extracting(PaperStore.GateRow::tradingDate).containsExactly(DAY, thu);
            assertThat(store.recentGate(1)).hasSize(1);
            throw new Rollback();
        })).isInstanceOf(Rollback.class);
    }

    private void insertPoint(String seriesId, String date, Double value) {
        jdbc.update("INSERT INTO timeseries (dataset, series_id, obs_date, value) VALUES (?, ?, ?::date, ?)",
                TS_DATASET, seriesId, date, value);
    }

    private static int epoch(String date) {
        return (int) LocalDate.parse(date).toEpochDay();
    }
}
