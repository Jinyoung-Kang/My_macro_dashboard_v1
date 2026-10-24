package com.macrodash.feature.paper;

import com.macrodash.Kst;
import com.macrodash.analytics.DailySeries;
import com.macrodash.analytics.TrackRecord13F;
import com.macrodash.analytics.TrackRecord13F.Filing;
import com.macrodash.analytics.TrackRecord13F.Holding13F;
import com.macrodash.analytics.TrackRecord13F.Period;
import com.macrodash.analytics.TrackRecord13F.Ranked;
import com.macrodash.analytics.TrackRecord13F.Result;
import com.macrodash.analytics.UsMarketCalendar;
import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.PaperStore;
import com.macrodash.store.PaperStore.Selection;
import com.macrodash.store.PaperStore.SettingsRow;
import com.macrodash.store.PaperStore.TrackRecordRow;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreRepository;
import com.macrodash.support.InvalidRequestException;
import com.macrodash.support.Json;
import com.macrodash.support.Params;
import com.macrodash.support.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoublePredicate;

/**
 * 🧪 기관 선정 — 후보 기관의 13F 트랙레코드를 계산해 순위를 매기고, 재선정일에 상위 N곳을 선정으로 남깁니다(명세 R1·R5·R7~R10,
 * 설계 §4-0 ⑤). 설계의 {@code TrackRecordService}입니다 — 국면 판정 성과의 {@code feature/insight/TrackRecordService}와 빈
 * 이름이 겹쳐 이름을 바꿨습니다(단계 0).
 *
 * <p><b>입력은 수집기 저장본뿐</b>입니다. 후보 목록·진행률은 {@code sec.13f.track_record_index}, 기관마다의 공시는
 * {@code sec.13f.{cik}.q30}(보유마다 {@code ticker}·{@code value}), 가격은 10년 종가 이력({@code equity_close})입니다. 스냅샷은
 * 언제나 {@link StoreReader#quietly}로 읽습니다 — 버튼(수동 재계산)이나 화면 조회가 SEC 수백 건짜리 수집을 깨우지 않게.
 * 가격은 창 안 모든 공시의 티커와 SPY를 <b>한 번에</b> 읽습니다(종목 약 550개 × 7년 — 티커마다 따로 읽으면 왕복이 수백 번).
 *
 * <p><b>룩어헤드</b>: 기준일 뒤 공시·가격을 쓰지 않습니다 — 공시는 {@link TrackRecord13F#compute}가 공시일로 거르고, 가격은
 * 읽는 범위를 기준일까지로 자릅니다. 최신 분기 공시 여부(R5)도 기준일까지 낸 공시로만 봅니다.
 *
 * <p><b>자격</b>은 {@link TrackRecord13F}의 기준(구간 수·커버리지)에 두 가지를 더합니다. ① 30분기 수집이 아직 끝나지 않은 기관
 * ({@code complete=false})은 "수집 중" — 창의 앞쪽 공시가 비어 구간 수·수익률이 실제보다 짧은 기간의 값이라 순위에 올리지
 * 않습니다(설계 §6-4). ② 그 기준일에 공시 기한이 지난 최신 분기를 공시하지 않은 기관은 그 분기 선정에서 빠집니다(R5, 사이언).
 * 수치는 둘 다 그대로 계산해 화면이 보이게 합니다.
 *
 * <p><b>선정은 한 번</b>: {@link #select}는 그 기준일에 선정이 이미 있으면 바꾸지 않고, {@link #rerank}(수동 재계산)는
 * 트랙레코드만 다시 계산합니다 — 그 선정으로 낸 주문 기록과 어긋나지 않게(설계 §4-0 ⑤).
 */
@Service
public class PaperSelectionService {

    private static final Logger log = LoggerFactory.getLogger(PaperSelectionService.class);

    /** 수집기 태스크 이름(registry) — quietly로만 읽으니 요청은 나가지 않고, 읽기 경로의 이름표입니다. */
    static final String TRACK_RECORD_TASK = "sec_13f_track_record";
    static final String SPY = "SPY";
    /**
     * 가격을 창 시작보다 이만큼 앞에서부터 읽습니다. 첫 구간 시작 가격은 그날 이하 마지막 종가가 7일 안이어야 하므로
     * ({@code TrackRecord13F.MAX_START_PRICE_AGE_DAYS}) 연휴·거래 정지를 넉넉히 덮습니다.
     */
    static final int PRICE_MARGIN_DAYS = 14;
    /** "수집 중" 사유의 앞머리 — 조회 응답의 {@code collecting}을 저장된 사유에서 다시 읽습니다(열이 따로 없음). */
    static final String COLLECTING_PREFIX = "수집 중(";
    static final String NO_INDEX_REASON = "후보 목록(sec.13f.track_record_index)이 아직 없습니다 — 수집기가 13F 트랙레코드 "
            + "태스크(sec_13f_track_record)를 돌린 뒤 다시 계산하세요.";
    static final String NO_CANDIDATE_REASON = "후보가 없습니다 — 설정 trackRecord.candidateCiks가 수집 목록의 기관과 "
            + "겹치지 않습니다.";
    /** 선정을 남기지 않은 이유 — 자격 기관 0곳인데 수집 중인 후보가 있음. %d = 수집 중 기관 수, 후보 수. */
    static final String COLLECTING_SKIP_REASON = "수집 중 %d곳(후보 %d곳) — 자격 있는 기관이 아직 없어 선정을 남기지 "
            + "않습니다. 수집이 덜 끝나서인지 기준 미달인지 몰라 '자격 기관 없음'으로 굳히지 않습니다 — 수집이 끝난 뒤 다시 "
            + "선정합니다.";
    /** 수동 재계산이 겹쳤을 때 다시 누를 때까지의 안내 시간. 27곳 계산은 보통 몇 초입니다. */
    static final Duration BUSY_RETRY = Duration.ofSeconds(15);

