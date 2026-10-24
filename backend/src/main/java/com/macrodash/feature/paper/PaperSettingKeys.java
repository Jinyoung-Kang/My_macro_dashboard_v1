package com.macrodash.feature.paper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 🧪 화면에서 바꿀 수 있는 설정 키(설계 §4-4, 명세 S1~S3) — 키(설정 본문 안의 점 경로)마다 형·범위·고를 수 있는 값·규칙 책 영향.
 *
 * <p>범위는 읽는 쪽({@link PaperSettings}·{@code RuleSettings}·{@code TrackRecordSettings}·{@code PacketBuilder.Limits}·
 * {@link ClaudeBookService.ClaudeSettings})이 받는 범위 <b>안</b>으로 잡았습니다 — 쓰기 API를 지난 값이 읽을 때 기본값으로 바뀌는 일이
 * 없게. 읽는 쪽보다 좁은 곳(슬리피지 100bp, 스탑 50% 등)은 실험이 뜻을 잃는 값을 막으려는 것입니다.
 *
 * <p><b>규칙 책 영향</b>({@code affectsRules}, S3): 규칙 책(대조군)의 코드가 읽는 키 전부입니다 — 트랙레코드·섞기·배분·스탑·재진입
 * 금지·비중 상한·최소 금액·유니버스 ETF·현금성 종목·슬리피지. 계약이 적은 목록(trackRecord.*·minHolders·rulesMaxNames·trailingStopPct·
 * maxWeight*)과 설계 §4-4의 blend.*에 더해, 규칙 책 계획·장부가 실제로 읽는 나머지 키도 넣었습니다(바꾸면 대조군의 조건이 바뀌는 것은
 * 같아서).
 */
final class PaperSettingKeys {

    private PaperSettingKeys() {
    }

    /** 규칙 책 영향 키를 바꿨을 때의 경고(명세 S3) — 화면의 확인 문구와 같은 문장입니다. */
    static final String RULES_FROZEN_WARNING = "규칙 책은 동결된 대조군입니다 — 이 변경은 실험 조건을 바꿉니다";

    /** 값의 형. {@link #wire}는 화면에 보내는 이름(frontend lib/paper.ts settingKind가 읽는 이름). */
    enum Type {
        NUMBER("number"), INTEGER("integer"), BOOLEAN("boolean"), CHOICE("string"), TIME("time"), TICKERS("list"),
        CIKS("list");

        final String wire;

        Type(String wire) {
            this.wire = wire;
        }
    }

