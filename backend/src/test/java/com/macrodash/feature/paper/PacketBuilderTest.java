package com.macrodash.feature.paper;

import com.macrodash.analytics.DailySeries;
import com.macrodash.analytics.PointInTime;
import com.macrodash.analytics.Signal13F;
import com.macrodash.analytics.UsMarketCalendar;
import com.macrodash.feature.paper.PacketBuilder.Packet;
import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.PaperStore.Book;
import com.macrodash.store.PaperStore.BookState;
import com.macrodash.store.PaperStore.Decision;
import com.macrodash.store.PaperStore.GateRow;
import com.macrodash.store.PaperStore.Lockout;
import com.macrodash.store.PaperStore.Order;
import com.macrodash.store.PaperStore.Position;
import com.macrodash.store.PaperStore.Selection;
import com.macrodash.store.PaperStore.SettingsRow;
import com.macrodash.store.PaperStore.TrackRecordRow;
import com.macrodash.store.RegimeLogStore;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreRepository;
import com.macrodash.store.StoreRepository.HoldingRow;
import com.macrodash.store.StoreRepository.TimeseriesColumns;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.macrodash.feature.paper.PaperFixtures.FRI;
import static com.macrodash.feature.paper.PaperFixtures.FRI_CLOSE;
import static com.macrodash.feature.paper.PaperFixtures.MAPPER;
import static com.macrodash.feature.paper.PaperFixtures.MON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Claude 패킷 — 결정적(같은 입력 같은 해시), 초기 패킷의 Point-in-Time(기준일 뒤 날짜 값 없음, 카드는 asOfMismatch), 게이트 문구·
 * 재구성, 트랙레코드·전략 프로필·13F 변화 섹션(그룹별 기관 상한), 하드 규칙 문구, 정기 패킷의 장부·대기 주문·최근 결정, 검증기와 같은
 * 가격 없음·묵은 입력 표시, 크기 목표(25,000토큰).
 *
 * <p>저장소·리더는 가짜이고, 시계열 가짜는 읽기 범위(from·to)를 <b>일부러 무시</b>합니다 — 기준일 뒤 값(10-26 가격·FRED, 11-13에
 * 낸 3분기 13F)이 들어와도 패킷이 스스로 자르는지 봅니다. 선정은 출발일(2026-10-23) 하나: 그룹 A = F1·F2, 그룹 B = F1·버크셔
 * (기존 메뉴의 실제 CIK — 이름·설명을 메뉴 목록에서 읽음). 13F 분기: Q1(03-31, 05-15 공시) · Q2(06-30, 08-14) · Q3(09-30, 11-13 —
 * 기준일 뒤 공시라 보이면 안 됨).
 * <ul>
 *   <li>CCC: F1·F2·버크셔 신규 → 점수 1.0(교차 그룹), BBB: F1·F2 신규 → 0.8333</li>
 *   <li>OLD: 세 곳 모두 전량 매도 → 매도 신호</li>
 * </ul>
 */
class PacketBuilderTest {

    private static final String F1 = "0000000001";
    private static final String F2 = "0000000002";
    private static final String F4 = "0000000004";
    /** 버크셔 해서웨이 — 기존 13F 메뉴(Institutions.ALL)에 있는 CIK. */
    private static final String BRK = "0001067983";
    private static final String NPS = "0001608046";
    private static final LocalDate TUE = LocalDate.of(2026, 10, 27);
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-27T03:00:00Z"), ZoneOffset.UTC);
    /** 관측치 저장본 수집 시각 — 월요일 마감 30분 뒤(MON 확정 종가). */
    private static final Instant QUOTES_AT = Instant.parse("2026-10-26T20:30:00Z");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** 자료가 아니라 달력·규칙이 정한 미래 날짜(다음 세션·휴장일·재진입 금지 끝). */
    private static final Set<String> CALENDAR_KEYS = Set.of("nextSession", "upcomingHolidays", "untilTradingDate");

