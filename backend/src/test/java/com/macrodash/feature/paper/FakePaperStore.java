package com.macrodash.feature.paper;

import com.macrodash.store.PaperStore;
import com.macrodash.store.SchemaCheck;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

import static org.mockito.Mockito.mock;

/**
 * 메모리 안의 {@link PaperStore} — 장부 서비스 단위 테스트용. DB 없이 PaperStore의 계약(정렬·유일 제약·확정 순자산 보호·
 * 트랜잭션 되돌림)만 흉내 냅니다. 실제 SQL은 {@code PaperStoreDbTest}가 테스트 DB로 확인합니다.
 *
 * <p>{@link #atomically}는 바깥 호출에서 모든 표를 복사해 두고, 예외가 나면 되돌립니다(안쪽 호출은 합류).
 */
class FakePaperStore extends PaperStore {

    /** DB가 now()로 채우는 시각. 테스트가 정합니다. */
    Instant dbNow = Instant.parse("2026-10-26T12:00:00Z");
    boolean ready = true;

    Map<String, Book> books = new TreeMap<>();
    Map<String, BookState> states = new TreeMap<>();
    Map<String, TreeMap<String, Position>> positions = new HashMap<>();
    Map<String, TreeMap<String, Lockout>> lockouts = new HashMap<>();
    TreeMap<Long, Order> orders = new TreeMap<>();
    TreeMap<Long, Fill> fills = new TreeMap<>();
    Map<String, TreeMap<LocalDate, NavRow>> nav = new HashMap<>();
    List<JobRun> jobRuns = new ArrayList<>();
    Map<String, StartPrice> startPrices = new TreeMap<>();
    /** 트랙레코드: 기준일 → ("그룹:CIK" → 행). 두 그룹에 모두 있는 기관은 행이 둘(V9). */
    TreeMap<LocalDate, TreeMap<String, TrackRecordRow>> trackRecords = new TreeMap<>();
    TreeMap<LocalDate, Selection> selections = new TreeMap<>();
    TreeMap<Long, Decision> decisions = new TreeMap<>();
    TreeMap<LocalDate, GateRow> gates = new TreeMap<>();
    SettingsRow settings;
    Map<Integer, SettingsRow> settingsHistory = new TreeMap<>();
    long sequence;
    private int depth;

    FakePaperStore() {
        super(new JdbcTemplate(mock(DataSource.class)), new ObjectMapper(), mock(SchemaCheck.class));
    }

    @Override
    public boolean ready() {
        return ready;
    }

    @Override
    public <T> T atomically(Supplier<T> work) {
        if (depth > 0) {
            return work.get();
        }
        Snapshot before = new Snapshot(this);
        depth++;
        try {
            return work.get();
        } catch (RuntimeException e) {
            before.restore(this);
            throw e;
        } finally {
            depth--;
        }
    }

    // ------------------------------------------------------------------ 책·상태
    @Override
    public List<Book> books() {
        return new ArrayList<>(books.values());
    }

    @Override
    public Optional<Book> book(String code) {
        return Optional.ofNullable(books.get(code));
    }

    @Override
    public boolean insertBookIfAbsent(Book book) {
        if (books.containsKey(book.code())) {
            return false;
        }
        books.put(book.code(), new Book(book.code(), book.kind(), book.label(), book.initialCash(), book.startedOn(),
                book.status() == null ? "NOT_STARTED" : book.status(), book.createdAt() == null ? dbNow : book.createdAt()));
        return true;
    }

    @Override
    public void updateBookStatus(String code, String status) {
        Book b = books.get(code);
        books.put(code, new Book(b.code(), b.kind(), b.label(), b.initialCash(), b.startedOn(), status, b.createdAt()));
    }

    @Override
    public void updateBookStartedOn(String code, LocalDate startedOn) {
        Book b = books.get(code);
        books.put(code, new Book(b.code(), b.kind(), b.label(), b.initialCash(), startedOn, b.status(), b.createdAt()));
    }

    @Override
    public Optional<BookState> state(String code) {
        return Optional.ofNullable(states.get(code));
    }

