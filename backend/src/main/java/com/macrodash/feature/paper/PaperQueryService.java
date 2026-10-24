package com.macrodash.feature.paper;

import com.macrodash.Kst;
import com.macrodash.analytics.PaperStats;
import com.macrodash.analytics.TrailingStop;
import com.macrodash.analytics.UsMarketCalendar;
import com.macrodash.feature.paper.PaperMarketData.Quotes;
import com.macrodash.read.StoreReader;
import com.macrodash.store.PaperStore;
import com.macrodash.store.PaperStore.Book;
import com.macrodash.store.PaperStore.BookState;
import com.macrodash.store.PaperStore.Fill;
import com.macrodash.store.PaperStore.JobRun;
import com.macrodash.store.PaperStore.Lockout;
import com.macrodash.store.PaperStore.NavRow;
import com.macrodash.store.PaperStore.Order;
import com.macrodash.store.PaperStore.Position;
import com.macrodash.support.InvalidRequestException;
import com.macrodash.support.Json;
import com.macrodash.support.Params;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 🧪 모의 매매 화면 응답(API.md "🧪 모의 매매") — 장부 테이블을 읽어 화면이 그대로 그릴 숫자로 만듭니다(ADR 0004: 화면은 계산하지 않음).
 *
 * <p>준비 여부는 {@code SchemaCheck}가 아니라 {@link PaperStore#ready()}로 봅니다(V8의 마지막 테이블). 테이블이 없으면 모든 응답이
 * {@code {available:false, message}}입니다. 초기화 전이면 {@code available:true, initialized:false}.
 *
 * <p>숫자 규칙: 총액(현금·순자산·평가액·체결 금액)은 소수 2자리, 주당 가격(평단·체결가·최고가·스탑가)은 4자리 — 5bp 슬리피지가
 * 싼 종목에서도 보이게, 비율(%)은 2자리로 반올림해 보냅니다. 모르는 값은 null(화면 —)이고 0으로 채우지 않습니다. 시각은 KST 문자열,
 * 거래일은 미국 동부 세션 날짜(yyyy-MM-dd)입니다.
 *
 * <p>관측치 저장본은 {@link StoreReader#quietly}로 읽습니다 — 화면을 열어도 수집을 깨우거나 기다리지 않습니다(명세 §2).
 * 장부는 백엔드 테이블이라 응답 캐시(ComputedCache)도 쓰지 않습니다.
 */
@Service
public class PaperQueryService {

    static final String NOT_READY_MESSAGE = "모의 매매 테이블이 아직 없습니다. 수집기가 스키마(V8)를 적용하면 생깁니다 — "
            + "make up 후 다시 보세요.";
    /** 개요에 싣는 최근 작업 기록 수. */
    static final int OVERVIEW_JOBS = 10;
    /** 상태 응답에 싣는 최근 작업 기록 수. */
    static final int STATUS_JOBS = 30;
    /** 주문·체결 목록 상한. */
    static final int MAX_LIMIT = 500;
    /** 관측치가 이보다 오래되면 늦었다고 표시합니다 — 수집 주기 1시간의 세 배(두 번 연속 못 받음). */
    static final Duration QUOTES_LATE = Duration.ofHours(3);

    private final PaperStore store;
    private final PaperMarketData marketData;
    private final StoreReader reader;
    private final PaperSettingsService settings;
    private final PaperConfig config;
    private final Clock clock;

    @Autowired
    public PaperQueryService(PaperStore store, PaperMarketData marketData, StoreReader reader,
                             PaperSettingsService settings, PaperConfig config) {
        this(store, marketData, reader, settings, config, Clock.systemUTC());
    }

    /** 테스트용 — 시계를 움직입니다. */
    PaperQueryService(PaperStore store, PaperMarketData marketData, StoreReader reader, PaperSettingsService settings,
                      PaperConfig config, Clock clock) {
        this.store = store;
        this.marketData = marketData;
        this.reader = reader;
        this.settings = settings;
        this.config = config;
        this.clock = clock;
    }

    public boolean ready() {
        return store.ready();
    }

    /** 테이블이 없을 때의 응답. */
    public Map<String, Object> notReady() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", false);
        out.put("message", NOT_READY_MESSAGE);
        return out;
    }

    /** 명세 §6 "화면에 반드시 적을 한계" — 순서대로. */
    List<String> limits() {
        return List.of(
                "모의 매매입니다. 어떤 주문도 밖으로 나가지 않습니다.",
                "체결가는 최대 1시간 묵은 지연 시세입니다.",
                "장부는 배당을 반영하지 않습니다(가격 수익률, 네 책 같은 조건). 트랙레코드는 수정주가라 배당이 들어갑니다 — "
                        + "두 숫자를 섞어 비교하지 마세요.",
                config.startDate() + " 체결은 사후 체결입니다.",
                "13F 신호는 공시 시차 45일을 안고 있습니다. 트랙레코드는 공시일 다음 영업일부터 복제한 값이라 실제 기관 수익률이 "
                        + "아닙니다.",
                "후보 기관 목록은 오늘 알려진 운용사라 생존 편향이 있습니다. 커버리지(티커 붙은 비중)가 100% 미만이면 "
                        + "'커버 보유분 기준'입니다.",
                "몇 달치 결과로는 통계적 결론을 낼 수 없습니다.",
                "Claude 책은 같은 입력에도 답이 달라질 수 있습니다. 재현은 기록으로만 합니다.");
    }

    // ------------------------------------------------------------------ 개요·상태
    /** GET /api/paper/overview */
    public Map<String, Object> overview() {
        if (!ready()) {
            return notReady();
        }
        List<Book> books = sortedBooks();
        List<NavRow> allNav = store.navSeriesAll();
        Map<String, List<NavRow>> navByBook = new HashMap<>();
        allNav.forEach(row -> navByBook.computeIfAbsent(row.bookCode(), key -> new ArrayList<>()).add(row));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("initialized", initialized(books));
        out.put("pendingBackfill", pendingBackfill(books));
        out.put("startDate", config.startDate().toString());
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Book book : books) {
            summaries.add(summary(book, navByBook));
        }
        out.put("books", summaries);
        out.put("navSeries", navSeries(books, navByBook));
        out.put("quotes", quotesSummary());
        out.put("selection", latestSelection());
        out.put("limits", limits());
        out.put("jobs", jobs(OVERVIEW_JOBS));
        return out;
    }

    /** GET /api/paper/status */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!ready()) {
            out.putAll(notReady());
            out.put("schemaReady", false);
            out.put("enabled", config.enabled());
            return out;
        }
        out.put("available", true);
        out.put("schemaReady", true);
        out.put("initialized", initialized(sortedBooks()));
        out.put("enabled", config.enabled());
        out.put("startDate", config.startDate().toString());
        out.put("initialCash", round2(config.initialCash()));
        out.put("quotes", quotesSummary());
        out.put("jobs", jobs(STATUS_JOBS));
        PaperSettingsService.Current current = settings.current();
        out.put("settingsVersion", current.version());
        out.put("settingsFallbacks", current.settings().fallbacks());
        return out;
    }

    /** 책 하나의 요약(BookSummary) — 버튼 응답이 바뀐 책을 다시 그릴 때. */
    public Map<String, Object> bookSummary(String code) {
        Book book = existing(code);
        Map<String, List<NavRow>> navByBook = new HashMap<>();
        navByBook.put(book.code(), store.navSeries(book.code()));
        String benchmark = PaperBooks.benchmarkOf(book.code());
        if (benchmark != null) {
            navByBook.put(benchmark, store.navSeries(benchmark));
        }
        return summary(book, navByBook);
    }

    // ------------------------------------------------------------------ 책별
    /** GET /api/paper/books/{code}/positions */
    public Map<String, Object> positions(String code) {
        if (!ready()) {
            return notReady();
        }
        Book book = existing(code);
        Optional<BookState> state = store.state(code);
        PaperSettings current = settings.current().settings();
        boolean benchmark = PaperBooks.KIND_BENCHMARK.equals(book.kind());
        Double nav = state.map(BookState::nav).orElse(null);
        LocalDate session = UsMarketCalendar.currentOrLastSession(clock.instant());

        // 사후 체결로 연 보유: 그 종목의 사후 매수 체결 관측 시각 = 보유를 연 시각.
        Map<String, Instant> backfillOpened = new HashMap<>();
        for (Fill fill : store.recentFills(code, MAX_LIMIT)) {
            if (fill.backfill() && PaperBooks.BUY.equals(fill.side())) {
                backfillOpened.put(fill.ticker(), fill.observedAt());
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Position p : store.positions(code)) {
            boolean cashLike = current.cashLike(p.ticker());
            double valuationPrice = p.lastPrice() != null ? p.lastPrice() : p.avgCost();
            double marketValue = p.qty() * valuationPrice;
            Double stopPrice = benchmark || cashLike ? null : TrailingStop.stopPrice(p.highWatermark(),
                    current.trailingStopPct());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticker", p.ticker());
            row.put("qty", p.qty());
            row.put("avgCost", round4(p.avgCost()));
            row.put("lastPrice", round4(p.lastPrice()));
            row.put("lastPriceAtKst", stamp(p.lastPriceAt()));
            // 가격을 한 번도 받지 못했으면 평단으로 평가한 값입니다(장부 평가와 같음) — priceStale로 함께 표시.
            row.put("marketValue", round2(marketValue));
            row.put("weightPct", nav != null && nav > 0 ? round2(marketValue / nav * 100.0) : null);
            row.put("unrealizedPct", p.lastPrice() != null && p.avgCost() > 0
                    ? round2((p.lastPrice() / p.avgCost() - 1.0) * 100.0) : null);
            row.put("highWatermark", round4(p.highWatermark()));
            row.put("stopPrice", round4(stopPrice));
            row.put("stopDistancePct", stopPrice != null && p.lastPrice() != null && stopPrice > 0
                    ? round2((p.lastPrice() / stopPrice - 1.0) * 100.0) : null);
            row.put("openedOn", text(p.openedOn()));
            row.put("holdingTradingDays", p.openedOn() == null ? null
                    : UsMarketCalendar.tradingDaysBetween(p.openedOn(), session));
            row.put("priceStale", p.priceStale() || p.lastPrice() == null);
            row.put("splitNote", p.splitNote());
            row.put("cashLike", cashLike);
            row.put("backfilled", p.openedAt() != null && p.openedAt().equals(backfillOpened.get(p.ticker())));
            rows.add(row);
        }
        List<Map<String, Object>> lockouts = new ArrayList<>();
        for (Lockout lockout : store.lockouts(code)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticker", lockout.ticker());
            row.put("untilTradingDate", text(lockout.untilTradingDate()));
            row.put("reason", lockout.reason());
            lockouts.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("book", bookSummary(code));
        out.put("positions", rows);
        out.put("lockouts", lockouts);
        out.put("cash", state.map(s -> round2(s.cash())).orElse(null));
        return out;
    }

    /** GET /api/paper/books/{code}/orders?limit= (1–500, 최근 순) */
    public Map<String, Object> orders(String code, int limit) {
        if (!ready()) {
            return notReady();
        }
        existing(code);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Order o : store.recentOrders(code, Params.clamp(limit, 1, MAX_LIMIT))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", o.id());
            row.put("ticker", o.ticker());
            row.put("side", o.side());
            row.put("qty", o.qty());
            row.put("targetWeightPct", o.targetWeightPct());
            row.put("sellFraction", o.sellFraction());
            row.put("timing", o.timing());
            row.put("source", o.source());
            row.put("status", o.status());
            row.put("rejectReason", o.rejectReason());
            row.put("reason", o.reason());
            row.put("asOfTradingDate", text(o.asOfTradingDate()));
            row.put("createdAtKst", stamp(o.createdAt()));
            row.put("expiresOn", text(o.expiresOn()));
            row.put("filledAtKst", stamp(o.filledAt()));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("orders", rows);
        return out;
    }

    /** GET /api/paper/books/{code}/fills?limit= (1–500, 최근 순) */
    public Map<String, Object> fills(String code, int limit) {
        if (!ready()) {
            return notReady();
        }
        existing(code);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Fill f : store.recentFills(code, Params.clamp(limit, 1, MAX_LIMIT))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", f.id());
            row.put("orderId", f.orderId());
            row.put("ticker", f.ticker());
            row.put("side", f.side());
            row.put("qty", f.qty());
            row.put("price", round4(f.price()));
            row.put("rawPrice", round4(f.rawPrice()));
            row.put("slippageBp", f.slippageBp());
            row.put("gross", round2(f.gross()));
            row.put("observedAtKst", stamp(f.observedAt()));
            row.put("observationDate", text(f.observationDate()));
            row.put("priceSource", f.priceSource());
            row.put("backfill", f.backfill());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("fills", rows);
        return out;
    }

    /** GET /api/paper/books/{code}/nav — 거래일 오름차순. */
    public Map<String, Object> nav(String code) {
        if (!ready()) {
            return notReady();
        }
        existing(code);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NavRow r : store.navSeries(code)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tradingDate", text(r.tradingDate()));
            row.put("nav", round2(r.nav()));
            row.put("cash", round2(r.cash()));
            row.put("equityValue", round2(r.equityValue()));
            row.put("exposurePct", round2(r.exposurePct()));
            row.put("pricesFinal", r.pricesFinal());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("book", bookSummary(code));
        out.put("series", rows);
        return out;
    }

    // ------------------------------------------------------------------ 조각
    /**
     * BookSummary. 시작 전 책은 수익률·통계를 내지 않습니다(null) — 현금만 든 책의 "0%"는 성과가 아니라 아직 시작하지 않은
     * 것이기 때문입니다. 통계는 그 책의 거래일 순자산과 같은 날의 SPY 100% 책 순자산(없는 날은 null)으로 계산합니다(L10).
     */
    private Map<String, Object> summary(Book book, Map<String, List<NavRow>> navByBook) {
        Optional<BookState> state = store.state(book.code());
        List<Position> positions = store.positions(book.code());
        boolean started = !PaperBooks.NOT_STARTED.equals(book.status());
        String benchmark = PaperBooks.benchmarkOf(book.code());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", book.code());
        out.put("kind", book.kind());
        out.put("label", book.label());
        out.put("status", book.status());
        out.put("startedOn", text(book.startedOn()));
        out.put("initialCash", round2(book.initialCash()));
        out.put("cash", state.map(s -> round2(s.cash())).orElse(null));
        out.put("nav", state.map(s -> round2(s.nav())).orElse(null));
        out.put("equityValue", state.map(s -> round2(s.equityValue())).orElse(null));
        out.put("exposurePct", state.map(s -> round2(s.exposurePct())).orElse(null));
        out.put("returnPct", started && book.initialCash() > 0
                ? state.map(s -> round2((s.nav() / book.initialCash() - 1.0) * 100.0)).orElse(null) : null);
        out.put("lastObservedAtKst", state.map(s -> stamp(s.lastObservedAt())).orElse(null));
        out.put("positions", positions.size());
        out.put("staleCount", (int) positions.stream().filter(p -> p.priceStale() || p.lastPrice() == null).count());
        out.put("stats", started ? stats(navByBook.getOrDefault(book.code(), List.of()),
                benchmark == null ? null : navByBook.getOrDefault(benchmark, List.of())) : null);
        out.put("benchmark", benchmark);
        return out;
    }

    /** 통계. 순자산이 0 이하인 날(있을 수 없지만)은 빼고, 계산이 거절되면(날짜 순서 등) null. */
    private static Map<String, Object> stats(List<NavRow> rows, List<NavRow> benchmarkRows) {
        Map<LocalDate, Double> benchmarkByDate = new HashMap<>();
        if (benchmarkRows != null) {
            benchmarkRows.forEach(row -> benchmarkByDate.put(row.tradingDate(), row.nav()));
        }
        List<LocalDate> dates = new ArrayList<>();
        List<Double> nav = new ArrayList<>();
        List<Double> bench = new ArrayList<>();
        for (NavRow row : rows) {
            if (row.nav() > 0) {
                dates.add(row.tradingDate());
                nav.add(row.nav());
                Double b = benchmarkByDate.get(row.tradingDate());
                bench.add(b != null && b > 0 ? b : null);
            }
        }
        PaperStats.Stats s;
        try {
            s = PaperStats.compute(dates, nav, benchmarkRows == null ? null : bench);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cumReturnPct", round2(s.cumReturnPct()));
        out.put("cagrPct", round2(s.cagrPct()));
        out.put("volPct", round2(s.volPct()));
        out.put("mddPct", round2(s.mddPct()));
        out.put("sharpe", round2(s.sharpe()));
        out.put("excessPct", round2(s.excessPct()));
        out.put("beta", round2(s.beta()));
        out.put("sampleDays", s.sampleDays());
        return out;
    }

    /** 순자산 곡선 — 거래일 합집합, 책마다 같은 길이(그날 기록이 없으면 null, 화면이 끊어 그림). */
    private static Map<String, Object> navSeries(List<Book> books, Map<String, List<NavRow>> navByBook) {
        TreeSet<LocalDate> dates = new TreeSet<>();
        navByBook.values().forEach(rows -> rows.forEach(row -> dates.add(row.tradingDate())));
        List<String> dateTexts = dates.stream().map(LocalDate::toString).toList();
        Map<String, Object> series = new LinkedHashMap<>();
        for (Book book : books) {
            Map<LocalDate, Double> byDate = new HashMap<>();
            navByBook.getOrDefault(book.code(), List.of()).forEach(row -> byDate.put(row.tradingDate(), row.nav()));
            List<Double> values = new ArrayList<>();
            for (LocalDate date : dates) {
                values.add(round2(byDate.get(date)));
            }
            series.put(book.code(), values);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dates", dateTexts);
        out.put("books", series);
        return out;
    }

    /** 관측치 요약. 저장본이 없으면 null. */
    private Map<String, Object> quotesSummary() {
        Optional<Quotes> found = reader.quietly(marketData::quotes);
        if (found.isEmpty()) {
            return null;
        }
        Quotes quotes = found.get();
        long age = Math.max(0, Duration.between(quotes.collectedAt(), clock.instant()).getSeconds());
        LocalDate barDate = quotes.modalBarDate();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collectedAtKst", Kst.stamp(quotes.collectedAt()));
        out.put("ageSeconds", age);
        out.put("barDate", text(barDate));
        out.put("finalClose", barDate != null && PaperMarketData.isFinal(barDate, quotes.collectedAt()));
        out.put("tickers", quotes.bars().size());
        out.put("staleTickers", quotes.staleTickers());
        out.put("stale", age > QUOTES_LATE.getSeconds());
        return out;
    }

    /**
     * 가장 최근 기관 선정의 요지 {@code {asOfDate, selected:[{cik, name, rank}]}}(단계 2 계약의 모양 — 자세한 것은
     * GET /track-record). 이름·순위는 선정 때 남긴 순위표({@code ranking})의 값이고, 순위표에 없는 CIK면 null. 선정이 없으면
     * null — 선정은 1년에 몇 줄뿐이라 싸게 읽습니다.
     */
    private Map<String, Object> latestSelection() {
        return store.selections().stream().findFirst().map(selection -> {
            Map<String, JsonNode> ranking = new HashMap<>();
            if (selection.ranking() != null && selection.ranking().isArray()) {
                for (JsonNode entry : selection.ranking()) {
                    String cik = Json.asText(entry, "cik");
                    if (cik != null) {
                        ranking.putIfAbsent(cik, entry);
                    }
                }
            }
            List<Map<String, Object>> selected = new ArrayList<>();
            for (String cik : selection.selectedCiks()) {
                JsonNode entry = ranking.get(cik);
                Double rank = entry == null ? null : Json.asDouble(entry, "rank");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cik", cik);
                row.put("name", entry == null ? null : Json.asText(entry, "name"));
                row.put("rank", rank == null ? null : rank.intValue());
                selected.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("asOfDate", selection.asOfDate().toString());
            out.put("selected", selected);
            return out;
        }).orElse(null);
    }

    private List<Map<String, Object>> jobs(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JobRun run : store.recentJobRuns(limit)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("job", run.job());
            row.put("startedAtKst", stamp(run.startedAt()));
            row.put("finishedAtKst", stamp(run.finishedAt()));
            row.put("outcome", run.outcome());
            row.put("detail", run.detail());
            out.add(row);
        }
        return out;
    }

    private List<Book> sortedBooks() {
        List<Book> books = new ArrayList<>(store.books());
        books.sort((a, b) -> PaperBooks.ORDER.compare(a.code(), b.code()));
        return books;
    }

    private static boolean initialized(List<Book> books) {
        return PaperBooks.ALL.stream().allMatch(d -> books.stream().anyMatch(b -> b.code().equals(d.code())));
    }

    /**
     * 아직 사후 체결하지 못한(시작 전) 벤치마크 책 코드, 정의 순. 초기화는 책 넷을 만들고 끝나므로 {@code initialized}는
     * true가 되지만, 출발일 종가를 받기 전에 눌렀으면 벤치마크가 시작 전으로 남습니다 — 화면은 이 목록이 비지 않으면
     * 초기화 버튼을 계속 보여 줍니다(다시 누르면 그때 체결, 초기화는 멱등).
     */
    private static List<String> pendingBackfill(List<Book> books) {
        return books.stream().filter(b -> PaperBooks.KIND_BENCHMARK.equals(b.kind())
                && PaperBooks.NOT_STARTED.equals(b.status())).map(Book::code).toList();
    }

    /** 없는 책 코드는 404가 아니라 400 — 고칠 것(코드)을 알려 줍니다. */
    private Book existing(String code) {
        return store.book(code).orElseThrow(() -> new InvalidRequestException(
                "없는 책입니다: " + Params.echo(code) + " (rules · claude · bench_spy · bench_mix)"));
    }

    // ------------------------------------------------------------------ 숫자·글자
    static Double round2(Double value) {
        return round(value, 2);
    }

    static Double round4(Double value) {
        return round(value, 4);
    }

    private static Double round(Double value, int scale) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static String stamp(TemporalAccessor moment) {
        return moment == null ? null : Kst.stamp(moment);
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