    /** 트랙레코드의 한계(명세 §6 5·6번을 이 탭에 맞게 나눔) — 순서대로 복제 수익률·생존 편향·커버리지. */
    static final List<String> LIMITS = List.of(
            "트랙레코드는 13F 공시일 다음 거래일 종가에 사서 다음 공시의 다음 거래일 종가까지 들었다고 복제한 값입니다 — "
                    + "실제 기관 수익률이 아닙니다(분기 중 매매·공매도·채권·옵션은 13F에 보이지 않습니다). 수정주가라 배당이 들어갑니다.",
            "후보 기관 목록은 오늘 알려진 운용사라 생존 편향이 있습니다 — 사라진 운용사는 처음부터 후보에 없습니다.",
            "커버리지(티커와 가격이 있는 평가액 비중)가 100% 미만이면 수익률은 '커버 보유분 기준'입니다. 커버리지가 낮은 "
                    + "기관은 자격에서 뺍니다.");

    private final PaperStore store;
    private final StoreReader reader;
    private final PaperMarketData marketData;
    private final PaperSettingsService settings;
    private final ObjectMapper mapper;
    private final Clock clock;
    /**
     * 계산은 한 번에 하나 — 저장본 27개·가격 수십만 점을 읽는 일이라 버튼을 두 번 누르면 같은 일을 두 번 합니다. 정기 작업
     * ({@link #select})은 기다리고, 수동 재계산은 기다리지 않고 429로 돌려보냅니다.
     */
    private final ReentrantLock computing = new ReentrantLock();

    @Autowired
    public PaperSelectionService(PaperStore store, StoreReader reader, PaperMarketData marketData,
                                 PaperSettingsService settings, ObjectMapper mapper) {
        this(store, reader, marketData, settings, mapper, Clock.systemUTC());
    }