    @Override
    public void upsertState(BookState s) {
        states.put(s.bookCode(), new BookState(s.bookCode(), s.cash(), s.nav(), s.equityValue(), s.exposurePct(),
                s.lastObservedAt(), s.lastProcessedCollectedAt(), s.lastNavTradingDate(), dbNow));
    }

    // ------------------------------------------------------------------ 보유·재진입 금지
    @Override
    public List<Position> positions(String code) {
        return new ArrayList<>(positions.getOrDefault(code, new TreeMap<>()).values());
    }

    @Override
    public void upsertPosition(Position p) {
        if (p.qty() <= 0) {
            throw new IllegalArgumentException("qty > 0 CHECK 위반: " + p);
        }
        positions.computeIfAbsent(p.bookCode(), k -> new TreeMap<>()).put(p.ticker(), p);
    }

    @Override
    public void deletePosition(String code, String ticker) {
        positions.getOrDefault(code, new TreeMap<>()).remove(ticker);
    }

    @Override
    public List<Lockout> lockouts(String code) {
        return new ArrayList<>(lockouts.getOrDefault(code, new TreeMap<>()).values());
    }

    @Override
    public void upsertLockout(Lockout l) {
        lockouts.computeIfAbsent(l.bookCode(), k -> new TreeMap<>()).put(l.ticker(), l);
    }

    @Override
    public int deleteLockoutsBefore(String code, LocalDate today) {
        TreeMap<String, Lockout> map = lockouts.getOrDefault(code, new TreeMap<>());
        int before = map.size();
        map.values().removeIf(l -> l.untilTradingDate().isBefore(today));
        return before - map.size();
    }

    // ------------------------------------------------------------------ 주문·체결
    @Override
    public long insertOrder(Order o) {
        long id = ++sequence;
        orders.put(id, new Order(id, o.bookCode(), o.decisionId(), o.ticker(), o.side(), o.qty(), o.targetWeightPct(),
                o.sellFraction(), o.timing(), o.source(), o.status(), o.rejectReason(), o.reason(),
                o.asOfTradingDate(), o.createdAt() == null ? dbNow : o.createdAt(), o.expiresOn(), o.filledAt()));
        return id;
    }

    @Override
    public List<Order> ordersByStatus(String code, String status) {
        return orders.values().stream().filter(o -> o.bookCode().equals(code) && o.status().equals(status))
                .sorted(Comparator.comparing(Order::createdAt).thenComparing(Order::id)).toList();
    }

    @Override
    public List<Order> recentOrders(String code, int limit) {
        return orders.values().stream().filter(o -> o.bookCode().equals(code))
                .sorted(Comparator.comparing(Order::createdAt).thenComparing(Order::id).reversed()).limit(limit).toList();
    }

    @Override
    public void markOrderFilled(long id, Instant filledAt) {
        Order o = orders.get(id);
        orders.put(id, with(o, "FILLED", o.rejectReason(), filledAt));
    }

    @Override
    public void markOrderStatus(long id, String status, String rejectReason) {
        Order o = orders.get(id);
        orders.put(id, with(o, status, rejectReason, o.filledAt()));
    }

    @Override
    public void updateOrderReason(long id, JsonNode reason) {
        Order o = orders.get(id);
        orders.put(id, new Order(o.id(), o.bookCode(), o.decisionId(), o.ticker(), o.side(), o.qty(),
                o.targetWeightPct(), o.sellFraction(), o.timing(), o.source(), o.status(), o.rejectReason(),
                reason == null ? new ObjectMapper().createObjectNode() : reason, o.asOfTradingDate(), o.createdAt(),
                o.expiresOn(), o.filledAt()));
    }

    @Override
    public List<Order> ordersByDecision(long decisionId) {
        return orders.values().stream().filter(o -> o.decisionId() != null && o.decisionId() == decisionId).toList();
    }

