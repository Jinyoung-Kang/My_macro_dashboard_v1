package com.macrodash.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 모의 알고리즘 매매 장부({@code paper_*}, V8) — 백엔드가 쓰는 테이블(ADR 0008). 이 테이블들의 JDBC는 <b>전부 여기</b>에
 * 있고, 기능 계층(feature/paper)은 이 저장소만 부릅니다.
 *
 * <p>값의 모양: 돈·가격·비율은 {@code double}(DB는 NUMERIC — 금액은 소수 4자리로 반올림돼 저장됨), 시각은 {@link Instant},
 * 거래일은 {@link LocalDate}(미국 동부 세션 날짜), JSON 열은 {@link JsonNode}입니다. 비어 있을 수 있는 열은 래퍼 타입
 * ({@code Double}·{@code Integer}·{@code Long})이고 없으면 null입니다 — 0으로 채우지 않습니다. NaN·무한대인 금액은 저장하지
 * 않고 {@link IllegalArgumentException}을 던집니다(NUMERIC은 NaN을 받아 주므로, 계산 오류가 장부에 조용히 남지 않게).
 *
 * <p>여러 쓰기를 한 번에 반영해야 하는 곳(관측치 하나를 책 하나에 적용 등)은 {@link #atomically}로 감쌉니다.
 */
@Repository
public class PaperStore {

    /** 작업마다 남기는 최근 실행 기록 수. 5분 주기 작업이면 약 17시간 치입니다. */
    static final int JOB_RUNS_KEPT = 200;

    // ------------------------------------------------------------------ 레코드
    /** 책. {@code kind}는 RULES·CLAUDE·BENCHMARK, {@code status}는 NOT_STARTED·ACTIVE·PAUSED. 시작 전이면 {@code startedOn}이 null. */
    public record Book(String code, String kind, String label, double initialCash, LocalDate startedOn, String status,
                       Instant createdAt) {
    }

    /** 책의 현재 상태. {@code lastProcessedCollectedAt}은 관측치 처리의 멱등 키입니다(이 저장본까지 처리함). */
    public record BookState(String bookCode, double cash, double nav, double equityValue, double exposurePct,
                            Instant lastObservedAt, Instant lastProcessedCollectedAt, LocalDate lastNavTradingDate,
                            Instant updatedAt) {
    }

    /**
     * 보유 한 건. 가격을 아직 못 받았으면 {@code lastPrice}·{@code lastPriceAt}이 null입니다. {@code fillRefDate}·
     * {@code fillRefClose}는 분할 감지 기준(체결에 쓴 봉 날짜와 그날 종가)입니다.
     */
    public record Position(String bookCode, String ticker, int qty, double avgCost, LocalDate openedOn, Instant openedAt,
                           double highWatermark, Instant watermarkAt, Double lastPrice, Instant lastPriceAt,
                           boolean priceStale, LocalDate fillRefDate, double fillRefClose, String splitNote) {
    }

    /** 재진입 금지. {@code untilTradingDate}까지(포함) 다시 사지 않습니다. */
    public record Lockout(String bookCode, String ticker, LocalDate untilTradingDate, String reason) {
    }

    /**
     * 주문. 수량({@code qty})·목표 비중({@code targetWeightPct})·매도 비율({@code sellFraction})은 주문 종류에 따라 하나만
     * 차 있고 나머지는 null입니다. {@code reason}은 근거 JSON(넣을 때 null이면 {@code {}}).
     */
    public record Order(long id, String bookCode, Long decisionId, String ticker, String side, Integer qty,
                        Double targetWeightPct, Double sellFraction, String timing, String source, String status,
                        String rejectReason, JsonNode reason, LocalDate asOfTradingDate, Instant createdAt,
                        LocalDate expiresOn, Instant filledAt) {
    }

    /** 체결. 주문당 하나입니다({@code order_id} 유일). {@code price}는 슬리피지를 얹은 값, {@code rawPrice}는 관측치 그대로. */
    public record Fill(long id, long orderId, String bookCode, String ticker, String side, int qty, double price,
                       double rawPrice, double slippageBp, double gross, Instant observedAt, LocalDate observationDate,
                       String priceSource, boolean backfill, Instant createdAt) {
    }

    /** 거래일별 순자산. {@code pricesFinal}이면 확정 종가로 평가한 값입니다. */
    public record NavRow(String bookCode, LocalDate tradingDate, double nav, double cash, double equityValue,
                         double exposurePct, boolean pricesFinal) {
    }

    /** 정기 작업 실행 기록. 끝나지 않았으면 {@code finishedAt}이 null. */
    public record JobRun(long id, String job, Instant startedAt, Instant finishedAt, String outcome, JsonNode detail) {
    }

    /** 설정 한 행과 그 버전. */
    public record SettingsRow(int version, JsonNode body, Instant updatedAt) {
    }

    /**
     * 얼려 둔 출발일 종가 한 건. {@code source}는 체결의 {@code price_source}와 같은 말(equity_close·paper.quotes)이고,
     * {@code capturedAt}은 넣을 때 null이면 DB가 지금으로 채웁니다.
     */
    public record StartPrice(String ticker, LocalDate tradingDate, double close, String source, Instant capturedAt) {
    }

    /**
     * 기준일 하나·기관 하나의 13F 트랙레코드(설계 §4-0 ④, {@code paper_track_record}). 수치는 {@code TrackRecord13F.Result}
     * 그대로(%는 ×100, 커버리지는 0~1 비율)이고 표본이 없으면 null입니다. {@code rank}는 자격 있는 기관만(1부터), 없으면 null.
     * {@code periods}는 구간 목록 JSON 배열(null이면 {@code []}), {@code computedAt}은 넣을 때 null이면 DB가 지금으로 채웁니다.
     */
    public record TrackRecordRow(LocalDate asOfDate, String cik, int windowYears, String metric, int periodsUsed,
                                 int periodsTotal, LocalDate firstFilingDate, LocalDate lastFilingDate,
                                 Double cumReturnPct, Double cagrPct, Double volPct, Double mddPct, Double sharpe,
                                 Double excessVsSpyPct, Double coverageMin, Double coverageAvg, boolean eligible,
                                 String ineligibleReason, Integer rank, JsonNode periods, int settingsVersion,
                                 Instant computedAt) {
    }

    /**
     * 기준일 하나의 기관 선정({@code paper_selection_log}). {@code selectedCiks}는 선정 순위 순, {@code ranking}은 그때의 순위표
     * JSON(null이면 {@code []}). {@code createdAt}은 넣을 때 쓰지 않고 DB가 채웁니다.
     */
    public record Selection(LocalDate asOfDate, List<String> selectedCiks, JsonNode ranking, int settingsVersion,
                            Instant createdAt) {
        public Selection {
            selectedCiks = selectedCiks == null ? List.of() : List.copyOf(selectedCiks);
        }
    }

    private static final String BOOK_COLUMNS = "code, kind, label, initial_cash, started_on, status, created_at";
    private static final String STATE_COLUMNS = "book_code, cash, nav, equity_value, exposure_pct, last_observed_at, "
            + "last_processed_collected_at, last_nav_trading_date, updated_at";
    private static final String POSITION_COLUMNS = "book_code, ticker, qty, avg_cost, opened_on, opened_at, "
            + "high_watermark, watermark_at, last_price, last_price_at, price_stale, fill_ref_date, fill_ref_close, "
            + "split_note";
    private static final String ORDER_COLUMNS = "id, book_code, decision_id, ticker, side, qty, target_weight_pct, "
            + "sell_fraction, timing, source, status, reject_reason, reason::text AS reason, as_of_trading_date, "
            + "created_at, expires_on, filled_at";
    private static final String FILL_COLUMNS = "id, order_id, book_code, ticker, side, qty, price, raw_price, "
            + "slippage_bp, gross, observed_at, observation_date, price_source, is_backfill, created_at";
    private static final String NAV_COLUMNS = "book_code, trading_date, nav, cash, equity_value, exposure_pct, "
            + "prices_final";
    private static final String JOB_COLUMNS = "id, job, started_at, finished_at, outcome, detail::text AS detail";
    private static final String START_PRICE_COLUMNS = "ticker, trading_date, close, source, captured_at";
    private static final String TRACK_RECORD_COLUMNS = "as_of_date, cik, window_years, metric, periods_used, "
            + "periods_total, first_filing_date, last_filing_date, cum_return_pct, cagr_pct, vol_pct, mdd_pct, sharpe, "
            + "excess_vs_spy_pct, coverage_min, coverage_avg, eligible, ineligible_reason, rank, periods::text AS periods, "
            + "settings_version, computed_at";
    private static final String SELECTION_COLUMNS = "as_of_date, selected_ciks, ranking::text AS ranking, "
            + "settings_version, created_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SchemaCheck schema;
    /** 같은 DataSource의 트랜잭션 — 안에서 부르는 이 저장소·{@link StoreRepository}의 문장이 같은 연결에 합류합니다. */
    private final TransactionTemplate transactions;

    public PaperStore(JdbcTemplate jdbc, ObjectMapper mapper, SchemaCheck schema) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.schema = schema;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    /** 테이블이 있는가. 수집기가 아직 V8을 적용하지 않았으면 false입니다(V8이 마지막에 만드는 테이블로 봄). */
    public boolean ready() {
        return schema.tableExists("paper_api_usage");
    }

    /**
     * 장부 쓰기 여러 개를 한 트랜잭션으로 묶습니다 — 안에서 예외가 나면 모두 되돌리고 그 예외를 그대로 던집니다
     * ({@link UserStore#atomically}와 같은 방식). 이미 트랜잭션 안이면 거기에 합류합니다.
     *
     * <p>안에서 DB 오류(예: {@link #insertFill}의 중복)를 잡고 계속 쓰면 안 됩니다 — PostgreSQL은 오류가 난 트랜잭션의 나머지
     * 문장을 모두 거절합니다. 예외는 밖으로 내보내 트랜잭션째 되돌립니다.
     */
    public <T> T atomically(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    // ------------------------------------------------------------------ 책
    /** 모든 책, code 순(화면에 보이는 순서는 서비스가 정함). */
    public List<Book> books() {
        return jdbc.query("SELECT " + BOOK_COLUMNS + " FROM paper_books ORDER BY code", bookMapper());
    }

    public Optional<Book> book(String code) {
        return jdbc.query("SELECT " + BOOK_COLUMNS + " FROM paper_books WHERE code = ?", bookMapper(), code)
                .stream().findFirst();
    }

    /**
     * 책이 없을 때만 넣습니다(초기화를 여러 번 눌러도 한 번). {@code status}가 null이면 NOT_STARTED,
     * {@code createdAt}이 null이면 지금.
     *
     * @return 이번에 넣었으면 true, 이미 있었으면 false(기존 행은 그대로)
     */
    public boolean insertBookIfAbsent(Book book) {
        return jdbc.update("INSERT INTO paper_books (code, kind, label, initial_cash, started_on, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, COALESCE(?, 'NOT_STARTED'), COALESCE(?, now())) "
                        + "ON CONFLICT (code) DO NOTHING",
                ps -> {
                    ps.setString(1, book.code());
                    ps.setString(2, book.kind());
                    ps.setString(3, book.label());
                    ps.setBigDecimal(4, money(book.initialCash(), "initial_cash"));
                    setDate(ps, 5, book.startedOn());
                    ps.setString(6, book.status());
                    setInstant(ps, 7, book.createdAt());
                }) == 1;
    }

    public void updateBookStatus(String code, String status) {
        jdbc.update("UPDATE paper_books SET status = ? WHERE code = ?", status, code);
    }

    /** 출발 거래일을 바꿉니다. null이면 "아직 시작 전"으로 되돌립니다(초기화). */
    public void updateBookStartedOn(String code, LocalDate startedOn) {
        jdbc.update("UPDATE paper_books SET started_on = ? WHERE code = ?", ps -> {
            setDate(ps, 1, startedOn);
            ps.setString(2, code);
        });
    }

    // ------------------------------------------------------------------ 상태
    public Optional<BookState> state(String code) {
        return jdbc.query("SELECT " + STATE_COLUMNS + " FROM paper_book_state WHERE book_code = ?",
                (rs, rowNum) -> new BookState(rs.getString("book_code"), rs.getDouble("cash"), rs.getDouble("nav"),
                        rs.getDouble("equity_value"), rs.getDouble("exposure_pct"),
                        instant(rs, "last_observed_at"), instant(rs, "last_processed_collected_at"),
                        date(rs, "last_nav_trading_date"), instant(rs, "updated_at")),
                code).stream().findFirst();
    }

    /** 상태를 넣거나 통째로 바꿉니다. {@code updatedAt}은 쓰지 않고 DB가 지금으로 채웁니다. */
    public void upsertState(BookState s) {
        jdbc.update("INSERT INTO paper_book_state (book_code, cash, nav, equity_value, exposure_pct, last_observed_at, "
                        + "last_processed_collected_at, last_nav_trading_date, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, now()) "
                        + "ON CONFLICT (book_code) DO UPDATE SET cash = EXCLUDED.cash, nav = EXCLUDED.nav, "
                        + "equity_value = EXCLUDED.equity_value, exposure_pct = EXCLUDED.exposure_pct, "
                        + "last_observed_at = EXCLUDED.last_observed_at, "
                        + "last_processed_collected_at = EXCLUDED.last_processed_collected_at, "
                        + "last_nav_trading_date = EXCLUDED.last_nav_trading_date, updated_at = now()",
                ps -> {
                    ps.setString(1, s.bookCode());
                    ps.setBigDecimal(2, money(s.cash(), "cash"));
                    ps.setBigDecimal(3, money(s.nav(), "nav"));
                    ps.setBigDecimal(4, money(s.equityValue(), "equity_value"));
                    ps.setBigDecimal(5, money(s.exposurePct(), "exposure_pct"));
                    setInstant(ps, 6, s.lastObservedAt());
                    setInstant(ps, 7, s.lastProcessedCollectedAt());
                    setDate(ps, 8, s.lastNavTradingDate());
                });
    }

    // ------------------------------------------------------------------ 보유
    /** 한 책의 보유, ticker 순. */
    public List<Position> positions(String code) {
        return jdbc.query("SELECT " + POSITION_COLUMNS + " FROM paper_positions WHERE book_code = ? ORDER BY ticker",
                (rs, rowNum) -> new Position(rs.getString("book_code"), rs.getString("ticker"), rs.getInt("qty"),
                        rs.getDouble("avg_cost"), date(rs, "opened_on"), instant(rs, "opened_at"),
                        rs.getDouble("high_watermark"), instant(rs, "watermark_at"), nullableDouble(rs, "last_price"),
                        instant(rs, "last_price_at"), rs.getBoolean("price_stale"), date(rs, "fill_ref_date"),
                        rs.getDouble("fill_ref_close"), rs.getString("split_note")),
                code);
    }

    /** 보유를 넣거나 통째로 바꿉니다. 수량은 1 이상이어야 합니다(0주가 되면 {@link #deletePosition} — DB CHECK). */
    public void upsertPosition(Position p) {
        jdbc.update("INSERT INTO paper_positions (book_code, ticker, qty, avg_cost, opened_on, opened_at, high_watermark, "
                        + "watermark_at, last_price, last_price_at, price_stale, fill_ref_date, fill_ref_close, split_note, "
                        + "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) "
                        + "ON CONFLICT (book_code, ticker) DO UPDATE SET qty = EXCLUDED.qty, avg_cost = EXCLUDED.avg_cost, "
                        + "opened_on = EXCLUDED.opened_on, opened_at = EXCLUDED.opened_at, "
                        + "high_watermark = EXCLUDED.high_watermark, watermark_at = EXCLUDED.watermark_at, "
                        + "last_price = EXCLUDED.last_price, last_price_at = EXCLUDED.last_price_at, "
                        + "price_stale = EXCLUDED.price_stale, fill_ref_date = EXCLUDED.fill_ref_date, "
                        + "fill_ref_close = EXCLUDED.fill_ref_close, split_note = EXCLUDED.split_note, updated_at = now()",
                ps -> {
                    ps.setString(1, p.bookCode());
                    ps.setString(2, p.ticker());
                    ps.setInt(3, p.qty());
                    ps.setBigDecimal(4, money(p.avgCost(), "avg_cost"));
                    setDate(ps, 5, p.openedOn());
                    setInstant(ps, 6, p.openedAt());
                    ps.setBigDecimal(7, money(p.highWatermark(), "high_watermark"));
                    setInstant(ps, 8, p.watermarkAt());
                    setMoney(ps, 9, p.lastPrice(), "last_price");
                    setInstant(ps, 10, p.lastPriceAt());
                    ps.setBoolean(11, p.priceStale());
                    setDate(ps, 12, p.fillRefDate());
                    ps.setBigDecimal(13, money(p.fillRefClose(), "fill_ref_close"));
                    ps.setString(14, p.splitNote());
                });
    }

    public void deletePosition(String code, String ticker) {
        jdbc.update("DELETE FROM paper_positions WHERE book_code = ? AND ticker = ?", code, ticker);
    }

    // ------------------------------------------------------------------ 재진입 금지
    /** 한 책의 재진입 금지, ticker 순. */
    public List<Lockout> lockouts(String code) {
        return jdbc.query("SELECT book_code, ticker, until_trading_date, reason FROM paper_lockouts "
                        + "WHERE book_code = ? ORDER BY ticker",
                (rs, rowNum) -> new Lockout(rs.getString("book_code"), rs.getString("ticker"),
                        date(rs, "until_trading_date"), rs.getString("reason")),
                code);
    }

    /** 재진입 금지를 넣거나 바꿉니다. 같은 종목이 이미 있으면 새 기한·사유로 덮습니다(늦게 걸린 스탑이 기준). */
    public void upsertLockout(Lockout l) {
        jdbc.update("INSERT INTO paper_lockouts (book_code, ticker, until_trading_date, reason) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (book_code, ticker) DO UPDATE SET until_trading_date = EXCLUDED.until_trading_date, "
                        + "reason = EXCLUDED.reason, created_at = now()",
                ps -> {
                    ps.setString(1, l.bookCode());
                    ps.setString(2, l.ticker());
                    setDate(ps, 3, l.untilTradingDate());
                    ps.setString(4, l.reason());
                });
    }

    /**
     * 기한이 지난 재진입 금지를 지웁니다({@code until_trading_date < today} — 기한 당일까지는 남김).
     *
     * @return 지운 행 수
     */
    public int deleteLockoutsBefore(String code, LocalDate today) {
        return jdbc.update("DELETE FROM paper_lockouts WHERE book_code = ? AND until_trading_date < ?",
                code, Date.valueOf(today));
    }

    // ------------------------------------------------------------------ 주문
    /**
     * 주문을 넣습니다. {@code id}는 무시하고 DB가 매깁니다. {@code createdAt}이 null이면 지금(트랜잭션 시작 시각),
     * 아니면 그 값을 씁니다 — 사후 체결(BACKFILL)은 체결 관측치보다 앞선 시각이어야 하기 때문입니다.
     *
     * @return 새 주문 번호
     */
    public long insertOrder(Order o) {
        List<Long> ids = jdbc.query("INSERT INTO paper_orders (book_code, decision_id, ticker, side, qty, "
                        + "target_weight_pct, sell_fraction, timing, source, status, reject_reason, reason, "
                        + "as_of_trading_date, created_at, expires_on, filled_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(CAST(? AS jsonb), '{}'::jsonb), ?, "
                        + "COALESCE(CAST(? AS timestamptz), now()), ?, ?) RETURNING id",
                ps -> {
                    ps.setString(1, o.bookCode());
                    setLong(ps, 2, o.decisionId());
                    ps.setString(3, o.ticker());
                    ps.setString(4, o.side());
                    setInteger(ps, 5, o.qty());
                    setMoney(ps, 6, o.targetWeightPct(), "target_weight_pct");
                    setMoney(ps, 7, o.sellFraction(), "sell_fraction");
                    ps.setString(8, o.timing());
                    ps.setString(9, o.source());
                    ps.setString(10, o.status());
                    ps.setString(11, o.rejectReason());
                    ps.setString(12, o.reason() == null ? null : mapper.writeValueAsString(o.reason()));
                    setDate(ps, 13, o.asOfTradingDate());
                    setInstant(ps, 14, o.createdAt());
                    setDate(ps, 15, o.expiresOn());
                    setInstant(ps, 16, o.filledAt());
                },
                (rs, rowNum) -> rs.getLong("id"));
        return single(ids, "주문");
    }

    /** 한 책의 그 상태 주문, 만든 순(created_at, id). */
    public List<Order> ordersByStatus(String code, String status) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM paper_orders WHERE book_code = ? AND status = ? "
                + "ORDER BY created_at, id", orderMapper(), code, status);
    }

    /** 한 책의 최근 주문 {@code limit}건, 최근 순. */
    public List<Order> recentOrders(String code, int limit) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM paper_orders WHERE book_code = ? "
                + "ORDER BY created_at DESC, id DESC LIMIT ?", orderMapper(), code, limit);
    }

    public void markOrderFilled(long id, Instant filledAt) {
        jdbc.update("UPDATE paper_orders SET status = 'FILLED', filled_at = ? WHERE id = ?", ps -> {
            setInstant(ps, 1, filledAt);
            ps.setLong(2, id);
        });
    }

    /** 상태와 거절 사유를 그대로 씁니다(사유가 null이면 비움). */
    public void markOrderStatus(long id, String status, String rejectReason) {
        jdbc.update("UPDATE paper_orders SET status = ?, reject_reason = ? WHERE id = ?", ps -> {
            ps.setString(1, status);
            ps.setString(2, rejectReason);
            ps.setLong(3, id);
        });
    }

    /**
     * 주문 근거(JSON)를 통째로 바꿉니다(null이면 {@code {}}). 체결하면서 알게 된 사실 — 현금이 모자라 요청보다 적게 산 것
     * ({@code cashLimited}) 같은 것 — 을 그 주문 기록에 남길 때 씁니다. 부르는 쪽이 기존 근거에 덧붙인 전체를 넘깁니다.
     */
    public void updateOrderReason(long id, JsonNode reason) {
        String json = reason == null ? null : mapper.writeValueAsString(reason);
        jdbc.update("UPDATE paper_orders SET reason = COALESCE(CAST(? AS jsonb), '{}'::jsonb) WHERE id = ?", ps -> {
            ps.setString(1, json);
            ps.setLong(2, id);
        });
    }

    /**
     * 기한이 지난 대기 주문을 만료시킵니다(PENDING이고 {@code expires_on < today}). 기한이 없는 주문은 건드리지 않습니다.
     *
     * @return 만료시킨 주문 수
     */
    public int expireOrders(String code, LocalDate today) {
        return jdbc.update("UPDATE paper_orders SET status = 'EXPIRED' "
                + "WHERE book_code = ? AND status = 'PENDING' AND expires_on < ?", code, Date.valueOf(today));
    }

    /**
     * 체결 전 주문(PENDING·PROPOSED)을 모두 취소합니다 — 일시정지할 때.
     *
     * @return 취소한 주문 수
     */
    public int cancelPendingOrders(String code) {
        return jdbc.update("UPDATE paper_orders SET status = 'CANCELLED' "
                + "WHERE book_code = ? AND status IN ('PENDING', 'PROPOSED')", code);
    }

    // ------------------------------------------------------------------ 체결
    /**
     * 체결을 넣습니다. {@code id}는 DB가 매기고, {@code createdAt}이 null이면 지금입니다.
     *
     * @return 새 체결 번호
     * @throws org.springframework.dao.DuplicateKeyException 그 주문의 체결이 이미 있으면(주문당 하나 — 같은 주문을 두 번
     *                                                       체결하지 않는 마지막 방어선). 트랜잭션 안이면 그 트랜잭션은
     *                                                       더 쓸 수 없으니 잡지 말고 내보냅니다({@link #atomically}).
     */
    public long insertFill(Fill f) {
        List<Long> ids = jdbc.query("INSERT INTO paper_fills (order_id, book_code, ticker, side, qty, price, raw_price, "
                        + "slippage_bp, gross, observed_at, observation_date, price_source, is_backfill, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(CAST(? AS timestamptz), now())) "
                        + "RETURNING id",
                ps -> {
                    ps.setLong(1, f.orderId());
                    ps.setString(2, f.bookCode());
                    ps.setString(3, f.ticker());
                    ps.setString(4, f.side());
                    ps.setInt(5, f.qty());
                    ps.setBigDecimal(6, money(f.price(), "price"));
                    ps.setBigDecimal(7, money(f.rawPrice(), "raw_price"));
                    ps.setBigDecimal(8, money(f.slippageBp(), "slippage_bp"));
                    ps.setBigDecimal(9, money(f.gross(), "gross"));
                    setInstant(ps, 10, f.observedAt());
                    setDate(ps, 11, f.observationDate());
                    ps.setString(12, f.priceSource());
                    ps.setBoolean(13, f.backfill());
                    setInstant(ps, 14, f.createdAt());
                },
                (rs, rowNum) -> rs.getLong("id"));
        return single(ids, "체결");
    }

    /** 한 책의 최근 체결 {@code limit}건, 최근 순. */
    public List<Fill> recentFills(String code, int limit) {
        return jdbc.query("SELECT " + FILL_COLUMNS + " FROM paper_fills WHERE book_code = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, rowNum) -> new Fill(rs.getLong("id"), rs.getLong("order_id"), rs.getString("book_code"),
                        rs.getString("ticker"), rs.getString("side"), rs.getInt("qty"), rs.getDouble("price"),
                        rs.getDouble("raw_price"), rs.getDouble("slippage_bp"), rs.getDouble("gross"),
                        instant(rs, "observed_at"), date(rs, "observation_date"), rs.getString("price_source"),
                        rs.getBoolean("is_backfill"), instant(rs, "created_at")),
                code, limit);
    }

    // ------------------------------------------------------------------ 순자산
    /**
     * 거래일 순자산을 넣거나 바꿉니다. 단, 이미 확정값({@code prices_final = true})이 있으면 미확정값으로 덮지 않습니다 —
     * 장 마감 뒤 확정 종가로 적은 줄을 다음 날 새벽의 묵은 관측치(같은 거래일 봉)가 지우지 않게. 확정값끼리는 새 값이 이깁니다.
     */
    public void upsertNav(NavRow r) {
        jdbc.update("INSERT INTO paper_nav_daily (book_code, trading_date, nav, cash, equity_value, exposure_pct, "
                        + "prices_final) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (book_code, trading_date) DO UPDATE SET nav = EXCLUDED.nav, cash = EXCLUDED.cash, "
                        + "equity_value = EXCLUDED.equity_value, exposure_pct = EXCLUDED.exposure_pct, "
                        + "prices_final = EXCLUDED.prices_final "
                        + "WHERE NOT paper_nav_daily.prices_final OR EXCLUDED.prices_final",
                ps -> {
                    ps.setString(1, r.bookCode());
                    setDate(ps, 2, r.tradingDate());
                    ps.setBigDecimal(3, money(r.nav(), "nav"));
                    ps.setBigDecimal(4, money(r.cash(), "cash"));
                    ps.setBigDecimal(5, money(r.equityValue(), "equity_value"));
                    ps.setBigDecimal(6, money(r.exposurePct(), "exposure_pct"));
                    ps.setBoolean(7, r.pricesFinal());
                });
    }

    /** 한 책의 순자산, 거래일 오름차순. */
    public List<NavRow> navSeries(String code) {
        return jdbc.query("SELECT " + NAV_COLUMNS + " FROM paper_nav_daily WHERE book_code = ? ORDER BY trading_date",
                navMapper(), code);
    }

    /** 모든 책의 순자산, (책, 거래일) 순. */
    public List<NavRow> navSeriesAll() {
        return jdbc.query("SELECT " + NAV_COLUMNS + " FROM paper_nav_daily ORDER BY book_code, trading_date",
                navMapper());
    }

    // ------------------------------------------------------------------ 작업 기록
    /**
     * 정기 작업 실행 한 건을 남기고, 그 작업의 기록은 최근 {@value #JOB_RUNS_KEPT}건만 둡니다(시작 시각 순). 두 문장을 한
     * 트랜잭션으로 씁니다. {@code detail}이 null이면 {@code {}}.
     */
    public void recordJobRun(String job, Instant startedAt, Instant finishedAt, String outcome, JsonNode detail) {
        String detailJson = detail == null ? null : mapper.writeValueAsString(detail);
        atomically(() -> {
            jdbc.update("INSERT INTO paper_job_runs (job, started_at, finished_at, outcome, detail) "
                    + "VALUES (?, ?, ?, ?, COALESCE(CAST(? AS jsonb), '{}'::jsonb))", ps -> {
                        ps.setString(1, job);
                        setInstant(ps, 2, startedAt);
                        setInstant(ps, 3, finishedAt);
                        ps.setString(4, outcome);
                        ps.setString(5, detailJson);
                    });
            jdbc.update("DELETE FROM paper_job_runs WHERE job = ? AND id NOT IN ("
                    + "SELECT id FROM paper_job_runs WHERE job = ? ORDER BY started_at DESC, id DESC LIMIT ?)",
                    job, job, JOB_RUNS_KEPT);
            return null;
        });
    }

    /** 모든 작업의 최근 기록 {@code limit}건, 최근 순(시작 시각). */
    public List<JobRun> recentJobRuns(int limit) {
        return jdbc.query("SELECT " + JOB_COLUMNS + " FROM paper_job_runs ORDER BY started_at DESC, id DESC LIMIT ?",
                (rs, rowNum) -> new JobRun(rs.getLong("id"), rs.getString("job"), instant(rs, "started_at"),
                        instant(rs, "finished_at"), rs.getString("outcome"), json(rs, "detail")),
                limit);
    }

    // ------------------------------------------------------------------ 설정
    /** 설정 한 행. 아직 초기화 전이면 비어 있음(서비스가 기본값을 씀). */
    public Optional<SettingsRow> settings() {
        return jdbc.query("SELECT version, body::text AS body, updated_at FROM paper_settings WHERE id = 1",
                (rs, rowNum) -> new SettingsRow(rs.getInt("version"), json(rs, "body"), instant(rs, "updated_at")))
                .stream().findFirst();
    }

    /**
     * 설정이 없을 때만 버전 1로 넣고 이력에도 1을 남깁니다(바뀐 키 없음 = 처음 값). 한 트랜잭션입니다.
     *
     * @return 이번에 넣었으면 true, 이미 있었으면 false(기존 설정은 그대로)
     */
    public boolean initSettingsIfAbsent(JsonNode body) {
        String json = mapper.writeValueAsString(body);
        return atomically(() -> {
            int inserted = jdbc.update("INSERT INTO paper_settings (id, version, body) VALUES (1, 1, CAST(? AS jsonb)) "
                    + "ON CONFLICT (id) DO NOTHING", json);
            if (inserted == 0) {
                return false;
            }
            jdbc.update("INSERT INTO paper_settings_history (version, body, changed_keys) "
                    + "VALUES (1, CAST(? AS jsonb), '{}')", json);
            return true;
        });
    }

    /**
     * 설정을 통째로 바꾸고 버전을 하나 올립니다. 이력에 새 버전·본문·바뀐 키를 남깁니다. 한 트랜잭션이고, 동시에 저장해도
     * 버전이 겹치지 않습니다(행 잠금). 설정 행이 아직 없으면 버전 1로 만듭니다.
     *
     * @return 새 버전
     */
    public int saveSettings(JsonNode body, List<String> changedKeys) {
        String json = mapper.writeValueAsString(body);
        String[] keys = changedKeys == null ? new String[0] : changedKeys.toArray(new String[0]);
        return atomically(() -> {
            Integer version = jdbc.queryForObject("INSERT INTO paper_settings (id, version, body) "
                    + "VALUES (1, 1, CAST(? AS jsonb)) ON CONFLICT (id) DO UPDATE SET version = paper_settings.version + 1, "
                    + "body = EXCLUDED.body, updated_at = now() RETURNING version", Integer.class, json);
            if (version == null) {
                throw new IllegalStateException("새 설정 버전을 받지 못했습니다");
            }
            jdbc.update("INSERT INTO paper_settings_history (version, body, changed_keys) VALUES (?, CAST(? AS jsonb), ?)",
                    ps -> {
                        ps.setInt(1, version);
                        ps.setString(2, json);
                        ps.setArray(3, ps.getConnection().createArrayOf("text", keys));
                    });
            return version;
        });
    }

    /**
     * 그 버전의 설정 본문(변경 이력 {@code paper_settings_history}) — "이 트랙레코드는 어떤 자격 기준으로 계산했나"를 그때의
     * 값으로 보이려고. {@code updatedAt}에는 그 버전을 남긴 시각이 들어갑니다. 없으면 empty.
     */
    public Optional<SettingsRow> settingsAt(int version) {
        return jdbc.query("SELECT version, body::text AS body, created_at FROM paper_settings_history WHERE version = ?",
                (rs, rowNum) -> new SettingsRow(rs.getInt("version"), json(rs, "body"), instant(rs, "created_at")),
                version).stream().findFirst();
    }

    // ------------------------------------------------------------------ 트랙레코드·선정
    /**
     * 트랙레코드 한 행을 넣거나 통째로 바꿉니다({@code (as_of_date, cik)} 충돌 시 갱신 — 같은 기준일의 수동 재계산이 덮어씀).
     * 수치가 NaN·무한대이면 저장하지 않고 {@link IllegalArgumentException}(DOUBLE PRECISION은 NaN을 받아 주므로 여기서 막음).
     */
    public void upsertTrackRecord(TrackRecordRow r) {
        String periodsJson = r.periods() == null ? null : mapper.writeValueAsString(r.periods());
        jdbc.update("INSERT INTO paper_track_record (as_of_date, cik, window_years, metric, periods_used, periods_total, "
                        + "first_filing_date, last_filing_date, cum_return_pct, cagr_pct, vol_pct, mdd_pct, sharpe, "
                        + "excess_vs_spy_pct, coverage_min, coverage_avg, eligible, ineligible_reason, rank, periods, "
                        + "settings_version, computed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "COALESCE(CAST(? AS jsonb), '[]'::jsonb), ?, COALESCE(CAST(? AS timestamptz), now())) "
                        + "ON CONFLICT (as_of_date, cik) DO UPDATE SET window_years = EXCLUDED.window_years, "
                        + "metric = EXCLUDED.metric, periods_used = EXCLUDED.periods_used, "
                        + "periods_total = EXCLUDED.periods_total, first_filing_date = EXCLUDED.first_filing_date, "
                        + "last_filing_date = EXCLUDED.last_filing_date, cum_return_pct = EXCLUDED.cum_return_pct, "
                        + "cagr_pct = EXCLUDED.cagr_pct, vol_pct = EXCLUDED.vol_pct, mdd_pct = EXCLUDED.mdd_pct, "
                        + "sharpe = EXCLUDED.sharpe, excess_vs_spy_pct = EXCLUDED.excess_vs_spy_pct, "
                        + "coverage_min = EXCLUDED.coverage_min, coverage_avg = EXCLUDED.coverage_avg, "
                        + "eligible = EXCLUDED.eligible, ineligible_reason = EXCLUDED.ineligible_reason, "
                        + "rank = EXCLUDED.rank, periods = EXCLUDED.periods, "
                        + "settings_version = EXCLUDED.settings_version, computed_at = EXCLUDED.computed_at",
                ps -> {
                    setDate(ps, 1, r.asOfDate());
                    ps.setString(2, r.cik());
                    ps.setInt(3, r.windowYears());
                    ps.setString(4, r.metric());
                    ps.setInt(5, r.periodsUsed());
                    ps.setInt(6, r.periodsTotal());
                    setDate(ps, 7, r.firstFilingDate());
                    setDate(ps, 8, r.lastFilingDate());
                    setStat(ps, 9, r.cumReturnPct(), "cum_return_pct");
                    setStat(ps, 10, r.cagrPct(), "cagr_pct");
                    setStat(ps, 11, r.volPct(), "vol_pct");
                    setStat(ps, 12, r.mddPct(), "mdd_pct");
                    setStat(ps, 13, r.sharpe(), "sharpe");
                    setStat(ps, 14, r.excessVsSpyPct(), "excess_vs_spy_pct");
                    setStat(ps, 15, r.coverageMin(), "coverage_min");
                    setStat(ps, 16, r.coverageAvg(), "coverage_avg");
                    ps.setBoolean(17, r.eligible());
                    ps.setString(18, r.ineligibleReason());
                    setInteger(ps, 19, r.rank());
                    ps.setString(20, periodsJson);
                    ps.setInt(21, r.settingsVersion());
                    setInstant(ps, 22, r.computedAt());
                });
    }

    /**
     * 그 기준일의 트랙레코드를 모두 지웁니다 — 다시 계산할 때 후보 목록이 줄었으면 빠진 기관의 옛 행이 남지 않게,
     * {@link #atomically} 안에서 지우고 새로 넣습니다.
     *
     * @return 지운 행 수
     */
    public int deleteTrackRecords(LocalDate asOf) {
        return jdbc.update("DELETE FROM paper_track_record WHERE as_of_date = ?", Date.valueOf(asOf));
    }

    /** 그 기준일의 트랙레코드, 순위 순(자격 없는 기관은 뒤, CIK 순). 계산한 적 없으면 빈 목록. */
    public List<TrackRecordRow> trackRecords(LocalDate asOf) {
        return jdbc.query("SELECT " + TRACK_RECORD_COLUMNS + " FROM paper_track_record WHERE as_of_date = ? "
                        + "ORDER BY rank NULLS LAST, cik",
                (rs, rowNum) -> new TrackRecordRow(date(rs, "as_of_date"), rs.getString("cik"),
                        rs.getInt("window_years"), rs.getString("metric"), rs.getInt("periods_used"),
                        rs.getInt("periods_total"), date(rs, "first_filing_date"), date(rs, "last_filing_date"),
                        stat(rs, "cum_return_pct"), stat(rs, "cagr_pct"), stat(rs, "vol_pct"), stat(rs, "mdd_pct"),
                        stat(rs, "sharpe"), stat(rs, "excess_vs_spy_pct"), stat(rs, "coverage_min"),
                        stat(rs, "coverage_avg"), rs.getBoolean("eligible"), rs.getString("ineligible_reason"),
                        rs.getObject("rank", Integer.class), json(rs, "periods"), rs.getInt("settings_version"),
                        instant(rs, "computed_at")),
                Date.valueOf(asOf));
    }

    /** 트랙레코드를 계산한 가장 늦은 기준일. 한 번도 없으면 empty. */
    public Optional<LocalDate> latestTrackRecordAsOf() {
        List<LocalDate> rows = jdbc.query("SELECT max(as_of_date) AS as_of_date FROM paper_track_record",
                (rs, rowNum) -> date(rs, "as_of_date"));
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /**
     * 선정이 그 기준일에 아직 없을 때만 넣습니다({@code ON CONFLICT DO NOTHING}). 선정은 재선정일·초기 선정 때 한 번이고,
     * 같은 기준일을 다시 계산해도(수동 재계산) 바꾸지 않습니다 — 그 선정으로 낸 주문 기록과 어긋나지 않게(설계 §4-0 ⑤).
     *
     * @return 이번에 넣었으면 true, 이미 있었으면 false(기존 선정 그대로)
     */
    public boolean insertSelectionIfAbsent(Selection s) {
        String rankingJson = s.ranking() == null ? null : mapper.writeValueAsString(s.ranking());
        String[] ciks = s.selectedCiks().toArray(new String[0]);
        return jdbc.update("INSERT INTO paper_selection_log (as_of_date, selected_ciks, ranking, settings_version) "
                        + "VALUES (?, ?, COALESCE(CAST(? AS jsonb), '[]'::jsonb), ?) ON CONFLICT (as_of_date) DO NOTHING",
                ps -> {
                    setDate(ps, 1, s.asOfDate());
                    ps.setArray(2, ps.getConnection().createArrayOf("text", ciks));
                    ps.setString(3, rankingJson);
                    ps.setInt(4, s.settingsVersion());
                }) == 1;
    }

    /** 모든 선정, 최근 기준일 순. */
    public List<Selection> selections() {
        return jdbc.query("SELECT " + SELECTION_COLUMNS + " FROM paper_selection_log ORDER BY as_of_date DESC",
                selectionMapper());
    }

    /** 그날(포함) 이전의 가장 최근 선정 — 그날 유효한 선정. 없으면 empty. */
    public Optional<Selection> selectionOnOrBefore(LocalDate date) {
        return jdbc.query("SELECT " + SELECTION_COLUMNS + " FROM paper_selection_log WHERE as_of_date <= ? "
                + "ORDER BY as_of_date DESC LIMIT 1", selectionMapper(), Date.valueOf(date)).stream().findFirst();
    }

    // ------------------------------------------------------------------ 출발일 종가
    /** 얼려 둔 출발일 종가 하나. 없으면 empty(그 거래일이 맞는지는 부르는 쪽이 봅니다). */
    public Optional<StartPrice> startPrice(String ticker) {
        return jdbc.query("SELECT " + START_PRICE_COLUMNS + " FROM paper_start_prices WHERE ticker = ?",
                startPriceMapper(), ticker).stream().findFirst();
    }

    /** 얼려 둔 출발일 종가 전부, ticker 순. */
    public List<StartPrice> startPrices() {
        return jdbc.query("SELECT " + START_PRICE_COLUMNS + " FROM paper_start_prices ORDER BY ticker",
                startPriceMapper());
    }

    /**
     * 그 종목의 출발일 종가가 아직 없을 때만 넣습니다. <b>같은 거래일의 값은 절대 덮지 않습니다</b> — 처음 얼린 값이 기준이고,
     * 그 뒤 수정주가 이력이 배당락으로 낮아져도 사후 체결·리셋이 같은 값을 쓰게 하려는 것입니다(V8 주석). 다른 거래일의
     * 행(출발일 설정을 바꾼 경우)은 그 날짜에는 쓸 수 없는 값이라 새 값으로 바꿉니다.
     *
     * @return 이번에 넣었거나 바꿨으면 true, 같은 거래일 값이 이미 있었으면 false(기존 값 그대로)
     */
    public boolean insertStartPriceIfAbsent(StartPrice p) {
        return jdbc.update("INSERT INTO paper_start_prices (ticker, trading_date, close, source, captured_at) "
                        + "VALUES (?, ?, ?, ?, COALESCE(CAST(? AS timestamptz), now())) "
                        + "ON CONFLICT (ticker) DO UPDATE SET trading_date = EXCLUDED.trading_date, "
                        + "close = EXCLUDED.close, source = EXCLUDED.source, captured_at = EXCLUDED.captured_at "
                        + "WHERE paper_start_prices.trading_date <> EXCLUDED.trading_date",
                ps -> {
                    ps.setString(1, p.ticker());
                    setDate(ps, 2, p.tradingDate());
                    ps.setBigDecimal(3, money(p.close(), "close"));
                    ps.setString(4, p.source());
                    setInstant(ps, 5, p.capturedAt());
                }) == 1;
    }

    // ------------------------------------------------------------------ 초기화
    /**
     * 한 책의 장부를 비웁니다 — 체결·주문·결정·보유·재진입 금지·순자산·상태를 한 트랜잭션으로 지웁니다. 책 행(상태·출발일)은
     * 그대로 두니 호출자가 {@link #updateBookStatus}·{@link #updateBookStartedOn}·{@link #upsertState}로 다시 맞춥니다.
     * 작업 기록·설정·얼려 둔 출발일 종가는 책에 속하지 않아 남깁니다(다시 사후 체결할 때 같은 종가를 쓰려고).
     */
    public void resetBook(String code) {
        atomically(() -> {
            // 외래키 방향(체결 → 주문 → 결정)대로 아래에서부터 지웁니다.
            for (String table : List.of("paper_fills", "paper_orders", "paper_decisions", "paper_positions",
                    "paper_lockouts", "paper_nav_daily", "paper_book_state")) {
                jdbc.update("DELETE FROM " + table + " WHERE book_code = ?", code);
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ 매핑
    private static RowMapper<Book> bookMapper() {
        return (rs, rowNum) -> new Book(rs.getString("code"), rs.getString("kind"), rs.getString("label"),
                rs.getDouble("initial_cash"), date(rs, "started_on"), rs.getString("status"),
                instant(rs, "created_at"));
    }

    private RowMapper<Order> orderMapper() {
        return (rs, rowNum) -> new Order(rs.getLong("id"), rs.getString("book_code"),
                rs.getObject("decision_id", Long.class), rs.getString("ticker"), rs.getString("side"),
                rs.getObject("qty", Integer.class), nullableDouble(rs, "target_weight_pct"),
                nullableDouble(rs, "sell_fraction"), rs.getString("timing"), rs.getString("source"),
                rs.getString("status"), rs.getString("reject_reason"), json(rs, "reason"),
                date(rs, "as_of_trading_date"), instant(rs, "created_at"), date(rs, "expires_on"),
                instant(rs, "filled_at"));
    }

    private static RowMapper<StartPrice> startPriceMapper() {
        return (rs, rowNum) -> new StartPrice(rs.getString("ticker"), date(rs, "trading_date"), rs.getDouble("close"),
                rs.getString("source"), instant(rs, "captured_at"));
    }

    private RowMapper<Selection> selectionMapper() {
        return (rs, rowNum) -> {
            java.sql.Array array = rs.getArray("selected_ciks");
            List<String> ciks = array == null ? List.of() : List.of((String[]) array.getArray());
            return new Selection(date(rs, "as_of_date"), ciks, json(rs, "ranking"), rs.getInt("settings_version"),
                    instant(rs, "created_at"));
        };
    }

    private static RowMapper<NavRow> navMapper() {
        return (rs, rowNum) -> new NavRow(rs.getString("book_code"), date(rs, "trading_date"), rs.getDouble("nav"),
                rs.getDouble("cash"), rs.getDouble("equity_value"), rs.getDouble("exposure_pct"),
                rs.getBoolean("prices_final"));
    }

    private JsonNode json(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        return raw == null ? null : mapper.readTree(raw);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    /** DOUBLE PRECISION 열(트랙레코드 수치). NULL은 null — 0으로 읽지 않습니다. */
    private static Double stat(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    /** 트랙레코드 수치. null은 NULL, NaN·무한대는 계산 오류라 저장하지 않습니다(DOUBLE PRECISION은 NaN을 받아 주므로). */
    private static void setStat(PreparedStatement ps, int index, Double value, String column) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(column + " 값이 유한한 수가 아닙니다: " + value);
        } else {
            ps.setDouble(index, value);
        }
    }

    /** NUMERIC 열에 넣을 값. NaN·무한대는 계산 오류라 저장하지 않습니다(NUMERIC은 NaN을 받아 주므로 여기서 막음). */
    private static BigDecimal money(double value, String column) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(column + " 값이 유한한 수가 아닙니다: " + value);
        }
        return BigDecimal.valueOf(value);
    }

    private static void setMoney(PreparedStatement ps, int index, Double value, String column) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NUMERIC);
        } else {
            ps.setBigDecimal(index, money(value, column));
        }
    }

    private static void setDate(PreparedStatement ps, int index, LocalDate value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DATE);
        } else {
            ps.setDate(index, Date.valueOf(value));
        }
    }

    private static void setInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setTimestamp(index, Timestamp.from(value));
        }
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static long single(List<Long> ids, String what) {
        if (ids.isEmpty()) {
            throw new IllegalStateException("새 " + what + " 번호를 받지 못했습니다");
        }
        return ids.get(0);
    }
}
