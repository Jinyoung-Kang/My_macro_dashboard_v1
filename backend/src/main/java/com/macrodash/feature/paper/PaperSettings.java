package com.macrodash.feature.paper;

import com.macrodash.analytics.GateIndicator;
import com.macrodash.support.Json;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoublePredicate;

/**
 * 🧪 장부가 지금(단계 1) 읽는 설정 값 — DB 설정 한 행({@code paper_settings.body}, 설계 §4-4)에서 고른 것.
 *
 * <p>나머지 키(모델·상한·트랙레코드 등)는 본문에 그대로 있고 뒤 단계의 서비스가 읽습니다.
 *
 * <p><b>키가 없거나 값이 틀리면 그 키만 기본값으로 읽습니다.</b> 설정 쓰기 API(단계 3a)가 검사하기 전의 행이나 손으로 고친
 * 행이 있어도 장부가 멈추지 않게 — 예컨대 스탑 비율 0은 모든 보유를 바로 팔게 만들고, 슬리피지 음수는 '유리한 체결'이 됩니다.
 * 기본값으로 읽은 키는 {@link #fallbacks}에 남아 화면·로그가 알 수 있습니다.
 *
 * @param slippageBp         체결가에 얹는 슬리피지(bp, 0 이상 10000 미만, 기본 5 — D9)
 * @param trailingStopPct    트레일링 스탑(%, 0 초과 100 미만, 기본 8 — D10)
 * @param lockoutTradingDays 스탑 뒤 재진입 금지 거래일(0 이상, 기본 20 — D10)
 * @param cashLikeTickers    스탑을 걸지 않는 현금성 종목(기본 SHY)
 * @param gate               OS 게이트 지표의 참고 임계치(구속하지 않음 — D7)
 * @param fallbacks          기본값으로 대신 읽은 키(비어 있으면 모두 설정대로)
 */
public record PaperSettings(double slippageBp, double trailingStopPct, int lockoutTradingDays,
                            List<String> cashLikeTickers, GateIndicator.Thresholds gate, List<String> fallbacks) {

    public static final double DEFAULT_SLIPPAGE_BP = 5;
    public static final double DEFAULT_TRAILING_STOP_PCT = 8;
    public static final int DEFAULT_LOCKOUT_TRADING_DAYS = 20;
    public static final List<String> DEFAULT_CASH_LIKE = List.of("SHY");

    public PaperSettings {
        cashLikeTickers = List.copyOf(cashLikeTickers);
        fallbacks = List.copyOf(fallbacks);
    }

    /** 설정 행이 아직 없을 때(초기화 전). */
    public static PaperSettings defaults() {
        return new PaperSettings(DEFAULT_SLIPPAGE_BP, DEFAULT_TRAILING_STOP_PCT, DEFAULT_LOCKOUT_TRADING_DAYS,
                DEFAULT_CASH_LIKE, GateIndicator.Thresholds.defaults(), List.of());
    }

    /** 스탑을 걸지 않는 현금성 종목인가. */
    public boolean cashLike(String ticker) {
        return cashLikeTickers.contains(ticker);
    }

    /** DB 본문에서 읽습니다. null이면 기본값 그대로. */
    static PaperSettings from(JsonNode body) {
        if (body == null) {
            return defaults();
        }
        List<String> fallbacks = new ArrayList<>();
        double slippage = number(body, "slippageBp", DEFAULT_SLIPPAGE_BP, v -> v >= 0 && v < 10_000, fallbacks);
        double stop = number(body, "trailingStopPct", DEFAULT_TRAILING_STOP_PCT, v -> v > 0 && v < 100, fallbacks);
        double lockout = number(body, "lockoutTradingDays", DEFAULT_LOCKOUT_TRADING_DAYS,
                v -> v >= 0 && v <= 1000 && v == Math.rint(v), fallbacks);
        List<String> cashLike = tickers(body, "cashLikeTickers", fallbacks);

        GateIndicator.Thresholds base = GateIndicator.Thresholds.defaults();
        JsonNode gate = Json.child(body, "gate");
        GateIndicator.Thresholds thresholds = base;
        if (gate != null) {
            List<String> gateFallbacks = new ArrayList<>();
            double confirm = number(gate, "confirmDays", base.confirmDays(), v -> v >= 1 && v <= 250 && v == Math.rint(v),
                    gateFallbacks);
            thresholds = new GateIndicator.Thresholds(
                    number(gate, "us10yStage1", base.us10yStage1(), Double::isFinite, gateFallbacks),
                    number(gate, "us10yFull", base.us10yFull(), Double::isFinite, gateFallbacks),
                    number(gate, "brentMax", base.brentMax(), Double::isFinite, gateFallbacks),
                    number(gate, "wtiMax", base.wtiMax(), Double::isFinite, gateFallbacks),
                    number(gate, "usdjpyMin", base.usdjpyMin(), Double::isFinite, gateFallbacks),
                    (int) confirm);
            gateFallbacks.forEach(key -> fallbacks.add("gate." + key));
        } else {
            fallbacks.add("gate");
        }
        return new PaperSettings(slippage, stop, (int) lockout, cashLike, thresholds, fallbacks);
    }

    private static double number(JsonNode body, String key, double fallback, DoublePredicate valid,
                                 List<String> fallbacks) {
        Double value = Json.asDouble(body, key);
        if (value == null || !Double.isFinite(value) || !valid.test(value)) {
            fallbacks.add(key);
            return fallback;
        }
        return value;
    }

    private static List<String> tickers(JsonNode body, String key, List<String> fallbacks) {
        JsonNode node = Json.child(body, key);
        if (node == null || !node.isArray()) {
            fallbacks.add(key);
            return DEFAULT_CASH_LIKE;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isString() && !item.asString("").isBlank()) {
                out.add(item.asString("").trim().toUpperCase(java.util.Locale.ROOT));
            }
        }
        // 빈 목록은 '현금성 종목 없음'이라는 뜻일 수 있어 그대로 받습니다(SHY에도 스탑을 거는 설정).
        return out;
    }
}