    @Override
    public int expireOrders(String code, LocalDate today) {
        int n = 0;
        for (Order o : new ArrayList<>(orders.values())) {
            if (o.bookCode().equals(code) && "PENDING".equals(o.status()) && o.expiresOn() != null
                    && o.expiresOn().isBefore(today)) {
                orders.put(o.id(), with(o, "EXPIRED", o.rejectReason(), o.filledAt()));
                n++;
            }
        }
        return n;
    }

    @Override
    public int cancelPendingOrders(String code) {
        int n = 0;
        for (Order o : new ArrayList<>(orders.values())) {
            if (o.bookCode().equals(code) && ("PENDING".equals(o.status()) || "PROPOSED".equals(o.status()))) {
                orders.put(o.id(), with(o, "CANCELLED", o.rejectReason(), o.filledAt()));
                n++;
            }
        }
        return n;
    }

    private static Order with(Order o, String status, String rejectReason, Instant filledAt) {
        return new Order(o.id(), o.bookCode(), o.decisionId(), o.ticker(), o.side(), o.qty(), o.targetWeightPct(),
                o.sellFraction(), o.timing(), o.source(), status, rejectReason, o.reason(), o.asOfTradingDate(),
                o.createdAt(), o.expiresOn(), filledAt);
    }

    @Override
    public long insertFill(Fill f) {
        if (fills.values().stream().anyMatch(existing -> existing.orderId() == f.orderId())) {
            throw new DuplicateKeyException("paper_fills_order_id_key: " + f.orderId());
        }
        if (f.qty() <= 0) {
            throw new IllegalArgumentException("qty > 0 CHECK 위반: " + f);
        }
        long id = ++sequence;
        fills.put(id, new Fill(id, f.orderId(), f.bookCode(), f.ticker(), f.side(), f.qty(), f.price(), f.rawPrice(),
                f.slippageBp(), f.gross(), f.observedAt(), f.observationDate(), f.priceSource(), f.backfill(),
                f.createdAt() == null ? dbNow : f.createdAt()));
        return id;
    }

    @Override
    public List<Fill> recentFills(String code, int limit) {
        return fills.values().stream().filter(f -> f.bookCode().equals(code))
                .sorted(Comparator.comparing(Fill::createdAt).thenComparing(Fill::id).reversed()).limit(limit).toList();
    }

    List<Fill> fillsOf(String code) {
        return fills.values().stream().filter(f -> f.bookCode().equals(code)).toList();
    }

    List<Order> ordersOf(String code) {
        return orders.values().stream().filter(o -> o.bookCode().equals(code)).toList();
    }

    // ------------------------------------------------------------------ 순자산·기록·설정
    @Override
    public void upsertNav(NavRow r) {
        TreeMap<LocalDate, NavRow> map = nav.computeIfAbsent(r.bookCode(), k -> new TreeMap<>());
        NavRow existing = map.get(r.tradingDate());
        if (existing != null && existing.pricesFinal() && !r.pricesFinal()) {
            return;
        }
        map.put(r.tradingDate(), r);
    }

    @Override
    public List<NavRow> navSeries(String code) {
        return new ArrayList<>(nav.getOrDefault(code, new TreeMap<>()).values());
    }

    @Override
    public List<NavRow> navSeriesAll() {
        List<NavRow> out = new ArrayList<>();
        new TreeMap<>(nav).forEach((code, rows) -> out.addAll(rows.values()));
        return out;
    }

    @Override
    public void recordJobRun(String job, Instant startedAt, Instant finishedAt, String outcome, JsonNode detail) {
        jobRuns.add(new JobRun(++sequence, job, startedAt, finishedAt, outcome, detail));
    }

    @Override
    public List<JobRun> recentJobRuns(int limit) {
        List<JobRun> out = new ArrayList<>(jobRuns);
        java.util.Collections.reverse(out);
        return out.stream().limit(limit).toList();
    }

    // ------------------------------------------------------------------ 출발일 종가
    @Override
    public Optional<StartPrice> startPrice(String ticker) {
        return Optional.ofNullable(startPrices.get(ticker));
    }

    @Override
    public List<StartPrice> startPrices() {
        return new ArrayList<>(startPrices.values());
    }