    /**
     * 키 하나.
     *
     * @param key     설정 본문 안의 점 경로(예: {@code trackRecord.windowYears}) — 바뀐 키 목록·이력에도 이 이름
     * @param min     숫자 키의 최솟값(포함). 아니면 null
     * @param max     숫자 키의 최댓값(포함). 아니면 null
     * @param options 고를 수 있는 값(CHOICE). 아니면 빈 목록
     */
    record Spec(String key, Type type, Double min, Double max, List<String> options, boolean affectsRules,
                String description) {

        Spec {
            options = List.copyOf(options);
        }

        /** 화면에 보내는 모양 {@code {key, type, min, max, affectsRules, description, options}}. */
        Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("key", key);
            out.put("type", type.wire);
            out.put("min", min);
            out.put("max", max);
            out.put("affectsRules", affectsRules);
            out.put("description", description);
            out.put("options", options.isEmpty() ? null : options);
            return out;
        }
    }

    private static Spec number(String key, double min, double max, boolean rules, String description) {
        return new Spec(key, Type.NUMBER, min, max, List.of(), rules, description);
    }

    private static Spec integer(String key, int min, int max, boolean rules, String description) {
        return new Spec(key, Type.INTEGER, (double) min, (double) max, List.of(), rules, description);
    }

    private static Spec choice(String key, List<String> options, boolean rules, String description) {
        return new Spec(key, Type.CHOICE, null, null, options, rules, description);
    }

    private static Spec of(String key, Type type, boolean rules, String description) {
        return new Spec(key, type, null, null, List.of(), rules, description);
    }

    /** 화면에 보이는 순서이기도 합니다(점 경로의 첫 마디로 묶음). */
    static final List<Spec> ALL = List.of(
            choice("model", ClaudeBookService.MODELS, false, "Claude 책 모델(D5) — 거절·오류 때 다른 모델로 자동 전환하지 않음"),
            choice("effort", ClaudeBookService.EFFORTS, false, "Claude 책 effort(C2 기본 high)"),
            integer("apiDailyCallCap", 0, ClaudeBookService.MAX_DAILY_CALL_CAP, false,
                    "하루(KST) Claude 호출 상한(D18) — 정기·수동 결정·재시도·미리보기·시작을 모두 셈. 0이면 부르지 않음"),
            of("decisionTimeKst", Type.TIME, false, "결정 시각(KST, HH:mm) — 미국 세션 다음 날 이 시각부터 그 거래일 결정(C1)"),
            of("decisionDeadlineKst", Type.TIME, false,
                    "확정 종가를 기다리는 기한(KST, HH:mm) — 지나면 있는 값으로 진행하고 pricesFinal=false"),
            number("slippageBp", 0, 100, true, "체결 슬리피지(bp) — 네 책 공통(D9)"),
            number("maxWeightStockPct", 1, 100, true, "종목당 비중 상한(%) — Claude 검증기·규칙 책(D8)"),
            number("maxWeightEtfPct", 1, 100, true, "ETF 비중 상한(%) — Claude 검증기·규칙 책(D8)"),
            integer("maxPositions", 1, 100, false, "매수 뒤 최대 종목 수(현금성 제외) — Claude 검증기"),
            number("trailingStopPct", 1, 50, true, "트레일링 스탑(%) — 최고가 대비 이만큼 떨어지면 전량 매도(D10)"),
            integer("lockoutTradingDays", 0, 250, true, "스탑 뒤 재진입 금지 거래일(D10)"),
            integer("maxDiscretionaryOrdersPerMonth", 0, 200, false, "Claude 재량 주문 월 상한(C9)"),
            integer("minHoldTradingDaysForSell", 0, 60, false, "Claude 재량 매도 전 최소 보유 거래일(C9, 스탑 제외)"),
            number("minOrderNotional", 0, 100_000, true, "주문 최소 금액(달러) — Claude 검증기·규칙 책 매수(C9)"),
            integer("minHolders", 1, 12, true, "규칙 책 매수 후보·매도 신호가 되는 최소 기관 수(R2)"),
            integer("rulesMaxNames", 1, 50, true, "규칙 책 매수 종목 수 상한(R4)"),
            of("parkCashInShy", Type.BOOLEAN, true, "규칙 책이 남는 현금을 SHY에 둠(R3)"),
            of("universeExtraTickers", Type.TICKERS, true, "유니버스에 늘 넣는 ETF(D8) — ETF 비중 상한을 받는 종목"),
            of("cashLikeTickers", Type.TICKERS, true, "현금성 종목(스탑·비중 상한·종목 수 제외, 기본 SHY)"),
            integer("notesDefaultExpiryTradingDays", 1, 60, false, "시황 메모 기본 만료(거래일, F6)"),
            integer("notesMaxCharsInPacket", 0, 20_000, false, "패킷에 싣는 유효 메모 합계 글자 수(F6)"),
            integer("frameworkTokenBudget", 1_000, 50_000, false, "프레임 렌더링 크기 예산(토큰 어림, F4 — 넘으면 경고)"),
            number("pricing.sonnet.inputUsdPerMTok", 0, PaperApiCalls.MAX_PRICE, false,
                    "Sonnet 입력 단가(달러/100만 토큰, 추정 비용 C8). " + PaperApiCalls.PRICE_NOTE),
            number("pricing.sonnet.outputUsdPerMTok", 0, PaperApiCalls.MAX_PRICE, false,
                    "Sonnet 출력 단가(달러/100만 토큰, 사고 포함). " + PaperApiCalls.PRICE_NOTE),
            number("pricing.sonnet.cacheReadUsdPerMTok", 0, PaperApiCalls.MAX_PRICE, false,
                    "Sonnet 캐시 읽기 단가(달러/100만 토큰). " + PaperApiCalls.PRICE_NOTE),
            number("pricing.sonnet.cacheWriteUsdPerMTok", 0, PaperApiCalls.MAX_PRICE, false,
                    "Sonnet 캐시 쓰기 단가(달러/100만 토큰). " + PaperApiCalls.PRICE_NOTE),
            number("pricing.haiku.inputUsdPerMTok", 0, PaperApiCalls.MAX_PRICE, false,
                    "Haiku 입력 단가(달러/100만 토큰, 프롬프트 100K 토큰 이하 단가). " + PaperApiCalls.PRICE_NOTE),
            number("pricing.haiku.outputUsdPerMTok", 0, PaperApiCalls.MAX_PRICE, false,
                    "Haiku 출력 단가(달러/100만 토큰, 프롬프트 100K 토큰 이하 단가). " + PaperApiCalls.PRICE_NOTE),
            number("pricing.haiku.cacheReadUsdPerMTok", 0, PaperApiCalls.MAX_PRICE, false,
                    "Haiku 캐시 읽기 단가(달러/100만 토큰, 프롬프트 100K 토큰 이하 단가). " + PaperApiCalls.PRICE_NOTE),
            number("pricing.haiku.cacheWriteUsdPerMTok", 0, PaperApiCalls.MAX_PRICE, false,
                    "Haiku 캐시 쓰기 단가(달러/100만 토큰, 프롬프트 100K 토큰 이하 단가). " + PaperApiCalls.PRICE_NOTE),
            integer("trackRecord.windowYears", 1, 7, true, "그룹 A 트랙레코드 창(년, D6)"),
            integer("trackRecord.topN", 1, 50, true, "그룹 A 선정 수(R1)"),
            choice("trackRecord.metric", List.of("CAGR", "EXCESS_VS_SPY", "SHARPE"), true, "순위 기준(R10, D20)"),
            integer("trackRecord.minPeriods", 0, 30, true, "자격: 창 안 최소 구간 수(R9)"),
            number("trackRecord.minCoverage", 0, 1, true, "자격: 모든 구간의 최소 커버리지(0~1, R9)"),
            number("trackRecord.avgCoverage", 0, 1, true, "자격: 평균 커버리지(0~1, R9)"),
            of("trackRecord.candidateCiks", Type.CIKS, true, "후보 기관 CIK 목록(D21) — 바꾸면 그룹 A 순위가 달라짐"),
            number("rules.parkCashThresholdPct", 0, 20, true, "재선정일이 아닌 날 남는 현금을 SHY에 두는 기준(순자산 %)"),
            integer("blend.groupB.topN", 1, 12, true, "그룹 B 선정 수(D27)"),
            integer("blend.groupB.windowYears", 1, 2, true, "그룹 B 트랙레코드 창(년, D27)"),
            integer("blend.groupB.minPeriods", 1, 8, true, "그룹 B 자격: 최소 구간 수(D27)"),
            number("blend.groupWeightA", 0, 1, true, "기관 가중에서 그룹 A 몫(0~1, D27)"),
            number("blend.convictionCapPct", 1, 50, true, "확신도 상한(그 종목의 기관 내 비중 %, D27)"),
            choice("blend.allocation", List.of("CONVICTION", "EQUAL"), true, "규칙 책 배분 방식(확신도 비례·동일 비중, D27)"),
            number("blend.minTargetPct", 0, 10, true, "확신도 비례 배분의 최소 목표 비중(%, D27)"),
            number("gate.us10yStage1", 0, 20, false, "OS 게이트 참고 — 10년물 1단계(%) · 구속하지 않음(D7)"),
            number("gate.us10yFull", 0, 20, false, "OS 게이트 참고 — 10년물 전면(%) · 구속하지 않음(D7)"),
            number("gate.brentMax", 0, 500, false, "OS 게이트 참고 — 브렌트 상한(달러) · 구속하지 않음"),
            number("gate.wtiMax", 0, 500, false, "OS 게이트 참고 — WTI 상한(달러) · 구속하지 않음"),
            number("gate.usdjpyMin", 50, 400, false, "OS 게이트 참고 — 달러/엔 하한 · 구속하지 않음"),
            integer("gate.confirmDays", 1, 60, false, "OS 게이트 참고 — 연속 확인 거래일"));

    private static final Map<String, Spec> BY_KEY;

    static {
        Map<String, Spec> byKey = new LinkedHashMap<>();
        for (Spec spec : ALL) {
            if (byKey.put(spec.key(), spec) != null) {
                throw new IllegalStateException("설정 키가 두 번 있습니다: " + spec.key());
            }
        }
        BY_KEY = Collections.unmodifiableMap(byKey);
    }

    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final Pattern TICKER = Pattern.compile("[A-Z0-9][A-Z0-9.\\-]{0,9}");
    private static final int MAX_TICKERS = 50;
    private static final int MAX_CIKS = 100;

    static Optional<Spec> find(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    /** 이 경로 아래에 키가 있는 묶음인가({@code blend} → {@code blend.groupB.topN}이 있으니 참). */
    static boolean isGroup(String path) {
        String prefix = path + ".";
        return BY_KEY.keySet().stream().anyMatch(key -> key.startsWith(prefix));
    }

    /** 검사 결과 — 정규화한 값 또는 사유. */
    record Checked(JsonNode value, String error) {
    }

    /**
     * 값 하나를 그 키의 형·범위로 검사하고 저장할 모양으로 맞춥니다(정수 키는 정수, 선택 값은 정해진 표기, 티커는 대문자·중복 제거,
     * CIK는 10자리). 틀리면 무엇을 고칠지 적은 사유.
     */
    static Checked check(Spec spec, JsonNode value) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        String key = spec.key();
        if (value == null || value.isNull()) {
            return error(key + ": 값이 없습니다");
        }
        switch (spec.type()) {
            case NUMBER, INTEGER -> {
                if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
                    return error(key + ": 숫자여야 합니다");
                }
                double v = value.asDouble();
                if (spec.type() == Type.INTEGER && v != Math.rint(v)) {
                    return error(key + ": 정수여야 합니다 — " + value);
                }
                if (v < spec.min() || v > spec.max()) {
                    return error(key + ": " + plain(spec.min()) + "~" + plain(spec.max()) + " 범위여야 합니다 — " + value);
                }
                return new Checked(spec.type() == Type.INTEGER ? f.numberNode((int) v) : f.numberNode(v), null);
            }
            case BOOLEAN -> {
                return value.isBoolean() ? new Checked(f.booleanNode(value.asBoolean()), null)
                        : error(key + ": 예·아니오(true·false)여야 합니다");
            }
            case CHOICE -> {
                if (!value.isString()) {
                    return error(key + ": " + String.join(" · ", spec.options()) + " 가운데 하나여야 합니다");
                }
                String text = value.asString("").strip();
                for (String option : spec.options()) {
                    if (option.equalsIgnoreCase(text)) {
                        return new Checked(f.stringNode(option), null);
                    }
                }
                return error(key + ": " + String.join(" · ", spec.options()) + " 가운데 하나여야 합니다 — " + shown(text));
            }
            case TIME -> {
                String text = value.isString() ? value.asString("").strip() : "";
                return TIME.matcher(text).matches() ? new Checked(f.stringNode(text), null)
                        : error(key + ": HH:mm 형식(00:00~23:59)이어야 합니다 — " + shown(value.toString()));
            }
            case TICKERS -> {
                if (!value.isArray()) {
                    return error(key + ": 티커 목록이어야 합니다");
                }
                Set<String> tickers = new LinkedHashSet<>();
                for (JsonNode item : value) {
                    String text = item.isString() ? item.asString("").strip().toUpperCase(Locale.ROOT) : "";
                    if (!TICKER.matcher(text).matches()) {
                        return error(key + ": 티커 형식이 아닙니다 — " + shown(item.toString()));
                    }
                    tickers.add(text);
                }
                if (tickers.size() > MAX_TICKERS) {
                    return error(key + ": 티커는 " + MAX_TICKERS + "개까지입니다");
                }
                ArrayNode out = f.arrayNode();
                tickers.forEach(out::add);
                return new Checked(out, null);
            }
            case CIKS -> {
                if (!value.isArray()) {
                    return error(key + ": CIK 목록이어야 합니다");
                }
                Set<String> ciks = new LinkedHashSet<>();
                for (JsonNode item : value) {
                    String text = item.isString() ? item.asString("") : item.isIntegralNumber() ? item.asString("") : null;
                    String cik = PaperSelectionService.normalizeCik(text);
                    if (cik == null) {
                        return error(key + ": CIK는 10자리 이하 숫자여야 합니다 — " + shown(item.toString()));
                    }
                    ciks.add(cik);
                }
                if (ciks.isEmpty()) {
                    return error(key + ": 후보 기관이 비면 선정할 수 없습니다");
                }
                if (ciks.size() > MAX_CIKS) {
                    return error(key + ": 후보 기관은 " + MAX_CIKS + "곳까지입니다");
                }
                ArrayNode out = f.arrayNode();
                ciks.forEach(out::add);
                return new Checked(out, null);
            }
            default -> {
                return error(key + ": 바꿀 수 없는 키입니다");
            }
        }
    }

    /** 같은 값인가 — 숫자는 값으로(5와 5.0은 같음), 나머지는 JSON 그대로. 둘 다 없으면 같음. */
    static boolean same(JsonNode a, JsonNode b) {
        boolean aMissing = a == null || a.isNull() || a.isMissingNode();
        boolean bMissing = b == null || b.isNull() || b.isMissingNode();
        if (aMissing || bMissing) {
            return aMissing && bMissing;
        }
        if (a.isNumber() && b.isNumber()) {
            return Double.compare(a.asDouble(), b.asDouble()) == 0;
        }
        return a.equals(b);
    }

    /** 키 설명 전부(화면용). */
    static List<Map<String, Object>> views() {
        List<Map<String, Object>> out = new ArrayList<>();
        ALL.forEach(spec -> out.add(spec.view()));
        return out;
    }

    private static Checked error(String message) {
        return new Checked(null, message);
    }

    private static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String shown(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }
}