    /** 테스트용 — 시계를 움직입니다(수동 재계산의 기본 기준일 = 마지막 완료 세션). */
    PaperSelectionService(PaperStore store, StoreReader reader, PaperMarketData marketData,
                          PaperSettingsService settings, ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.reader = reader;
        this.marketData = marketData;
        this.settings = settings;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 설정
    /**
     * 설정 {@code trackRecord.*}(설계 §4-4) 중 계산이 읽는 값. 키가 없거나 범위 밖이면 그 키만 기본값으로 읽고
     * {@link #fallbacks}에 남깁니다({@link PaperSettings}와 같은 방식 — 설정 쓰기 API(단계 3a)가 검사하기 전의 행이 있어도
     * 계산이 멈추지 않게).
     *
     * @param candidateCiks null이면 수집 목록의 기관 전부, 아니면 그 목록만(명세 D21)
     */
    public record TrackRecordSettings(TrackRecord13F.Criteria criteria, TrackRecord13F.Metric metric, int topN,
                                      List<String> candidateCiks, List<String> fallbacks) {

        public static final int DEFAULT_TOP_N = 5;

        public TrackRecordSettings {
            candidateCiks = candidateCiks == null ? null : List.copyOf(candidateCiks);
            fallbacks = List.copyOf(fallbacks);
        }

        public static TrackRecordSettings defaults() {
            return new TrackRecordSettings(TrackRecord13F.Criteria.defaults(), TrackRecord13F.Metric.CAGR,
                    DEFAULT_TOP_N, null, List.of());
        }

        /** 설정 본문 전체에서 {@code trackRecord}를 읽습니다. null이면 기본값. */
        static TrackRecordSettings from(JsonNode body) {
            JsonNode node = Json.child(body, "trackRecord");
            if (node == null || !node.isObject()) {
                return new TrackRecordSettings(TrackRecord13F.Criteria.defaults(), TrackRecord13F.Metric.CAGR,
                        DEFAULT_TOP_N, null, body == null ? List.of() : List.of("trackRecord"));
            }
            TrackRecord13F.Criteria base = TrackRecord13F.Criteria.defaults();
            List<String> fallbacks = new ArrayList<>();
            // 창은 10년 이력(equity_close)과 30분기 저장본이 덮는 범위 안에서만 — 더 길면 앞쪽이 비어 짧은 기간의 값이 됩니다.
            int window = (int) number(node, "windowYears", base.windowYears(),
                    v -> v >= 1 && v <= 7 && v == Math.rint(v), fallbacks);
            int minPeriods = (int) number(node, "minPeriods", base.minPeriods(),
                    v -> v >= 0 && v <= 200 && v == Math.rint(v), fallbacks);
            double minCoverage = number(node, "minCoverage", base.minCoverage(), v -> v >= 0 && v <= 1, fallbacks);
            double avgCoverage = number(node, "avgCoverage", base.avgCoverage(), v -> v >= 0 && v <= 1, fallbacks);
            int topN = (int) number(node, "topN", DEFAULT_TOP_N, v -> v >= 1 && v <= 50 && v == Math.rint(v),
                    fallbacks);
            TrackRecord13F.Metric metric = TrackRecord13F.Metric.CAGR;
            String metricText = Json.asText(node, "metric");
            try {
                metric = TrackRecord13F.Metric.valueOf(metricText == null ? "" : metricText.trim()
                        .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                fallbacks.add("metric");
            }
            List<String> ciks = null;
            JsonNode list = Json.child(node, "candidateCiks");
            if (list != null) {
                if (list.isArray()) {
                    LinkedHashSet<String> unique = new LinkedHashSet<>();
                    for (JsonNode item : list) {
                        String cik = normalizeCik(item.isString() ? item.asString("") : null);
                        if (cik != null) {
                            unique.add(cik);
                        }
                    }
                    ciks = List.copyOf(unique);
                } else {
                    fallbacks.add("candidateCiks");
                }
            }
            return new TrackRecordSettings(new TrackRecord13F.Criteria(window, minPeriods, minCoverage, avgCoverage),
                    metric, topN, ciks, fallbacks.stream().map(key -> "trackRecord." + key).toList());
        }

        private static double number(JsonNode node, String key, double fallback, DoublePredicate valid,
                                     List<String> fallbacks) {
            Double value = Json.asDouble(node, key);
            if (value == null || !Double.isFinite(value) || !valid.test(value)) {
                fallbacks.add(key);
                return fallback;
            }
            return value;
        }
    }

    /** CIK를 저장본과 같은 10자리(앞 0 채움)로. 숫자가 아니거나 비면 null. */
    static String normalizeCik(String text) {
        if (text == null) {
            return null;
        }
        String digits = text.trim();
        if (digits.isEmpty() || digits.length() > 10 || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return "0".repeat(10 - digits.length()) + digits;
    }

    // ------------------------------------------------------------------ 수집 저장본
    /** 후보 목록 저장본 한 줄. 저장 분기 수를 모르면 0. */
    record Filer(String cik, String name, String style, int storedQuarters, boolean complete) {
    }

    /** 후보 목록 저장본. 기관 순서는 수집기 목록 순(스타일별)입니다. */
    record CollectionIndex(Instant collectedAt, List<Filer> filers, int targetQuarters) {
    }

    /** 기관 하나의 q30 저장본을 계산이 받는 모양으로. 저장본이 없으면 공시 없음·수집 중. */
    record StoredFilings(List<Filing> filings, int storedQuarters, boolean complete) {
    }

    Optional<CollectionIndex> index() {
        return reader.quietly(() -> reader.read(Datasets.SNAP_TRACK_RECORD_INDEX, Datasets.MAX_AGE_WEEKLY,
                TRACK_RECORD_TASK)).flatMap(PaperSelectionService::parseIndex);
    }

    static Optional<CollectionIndex> parseIndex(Snapshot snapshot) {
        JsonNode payload = snapshot.payload();
        List<Filer> filers = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : Json.array(payload, "filers")) {
            String cik = normalizeCik(Json.asText(node, "cik"));
            if (cik == null || !seen.add(cik)) {
                continue;
            }
            Double stored = Json.asDouble(node, "storedQuarters");
            filers.add(new Filer(cik, Json.asText(node, "name"), Json.asText(node, "style"),
                    stored == null ? 0 : stored.intValue(), Json.asBoolean(node, "complete")));
        }
        if (filers.isEmpty()) {
            return Optional.empty();
        }
        Double target = Json.asDouble(payload, "targetQuarters");
        return Optional.of(new CollectionIndex(snapshot.collectedAt(), filers,
                target == null || target < 1 ? Datasets.TRACK_RECORD_QUARTERS : target.intValue()));
    }

    /**
     * 기관 하나의 q30 저장본 → 공시 목록. 공시일을 모르는 분기는 구간을 정할 수 없어 빼고, 평가액이 없는 보유 줄은 총평가액에도
     * 넣지 않습니다(모르는 값을 0으로 세지 않음). 티커 문자열은 {@code pool}로 같은 객체를 씁니다(30분기 × 보유 수천 줄).
     *
     * <p>저장본을 JSON 트리로 읽지 않고, 메타(완료 여부)와 보유 열만 DB에서 풀어 읽습니다 — 27곳 × 30분기 트리를 저장소 파싱
     * 캐시에 남기면 백엔드 힙을 위협합니다({@link StoreReader#readStoredHoldings}). 수집을 깨우지 않는 읽기입니다.
     */
    private StoredFilings stored(String cik, Map<String, String> pool) {
        String name = Datasets.sec13f(cik, Datasets.TRACK_RECORD_QUARTERS);
        Optional<Snapshot> meta = reader.readStoredMeta(name, "quarters");
        if (meta.isEmpty()) {
            return new StoredFilings(List.of(), 0, false);
        }
        List<StoreRepository.HoldingRow> rows = reader.readStoredHoldings(name);
        Map<Integer, List<StoreRepository.HoldingRow>> byQuarter = new LinkedHashMap<>();
        for (StoreRepository.HoldingRow row : rows) {
            byQuarter.computeIfAbsent(row.quarterIndex(), key -> new ArrayList<>()).add(row);
        }
        List<Filing> filings = new ArrayList<>(byQuarter.size());
        for (List<StoreRepository.HoldingRow> quarter : byQuarter.values()) {
            StoreRepository.HoldingRow first = quarter.get(0);
            LocalDate filingDate = Json.parseDate(first.filingDate());
            if (filingDate == null) {
                continue;
            }
            List<Holding13F> holdings = new ArrayList<>(quarter.size());
            for (StoreRepository.HoldingRow holding : quarter) {
                if (holding.value() == null) {
                    continue;
                }
                String ticker = holding.ticker();
                ticker = ticker == null || ticker.isBlank() ? null : pool.computeIfAbsent(ticker.strip(), key -> key);
                holdings.add(new Holding13F(ticker, holding.value()));
            }
            filings.add(new Filing(filingDate, Json.parseDate(first.reportDate()), holdings));
        }
        return new StoredFilings(filings, byQuarter.size(), Json.asBoolean(meta.get().payload(), "complete"));
    }

    /**
     * 창 안 구간에 들어갈 공시의 티커 — 가격을 한 번에 읽을 목록. {@link TrackRecord13F#compute}가 구간으로 세는 공시의
     * 상위 집합입니다(다음 공시의 다음 거래일이 창 시작 뒤인 공시와, 기준일까지의 마지막 공시). 기준일 뒤 공시는 넣지 않습니다.
     * "다음 공시"는 계산과 같은 순서({@link TrackRecord13F#FILING_ORDER} — 같은 날이면 보고일 순)로 정합니다. 저장본은 보고일
     * 내림차순이라 공시일로만 정렬하면 같은 날 낸 두 공시가 [뒤 분기, 앞 분기]로 남아, 계산이 뒤 분기로 만드는 구간의 티커를 빠뜨립니다.
     */
    static Set<String> windowTickers(List<Filing> filings, LocalDate asOf, LocalDate windowStart) {
        List<Filing> seen = filings.stream().filter(f -> !f.filingDate().isAfter(asOf))
                .sorted(TrackRecord13F.FILING_ORDER).toList();
        Set<String> out = new TreeSet<>();
        for (int i = 0; i < seen.size(); i++) {
            boolean last = i == seen.size() - 1;
            if (!last && !UsMarketCalendar.nextTradingDay(seen.get(i + 1).filingDate()).isAfter(windowStart)) {
                continue;
            }
            for (Holding13F holding : seen.get(i).holdings()) {
                if (holding.ticker() != null) {
                    out.add(holding.ticker());
                }
            }
        }
        return out;
    }

    /**
     * 기준일까지 그 분기(또는 더 뒤 분기)를 공시했는가(R5). 보고일을 모르는 공시는 어느 분기인지 알 수 없어 세지 않습니다.
     */
    static boolean filedQuarter(List<Filing> filings, LocalDate quarterEnd, LocalDate asOf) {
        return filings.stream().anyMatch(f -> !f.filingDate().isAfter(asOf) && f.reportDate() != null
                && !f.reportDate().isBefore(quarterEnd));
    }

    // ------------------------------------------------------------------ 계산
    /**
     * 후보 한 곳의 최종 결과.
     *
     * @param result        자격·사유는 "수집 중"·"최신 분기 공시 없음"까지 반영한 값(수치는 {@link TrackRecord13F} 그대로)
     * @param rank          자격 있는 기관의 순위(1부터), 없으면 null
     * @param collecting    30분기 수집이 끝나지 않아 자격에서 뺌
     * @param missingLatest 기준일에 공시 기한이 지난 최신 분기를 공시하지 않아 자격에서 뺌(R5)
     */
    public record Scored(String cik, String name, String style, Result result, Integer rank, boolean collecting,
                         boolean missingLatest, int storedQuarters, boolean complete) {
    }

    /**
     * 기준일 하나의 계산.
     *
     * @param settingsVersion 계산에 쓴 설정 버전(건너뛰었으면 null)
     * @param latestQuarter   그 기준일에 공시 기한이 지난 최신 분기 말(R5)
     * @param candidates      후보 목록 순서(수집기 목록 순)
     * @param skippedReason   계산하지 못한 이유(후보 목록 없음 등). 계산했으면 null — 이때만 저장합니다
     */
    public record Computation(LocalDate asOf, Integer settingsVersion, TrackRecordSettings settings,
                              LocalDate latestQuarter, List<Scored> candidates, String skippedReason) {

        public Computation {
            candidates = List.copyOf(candidates);
        }

        /** 자격 있는 기관, 순위 순. */
        public List<Scored> ranked() {
            return candidates.stream().filter(s -> s.rank() != null)
                    .sorted(Comparator.comparingInt(Scored::rank)).toList();
        }
    }

    /**
     * 그 기준일의 트랙레코드를 계산해 {@code paper_track_record}에 저장합니다(같은 기준일의 행은 지우고 다시 넣음 — 후보 목록이
     * 줄었으면 빠진 기관의 옛 행이 남지 않게). 후보 목록 저장본이 없으면 아무것도 쓰지 않고 {@code skippedReason}만 돌려줍니다.
     * 다른 계산이 돌고 있으면 끝날 때까지 기다립니다.
     *
     * @throws IllegalStateException 모의 매매 테이블이 없음
     */
    public Computation compute(LocalDate asOf) {
        computing.lock();
        try {
            return computeLocked(asOf);
        } finally {
            computing.unlock();
        }
    }

    private Computation computeLocked(LocalDate asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("기준일이 없습니다");
        }
        requireReady();
        LocalDate latestQuarter = UsMarketCalendar.latestQuarterEndReselectedBy(asOf);
        Optional<CollectionIndex> index = index();
        if (index.isEmpty()) {
            return new Computation(asOf, null, currentSettings(), latestQuarter, List.of(), NO_INDEX_REASON);
        }
        // 저장할 행에는 설정 버전이 필요합니다. 초기화 전이면 기본값으로 설정 행을 만듭니다(초기화가 만드는 것과 같은 값).
        SettingsRow row = settingsRow();
        TrackRecordSettings trackSettings = TrackRecordSettings.from(row.body());
        if (!trackSettings.fallbacks().isEmpty()) {
            log.warn("모의 매매 설정 v{}의 키 {}가 없거나 범위 밖이라 기본값으로 계산합니다", row.version(),
                    trackSettings.fallbacks());
        }
        List<Filer> candidates = index.get().filers().stream()
                .filter(f -> trackSettings.candidateCiks() == null || trackSettings.candidateCiks().contains(f.cik()))
                .toList();
        if (candidates.isEmpty()) {
            return new Computation(asOf, row.version(), trackSettings, latestQuarter, List.of(), NO_CANDIDATE_REASON);
        }

        TrackRecord13F.Criteria criteria = trackSettings.criteria();
        LocalDate windowStart = asOf.minusYears(criteria.windowYears());
        Map<String, String> pool = new HashMap<>();
        Map<String, StoredFilings> storedByCik = new LinkedHashMap<>();
        Set<String> tickers = new TreeSet<>();
        tickers.add(SPY);
        for (Filer filer : candidates) {
            StoredFilings stored = stored(filer.cik(), pool);
            storedByCik.put(filer.cik(), stored);
            tickers.addAll(windowTickers(stored.filings(), asOf, windowStart));
        }
        // 한 번에 읽고, 기준일 뒤 값은 읽지도 않습니다(룩어헤드 방지).
        Map<String, DailySeries> prices = marketData.history(tickers, windowStart.minusDays(PRICE_MARGIN_DAYS), asOf);
        DailySeries spy = prices.get(SPY);

        List<Scored> unranked = new ArrayList<>();
        for (Filer filer : candidates) {
            StoredFilings stored = storedByCik.get(filer.cik());
            Result raw = TrackRecord13F.compute(filer.cik(), stored.filings(), prices::get, spy, asOf, criteria);
            boolean collecting = !stored.complete();
            // 공시가 하나도 없으면 사유 "공시 없음"이 이미 모든 것을 말합니다.
            boolean missingLatest = raw.lastFilingDate() != null
                    && !filedQuarter(stored.filings(), latestQuarter, asOf);
            List<String> reasons = new ArrayList<>();
            if (collecting) {
                reasons.add(COLLECTING_PREFIX + "저장 분기 " + stored.storedQuarters() + "/"
                        + Datasets.TRACK_RECORD_QUARTERS + ")");
            }
            if (missingLatest) {
                reasons.add("최신 분기(" + latestQuarter + ") 공시 없음");
            }
            if (!raw.eligible() && raw.ineligibleReason() != null) {
                reasons.add(raw.ineligibleReason());
            }
            Result result = finite(raw, reasons.isEmpty() && raw.eligible(), String.join("; ", reasons));
            unranked.add(new Scored(filer.cik(), filer.name(), filer.style(), result, null, collecting, missingLatest,
                    stored.storedQuarters(), stored.complete()));
        }

        Map<String, Integer> rankByCik = new HashMap<>();
        for (Ranked ranked : TrackRecord13F.rank(unranked.stream().map(Scored::result).toList(),
                trackSettings.metric())) {
            rankByCik.put(ranked.result().cik(), ranked.rank());
        }
        List<Scored> scored = unranked.stream().map(s -> new Scored(s.cik(), s.name(), s.style(), s.result(),
                rankByCik.get(s.cik()), s.collecting(), s.missingLatest(), s.storedQuarters(), s.complete())).toList();

        List<TrackRecordRow> rows = new ArrayList<>();
        for (Scored s : scored) {
            rows.add(row(asOf, s, trackSettings, row.version()));
        }
        store.atomically(() -> {
            store.deleteTrackRecords(asOf);
            rows.forEach(store::upsertTrackRecord);
            return null;
        });
        log.info("13F 트랙레코드 계산: 기준일 {}, 후보 {}곳, 자격 {}곳, 가격 {}종목", asOf, scored.size(),
                rankByCik.size(), prices.size());
        return new Computation(asOf, row.version(), trackSettings, latestQuarter, scored, null);
    }

    /**
     * 자격·사유를 최종값으로 바꾸고, 유한하지 않은 수치는 null로 — 극단적인 짧은 기간의 연율(CAGR)이 넘칠 수 있는데, 그 값을
     * 저장하면 DB가 거절하고(PaperStore) 순위가 무한대에 끌려갑니다.
     */
    private static Result finite(Result r, boolean eligible, String reason) {
        return new Result(r.cik(), r.periodsUsed(), r.periodsTotal(), r.firstFilingDate(), r.lastFilingDate(),
                finite(r.cumReturnPct()), finite(r.cagrPct()), finite(r.volPct()), finite(r.mddPct()),
                finite(r.sharpe()), finite(r.spyCumReturnPct()), finite(r.excessVsSpyPct()), finite(r.coverageMin()),
                finite(r.coverageAvg()), eligible, eligible ? null : reason, r.periods());
    }

    private static Double finite(Double value) {
        return value == null || !Double.isFinite(value) ? null : value;
    }

    private TrackRecordRow row(LocalDate asOf, Scored s, TrackRecordSettings trackSettings, int version) {
        Result r = s.result();
        List<Map<String, Object>> periods = new ArrayList<>();
        for (Period p : r.periods()) {
            Map<String, Object> period = new LinkedHashMap<>();
            period.put("filingDate", text(p.filingDate()));
            period.put("reportDate", text(p.reportDate()));
            period.put("start", text(p.start()));
            period.put("end", text(p.end()));
            period.put("returnPct", finite(p.returnPct()));
            period.put("spyReturnPct", finite(p.spyReturnPct()));
            period.put("coverage", Double.isFinite(p.coverage()) ? p.coverage() : null);
            period.put("coveredTickers", p.coveredTickers());
            period.put("holdings", p.holdings());
            periods.add(period);
        }
        return new TrackRecordRow(asOf, s.cik(), trackSettings.criteria().windowYears(), trackSettings.metric().name(),
                r.periodsUsed(), r.periodsTotal(), r.firstFilingDate(), r.lastFilingDate(), r.cumReturnPct(),
                r.cagrPct(), r.volPct(), r.mddPct(), r.sharpe(), r.excessVsSpyPct(), r.coverageMin(), r.coverageAvg(),
                r.eligible(), r.ineligibleReason(), s.rank(), mapper.valueToTree(periods), version, null);
    }

    // ------------------------------------------------------------------ 선정
    /**
     * 선정 결과.
     *
     * @param created       이번에 선정을 새로 남겼는가(이미 있었으면 false — 기존 선정 그대로)
     * @param selection     그 기준일의 선정. 계산하지 못했으면 null
     * @param computation   이번에 한 계산. 선정이 이미 있어 계산하지 않았으면 null
     * @param skippedReason 계산하지 못한 이유
     */
    public record SelectionOutcome(LocalDate asOf, boolean created, Selection selection, Computation computation,
                                   String skippedReason) {
    }

    /**
     * 재선정일·초기 선정(설계 §4-3·§6-2): 그 기준일에 선정이 <b>이미 있으면 계산하지 않고 그대로</b> 돌려줍니다(10분마다 도는
     * 결정 작업이 같은 날 다시 불러도 한 번). 없으면 계산하고 자격 있는 기관 상위 {@code trackRecord.topN}곳을 남깁니다 —
     * 자격 기관이 N곳보다 적으면 있는 만큼, 0곳이면 빈 선정(규칙 책은 SHY만). 수집 중인 후보는 자격에서 빠진 채로 선정합니다
     * (설계 §6-4 — 재선정일에 새로 더한 기관 하나가 덜 모였다고 선정을 멈추지 않게).
     *
     * <p>선정을 남기지 않는 경우(모르는 것을 '자격 기관 없음'으로 굳히지 않게 — 같은 기준일은 다시 계산하지 않으므로 한 번 남긴
     * 빈 선정은 다음 재선정일까지 갑니다): ① 후보 목록 저장본이 없음, ② 자격 기관이 0곳인데 수집 중인 후보가 있음 — 처음 기동한
     * 뒤 30분기를 채우는 동안(실행 몇 번)에 불리면 0곳이 기준 미달 때문인지 수집이 덜 끝나서인지 알 수 없습니다. 트랙레코드
     * 계산은 둘째 경우에도 저장합니다.
     */
    public SelectionOutcome select(LocalDate asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("기준일이 없습니다");
        }
        computing.lock();
        try {
            requireReady();
            Optional<Selection> existing = selectionAt(asOf);
            if (existing.isPresent()) {
                return new SelectionOutcome(asOf, false, existing.get(), null, null);
            }
            Computation computation = computeLocked(asOf);
            if (computation.skippedReason() != null) {
                return new SelectionOutcome(asOf, false, null, computation, computation.skippedReason());
            }
            List<Scored> ranked = computation.ranked();
            long collecting = computation.candidates().stream().filter(Scored::collecting).count();
            if (ranked.isEmpty() && collecting > 0) {
                String reason = String.format(Locale.ROOT, COLLECTING_SKIP_REASON, collecting,
                        computation.candidates().size());
                log.info("기관 선정 건너뜀: 기준일 {}, {}", asOf, reason);
                return new SelectionOutcome(asOf, false, null, computation, reason);
            }
            int topN = computation.settings().topN();
            List<String> selected = ranked.stream().limit(topN).map(Scored::cik).toList();
            List<Map<String, Object>> ranking = new ArrayList<>();
            for (Scored s : ranked) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("cik", s.cik());
                entry.put("name", s.name());
                entry.put("rank", s.rank());
                entry.put("metric", computation.settings().metric().name());
                entry.put("cagrPct", s.result().cagrPct());
                entry.put("excessVsSpyPct", s.result().excessVsSpyPct());
                entry.put("sharpe", s.result().sharpe());
                entry.put("selected", selected.contains(s.cik()));
                ranking.add(entry);
            }
            boolean created = store.insertSelectionIfAbsent(new Selection(asOf, selected, mapper.valueToTree(ranking),
                    computation.settingsVersion(), null));
            Selection saved = selectionAt(asOf).orElseThrow(() -> new IllegalStateException(
                    "선정을 남겼는데 다시 읽지 못했습니다: " + asOf));
            log.info("기관 선정 {}: 기준일 {}, {}", created ? "기록" : "이미 있음", asOf, saved.selectedCiks());
            return new SelectionOutcome(asOf, created, saved, computation, null);
        } finally {
            computing.unlock();
        }
    }

    /** 가장 최근 선정. 없으면 empty. */
    public Optional<Selection> latestSelection() {
        if (!store.ready()) {
            return Optional.empty();
        }
        return store.selections().stream().findFirst();
    }

    /** 그날 유효한 선정(그날 이하 가장 최근 선정). 없으면 empty. */
    public Optional<Selection> selectionOn(LocalDate date) {
        if (date == null || !store.ready()) {
            return Optional.empty();
        }
        return store.selectionOnOrBefore(date);
    }

    private Optional<Selection> selectionAt(LocalDate asOf) {
        return store.selectionOnOrBefore(asOf).filter(s -> s.asOfDate().equals(asOf));
    }

    // ------------------------------------------------------------------ HTTP
    /**
     * POST /api/paper/run/rerank — 수동 재계산. 그 기준일의 트랙레코드를 덮어쓰고 <b>선정은 바꾸지 않습니다</b>(기록용, 설계
     * §6-4). 기준일이 없으면 마지막 완료 세션. 끝나지 않은 세션(장중의 오늘·미래)은 그날 종가가 아직 없거나 장중 값이라 400.
     *
     * @throws TooManyRequestsException 다른 계산이 돌고 있음(429)
     */
    public Map<String, Object> rerank(String asOfText) {
        if (!store.ready()) {
            Map<String, Object> out = notReady();
            out.put("ok", false);
            return out;
        }
        LocalDate session = UsMarketCalendar.lastCompletedSession(clock.instant());
        LocalDate asOf = Params.optionalDate("asOf", asOfText);
        if (asOf == null) {
            asOf = session;
        }
        LocalDate lastTradingDay = UsMarketCalendar.isTradingDay(asOf) ? asOf
                : UsMarketCalendar.previousTradingDay(asOf);
        if (lastTradingDay.isAfter(session)) {
            throw new InvalidRequestException("asOf는 끝난 세션까지만 됩니다(마지막 완료 세션 " + session + "): "
                    + Params.echo(asOfText));
        }
        if (!computing.tryLock()) {
            throw new TooManyRequestsException("트랙레코드를 이미 계산하고 있습니다 — 끝난 뒤 다시 누르세요.", BUSY_RETRY);
        }
        Computation computation;
        try {
            computation = computeLocked(asOf);
        } finally {
            computing.unlock();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("asOfDate", asOf.toString());
        out.put("computed", computation.candidates().size());
        out.put("eligible", computation.ranked().size());
        out.put("collecting", (int) computation.candidates().stream().filter(Scored::collecting).count());
        out.put("selectedUnchanged", true);
        out.put("skippedReason", computation.skippedReason());
        return out;
    }

    /**
     * GET /api/paper/track-record?asOf= — 그 기준일(없으면 가장 최근에 계산한 기준일)의 트랙레코드·순위·선정·수집 진행률.
     * 계산이 없으면 {@code computed:false}와 수집 진행률. 계산하지 않고 저장된 결과만 읽습니다.
     */
    public Map<String, Object> trackRecord(String asOfText) {
        if (!store.ready()) {
            return notReady();
        }
        LocalDate requested = Params.optionalDate("asOf", asOfText);
        Optional<CollectionIndex> index = index();
        LocalDate asOf = requested != null ? requested : store.latestTrackRecordAsOf().orElse(null);
        List<TrackRecordRow> rows = asOf == null ? List.of() : store.trackRecords(asOf);
        Optional<Selection> effective = asOf == null ? Optional.empty() : store.selectionOnOrBefore(asOf);
        List<String> selected = effective.map(Selection::selectedCiks).orElse(List.of());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("computed", !rows.isEmpty());
        out.put("asOfDate", text(asOf));
        // 계산했으면 그때의 창·기준·설정, 아직이면 지금 설정(다음 계산이 쓸 값).
        TrackRecordSettings used = rows.isEmpty() ? currentSettings() : settingsOf(rows.get(0).settingsVersion());
        out.put("windowYears", rows.isEmpty() ? used.criteria().windowYears() : rows.get(0).windowYears());
        out.put("metric", rows.isEmpty() ? used.metric().name() : rows.get(0).metric());
        out.put("criteria", criteria(used.criteria()));
        out.put("settingsVersion", rows.isEmpty() ? null : rows.get(0).settingsVersion());
        out.put("computedAtKst", rows.isEmpty() ? null : stamp(rows.get(0).computedAt()));
        out.put("candidates", candidates(rows, index, selected));
        out.put("selected", selected);
        out.put("selectedAsOfDate", effective.map(s -> s.asOfDate().toString()).orElse(null));
        List<Map<String, Object>> history = new ArrayList<>();
        for (Selection selection : store.selections()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("asOfDate", selection.asOfDate().toString());
            entry.put("selectedCiks", selection.selectedCiks());
            history.add(entry);
        }
        out.put("selectionHistory", history);
        out.put("collection", collection(index));
        out.put("limits", LIMITS);
        return out;
    }

    /**
     * 후보 행. 순위가 있는 기관을 순위 순으로, 나머지는 수집 목록 순(스타일별)으로. 이름·스타일·저장 분기·완료 여부는 지금의
     * 수집 목록 값입니다(진행률은 계산 뒤에도 움직이므로) — 목록에서 빠진 기관이면 null·false.
     */
    private List<Map<String, Object>> candidates(List<TrackRecordRow> rows, Optional<CollectionIndex> index,
                                                 List<String> selected) {
        Map<String, Filer> filers = new LinkedHashMap<>();
        index.ifPresent(i -> i.filers().forEach(f -> filers.put(f.cik(), f)));
        List<String> order = new ArrayList<>(filers.keySet());
        List<TrackRecordRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(TrackRecordRow::rank, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(r -> order.contains(r.cik()) ? order.indexOf(r.cik()) : Integer.MAX_VALUE)
                .thenComparing(TrackRecordRow::cik));
        List<Map<String, Object>> out = new ArrayList<>();
        for (TrackRecordRow r : sorted) {
            Filer filer = filers.get(r.cik());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cik", r.cik());
            row.put("name", filer == null ? null : filer.name());
            row.put("style", filer == null ? null : filer.style());
            row.put("rank", r.rank());
            row.put("eligible", r.eligible());
            row.put("ineligibleReason", r.ineligibleReason());
            row.put("collecting", r.ineligibleReason() != null && r.ineligibleReason().startsWith(COLLECTING_PREFIX));
            row.put("cagrPct", PaperQueryService.round2(r.cagrPct()));
            row.put("cumReturnPct", PaperQueryService.round2(r.cumReturnPct()));
            // SPY 누적은 열이 없어 초과(= 누적 − SPY 누적)에서 되돌립니다. 둘 중 하나라도 없으면 null.
            row.put("spyCumReturnPct", r.cumReturnPct() == null || r.excessVsSpyPct() == null ? null
                    : PaperQueryService.round2(r.cumReturnPct() - r.excessVsSpyPct()));
            row.put("excessVsSpyPct", PaperQueryService.round2(r.excessVsSpyPct()));
            row.put("volPct", PaperQueryService.round2(r.volPct()));
            row.put("mddPct", PaperQueryService.round2(r.mddPct()));
            row.put("sharpe", PaperQueryService.round2(r.sharpe()));
            row.put("periodsUsed", r.periodsUsed());
            row.put("periodsTotal", r.periodsTotal());
            row.put("coverageMin", PaperQueryService.round4(r.coverageMin()));
            row.put("coverageAvg", PaperQueryService.round4(r.coverageAvg()));
            row.put("firstFilingDate", text(r.firstFilingDate()));
            row.put("lastFilingDate", text(r.lastFilingDate()));
            row.put("storedQuarters", filer == null ? null : filer.storedQuarters());
            row.put("complete", filer != null && filer.complete());
            row.put("selected", selected.contains(r.cik()));
            row.put("periods", periods(r.periods()));
            out.add(row);
        }
        return out;
    }

    /** 저장한 구간 JSON → 응답(수익률 2자리, 커버리지 4자리 — 0~1 비율). */
    private static List<Map<String, Object>> periods(JsonNode stored) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (stored == null || !stored.isArray()) {
            return out;
        }
        for (JsonNode p : stored) {
            Map<String, Object> period = new LinkedHashMap<>();
            period.put("filingDate", Json.asText(p, "filingDate"));
            period.put("reportDate", Json.asText(p, "reportDate"));
            period.put("start", Json.asText(p, "start"));
            period.put("end", Json.asText(p, "end"));
            period.put("returnPct", PaperQueryService.round2(Json.asDouble(p, "returnPct")));
            period.put("spyReturnPct", PaperQueryService.round2(Json.asDouble(p, "spyReturnPct")));
            period.put("coverage", PaperQueryService.round4(Json.asDouble(p, "coverage")));
            Double covered = Json.asDouble(p, "coveredTickers");
            Double holdings = Json.asDouble(p, "holdings");
            period.put("coveredTickers", covered == null ? null : covered.intValue());
            period.put("holdings", holdings == null ? null : holdings.intValue());
            out.add(period);
        }
        return out;
    }

    /** 수집 진행률. 후보 목록 저장본이 없으면 null. */
    private static Map<String, Object> collection(Optional<CollectionIndex> index) {
        if (index.isEmpty()) {
            return null;
        }
        CollectionIndex i = index.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filers", i.filers().size());
        out.put("completeCount", (int) i.filers().stream().filter(Filer::complete).count());
        out.put("storedFilings", i.filers().stream().mapToInt(Filer::storedQuarters).sum());
        out.put("targetFilings", i.filers().size() * i.targetQuarters());
        out.put("targetQuarters", i.targetQuarters());
        out.put("updatedAtKst", stamp(i.collectedAt()));
        return out;
    }

    private static Map<String, Object> criteria(TrackRecord13F.Criteria c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowYears", c.windowYears());
        out.put("minPeriods", c.minPeriods());
        out.put("minCoverage", c.minCoverage());
        out.put("avgCoverage", c.avgCoverage());
        return out;
    }

    // ------------------------------------------------------------------ 조각
    private void requireReady() {
        if (!store.ready()) {
            throw new IllegalStateException(PaperQueryService.NOT_READY_MESSAGE);
        }
    }

    private Map<String, Object> notReady() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", false);
        out.put("message", PaperQueryService.NOT_READY_MESSAGE);
        return out;
    }

    /** 설정 행(없으면 기본값으로 만듦 — {@link PaperSettingsService#ensureInitialized}). */
    private SettingsRow settingsRow() {
        Optional<SettingsRow> row = store.settings();
        if (row.isPresent()) {
            return row.get();
        }
        settings.ensureInitialized();
        return store.settings().orElseThrow(() -> new IllegalStateException("모의 매매 설정 행을 만들지 못했습니다"));
    }

    /** 지금 설정의 트랙레코드 값(쓰지 않음 — 행이 없으면 기본값). */
    private TrackRecordSettings currentSettings() {
        return store.settings().map(row -> TrackRecordSettings.from(row.body()))
                .orElseGet(() -> TrackRecordSettings.from(settings.defaultsJson()));
    }

    /** 그 버전의 설정(이력). 이력에 없으면 지금 설정. */
    private TrackRecordSettings settingsOf(int version) {
        return store.settingsAt(version).map(row -> TrackRecordSettings.from(row.body()))
                .orElseGet(this::currentSettings);
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static String stamp(Instant moment) {
        return moment == null ? null : Kst.stamp(moment);
    }
}