    /** 같은 거래일 값은 덮지 않고, 다른 거래일 행만 바꿉니다. 종가는 저장 열처럼 소수 6자리로 반올림합니다(NUMERIC(18,6)). */
    @Override
    public boolean insertStartPriceIfAbsent(StartPrice p) {
        StartPrice existing = startPrices.get(p.ticker());
        if (existing != null && existing.tradingDate().equals(p.tradingDate())) {
            return false;
        }
        double close = java.math.BigDecimal.valueOf(p.close()).setScale(6, java.math.RoundingMode.HALF_UP).doubleValue();
        startPrices.put(p.ticker(), new StartPrice(p.ticker(), p.tradingDate(), close, p.source(),
                p.capturedAt() == null ? dbNow : p.capturedAt()));
        return true;
    }

    @Override
    public Optional<SettingsRow> settings() {
        return Optional.ofNullable(settings);
    }

    @Override
    public boolean initSettingsIfAbsent(JsonNode body) {
        if (settings != null) {
            return false;
        }
        settings = new SettingsRow(1, body, dbNow);
        settingsHistory.put(1, settings);
        return true;
    }

    @Override
    public Optional<SettingsRow> settingsAt(int version) {
        return Optional.ofNullable(settingsHistory.get(version));
    }

    // ------------------------------------------------------------------ 트랙레코드·선정
    /** (기준일, 그룹, CIK) 충돌 시 통째로 바꿉니다. 비유한 수치는 실제 저장소처럼 거절합니다. */
    @Override
    public void upsertTrackRecord(TrackRecordRow r) {
        for (Double value : java.util.Arrays.asList(r.cumReturnPct(), r.cagrPct(), r.volPct(), r.mddPct(), r.sharpe(),
                r.excessVsSpyPct(), r.coverageMin(), r.coverageAvg())) {
            if (value != null && !Double.isFinite(value)) {
                throw new IllegalArgumentException("유한하지 않은 수치: " + r);
            }
        }
        trackRecords.computeIfAbsent(r.asOfDate(), k -> new TreeMap<>()).put(r.groupCode() + ":" + r.cik(),
                new TrackRecordRow(r.asOfDate(), r.cik(), r.windowYears(), r.metric(), r.periodsUsed(),
                        r.periodsTotal(), r.firstFilingDate(), r.lastFilingDate(), r.cumReturnPct(), r.cagrPct(),
                        r.volPct(), r.mddPct(), r.sharpe(), r.excessVsSpyPct(), r.coverageMin(), r.coverageAvg(),
                        r.eligible(), r.ineligibleReason(), r.rank(), r.periods(), r.settingsVersion(),
                        r.computedAt() == null ? dbNow : r.computedAt(), r.groupCode(), r.dataSource()));
    }

    @Override
    public int deleteTrackRecords(LocalDate asOf, String groupCode) {
        TrackRecordRow.requireGroup(groupCode);
        TreeMap<String, TrackRecordRow> rows = trackRecords.get(asOf);
        if (rows == null) {
            return 0;
        }
        int before = rows.size();
        rows.values().removeIf(r -> r.groupCode().equals(groupCode));
        if (rows.isEmpty()) {
            trackRecords.remove(asOf);
        }
        return before - rows.size();
    }

    /** 그 그룹만, 순위 순(없는 것은 뒤), CIK 순. */
    @Override
    public List<TrackRecordRow> trackRecords(LocalDate asOf, String groupCode) {
        TrackRecordRow.requireGroup(groupCode);
        return trackRecords.getOrDefault(asOf, new TreeMap<>()).values().stream()
                .filter(r -> r.groupCode().equals(groupCode))
                .sorted(Comparator.comparing(TrackRecordRow::rank, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TrackRecordRow::cik))
                .toList();
    }

    @Override
    public Optional<LocalDate> latestTrackRecordAsOf(String groupCode) {
        TrackRecordRow.requireGroup(groupCode);
        return trackRecords.descendingMap().entrySet().stream()
                .filter(e -> e.getValue().values().stream().anyMatch(r -> r.groupCode().equals(groupCode)))
                .map(Map.Entry::getKey).findFirst();
    }