    private FakePaperStore store;
    private StoreReader reader;
    private StoreRepository repository;
    private RegimeLogStore regimeLog;
    private Map<String, List<HoldingRow>> holdings;
    private Map<String, Snapshot> snapshots;
    private Map<String, TimeseriesColumns> fredColumns;
    private Map<String, TimeseriesColumns> priceColumns;
    private List<RegimeLogStore.Entry> regimeEntries;
    private PacketBuilder builder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = new FakePaperStore();
        holdings = new HashMap<>();
        snapshots = new HashMap<>();
        fredColumns = new TreeMap<>();
        priceColumns = new TreeMap<>();
        regimeEntries = new ArrayList<>();
        reader = mock(StoreReader.class);
        when(reader.quietly(any())).thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(0)).get());
        when(reader.readStoredHoldings(anyString())).thenAnswer(invocation ->
                holdings.getOrDefault((String) invocation.getArgument(0), List.of()));
        when(reader.read(anyString(), anyLong(), any())).thenAnswer(invocation ->
                Optional.ofNullable(snapshots.get((String) invocation.getArgument(0))));
        repository = mock(StoreRepository.class);
        when(repository.readTimeseriesColumns(anyString(), anyCollection(), any(), any())).thenAnswer(invocation -> {
            String dataset = invocation.getArgument(0);
            Collection<String> ids = invocation.getArgument(1);
            Map<String, TimeseriesColumns> source = Datasets.TS_FRED.equals(dataset) ? fredColumns
                    : Datasets.TS_EQUITY_CLOSE.equals(dataset) ? priceColumns : Map.of();
            // 범위(from·to)는 일부러 무시합니다 — 패킷이 기준일에서 스스로 자르는지 보려고.
            Map<String, TimeseriesColumns> out = new LinkedHashMap<>();
            new TreeSet<>(ids).forEach(id -> {
                if (source.containsKey(id)) {
                    out.put(id, source.get(id));
                }
            });
            return out;
        });
        regimeLog = mock(RegimeLogStore.class);
        when(regimeLog.ready()).thenReturn(true);
        when(regimeLog.recent(anyInt())).thenAnswer(invocation -> regimeEntries);

        ObjectNode body = (ObjectNode) PaperFixtures.settings(store).defaultsJson();
        ((ObjectNode) body.get("trackRecord")).putNull("candidateCiks");
        store.settings = new SettingsRow(1, body, store.dbNow);
        store.settingsHistory.put(1, store.settings);

        builder = newBuilder();
        standardFixture();
    }

    private PacketBuilder newBuilder() {
        return newBuilder(NOW);
    }

    /** 시계 — 정기 결정의 inputsStale(관측치 저장본 24시간 초과)만 읽습니다. */
    private PacketBuilder newBuilder(Clock clock) {
        PaperMarketData marketData = new PaperMarketData(reader, repository);
        PaperSettingsService settings = PaperFixtures.settings(store);
        PaperSelectionService selection = new PaperSelectionService(store, reader, marketData, settings, MAPPER, NOW);
        PaperLedgerService ledger = new PaperLedgerService(store, marketData, settings, MAPPER);
        PaperAdminService admin = new PaperAdminService(store, marketData, reader, settings, ledger,
                PaperFixtures.config(), MAPPER, NOW);
        RuleBookService rules = new RuleBookService(store, reader, marketData, selection, settings, ledger, admin,
                PaperFixtures.config(), MAPPER, NOW);
        return new PacketBuilder(store, reader, repository, regimeLog, marketData, selection, rules, settings,
                PaperFixtures.config(), MAPPER, clock);
    }

    // ------------------------------------------------------------------ 재료
    private record Line(String ticker, double shares, double value) {
    }

    private record Quarter(String report, String filed, List<Line> lines) {
    }

    private static Line line(String ticker, double shares, double value) {
        return new Line(ticker, shares, value);
    }

    private static Quarter quarter(String report, String filed, Line... lines) {
        return new Quarter(report, filed, List.of(lines));
    }

    /** 13F 저장본(최근 분기 먼저). */
    private void filings(String cik, int stored, Quarter... quarters) {
        List<HoldingRow> rows = new ArrayList<>();
        for (int i = 0; i < quarters.length; i++) {
            Quarter q = quarters[i];
            for (Line l : q.lines()) {
                rows.add(new HoldingRow(i, q.filed(), q.report(), cik, "CUSIP" + l.ticker(), l.ticker(), l.value(),
                        l.shares()));
            }
        }
        holdings.put(Datasets.sec13f(cik, stored), rows);
    }

    private static List<LocalDate> tradingDays(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (UsMarketCalendar.isTradingDay(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /** 날짜로 정해지는 가격 — 이력과 관측치 저장본이 같은 날 같은 값이 되게. */
    private static double price(double base, LocalDate date) {
        return Math.round((base + 0.01 * (date.toEpochDay() - 20_000)) * 10_000) / 10_000.0;
    }

    private static TimeseriesColumns columns(NavigableMap<LocalDate, Double> points) {
        int[] days = new int[points.size()];
        double[] values = new double[points.size()];
        int i = 0;
        for (Map.Entry<LocalDate, Double> e : points.entrySet()) {
            days[i] = (int) e.getKey().toEpochDay();
            values[i] = e.getValue();
            i++;
        }
        return new TimeseriesColumns(days, values);
    }

    private void history(String ticker, double base, LocalDate from, LocalDate to) {
        NavigableMap<LocalDate, Double> points = new TreeMap<>();
        tradingDays(from, to).forEach(d -> points.put(d, price(base, d)));
        priceColumns.put(ticker, columns(points));
    }

    private static Map<String, Object> frame(double base, List<LocalDate> dates) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dates", dates.stream().map(LocalDate::toString).toList());
        out.put("close", dates.stream().map(d -> price(base, d)).toList());
        return out;
    }

    private void snapshot(String name, Object payload, Instant collectedAt) {
        snapshots.put(name, new Snapshot(name, MAPPER.valueToTree(payload), "json", "ok", null, collectedAt));
    }

    private static Map<String, Object> card(String key, String name, double price, double prev, String lastTs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("name", name);
        out.put("status", "ok");
        out.put("price", price);
        out.put("prevValue", prev);
        out.put("pct", (price / prev - 1) * 100);
        out.put("delayMinutes", 10);
        out.put("lastTs", lastTs);
        out.put("source", "TradingView");
        return out;
    }

    private static Map<String, Object> category(String id, List<Map<String, Object>> items) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("items", items);
        return out;
    }

    private static TrackRecordRow track(String cik, String group, Integer rank, double cagr, boolean eligible,
                                        String reason, String source) {
        return new TrackRecordRow(FRI, cik, TrackRecordRow.GROUP_A.equals(group) ? 7 : 2, "CAGR", 26, 28,
                LocalDate.of(2019, 11, 14), LocalDate.of(2026, 8, 14), cagr * 3, cagr, 18.0, -25.5, 1.1, cagr - 12.3,
                0.62, 0.81, eligible, reason, rank, MAPPER.createArrayNode(), 1, FRI_CLOSE, group, source);
    }

    /** D27 선정 행 — PaperSelectionService.draftSelection과 같은 모양(가중 = blendWeights). */
    private void selection(LocalDate asOf, List<String> groupA, List<String> groupB, Map<String, String> names) {
        Map<String, Double> weights = Signal13F.blendWeights(groupA, groupB, 0.5);
        Map<String, Object> ranking = new LinkedHashMap<>();
        List<Map<String, Object>> a = new ArrayList<>();
        for (int i = 0; i < groupA.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cik", groupA.get(i));
            row.put("name", names.get(groupA.get(i)));
            row.put("rank", i + 1);
            row.put("selected", true);
            a.add(row);
        }
        List<Map<String, Object>> b = new ArrayList<>();
        for (int i = 0; i < groupB.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cik", groupB.get(i));
            row.put("name", names.get(groupB.get(i)));
            row.put("rank", i + 1);
            row.put("selected", true);
            b.add(row);
        }
        List<Map<String, Object>> pool = new ArrayList<>();
        weights.forEach((cik, weight) -> {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("cik", cik);
            member.put("name", names.get(cik));
            List<String> groups = new ArrayList<>();
            if (groupA.contains(cik)) {
                groups.add("A");
            }
            if (groupB.contains(cik)) {
                groups.add("B");
            }
            member.put("groups", groups);
            member.put("rankA", groupA.contains(cik) ? groupA.indexOf(cik) + 1 : null);
            member.put("rankB", groupB.contains(cik) ? groupB.indexOf(cik) + 1 : null);
            member.put("weight", weight);
            pool.add(member);
        });
        ranking.put("groupA", a);
        ranking.put("groupB", b);
        ranking.put("pool", pool);
        ranking.put("groupWeightA", 0.5);
        store.selections.put(asOf, new Selection(asOf, List.copyOf(weights.keySet()), MAPPER.valueToTree(ranking), 1,
                store.dbNow));
    }

    private Map<String, Object> gateInputs(double brent, String brentTs, double wti, String wtiTs) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("us10y", Map.of("value", 4.99, "lastTs", "2026-10-24 05:00", "isStale", false));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("value", brent);
        b.put("lastTs", brentTs);
        b.put("isStale", false);
        inputs.put("brent", b);
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("value", wti);
        w.put("lastTs", wtiTs);
        w.put("isStale", false);
        inputs.put("wti", w);
        inputs.put("usdjpy", Map.of("value", 151.0, "lastTs", "2026-10-24 05:00", "isStale", false));
        inputs.put("snapshotCollectedAtKst", "2026-10-24 06:31 KST");
        return inputs;
    }

    /** 클래스 설명의 기본 재료. Claude 책은 시작 전(현금만). */
    private void standardFixture() {
        store.books.put(PaperBooks.CLAUDE, new Book(PaperBooks.CLAUDE, PaperBooks.KIND_CLAUDE, "Claude 재량 책", 100_000,
                null, PaperBooks.NOT_STARTED, FRI_CLOSE));
        store.states.put(PaperBooks.CLAUDE, new BookState(PaperBooks.CLAUDE, 100_000, 100_000, 0, 0, null, null, null,
                null));

        Map<String, String> names = Map.of(F1, "Alpha Capital", F2, "Beta Partners", BRK, "버크셔(선정 행 이름)");
        selection(FRI, List.of(F1, F2), List.of(F1, BRK), names);
        store.upsertTrackRecord(track(F1, TrackRecordRow.GROUP_A, 1, 20.5, true, null, TrackRecordRow.SOURCE_Q30));
        store.upsertTrackRecord(track(F2, TrackRecordRow.GROUP_A, 2, 15.2, true, null, TrackRecordRow.SOURCE_Q30));
        store.upsertTrackRecord(track(F4, TrackRecordRow.GROUP_A, null, 30.0, false, "커버리지 미달(평균 0.55 < 0.7)",
                TrackRecordRow.SOURCE_Q30));
        store.upsertTrackRecord(track(F1, TrackRecordRow.GROUP_B, 1, 26.8, true, null, TrackRecordRow.SOURCE_Q30));
        store.upsertTrackRecord(track(BRK, TrackRecordRow.GROUP_B, 2, 13.8, true, null, TrackRecordRow.SOURCE_Q8));
        store.upsertTrackRecord(track(NPS, TrackRecordRow.GROUP_B, null, 18.5, false, "커버리지 미달(평균 0.57 < 0.7)",
                TrackRecordRow.SOURCE_Q8));

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("filers", List.of(
                Map.of("cik", F1, "name", "Alpha Capital", "style", "value", "storedQuarters", 30, "complete", true),
                Map.of("cik", F2, "name", "Beta Partners", "style", "growth", "storedQuarters", 30, "complete", true),
                Map.of("cik", F4, "name", "Delta Macro", "style", "macro", "storedQuarters", 30, "complete", true)));
        index.put("targetQuarters", 30);
        snapshot(Datasets.SNAP_TRACK_RECORD_INDEX, index, FRI_CLOSE);

        Quarter q3 = quarter("2026-09-30", "2026-11-13", line("ZZZ", 100, 90_000), line("AAA", 1000, 120_000));
        filings(F1, Datasets.TRACK_RECORD_QUARTERS, q3,
                quarter("2026-06-30", "2026-08-14", line("AAA", 1000, 110_000), line("BBB", 400, 40_000),
                        line("CCC", 300, 30_000)),
                quarter("2026-03-31", "2026-05-15", line("AAA", 1000, 100_000), line("OLD", 500, 50_000)));
        filings(F2, Datasets.TRACK_RECORD_QUARTERS,
                quarter("2026-09-30", "2026-11-13", line("ZZZ", 100, 50_000)),
                quarter("2026-06-30", "2026-08-14", line("BBB", 100, 10_000), line("CCC", 100, 10_000),
                        line("DDD", 300, 30_000)),
                quarter("2026-03-31", "2026-05-15", line("OLD", 100, 10_000), line("DDD", 200, 20_000)));
        filings(BRK, Datasets.MAX_TRACKED_QUARTERS,
                quarter("2026-06-30", "2026-08-14", line("CCC", 50, 5_000), line("EEE", 100, 10_000)),
                quarter("2026-03-31", "2026-05-15", line("OLD", 300, 30_000), line("EEE", 100, 10_000)));

        // 가격: 이력(10-26까지 — 기준일 뒤 값 포함)과 관측치 저장본(최근 60거래일, 10-26 봉 포함).
        LocalDate from = LocalDate.of(2025, 6, 2);
        history("BBB", 40, from, MON);
        history("CCC", 90, from, MON);
        history("SPY", 600, from, MON);
        history("XLK", 250, from, MON);
        List<LocalDate> recent = tradingDays(LocalDate.of(2026, 7, 30), MON);
        Map<String, Object> tickers = new LinkedHashMap<>();
        Map<String, Double> bases = new LinkedHashMap<>();
        bases.put("AAA", 100.0);
        bases.put("BBB", 40.0);
        bases.put("CCC", 90.0);
        bases.put("DDD", 20.0);
        bases.put("EEE", 30.0);
        bases.put("OLD", 10.0);
        bases.put("SHY", 82.0);
        bases.put("SPY", 600.0);
        bases.put("XLK", 250.0);
        bases.forEach((ticker, base) -> tickers.put(ticker, frame(base, recent)));
        snapshot(Datasets.SNAP_PAPER_QUOTES, Map.of("period", "3mo", "tickers", tickers), QUOTES_AT);

        // FRED: 10년물은 10-22 4.96 · 10-23 5.01 · 10-26 5.05(10-23 결정에는 10-22만 알려짐), NFCI는 주간(금요일 기준).
        NavigableMap<LocalDate, Double> dgs10 = new TreeMap<>();
        int k = 0;
        for (LocalDate d = LocalDate.of(2025, 9, 1); !d.isAfter(MON); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) {
                dgs10.put(d, 4.5 + 0.001 * k++);
            }
        }
        dgs10.put(LocalDate.of(2026, 10, 22), 4.96);
        dgs10.put(FRI, 5.01);
        dgs10.put(MON, 5.05);
        fredColumns.put("DGS10", columns(dgs10));
        NavigableMap<LocalDate, Double> nfci = new TreeMap<>();
        k = 0;
        for (LocalDate d = LocalDate.of(2025, 9, 5); !d.isAfter(FRI); d = d.plusWeeks(1)) {
            nfci.put(d, -0.6 + 0.001 * k++);
        }
        nfci.put(LocalDate.of(2026, 10, 16), -0.5);
        nfci.put(FRI, -0.45);
        fredColumns.put("NFCI", columns(nfci));

        // 환율 일봉 — 10-23 151.2, 10-26 152.3.
        List<LocalDate> fxDays = tradingDays(LocalDate.of(2026, 9, 1), MON);
        Map<String, Object> usdjpy = new LinkedHashMap<>();
        usdjpy.put("name", "달러/엔 (USD/JPY)");
        usdjpy.put("dates", fxDays.stream().map(LocalDate::toString).toList());
        usdjpy.put("close", fxDays.stream().map(d -> d.equals(FRI) ? 151.2 : d.equals(MON) ? 152.3 : 148.0).toList());
        snapshot(Datasets.SNAP_FX_HISTORY, Map.of("series", Map.of("usdjpy", usdjpy)), QUOTES_AT);

        // 매크로 카드 — 지금 수집본(10-27 KST 아침). us10y 카드 시각 = 10-26 16:00 ET, kospi = 10-27 02:30 ET.
        List<Map<String, Object>> categories = List.of(
                category("ust", List.of(card("us10y", "미국채 10년물 수익률(%)", 5.05, 5.01, "2026-10-27 05:00"))),
                category("commodity", List.of(card("brent", "브렌트유 ($)", 82.5, 81.0, "2026-10-27 05:59"),
                        card("wti", "WTI 원유 ($)", 78.1, 77.0, "2026-10-27 05:59"))),
                category("asia_equity", List.of(card("kospi", "코스피 (KOSPI)", 2650, 2640, "2026-10-27 15:30"))));
        snapshot(Datasets.SNAP_MACRO_COLLECTED, Map.of("categories", categories), Instant.parse("2026-10-27T06:31:00Z"));

        // 게이트 기록(출발일) — 브렌트 카드는 10-23 17:30 ET 관측(쓸 수 있음), WTI는 10-26 관측(결정 작업이 늦게 돎 — 못 씀).
        store.gates.put(FRI, new GateRow(FRI, MAPPER.valueToTree(gateInputs(81.0, "2026-10-24 06:30 (TradingView 수집 시각)",
                77.0, "2026-10-27 06:30")), "L0", MAPPER.valueToTree(Map.of("G1", 0)), FRI_CLOSE));

        regimeEntries.add(new RegimeLogStore.Entry(MON, "RISK_ON", "위험 선호",
                MAPPER.valueToTree(Map.of("curve", Map.of("value", 0.5, "asOf", "2026-10-23"))), List.of(), "v1"));
        regimeEntries.add(new RegimeLogStore.Entry(LocalDate.of(2026, 10, 22), "NEUTRAL", "중립",
                MAPPER.valueToTree(Map.of("curve", Map.of("value", 0.4, "asOf", "2026-10-21"))), List.of(), "v1"));
    }

    /** 객체·배열을 훑어 문자열 안의 날짜를 모읍니다 — asOfMismatch:true인 객체와 달력 키는 건너뜀. */
    private static void collectDates(JsonNode node, String key, List<String> out) {
        if (node == null || CALENDAR_KEYS.contains(key)) {
            return;
        }
        if (node.isObject()) {
            if (node.path("asOfMismatch").asBoolean(false)) {
                return;
            }
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                collectDates(entry.getValue(), entry.getKey(), out);
            }
        } else if (node.isArray()) {
            node.forEach(item -> collectDates(item, key, out));
        } else if (node.isString()) {
            Matcher m = DATE.matcher(node.asString(""));
            while (m.find()) {
                out.add(key + "=" + m.group());
            }
        }
    }

    private static List<String> datesAfter(JsonNode body, LocalDate asOf) {
        List<String> found = new ArrayList<>();
        collectDates(body, "", found);
        return found.stream().filter(entry -> LocalDate.parse(entry.substring(entry.length() - 10)).isAfter(asOf))
                .toList();
    }

    /** 8_prices의 열 배열 한 줄을 열 이름 → 값 객체로. */
    private static JsonNode priceRow(JsonNode body, String ticker) {
        JsonNode section = body.get(PacketBuilder.PRICES);
        List<String> columns = new ArrayList<>();
        section.get("columns").forEach(c -> columns.add(c.asString()));
        for (JsonNode values : section.get("rows")) {
            if (ticker.equals(values.get(0).asString())) {
                ObjectNode out = MAPPER.createObjectNode();
                for (int i = 0; i < columns.size(); i++) {
                    out.set(columns.get(i), values.get(i));
                }
                return out;
            }
        }
        throw new AssertionError(ticker + " 가격 줄이 없습니다");
    }

    private static JsonNode row(JsonNode rows, String field, String value) {
        for (JsonNode r : rows) {
            if (value.equals(r.path(field).asString(""))) {
                return r;
            }
        }
        throw new AssertionError(field + "=" + value + " 행이 없습니다: " + rows);
    }

    // ------------------------------------------------------------------ 결정적
    @Test
    @DisplayName("같은 입력이면 같은 문자열·같은 해시 — 다시 만들어도, 새 인스턴스로 만들어도")
    void deterministic() {
        Packet first = builder.build(FRI, RuleBookService.INITIAL, true);
        Packet second = builder.build(FRI, RuleBookService.INITIAL, true);
        Packet fresh = newBuilder().build(FRI, RuleBookService.INITIAL, true);

        assertThat(second.text()).isEqualTo(first.text());
        assertThat(second.sha256()).isEqualTo(first.sha256()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(fresh.sha256()).isEqualTo(first.sha256());
        assertThat(PacketBuilder.sha256(first.text())).isEqualTo(first.sha256());
        assertThat(MAPPER.writeValueAsString(first.body())).isEqualTo(first.text());
        assertThat(first.text()).doesNotContain("\n").doesNotContain("E-").doesNotContain("NaN");
        assertThat(first.noteIds()).isEmpty();

        // 입력이 바뀌면 해시가 바뀝니다(해시가 입력을 실제로 가리킴).
        store.states.put(PaperBooks.CLAUDE, new BookState(PaperBooks.CLAUDE, 100_000, 100_000, 0, 0, null, null, null,
                null));
        ((ObjectNode) store.settings.body()).put("maxWeightStockPct", 12);
        assertThat(builder.build(FRI, RuleBookService.INITIAL, true).sha256()).isNotEqualTo(first.sha256());
    }

    @Test
    @DisplayName("섹션은 0_instructions … 12_hardRules(6b 포함) 순서로, 들여쓰기 없이 직렬화")
    void sectionsInReadingOrder() {
        Packet packet = builder.build(FRI, RuleBookService.INITIAL, true);
        List<String> keys = new ArrayList<>();
        packet.body().propertyNames().forEach(keys::add);
        assertThat(keys).containsExactlyElementsOf(PacketBuilder.SECTION_KEYS);
        assertThat(packet.text()).startsWith("{\"0_instructions\":{");
        JsonNode basis = packet.body().get(PacketBuilder.BASIS);
        assertThat(basis.get("asOfTradingDate").asString()).isEqualTo("2026-10-23");
        assertThat(basis.get("decisionKind").asString()).isEqualTo("INITIAL");
        assertThat(basis.get("nextSession").asString()).isEqualTo("2026-10-26");
        assertThat(basis.get("selectionAsOf").asString()).isEqualTo("2026-10-23");
        assertThat(basis.get("settingsVersion").asInt()).isEqualTo(1);
        assertThat(basis.get("frameworkVersion").isNull()).isTrue();
        assertThat(builder.build(FRI, RuleBookService.INITIAL, true, 3).body().get(PacketBuilder.BASIS)
                .get("frameworkVersion").asInt()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 초기 결정 Point-in-Time
    @Test
    @DisplayName("초기 패킷에는 기준일(10-23) 뒤 날짜의 값이 없다 — 카드만 asOfMismatch로 현재 수집본")
    void initialPacketHasNothingAfterAsOf() {
        Packet packet = builder.build(FRI, RuleBookService.INITIAL, true);
        JsonNode body = packet.body();

        assertThat(datesAfter(body, FRI)).as("기준일 뒤 날짜").isEmpty();
        assertThat(packet.text()).doesNotContain("ZZZ").doesNotContain("2026-11-13");

        JsonNode cards = body.get(PacketBuilder.MACRO).get("cards");
        assertThat(cards.get("asOfMismatch").asBoolean()).isTrue();
        assertThat(cards.get("note").asString()).isEqualTo("10-23 기준값 없음 — 현재 수집본, 참고만");
        assertThat(cards.get("items")).hasSize(4);
        cards.get("items").forEach(card -> assertThat(card.get("asOfMismatch").asBoolean()).isTrue());

        JsonNode fx = row(body.get(PacketBuilder.MACRO).get("fxHistory").get("items"), "key", "usdjpy");
        assertThat(fx.get("close").asDouble()).isEqualTo(151.2);
        assertThat(fx.get("date").asString()).isEqualTo("2026-10-23");
        assertThat(row(body.get(PacketBuilder.MACRO).get("fxHistory").get("items"), "key", "dxy").get("close")
                .isNull()).as("저장본에 없는 계열은 0이 아니라 null").isTrue();

        // FRED: 10-23에 알려진 10년물 = 10-22 관측 4.96, NFCI = 10-16 관측(10-21 수요일 발표).
        JsonNode fred = body.get(PacketBuilder.FRED).get("series");
        JsonNode dgs10 = row(fred, "id", "DGS10");
        assertThat(dgs10.get("value").asDouble()).isEqualTo(4.96);
        assertThat(dgs10.get("observedDate").asString()).isEqualTo("2026-10-22");
        assertThat(dgs10.get("knownOn").asString()).isEqualTo("2026-10-23");
        JsonNode nfci = row(fred, "id", "NFCI");
        assertThat(nfci.get("observedDate").asString()).isEqualTo("2026-10-16");
        assertThat(nfci.get("value").asDouble()).isEqualTo(-0.5);
        assertThat(row(fred, "id", "DGS2").get("value").isNull()).as("저장본 없는 시리즈는 null").isTrue();

        // 가격: 10-23 종가에서 끝남(10-26 봉·이력은 잘림).
        JsonNode ccc = priceRow(body, "CCC");
        assertThat(ccc.get("role").asString()).isEqualTo("CANDIDATE");
        assertThat(ccc.get("date").asString()).isEqualTo("2026-10-23");
        assertThat(ccc.get("close").asDouble()).isEqualTo(price(90, FRI));
        assertThat(ccc.get("ret12mPct").isNull()).isFalse();
        assertThat(priceRow(body, "XLC").get("close").isNull()).as("가격 없는 종목은 null").isTrue();
        assertThat(priceRow(body, "XLC").get("ret1mPct").isNull()).isTrue();

        // 13F: 선정 기준일까지 낸 2분기(06-30)만 — 11-13에 낸 3분기는 보이지 않음.
        JsonNode filers = body.get(PacketBuilder.CHANGES).get("filers");
        assertThat(filers).hasSize(3);
        filers.forEach(f -> {
            assertThat(f.get("reportDate").asString()).isEqualTo("2026-06-30");
            assertThat(f.get("filingDate").asString()).isEqualTo("2026-08-14");
        });

        assertThat(body.get(PacketBuilder.REGIME).get("verdictDate").asString()).isEqualTo("2026-10-22");
        JsonNode portfolio = body.get(PacketBuilder.PORTFOLIO);
        assertThat(portfolio.get("cash").asDouble()).isEqualTo(100_000);
        assertThat(portfolio.get("positions")).isEmpty();
        assertThat(body.get(PacketBuilder.DECISIONS).get("decisions")).isEmpty();
    }

    @Test
    @DisplayName("게이트 지표: '구속하지 않음' 문구, 초기 결정은 기준일에 알려진 값으로 재구성(10년물 FRED 10-22, 기준일 관측 카드만)")
    void gateSection() {
        JsonNode gate = builder.build(FRI, RuleBookService.INITIAL, true).body().get(PacketBuilder.GATE);
        assertThat(gate.get("instruction").asString()).isEqualTo("과거 OS가 쓴 트리거 수준이며 구속하지 않음. 현금 비중은 네 판단");
        assertThat(gate.get("source").asString()).startsWith("재구성");
        assertThat(gate.get("inputs").get("us10y").get("value").asDouble()).isEqualTo(4.96);
        assertThat(gate.get("inputs").get("us10y").get("observedDate").asString()).isEqualTo("2026-10-22");
        assertThat(gate.get("inputs").get("brent").get("value").asDouble()).isEqualTo(81.0);
        assertThat(gate.get("inputs").get("wti").get("value").isNull()).as("10-26 관측 카드는 쓰지 않음").isTrue();
        assertThat(gate.get("inputs").get("usdjpy").get("value").asDouble()).isEqualTo(151.2);
        assertThat(gate.get("level").asString()).isEqualTo("UNKNOWN");
        assertThat(gate.get("missing").toString()).isEqualTo("[\"wti\"]");
        assertThat(gate.get("conditions").get("G1").asBoolean()).as("4.96 < 4.80 거짓").isFalse();

        // 정기 결정은 그날 기록(paper_gate_log)을 그대로.
        store.gates.put(MON, new GateRow(MON, MAPPER.valueToTree(gateInputs(82.5, "2026-10-27 05:59", 78.1,
                "2026-10-27 05:59")), "L0", MAPPER.valueToTree(Map.of("G1", 0)), FRI_CLOSE));
        activeClaude();
        JsonNode scheduled = builder.build(MON, RuleBookService.SCHEDULED, false).body().get(PacketBuilder.GATE);
        assertThat(scheduled.get("instruction").asString()).isEqualTo(PacketBuilder.GATE_INSTRUCTION);
        assertThat(scheduled.get("source").asString()).isEqualTo("paper_gate_log");
        assertThat(scheduled.get("level").asString()).isEqualTo("L0");
        assertThat(scheduled.get("tradingDate").asString()).isEqualTo("2026-10-26");

        // 그날 기록이 없으면 같은 규칙으로 계산만(저장하지 않음).
        store.gates.remove(MON);
        JsonNode computed = builder.build(MON, RuleBookService.SCHEDULED, false).body().get(PacketBuilder.GATE);
        assertThat(computed.get("source").asString()).startsWith("계산");
        assertThat(computed.get("inputs").get("us10y").get("value").asDouble()).isEqualTo(5.05);
        assertThat(store.gates).doesNotContainKey(MON);
    }

    // ------------------------------------------------------------------ 트랙레코드·프로필·13F
    @Test
    @DisplayName("트랙레코드: 그룹 A 순위 순(자격 없는 기관은 뒤·사유), 그룹 B 출처, 신호 풀(A1·B1 표시·가중), 복제 수익률 문구")
    void trackRecordSection() {
        JsonNode section = builder.build(FRI, RuleBookService.INITIAL, true).body().get(PacketBuilder.TRACK_RECORD);
        assertThat(section.get("available").asBoolean()).isTrue();
        assertThat(section.get("selectionAsOf").asString()).isEqualTo("2026-10-23");
        assertThat(section.get("note").asString()).startsWith("공시일 기준 복제 수익률");

        JsonNode a = section.get("groupA");
        assertThat(a.get("windowYears").asInt()).isEqualTo(7);
        List<String> ciksA = new ArrayList<>();
        a.get("rows").forEach(r -> ciksA.add(r.get("cik").asString()));
        assertThat(ciksA).containsExactly(F1, F2, F4);
        assertThat(a.get("rows").get(0).get("name").asString()).isEqualTo("Alpha Capital");
        assertThat(a.get("rows").get(0).get("style").asString()).isEqualTo("value");
        assertThat(a.get("rows").get(0).get("inPool").asBoolean()).isTrue();
        assertThat(a.get("rows").get(0).get("cagrPct").asDouble()).isEqualTo(20.5);
        assertThat(a.get("rows").get(2).get("inPool").asBoolean()).isFalse();
        assertThat(a.get("rows").get(2).get("ineligibleReason").asString()).contains("커버리지 미달");
        assertThat(a.get("rows").get(0).has("ineligibleReason")).isFalse();

        JsonNode b = section.get("groupB");
        assertThat(b.get("windowYears").asInt()).isEqualTo(2);
        assertThat(b.get("rows")).hasSize(3);
        assertThat(b.get("rows").get(1).get("cik").asString()).isEqualTo(BRK);
        assertThat(b.get("rows").get(1).get("source").asString()).isEqualTo("q8");
        assertThat(b.get("rows").get(1).get("name").asString()).contains("버크셔");

        JsonNode pool = section.get("pool");
        assertThat(pool).hasSize(3);
        assertThat(row(pool, "cik", F1).get("filer").asString()).isEqualTo("A1·B1");
        assertThat(row(pool, "cik", F1).get("weight").asDouble()).isEqualTo(0.6667);
        assertThat(row(pool, "cik", F2).get("filer").asString()).isEqualTo("A2");
        assertThat(row(pool, "cik", BRK).get("filer").asString()).isEqualTo("B2");
    }

    @Test
    @DisplayName("전략 프로필: 그룹·가중·수익률·집중도(유효 종목 수·HHI·상위 10)·회전율·상위 보유·메뉴 설명과 섞기 지시 문구")
    void profilesSection() {
        JsonNode section = builder.build(FRI, RuleBookService.INITIAL, true).body().get(PacketBuilder.PROFILES);
        assertThat(section.get("instruction").asString()).isEqualTo("두 그룹의 전략·패턴·장점을 섞어 판단 — 교차 확인 종목 우선, "
                + "집중형의 고확신은 크게, 분산형의 신규 편입은 테마 확인");
        JsonNode f1 = row(section.get("filers"), "cik", F1);
        assertThat(f1.get("groups").toString()).isEqualTo("[\"A\",\"B\"]");
        assertThat(f1.get("filerWeight").asDouble()).isEqualTo(0.6667);
        assertThat(f1.get("trackRecordA").get("cagrPct").asDouble()).isEqualTo(20.5);
        assertThat(f1.get("trackRecordB").get("cagrPct").asDouble()).isEqualTo(26.8);
        assertThat(f1.get("source").asString()).isEqualTo("q30");
        JsonNode quarter = f1.get("quarter");
        assertThat(quarter.get("reportDate").asString()).isEqualTo("2026-06-30");
        assertThat(quarter.get("latestQuarter").asBoolean()).isTrue();
        assertThat(quarter.get("holdings").asInt()).isEqualTo(3);
        // 비중 61.11·22.22·16.67 → HHI 0.4506, 유효 종목 2.2, 회전율 (5.56 + 22.22 + 16.67 + 33.33)/2 = 38.89.
        assertThat(quarter.get("hhi").asDouble()).isCloseTo(0.4506, within(1e-4));
        assertThat(quarter.get("effectiveHoldings").asDouble()).isEqualTo(2.2);
        assertThat(quarter.get("top10WeightPct").asDouble()).isEqualTo(100.0);
        assertThat(quarter.get("turnoverPct").asDouble()).isCloseTo(38.89, within(0.01));
        assertThat(quarter.get("turnoverVsReportDate").asString()).isEqualTo("2026-03-31");
        assertThat(f1.get("concentration").asString()).isEqualTo("집중형");
        assertThat(f1.get("topHoldings").get(0).get("ticker").asString()).isEqualTo("AAA");
        assertThat(f1.get("topHoldings").get(0).get("weightPct").asDouble()).isEqualTo(61.11);
        assertThat(f1.get("style").asString()).isEqualTo("value");

        JsonNode brk = row(section.get("filers"), "cik", BRK);
        assertThat(brk.get("source").asString()).isEqualTo("q8");
        assertThat(brk.get("menuDesc").asString()).isEqualTo("가치투자 포트폴리오, 핵심 우량주 집중");
        assertThat(brk.get("trackRecordA").isNull()).isTrue();
    }

    @Test
    @DisplayName("13F 변화: 기관마다 큰 변화 순, 교집합 후보(점수·교차 그룹), 매도 신호, 공시 시차 문구")
    void changesSection() {
        JsonNode section = builder.build(FRI, RuleBookService.INITIAL, true).body().get(PacketBuilder.CHANGES);
        assertThat(section.get("note").asString()).startsWith("공시 시차 45일");
        assertThat(section.get("quarterEnd").asString()).isEqualTo("2026-06-30");

        JsonNode f2 = row(section.get("filers"), "cik", F2);
        assertThat(f2.get("filer").asString()).isEqualTo("A2");
        assertThat(f2.get("eventCount").asInt()).isEqualTo(4);
        assertThat(section.get("eventColumns").toString())
                .isEqualTo("[\"ticker\",\"action\",\"weightPct\",\"previousWeightPct\",\"sharesChangePct\"]");
        JsonNode first = f2.get("events").get(0);
        assertThat(first.get(0).asString()).as("비중 60%인 DDD 확대가 먼저").isEqualTo("DDD");
        assertThat(first.get(1).asString()).isEqualTo("ADDED");
        assertThat(first.get(2).asDouble()).isEqualTo(60.0);
        assertThat(first.get(3).asDouble()).isEqualTo(66.67);
        assertThat(first.get(4).asDouble()).isEqualTo(50.0);
        assertThat(f2.get("events").get(1).toString()).isEqualTo("[\"OLD\",\"CLOSED\",0,33.33,-100]");

        JsonNode candidates = section.get("candidates");
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).get("ticker").asString()).isEqualTo("CCC");
        assertThat(candidates.get(0).get("score").asDouble()).isEqualTo(1.0);
        assertThat(candidates.get(0).get("buyerCount").asInt()).isEqualTo(3);
        assertThat(candidates.get(0).get("crossGroup").asBoolean()).isTrue();
        assertThat(candidates.get(0).get("inUniverse").asBoolean()).isTrue();
        assertThat(candidates.get(0).get("buyers").toString())
                .isEqualTo("[\"A1·B1 NEW 16.67%\",\"A2 NEW 20%\",\"B2 NEW 33.33%\"]");
        assertThat(candidates.get(1).get("ticker").asString()).isEqualTo("BBB");
        assertThat(candidates.get(1).get("score").asDouble()).isEqualTo(0.8333);

        JsonNode sells = section.get("sellSignals");
        assertThat(sells).hasSize(1);
        assertThat(sells.get(0).get("ticker").asString()).isEqualTo("OLD");
        assertThat(sells.get(0).get("closers").toString()).isEqualTo("[\"A1·B1\",\"A2\",\"B2\"]");
    }

    @Test
    @DisplayName("선정이 없으면 트랙레코드·프로필·13F는 '선정 없음'으로 — 지어내지 않음")
    void noSelection() {
        store.selections.clear();
        JsonNode body = builder.build(FRI, RuleBookService.INITIAL, true).body();
        assertThat(body.get(PacketBuilder.TRACK_RECORD).get("available").asBoolean()).isFalse();
        assertThat(body.get(PacketBuilder.PROFILES).get("available").asBoolean()).isFalse();
        assertThat(body.get(PacketBuilder.CHANGES).get("available").asBoolean()).isFalse();
        assertThat(body.get(PacketBuilder.BASIS).get("selectionAsOf").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ 하드 규칙·메모
    @Test
    @DisplayName("하드 규칙: '현금 비중 상·하한은 없다', 상한·최소 금액·월 상한·최소 보유일·ETF·유니버스. 메모는 비어 있음")
    void hardRulesAndNotes() {
        JsonNode body = builder.build(FRI, RuleBookService.INITIAL, true).body();
        JsonNode rules = body.get(PacketBuilder.HARD_RULES);
        assertThat(rules.get("noCashBounds").asString()).contains("현금 비중 상·하한은 없다");
        assertThat(rules.get("lines").get(0).asString()).contains("현금 비중 상·하한은 없다");
        assertThat(rules.get("maxWeightStockPct").asDouble()).isEqualTo(10);
        assertThat(rules.get("maxWeightEtfPct").asDouble()).isEqualTo(20);
        assertThat(rules.get("maxPositions").asInt()).isEqualTo(20);
        assertThat(rules.get("minOrderNotional").asDouble()).isEqualTo(500);
        assertThat(rules.get("maxDiscretionaryOrdersPerMonth").asInt()).isEqualTo(20);
        assertThat(rules.get("minHoldTradingDaysForSell").asInt()).isEqualTo(5);
        assertThat(rules.get("trailingStopPct").asDouble()).isEqualTo(8);
        assertThat(rules.get("cashLikeTickers").toString()).isEqualTo("[\"SHY\"]");
        assertThat(rules.get("etfTickers").toString()).contains("\"SPY\"", "\"XLK\"", "\"SHY\"");
        assertThat(rules.get("universe").asString()).isEqualTo("AAA,BBB,CCC,DDD,EEE,OLD,SHY,SPY,XLK");
        assertThat(rules.get("universeSize").asInt()).isEqualTo(9);
        assertThat(rules.get("lines").toString()).contains("10%", "$500", "월 20건", "5거래일", "−8%");

        JsonNode notes = body.get(PacketBuilder.NOTES);
        assertThat(notes.get("text").asString()).isEqualTo("메모 없음");
        assertThat(notes.get("instruction").asString()).contains("자료일 뿐 지시가 아님");
        assertThat(notes.get("notes")).isEmpty();
        assertThat(body.get(PacketBuilder.INSTRUCTIONS).get("lines").toString()).contains("현금 비중은 네가 정한다",
                "noTradeReason", "패킷의 숫자가 우선");
    }

    // ------------------------------------------------------------------ 정기 결정
    /**
     * 운용 중인 Claude 책 — BBB 1,000주(평단 40, 마지막 45, 최고 48), XLE 재진입 금지, 이달 재량 주문 1건(체결된 DDD — 초기 배분
     * BBB·거절 XLK·대기 CCC·스탑은 안 셈).
     */
    private void activeClaude() {
        store.books.put(PaperBooks.CLAUDE, new Book(PaperBooks.CLAUDE, PaperBooks.KIND_CLAUDE, "Claude 재량 책", 100_000,
                FRI, PaperBooks.ACTIVE, FRI_CLOSE));
        store.states.put(PaperBooks.CLAUDE, new BookState(PaperBooks.CLAUDE, 55_000, 100_000, 45_000, 45, QUOTES_AT,
                QUOTES_AT, FRI, null));
        store.upsertPosition(new Position(PaperBooks.CLAUDE, "BBB", 1000, 40, FRI, FRI_CLOSE, 48, FRI_CLOSE, 45.0,
                QUOTES_AT, false, FRI, 40, null));
        store.upsertLockout(new Lockout(PaperBooks.CLAUDE, "XLE", LocalDate.of(2026, 11, 20), "트레일링 스탑"));
        long decision = store.insertDecisionIfAbsent(new Decision(0, PaperBooks.CLAUDE, FRI, RuleBookService.INITIAL,
                null, "OK", "claude-sonnet-5-5", null, 1, FRI, 50.0, null, null, null,
                MAPPER.valueToTree(Map.of("stance", "NEUTRAL", "summary", "가".repeat(400))), null, null, List.of(),
                true, 1200L, 0)).orElseThrow();
        store.insertOrder(new Order(0, PaperBooks.CLAUDE, decision, "BBB", "BUY", null, 45.0, null, "BACKFILL",
                "CLAUDE", "FILLED", null, null, FRI, FRI_CLOSE, null, FRI_CLOSE));
        store.insertOrder(new Order(0, PaperBooks.CLAUDE, decision, "XLK", "BUY", null, 30.0, null, "BACKFILL",
                "CLAUDE", "REJECTED", "WEIGHT_CAP", null, FRI, FRI_CLOSE, null, null));
        store.insertOrder(new Order(0, PaperBooks.CLAUDE, null, "CCC", "BUY", null, 5.0, null, "NEXT_OBSERVATION",
                "CLAUDE", "PENDING", null, null, FRI, FRI_CLOSE, TUE, null));
        store.insertOrder(new Order(0, PaperBooks.CLAUDE, null, "DDD", "BUY", null, 2.0, null, "NEXT_OBSERVATION",
                "CLAUDE", "FILLED", null, null, FRI, FRI_CLOSE, TUE, FRI_CLOSE));
        store.insertOrder(new Order(0, PaperBooks.CLAUDE, null, "AAA", "BUY", null, 5.0, null, "NEXT_OBSERVATION",
                "CLAUDE", "FILLED", null, null, LocalDate.of(2026, 9, 30), FRI_CLOSE, null, FRI_CLOSE));
        store.insertOrder(new Order(0, PaperBooks.CLAUDE, null, "SHY", "SELL", null, null, null, "IMMEDIATE",
                "STOP", "FILLED", null, null, FRI, FRI_CLOSE, null, FRI_CLOSE));
    }

    @Test
    @DisplayName("정기 패킷: 확정 종가 표시, 카드별 asOfMismatch, 장부(비중·스탑 거리·보유일·재진입 금지·이달 주문), 최근 결정")
    void scheduledPacket() {
        activeClaude();
        JsonNode body = builder.build(MON, RuleBookService.SCHEDULED, false).body();

        assertThat(body.get(PacketBuilder.BASIS).get("pricesFinal").asBoolean()).isTrue();
        JsonNode cards = body.get(PacketBuilder.MACRO).get("cards");
        assertThat(cards.get("asOfMismatch").asBoolean()).isFalse();
        assertThat(row(cards.get("items"), "key", "us10y").get("asOfMismatch").asBoolean()).isFalse();
        assertThat(row(cards.get("items"), "key", "kospi").get("asOfMismatch").asBoolean())
                .as("10-27 관측 카드는 10-26 값이 아님").isTrue();
        // 10-26 결정에 알려진 10년물 = 10-23 관측(10-26 관측은 다음 영업일에 알려짐).
        assertThat(row(body.get(PacketBuilder.FRED).get("series"), "id", "DGS10").get("value").asDouble())
                .isEqualTo(5.01);

        JsonNode portfolio = body.get(PacketBuilder.PORTFOLIO);
        assertThat(portfolio.get("nav").asDouble()).isEqualTo(100_000);
        assertThat(portfolio.get("currentExposurePct").asDouble()).isEqualTo(45);
        JsonNode bbb = row(portfolio.get("positions"), "ticker", "BBB");
        assertThat(bbb.get("weightPct").asDouble()).isEqualTo(45);
        assertThat(bbb.get("stopPrice").asDouble()).isEqualTo(44.16);
        assertThat(bbb.get("stopDistancePct").asDouble()).isEqualTo(1.9);
        assertThat(bbb.get("holdingTradingDays").asInt()).isEqualTo(1);
        assertThat(bbb.get("unrealizedPct").asDouble()).isEqualTo(12.5);
        assertThat(portfolio.get("lockouts").get(0).get("ticker").asString()).isEqualTo("XLE");
        assertThat(portfolio.get("monthlyDiscretionaryOrders").get("used").asInt()).as("체결된 DDD만").isEqualTo(1);
        assertThat(portfolio.get("monthlyDiscretionaryOrders").get("cap").asInt()).isEqualTo(20);
        assertThat(body.get(PacketBuilder.HARD_RULES).get("lockedOut").toString()).isEqualTo("[\"XLE\"]");
        assertThat(priceRow(body, "BBB").get("role").asString()).isEqualTo("HOLDING");
        assertThat(priceRow(body, "BBB").get("date").asString()).isEqualTo("2026-10-26");

        JsonNode decisions = body.get(PacketBuilder.DECISIONS).get("decisions");
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).get("stance").asString()).isEqualTo("NEUTRAL");
        assertThat(decisions.get(0).get("targetExposurePct").asDouble()).isEqualTo(50);
        assertThat(decisions.get(0).get("summary").asString()).hasSize(300).endsWith("…");
        assertThat(decisions.get(0).get("acceptedOrders").asInt()).isEqualTo(1);
        assertThat(decisions.get(0).get("rejectedOrders").asInt()).isEqualTo(1);

        // 같은 (날, 종류)를 다시 만들면(재시도) 그 결정 자신은 최근 결정에 넣지 않습니다.
        JsonNode retry = builder.build(FRI, RuleBookService.INITIAL, false).body().get(PacketBuilder.DECISIONS);
        assertThat(retry.get("decisions")).isEmpty();
    }

    @Test
    @DisplayName("대기 주문: 9_portfolio.pendingOrders에 CLAUDE 출처 PENDING만, 하드 규칙·프롬프트에 '모두 취소된다 — 유지하려면 다시 내라'")
    void pendingOrdersAreShownWithSupersession() {
        activeClaude();
        long initial = store.decisionFor(PaperBooks.CLAUDE, FRI, RuleBookService.INITIAL).orElseThrow().id();
        store.insertOrder(new Order(0, PaperBooks.CLAUDE, initial, "BBB", "SELL", null, null, 0.5, "NEXT_CLOSE",
                "CLAUDE", "PENDING", null, null, FRI, FRI_CLOSE, TUE, null));
        store.insertOrder(new Order(0, PaperBooks.CLAUDE, initial, "EEE", "BUY", null, 3.0, null, "NEXT_OBSERVATION",
                "CLAUDE", "PENDING", null, MAPPER.valueToTree(Map.of("targetWeightPct", 4.0)), FRI, FRI_CLOSE, TUE, null));
        // 출처가 CLAUDE가 아닌 대기 주문은 새 결정이 취소하지 않으므로(supersedePending) 싣지 않습니다.
        store.insertOrder(new Order(0, PaperBooks.CLAUDE, null, "SPY", "BUY", null, 1.0, null, "NEXT_OBSERVATION",
                "RULE", "PENDING", null, null, FRI, FRI_CLOSE, TUE, null));

        JsonNode body = builder.build(MON, RuleBookService.SCHEDULED, false).body();
        JsonNode pending = body.get(PacketBuilder.PORTFOLIO).get("pendingOrders");
        assertThat(pending.get("columns").toString()).isEqualTo("[\"ticker\",\"side\",\"targetWeightPct\","
                + "\"orderWeightPct\",\"sellFraction\",\"timing\",\"decisionDate\",\"decisionKind\",\"expiresOn\"]");
        assertThat(pending.get("rows").toString()).isEqualTo("["
                + "[\"CCC\",\"BUY\",null,5,null,\"NEXT_OBSERVATION\",\"2026-10-23\",null,\"2026-10-27\"],"
                + "[\"BBB\",\"SELL\",null,null,0.5,\"NEXT_CLOSE\",\"2026-10-23\",\"INITIAL\",\"2026-10-27\"],"
                + "[\"EEE\",\"BUY\",4,3,null,\"NEXT_OBSERVATION\",\"2026-10-23\",\"INITIAL\",\"2026-10-27\"]]");
        assertThat(pending.get("note").asString()).contains(PacketBuilder.PENDING_SUPERSEDED);
        String supersede = "이번 결정의 응답이 형식 검사를 통과하면(주문이 없거나 모두 거절돼도) 위 대기 주문은 모두 취소된다 — 유지하려면 다시 내라";
        assertThat(body.get(PacketBuilder.HARD_RULES).get("lines").toString()).contains(supersede);
        assertThat(ClaudePrompts.INTERIM_SYSTEM).contains(supersede);

        // 초기 패킷은 대기 주문이 비어 있습니다(시작이 남은 대기 주문을 취소).
        JsonNode initialPending = builder.build(FRI, RuleBookService.INITIAL, true).body().get(PacketBuilder.PORTFOLIO)
                .get("pendingOrders");
        assertThat(initialPending.get("rows")).isEmpty();
    }

    @Test
    @DisplayName("기관 상한은 그룹마다 — 그룹 A를 10곳으로 키워도 6b(8곳)·7(10곳)에 그룹 B 넷이 남고, 자른 수·뺀 기관을 적는다")
    void filerCapsKeepBothGroups() {
        JsonNode standard = builder.build(FRI, RuleBookService.INITIAL, true).body();
        assertThat(standard.get(PacketBuilder.PROFILES).has("filerCap")).as("자르지 않으면 표시 없음").isFalse();
        assertThat(standard.get(PacketBuilder.CHANGES).has("filerCap")).isFalse();

        List<String> groupA = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            String cik = String.format("00000002%02d", i);
            groupA.add(cik);
            names.put(cik, "A Capital " + i);
        }
        List<String> groupB = List.of("0000000301", "0000000302", "0000000303", "0000000304");
        groupB.forEach(cik -> names.put(cik, "B Capital " + cik));
        selection(FRI, groupA, groupB, names);

        JsonNode body = builder.build(FRI, RuleBookService.INITIAL, true).body();
        JsonNode profiles = body.get(PacketBuilder.PROFILES);
        List<String> shownProfiles = new ArrayList<>();
        profiles.get("filers").forEach(f -> shownProfiles.add(f.get("filer").asString()));
        assertThat(shownProfiles).containsExactly("A1", "A2", "A3", "A4", "B1", "B2", "B3", "B4");
        JsonNode cap = profiles.get("filerCap");
        assertThat(cap.get("poolSize").asInt()).isEqualTo(14);
        assertThat(cap.get("shown").asInt()).isEqualTo(PacketBuilder.MAX_PROFILE_FILERS);
        assertThat(cap.get("truncated").asInt()).isEqualTo(6);
        assertThat(cap.get("omitted").toString()).isEqualTo("[\"A5\",\"A6\",\"A7\",\"A8\",\"A9\",\"A10\"]");

        JsonNode changes = body.get(PacketBuilder.CHANGES);
        List<String> shownChanges = new ArrayList<>();
        changes.get("filers").forEach(f -> shownChanges.add(f.get("filer").asString()));
        assertThat(shownChanges).containsExactly("A1", "A2", "A3", "A4", "A5", "A6", "B1", "B2", "B3", "B4");
        assertThat(changes.get("filerCap").get("truncated").asInt()).isEqualTo(4);
        assertThat(changes.get("filerCap").get("omitted").toString()).isEqualTo("[\"A7\",\"A8\",\"A9\",\"A10\"]");
    }

    @Test
    @DisplayName("그룹별 자르기: 그룹마다 먼저 절반, 겹치는 기관은 한 번(두 그룹 몫), 남는 자리는 풀 순서")
    void capByGroup() {
        java.util.function.Function<String, List<String>> groups = label -> {
            List<String> out = new ArrayList<>();
            if (label.contains("A")) {
                out.add("A");
            }
            if (label.contains("B")) {
                out.add("B");
            }
            return out;
        };
        // 기본 설정 모양(A 5 + B 4)에서 겹치는 기관이 하나면 8곳이라 모두.
        List<String> overlap = List.of("A1B1", "A2", "A3", "A4", "A5", "B2", "B3", "B4");
        assertThat(PacketBuilder.capByGroup(overlap, 8, groups)).containsExactlyElementsOf(overlap);
        // 겹치지 않으면 9곳 → 그룹마다 4곳(A5가 빠짐).
        List<String> apart = List.of("A1", "A2", "A3", "A4", "A5", "B1", "B2", "B3", "B4");
        assertThat(PacketBuilder.capByGroup(apart, 8, groups))
                .containsExactly("A1", "A2", "A3", "A4", "B1", "B2", "B3", "B4");
        // 한 그룹이 작으면 남는 자리는 풀 순서로 다른 그룹이 씁니다.
        List<String> fewB = List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "B1");
        assertThat(PacketBuilder.capByGroup(fewB, 6, groups)).containsExactly("A1", "A2", "A3", "A4", "A5", "B1");
        // D27 전 선정(그룹 A만)은 앞에서부터.
        assertThat(PacketBuilder.capByGroup(List.of("A1", "A2", "A3"), 2, groups)).containsExactly("A1", "A2");
    }

    @Test
    @DisplayName("가격 없음·묵은 입력: 이어 쓴 봉·봉 없는 보유는 noPriceTickers와 8_prices stale, 저장본 24시간 초과면 inputsStale")
    void noPriceAndStaleInputsAreVisible() {
        activeClaude();
        // CCC 봉은 이번 수집에서 받지 못해 이어 씀(isStale), NOB는 보유인데 관측치 저장본에 봉이 없음.
        ((ObjectNode) snapshots.get(Datasets.SNAP_PAPER_QUOTES).payload().get("tickers").get("CCC"))
                .put("isStale", true);
        store.upsertPosition(new Position(PaperBooks.CLAUDE, "NOB", 10, 50, FRI, FRI_CLOSE, 50, FRI_CLOSE, 50.0,
                QUOTES_AT, false, FRI, 50, null));

        JsonNode body = builder.build(MON, RuleBookService.SCHEDULED, false).body();
        JsonNode rules = body.get(PacketBuilder.HARD_RULES);
        assertThat(rules.get("noPriceTickers").toString()).isEqualTo("[\"CCC\",\"NOB\"]");
        assertThat(rules.get("universe").asString()).as("검증기와 같은 유니버스(이어 쓴 봉 포함)").contains("CCC");
        assertThat(rules.get("inputsStale").asBoolean()).isFalse();
        assertThat(rules.get("quotesCollectedAtKst").asString()).isEqualTo("2026-10-27 05:30 KST");
        assertThat(rules.get("lines").toString()).contains("NO_PRICE", "STALE_INPUT", "24시간");
        assertThat(priceRow(body, "CCC").get("stale").asBoolean()).isTrue();
        assertThat(priceRow(body, "NOB").get("stale").asBoolean()).isTrue();
        assertThat(priceRow(body, "BBB").get("stale").asBoolean()).isFalse();

        // 초기 결정은 출발일 종가로 체결해 두 판정을 하지 않습니다(null·false — 0이나 빈 목록으로 지어내지 않음).
        JsonNode initial = builder.build(FRI, RuleBookService.INITIAL, true).body();
        assertThat(initial.get(PacketBuilder.HARD_RULES).get("noPriceTickers").isNull()).isTrue();
        assertThat(initial.get(PacketBuilder.HARD_RULES).get("inputsStale").asBoolean()).isFalse();
        assertThat(initial.get(PacketBuilder.HARD_RULES).get("quotesCollectedAtKst").isNull()).isTrue();
        assertThat(priceRow(initial, "CCC").get("stale").isNull()).isTrue();

        // 검증기와 같은 문턱 — 수집 24시간 뒤면 그날 주문 전부 STALE_INPUT이라 패킷도 그렇게 말합니다.
        Clock later = Clock.fixed(QUOTES_AT.plus(ClaudeBookService.STALE_INPUT_AFTER).plusSeconds(60), ZoneOffset.UTC);
        assertThat(newBuilder(later).build(MON, RuleBookService.SCHEDULED, false).body().get(PacketBuilder.HARD_RULES)
                .get("inputsStale").asBoolean()).isTrue();
        snapshots.remove(Datasets.SNAP_PAPER_QUOTES);
        JsonNode none = builder.build(MON, RuleBookService.SCHEDULED, false).body().get(PacketBuilder.HARD_RULES);
        assertThat(none.get("inputsStale").asBoolean()).as("관측치 저장본이 없으면 묵은 입력").isTrue();
        assertThat(none.get("noPriceTickers").toString()).as("봉 없는 보유").isEqualTo("[\"BBB\",\"NOB\"]");
    }

    @Test
    @DisplayName("하드 규칙 문구: sellFraction '0 초과 1 이하'·1주 미만 부분 매도 거절·시점별 자리, 월 상한은 초기 결정에도 걸림")
    void hardRuleWording() {
        String initial = builder.build(FRI, RuleBookService.INITIAL, true).body().get(PacketBuilder.HARD_RULES)
                .get("lines").toString();
        assertThat(initial).contains("0 초과 1 이하", "BELOW_ONE_SHARE", "qty", "MAX_POSITIONS", "OVER_ALLOCATED",
                "초기 결정에도 걸린다 — 이번 결정 안에서 20건까지만").doesNotContain("비율(0~1", "초기 배분 제외)");
        activeClaude();
        String scheduled = builder.build(MON, RuleBookService.SCHEDULED, false).body().get(PacketBuilder.HARD_RULES)
                .get("lines").toString();
        assertThat(scheduled).contains("월 20건까지", "monthlyDiscretionaryOrders.used", "0 초과 1 이하",
                "BELOW_ONE_SHARE");
        assertThat(ClaudePrompts.INTERIM_SYSTEM).contains("sellFraction 0 초과 1 이하").doesNotContain("sellFraction 0~1");
    }

    @Test
    @DisplayName("모르는 종류·기준일 없음·테이블 없음은 거절")
    void rejectsBadInput() {
        assertThatThrownBy(() -> builder.build(null, RuleBookService.SCHEDULED, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(FRI, "DAILY", false)).isInstanceOf(IllegalArgumentException.class);
        store.ready = false;
        assertThatThrownBy(() -> builder.build(FRI, RuleBookService.SCHEDULED, false))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ 조각
    @Test
    @DisplayName("FRED: 발표일 기준으로 자르고 20거래일 변화·1년 백분위를 낸다")
    void fredPoint() {
        NavigableMap<LocalDate, Double> points = new TreeMap<>();
        for (LocalDate d : tradingDays(LocalDate.of(2026, 9, 1), MON)) {
            points.put(d, 4.0);
        }
        points.put(LocalDate.of(2026, 10, 22), 4.5);
        points.put(FRI, 9.9);
        PacketBuilder.FredPoint p = PacketBuilder.fredPoint(new PacketBuilder.FredSeries("DGS10", "10년",
                PointInTime.Release.NEXT_BUSINESS_DAY), columns(points), FRI);
        assertThat(p.observed()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(p.value()).isEqualTo(4.5);
        assertThat(p.change20d()).isCloseTo(0.5, within(1e-12));
        assertThat(p.percentile1y()).isEqualTo(100.0);
        assertThat(PacketBuilder.fredPoint(new PacketBuilder.FredSeries("X", "x", PointInTime.Release.NEXT_BUSINESS_DAY),
                null, FRI).value()).isNull();
    }

    @Test
    @DisplayName("가격 잇기: 이력 뒤에 최근 봉, 기준일에서 자르고, 기준일 값이 없으면 얼린 종가")
    void mergePrices() {
        DailySeries history = new DailySeries(new int[]{(int) LocalDate.of(2026, 10, 21).toEpochDay(),
                (int) LocalDate.of(2026, 10, 22).toEpochDay()}, new double[]{10, 11});
        DailySeries recent = new DailySeries(new int[]{(int) LocalDate.of(2026, 10, 22).toEpochDay(),
                (int) FRI.toEpochDay(), (int) MON.toEpochDay()}, new double[]{99, 12, 13});
        DailySeries merged = PacketBuilder.merge(history, recent, FRI, null);
        assertThat(merged.size()).isEqualTo(3);
        assertThat(merged.valueOn(LocalDate.of(2026, 10, 22))).as("이력이 우선").isEqualTo(11);
        assertThat(merged.lastDate()).isEqualTo(FRI);
        assertThat(merged.lastValue()).isEqualTo(12);
        DailySeries frozen = PacketBuilder.merge(history, null, FRI, 11.5);
        assertThat(frozen.lastDate()).isEqualTo(FRI);
        assertThat(frozen.lastValue()).isEqualTo(11.5);
        assertThat(PacketBuilder.merge(null, null, FRI, null)).isNull();
    }

    @Test
    @DisplayName("이달 재량 주문: CLAUDE 출처·그 달·기준일 이하의 체결 주문만 — 초기 배분(BACKFILL)·대기·취소·만료·거절·제안 제외")
    void monthlyOrders() {
        List<Order> orders = List.of(
                order("CLAUDE", "FILLED", FRI), order("CLAUDE", "FILLED", MON), order("CLAUDE", "PENDING", MON),
                order("CLAUDE", "REJECTED", FRI), order("CLAUDE", "PROPOSED", FRI),
                order("CLAUDE", "EXPIRED", LocalDate.of(2026, 10, 1)), order("CLAUDE", "CANCELLED", FRI),
                order("CLAUDE", "FILLED", LocalDate.of(2026, 9, 30)), order("RULE", "FILLED", FRI),
                order("CLAUDE", "FILLED", TUE));
        assertThat(PacketBuilder.monthlyDiscretionaryOrders(orders, MON)).isEqualTo(2);

        // 출발일 사후 체결(기준일 = 출발일 전 거래일 10-22)은 출발일이 그달 첫 거래일이든 아니든 세지 않음 — 이달 남는 자리가 날짜에
        // 따라 달라지지 않게(초기 검증도 이달 0건으로 봄).
        List<Order> backfill = List.of(order("CLAUDE", "FILLED", LocalDate.of(2026, 10, 22), "BACKFILL"),
                order("CLAUDE", "FILLED", LocalDate.of(2026, 10, 22), "BACKFILL"));
        assertThat(PacketBuilder.monthlyDiscretionaryOrders(backfill, MON)).isZero();
    }

    private static Order order(String source, String status, LocalDate asOf) {
        return order(source, status, asOf, "NEXT_OBSERVATION");
    }

    private static Order order(String source, String status, LocalDate asOf, String timing) {
        return new Order(1, PaperBooks.CLAUDE, null, "AAA", "BUY", null, 1.0, null, timing, source, status,
                null, null, asOf, FRI_CLOSE, null, null);
    }

    @Test
    @DisplayName("숫자는 고정 자릿수 십진수(지수 표기 없음), 유한하지 않으면 null")
    void decimals() {
        assertThat(PacketBuilder.decimal(100_000.0, 2).toString()).isEqualTo("100000");
        assertThat(PacketBuilder.decimal(0.000123, 6).toString()).isEqualTo("0.000123");
        assertThat(PacketBuilder.decimal(4.955, 2).toString()).isEqualTo("4.96");
        assertThat(PacketBuilder.decimal(-0.0000001, 4).toString()).isEqualTo("0");
        assertThat(PacketBuilder.decimal(Double.NaN, 2)).isNull();
        assertThat(PacketBuilder.decimal(null, 2)).isNull();
        assertThat(PacketBuilder.toNode(Double.POSITIVE_INFINITY).isNull()).isTrue();
        assertThatThrownBy(() -> PacketBuilder.toNode(new Object())).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 크기
    @Test
    @DisplayName("크기: 상한을 꽉 채운 패킷도 25,000토큰(어림) 이하")
    void fitsTokenBudget() {
        largeFixture();
        Packet packet = builder.build(MON, RuleBookService.SCHEDULED, false);
        int tokens = PacketBuilder.estimateTokens(packet.text());
        JsonNode body = packet.body();
        assertThat(body.get(PacketBuilder.PRICES).get("rows")).hasSize(PacketBuilder.MAX_PRICE_ROWS);
        assertThat(body.get(PacketBuilder.CHANGES).get("candidates")).hasSize(PacketBuilder.MAX_CANDIDATES);
        assertThat(body.get(PacketBuilder.CHANGES).get("filers")).hasSize(9);
        body.get(PacketBuilder.CHANGES).get("filers").forEach(f ->
                assertThat(f.get("events")).hasSize(PacketBuilder.MAX_EVENTS_PER_FILER));
        assertThat(body.get(PacketBuilder.TRACK_RECORD).get("groupA").get("rows")).hasSize(10);
        assertThat(body.get(PacketBuilder.TRACK_RECORD).get("groupB").get("rows")).hasSize(12);
        assertThat(body.get(PacketBuilder.MACRO).get("cards").get("items")).hasSize(18);
        assertThat(body.get(PacketBuilder.DECISIONS).get("decisions")).hasSize(5);
        assertThat(tokens).as("큰 패킷 %d자", packet.text().length()).isLessThanOrEqualTo(PacketBuilder.TARGET_TOKENS);
        assertThat(PacketBuilder.estimateTokens("가나다abc")).isEqualTo(4);
    }

    /**
     * 상한을 넘치게 채운 재료 — 신호 풀 9곳(그룹 A 다섯 + 그룹 B 기존 메뉴 넷)이 120종목씩 들고 분기마다 30개 신규·30개 전량 매도·
     * 40개 확대, 관측 유니버스 600종목, 이력 있는 종목 80개, 보유 20·재진입 금지 5, 결정 7건(요약 400자), 카드 22장, 그룹 A 27곳·B 12곳.
     */
    private void largeFixture() {
        holdings.clear();
        store.trackRecords.clear();
        store.selections.clear();
        List<String> groupA = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        List<Map<String, Object>> indexFilers = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            String cik = String.format("00000001%02d", i);
            String name = "Synthetic Capital Management Partners LP " + i;
            names.put(cik, name);
            if (i < 5) {
                groupA.add(cik);
            }
            indexFilers.add(Map.of("cik", cik, "name", name, "style", i % 2 == 0 ? "value" : "growth",
                    "storedQuarters", 30, "complete", true));
            store.upsertTrackRecord(track(cik, TrackRecordRow.GROUP_A, i < 12 ? i + 1 : null, 30.0 - i, i < 12,
                    i < 12 ? null : "커버리지 미달(최소 0.31 < 0.5, 평균 0.55 < 0.7); 최신 분기(2026-06-30) 공시 없음 — "
                            + "수집 목록에서 이 기관의 마지막 공시가 기준일 전 분기에 머물러 있습니다", TrackRecordRow.SOURCE_Q30));
        }
        snapshot(Datasets.SNAP_TRACK_RECORD_INDEX, Map.of("filers", indexFilers, "targetQuarters", 30), FRI_CLOSE);
        List<String> menu = com.macrodash.feature.institution.Institutions.ciks();
        List<String> groupB = List.of("0001536411", "0000850529", "0001350694", BRK);
        for (int i = 0; i < menu.size(); i++) {
            store.upsertTrackRecord(track(menu.get(i), TrackRecordRow.GROUP_B, i < 6 ? i + 1 : null, 25.0 - i, i < 6,
                    i < 6 ? null : "커버리지 미달(평균 0.57 < 0.7) — 인덱스·연기금은 덮인 대형주 쪽만 보여 복제 수익률이 부풂",
                    TrackRecordRow.SOURCE_Q8));
        }
        selection(FRI, groupA, groupB, names);

        List<String> filers = new ArrayList<>(groupA);
        filers.addAll(groupB);
        for (int f = 0; f < filers.size(); f++) {
            List<Line> before = new ArrayList<>();
            List<Line> after = new ArrayList<>();
            for (int j = 0; j < 120; j++) {
                String ticker = String.format("T%03d", (f * 7 + j) % 200);
                double shares = 1000 + j;
                before.add(line(ticker, shares, shares * (10 + j % 13)));
                if (j < 30) {
                    continue;   // 전량 매도
                }
                double grown = j < 70 ? shares * 1.5 : shares;
                after.add(line(ticker, grown, grown * (10 + j % 13)));
            }
            for (int j = 0; j < 30; j++) {
                String ticker = String.format("T%03d", (f * 11 + j + 120) % 200 + 200);
                after.add(line(ticker, 500, 500 * (12 + j % 7)));
            }
            int stored = f < 5 ? Datasets.TRACK_RECORD_QUARTERS : Datasets.MAX_TRACKED_QUARTERS;
            filings(filers.get(f), stored, new Quarter("2026-06-30", "2026-08-14", after),
                    new Quarter("2026-03-31", "2026-05-15", before));
        }

        List<LocalDate> recent = tradingDays(LocalDate.of(2026, 9, 1), MON);
        Map<String, Object> tickers = new LinkedHashMap<>();
        for (int i = 0; i < 600; i++) {
            tickers.put(String.format("T%03d", i), frame(20 + i % 50, recent));
        }
        for (String etf : PaperSettingsService.UNIVERSE_EXTRA_TICKERS) {
            tickers.put(etf, frame(50, recent));
        }
        tickers.put("SPY", frame(600, recent));
        tickers.put("QQQ", frame(500, recent));
        snapshot(Datasets.SNAP_PAPER_QUOTES, Map.of("period", "3mo", "tickers", tickers), QUOTES_AT);
        for (int i = 0; i < 400; i++) {
            history(String.format("T%03d", i), 20 + i % 50, LocalDate.of(2025, 6, 2), MON);
        }

        store.books.put(PaperBooks.CLAUDE, new Book(PaperBooks.CLAUDE, PaperBooks.KIND_CLAUDE, "Claude 재량 책", 100_000,
                FRI, PaperBooks.ACTIVE, FRI_CLOSE));
        store.states.put(PaperBooks.CLAUDE, new BookState(PaperBooks.CLAUDE, 20_000, 100_000, 80_000, 80, QUOTES_AT,
                QUOTES_AT, FRI, null));
        for (int i = 0; i < 20; i++) {
            store.upsertPosition(new Position(PaperBooks.CLAUDE, String.format("T%03d", i * 3), 100, 40, FRI,
                    FRI_CLOSE, 44, FRI_CLOSE, 42.0, QUOTES_AT, false, FRI, 40, null));
        }
        for (int i = 0; i < 5; i++) {
            store.upsertLockout(new Lockout(PaperBooks.CLAUDE, String.format("T%03d", 100 + i),
                    LocalDate.of(2026, 11, 20), "트레일링 스탑 −8% 도달 — 2026-10-26 관측치로 전량 매도"));
        }
        for (int i = 0; i < 7; i++) {
            store.insertDecisionIfAbsent(new Decision(0, PaperBooks.CLAUDE, FRI.minusDays(i + 1),
                    RuleBookService.SCHEDULED, null, "OK", "claude-sonnet-5-5", 1, 1, FRI, 60.0 + i, null, null, null,
                    MAPPER.valueToTree(Map.of("stance", "RISK_ON", "summary", "요약 ".repeat(100))), null, null,
                    List.of(), true, 1000L, 0));
        }

        List<Map<String, Object>> cards = new ArrayList<>();
        for (int i = 0; i < 22; i++) {
            cards.add(card("card" + i, "매크로 카드 이름 " + i + " (긴 이름 예시)", 100 + i, 99 + i,
                    "2026-10-27 05:00 (TradingView 수집 시각)"));
        }
        snapshot(Datasets.SNAP_MACRO_COLLECTED, Map.of("categories", List.of(category("all", cards))),
                Instant.parse("2026-10-27T06:31:00Z"));
        for (PacketBuilder.FredSeries spec : PacketBuilder.FRED_SERIES) {
            NavigableMap<LocalDate, Double> points = new TreeMap<>();
            for (LocalDate d : tradingDays(LocalDate.of(2025, 9, 1), MON)) {
                points.put(d, 1.2345 + d.getDayOfMonth() / 100.0);
            }
            fredColumns.put(spec.id(), columns(points));
        }
    }
}
