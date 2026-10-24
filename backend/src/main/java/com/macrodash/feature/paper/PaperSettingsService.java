package com.macrodash.feature.paper;

import com.macrodash.analytics.GateIndicator;
import com.macrodash.store.PaperStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🧪 모의 매매 설정(명세 S1, 설계 §4-4) — DB 한 행({@code paper_settings}) + 변경 이력.
 *
 * <p>단계 1은 읽기와 처음 값 넣기({@link #ensureInitialized})만 합니다. 화면에서 바꾸는 쓰기 API는 단계 3a입니다.
 * 설정 행이 아직 없으면(초기화 전) {@link #defaultsJson()}을 그대로 쓰고 버전은 null입니다 — "버전 0"을 지어내지 않습니다.
 *
 * <p>읽을 때마다 DB 한 줄을 읽습니다(캐시 없음). 5분마다 도는 정기 작업과 화면 조회뿐이라 비용이 작고, 재기동 없이 바로
 * 반영돼야 하기 때문입니다(S1).
 */
@Service
public class PaperSettingsService {

    private static final Logger log = LoggerFactory.getLogger(PaperSettingsService.class);

    /** 설정 버전과 장부가 읽는 값. 행이 없으면 version은 null(기본값). */
    public record Current(Integer version, PaperSettings settings) {
    }

    /**
     * 후보 기관 27곳(설계 §4-0 ①, 단계 0 표 A — EDGAR에서 확인한 지금 CIK). 선행 CIK(그린라이트·퍼싱 스퀘어의 옛 등록)는
     * 수집기({@code indicators.TRACK_RECORD_FILERS}, 단계 1b)가 잇습니다 — 여기는 "누구를 순위에 올리나"만.
     */
    static final List<String> CANDIDATE_CIKS = List.of(
            "0001067983", "0001061768", "0002026053", "0001040273", "0001489933", "0001656456", "0001112520",
            "0001720792", "0000200217", "0001036325", "0001096343", "0001709323", "0000860643", "0001649339",
            "0001167483", "0001061165", "0001103804", "0001135730", "0001541617", "0001747057", "0000934639",
            "0001088875", "0001536411", "0000850529", "0001350694", "0001029160", "0000923093");

    /** 유니버스에 늘 넣는 ETF(D8): 섹터 11 + 현금성·헷지 6. 벤치마크 SPY·QQQ는 수집 유니버스에만 있습니다. */
    static final List<String> UNIVERSE_EXTRA_TICKERS = List.of(
            "XLK", "XLC", "XLY", "XLI", "XLF", "XLV", "XLP", "XLE", "XLB", "XLU", "XLRE",
            "SHY", "UUP", "GLD", "DBC", "TLT", "IEF");

    private final PaperStore store;
    private final ObjectMapper mapper;

    public PaperSettingsService(PaperStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    /** 지금 설정. 테이블이 없거나 행이 없으면 기본값(버전 null). */
    public Current current() {
        if (!store.ready()) {
            return new Current(null, PaperSettings.defaults());
        }
        return store.settings()
                .map(row -> {
                    PaperSettings settings = PaperSettings.from(row.body());
                    if (!settings.fallbacks().isEmpty()) {
                        log.warn("모의 매매 설정 v{}의 키 {}가 없거나 범위 밖이라 기본값으로 읽습니다", row.version(),
                                settings.fallbacks());
                    }
                    return new Current(row.version(), settings);
                })
                .orElseGet(() -> new Current(null, PaperSettings.defaults()));
    }

    /** 설정 행이 없으면 기본값으로 만듭니다(버전 1). 이미 있으면 그대로 둡니다. @return 이번에 만들었으면 true */
    public boolean ensureInitialized() {
        return store.initSettingsIfAbsent(defaultsJson());
    }

    /**
     * 설계 §4-4의 키 전부와 기본값. 단계 1이 읽는 키는 slippageBp·trailingStopPct·lockoutTradingDays·cashLikeTickers·gate.*
     * 이고, 나머지는 뒤 단계(규칙 책·Claude 책·메모)가 읽도록 처음부터 한 행에 둡니다 — 나중에 키를 더하면 버전이 바뀌어
     * "이 결정은 어느 설정으로 했나" 기록이 끊깁니다. {@code blend.*}(D27)·{@code rules.*}는 단계 2에 더한 키라 그 전에 만든 행에는 없고,
     * 없으면 읽는 쪽이 이 기본값으로 읽습니다(행을 고쳐 쓰지 않음 — 버전을 바꾸는 쓰기는 설정 API, 단계 3a).
     */
    public JsonNode defaultsJson() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "claude-sonnet-5-5");
        body.put("effort", "high");
        body.put("decisionTimeKst", "07:30");
        body.put("decisionDeadlineKst", "12:00");
        body.put("slippageBp", PaperSettings.DEFAULT_SLIPPAGE_BP);
        body.put("maxWeightStockPct", 10);
        body.put("maxWeightEtfPct", 20);
        body.put("maxPositions", 20);
        body.put("trailingStopPct", PaperSettings.DEFAULT_TRAILING_STOP_PCT);
        body.put("lockoutTradingDays", PaperSettings.DEFAULT_LOCKOUT_TRADING_DAYS);
        body.put("maxDiscretionaryOrdersPerMonth", 20);
        body.put("minHoldTradingDaysForSell", 5);
        body.put("minOrderNotional", 500);
        Map<String, Object> trackRecord = new LinkedHashMap<>();
        trackRecord.put("windowYears", 7);
        trackRecord.put("topN", 5);
        trackRecord.put("metric", "CAGR");
        trackRecord.put("minPeriods", 24);
        trackRecord.put("minCoverage", 0.5);
        trackRecord.put("avgCoverage", 0.7);
        trackRecord.put("candidateCiks", CANDIDATE_CIKS);
        body.put("trackRecord", trackRecord);
        body.put("minHolders", 2);
        body.put("rulesMaxNames", 10);
        body.put("parkCashInShy", true);
        // 규칙 책 묶음(rules.*) — 단계 2에 더한 키라 그 전에 만든 설정 행에는 없고, 그 행은 PaperSettings.Rules가 이 기본값으로 읽습니다.
        Map<String, Object> rulesBody = new LinkedHashMap<>();
        rulesBody.put("parkCashThresholdPct", PaperSettings.Rules.DEFAULT_PARK_CASH_THRESHOLD_PCT);
        body.put("rules", rulesBody);
        // 두 기관 그룹 섞기(D27) — D27 전에 만든 설정 행에는 없고, 그 행은 PaperSettings.Blend가 이 기본값으로 읽습니다.
        PaperSettings.Blend blend = PaperSettings.Blend.defaults();
        Map<String, Object> groupB = new LinkedHashMap<>();
        groupB.put("topN", blend.groupBTopN());
        groupB.put("windowYears", blend.groupBWindowYears());
        groupB.put("minPeriods", blend.groupBMinPeriods());
        Map<String, Object> blendBody = new LinkedHashMap<>();
        blendBody.put("groupB", groupB);
        blendBody.put("groupWeightA", blend.groupWeightA());
        blendBody.put("convictionCapPct", blend.convictionCapPct());
        blendBody.put("allocation", blend.allocation().name());
        blendBody.put("minTargetPct", blend.minTargetPct());
        body.put("blend", blendBody);
        body.put("universeExtraTickers", UNIVERSE_EXTRA_TICKERS);
        body.put("cashLikeTickers", PaperSettings.DEFAULT_CASH_LIKE);
        GateIndicator.Thresholds gate = GateIndicator.Thresholds.defaults();
        Map<String, Object> gateBody = new LinkedHashMap<>();
        gateBody.put("us10yStage1", gate.us10yStage1());
        gateBody.put("us10yFull", gate.us10yFull());
        gateBody.put("brentMax", gate.brentMax());
        gateBody.put("wtiMax", gate.wtiMax());
        gateBody.put("usdjpyMin", gate.usdjpyMin());
        gateBody.put("confirmDays", gate.confirmDays());
        body.put("gate", gateBody);
        body.put("notesDefaultExpiryTradingDays", 3);
        body.put("notesMaxCharsInPacket", 6000);
        body.put("apiDailyCallCap", 6);
        body.put("frameworkTokenBudget", 8000);
        return mapper.valueToTree(body);
    }
}