    @Override
    public boolean insertSelectionIfAbsent(Selection s) {
        if (selections.containsKey(s.asOfDate())) {
            return false;
        }
        selections.put(s.asOfDate(), new Selection(s.asOfDate(), s.selectedCiks(),
                s.ranking() == null ? new ObjectMapper().createArrayNode() : s.ranking(), s.settingsVersion(), dbNow));
        return true;
    }

    /** 최근 기준일 순. */
    @Override
    public List<Selection> selections() {
        return new ArrayList<>(selections.descendingMap().values());
    }

    @Override
    public Optional<Selection> selectionOnOrBefore(LocalDate date) {
        return Optional.ofNullable(selections.floorEntry(date)).map(Map.Entry::getValue);
    }

    // ------------------------------------------------------------------ 결정·게이트
    /** (책, 거래일, 종류) 유일 — 이미 있으면 empty. */
    @Override
    public Optional<Long> insertDecisionIfAbsent(Decision d) {
        if (decisionFor(d.bookCode(), d.asOfTradingDate(), d.kind()).isPresent()) {
            return Optional.empty();
        }
        long id = ++sequence;
        decisions.put(id, new Decision(id, d.bookCode(), d.asOfTradingDate(), d.kind(),
                d.decidedAt() == null ? dbNow : d.decidedAt(), d.status(), d.model(), d.frameworkVersion(),
                d.settingsVersion(), d.selectionAsOf(), d.targetExposurePct(), d.packet(), d.packetHash(),
                d.responseRaw(), d.response(), d.usage(), d.error(), d.noteIds(), d.pricesFinal(), d.latencyMs(),
                d.retryCount()));
        return Optional.of(id);
    }

    @Override
    public Optional<Decision> decision(long id) {
        return Optional.ofNullable(decisions.get(id));
    }

    @Override
    public Optional<Decision> decisionFor(String code, LocalDate tradingDate, String kind) {
        return decisions.values().stream().filter(d -> d.bookCode().equals(code)
                && d.asOfTradingDate().equals(tradingDate) && d.kind().equals(kind)).findFirst();
    }

    @Override
    public boolean hasDecisionWithSelection(String code, java.util.Collection<String> kinds, LocalDate selectionAsOf) {
        return selectionAsOf != null && decisions.values().stream().anyMatch(d -> d.bookCode().equals(code)
                && kinds.contains(d.kind()) && selectionAsOf.equals(d.selectionAsOf()));
    }

    @Override
    public List<DecisionSummary> decisions(String code, int limit) {
        return decisions.values().stream().filter(d -> d.bookCode().equals(code))
                .sorted(Comparator.comparing(Decision::asOfTradingDate).thenComparing(Decision::decidedAt)
                        .thenComparing(Decision::id).reversed())
                .limit(limit)
                .map(d -> {
                    List<Order> made = ordersByDecision(d.id());
                    JsonNode summary = d.response() == null ? null : d.response().get("summary");
                    return new DecisionSummary(d.id(), d.bookCode(), d.asOfTradingDate(), d.kind(), d.decidedAt(),
                            d.status(), d.model(), d.frameworkVersion(), d.settingsVersion(), d.selectionAsOf(),
                            d.targetExposurePct(), summary == null || summary.isNull() ? null : summary.asString(""),
                            d.error(), d.pricesFinal(), made.size(),
                            (int) made.stream().filter(o -> "REJECTED".equals(o.status())).count());
                }).toList();
    }

    @Override
    public void updateDecision(long id, String status, JsonNode response, String error) {
        Decision d = decisions.get(id);
        decisions.put(id, new Decision(d.id(), d.bookCode(), d.asOfTradingDate(), d.kind(), d.decidedAt(), status,
                d.model(), d.frameworkVersion(), d.settingsVersion(), d.selectionAsOf(), d.targetExposurePct(),
                d.packet(), d.packetHash(), d.responseRaw(), response == null ? d.response() : response, d.usage(),
                error, d.noteIds(), d.pricesFinal(), d.latencyMs(), d.retryCount()));
    }

    List<Decision> decisionsOf(String code) {
        return decisions.values().stream().filter(d -> d.bookCode().equals(code)).toList();
    }

    @Override
    public boolean insertGateIfAbsent(GateRow g) {
        if (gates.containsKey(g.tradingDate())) {
            return false;
        }
        gates.put(g.tradingDate(), new GateRow(g.tradingDate(), g.inputs(), g.level(),
                g.streaks() == null ? new ObjectMapper().createObjectNode() : g.streaks(), dbNow));
        return true;
    }

    @Override
    public Optional<GateRow> gate(LocalDate tradingDate) {
        return Optional.ofNullable(gates.get(tradingDate));
    }

    @Override
    public Optional<GateRow> latestGateBefore(LocalDate tradingDate) {
        return Optional.ofNullable(gates.lowerEntry(tradingDate)).map(Map.Entry::getValue);
    }

    @Override
    public List<GateRow> recentGate(int limit) {
        return gates.descendingMap().values().stream().limit(limit).toList();
    }

    @Override
    public void resetBook(String code) {
        decisions.values().removeIf(d -> d.bookCode().equals(code));
        orders.values().removeIf(o -> o.bookCode().equals(code));
        fills.values().removeIf(f -> f.bookCode().equals(code));
        positions.remove(code);
        lockouts.remove(code);
        nav.remove(code);
        states.remove(code);
    }

    /** 표 전체의 얕은 복사(레코드는 바뀌지 않으니 표만 복사하면 됨). */
    private record Snapshot(Map<String, Book> books, Map<String, BookState> states,
                            Map<String, TreeMap<String, Position>> positions,
                            Map<String, TreeMap<String, Lockout>> lockouts, TreeMap<Long, Order> orders,
                            TreeMap<Long, Fill> fills, Map<String, TreeMap<LocalDate, NavRow>> nav,
                            List<JobRun> jobRuns, Map<String, StartPrice> startPrices, SettingsRow settings,
                            TreeMap<LocalDate, TreeMap<String, TrackRecordRow>> trackRecords,
                            TreeMap<LocalDate, Selection> selections, TreeMap<Long, Decision> decisions,
                            TreeMap<LocalDate, GateRow> gates) {
        Snapshot(FakePaperStore s) {
            this(new TreeMap<>(s.books), new TreeMap<>(s.states), deep(s.positions), deep(s.lockouts),
                    new TreeMap<>(s.orders), new TreeMap<>(s.fills), deep(s.nav), new ArrayList<>(s.jobRuns),
                    new TreeMap<>(s.startPrices), s.settings, deepDates(s.trackRecords), new TreeMap<>(s.selections),
                    new TreeMap<>(s.decisions), new TreeMap<>(s.gates));
        }

        void restore(FakePaperStore s) {
            s.books = books;
            s.states = states;
            s.positions = positions;
            s.lockouts = lockouts;
            s.orders = orders;
            s.fills = fills;
            s.nav = nav;
            s.jobRuns = jobRuns;
            s.startPrices = startPrices;
            s.settings = settings;
            s.trackRecords = trackRecords;
            s.selections = selections;
            s.decisions = decisions;
            s.gates = gates;
        }

        private static TreeMap<LocalDate, TreeMap<String, TrackRecordRow>> deepDates(
                TreeMap<LocalDate, TreeMap<String, TrackRecordRow>> in) {
            TreeMap<LocalDate, TreeMap<String, TrackRecordRow>> out = new TreeMap<>();
            in.forEach((k, v) -> out.put(k, new TreeMap<>(v)));
            return out;
        }

        private static <K, X> Map<String, TreeMap<K, X>> deep(Map<String, TreeMap<K, X>> in) {
            Map<String, TreeMap<K, X>> out = new HashMap<>();
            in.forEach((k, v) -> out.put(k, new TreeMap<>(v)));
            return out;
        }
    }
}
