package com.macrodash.feature.paper;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 🧪 네 책의 정의와 장부에서 쓰는 상태 값(DB CHECK와 같은 문자열, V8).
 *
 * <p>책은 넷으로 고정입니다(명세 L1): 규칙 책(대조군) · Claude 재량 책(실험군) · 벤치마크 둘. 벤치마크는 시작할 때 정한 비중으로
 * 한 번 사고 그대로 들고 갑니다(단계 1은 리밸런싱하지 않음 — bench_mix의 60/40은 시간이 지나며 벌어집니다).
 */
final class PaperBooks {

    private PaperBooks() {
    }

    static final String RULES = "rules";
    static final String CLAUDE = "claude";
    static final String BENCH_SPY = "bench_spy";
    static final String BENCH_MIX = "bench_mix";

    /** 책 종류(paper_books.kind). */
    static final String KIND_RULES = "RULES";
    static final String KIND_CLAUDE = "CLAUDE";
    static final String KIND_BENCHMARK = "BENCHMARK";

    /** 책 상태(paper_books.status). */
    static final String NOT_STARTED = "NOT_STARTED";
    static final String ACTIVE = "ACTIVE";
    static final String PAUSED = "PAUSED";

    /** 주문 상태(paper_orders.status). */
    static final String ORDER_PENDING = "PENDING";
    static final String ORDER_FILLED = "FILLED";
    static final String ORDER_CANCELLED = "CANCELLED";

    /** 주문 시점(paper_orders.timing). */
    static final String NEXT_CLOSE = "NEXT_CLOSE";
    static final String IMMEDIATE = "IMMEDIATE";
    static final String BACKFILL = "BACKFILL";

    /** 주문 출처(paper_orders.source). */
    static final String SOURCE_STOP = "STOP";
    static final String SOURCE_BENCHMARK = "BENCHMARK";

    static final String BUY = "BUY";
    static final String SELL = "SELL";

    /** 체결 가격 출처(paper_fills.price_source). */
    static final String SOURCE_QUOTES = "paper.quotes";
    static final String SOURCE_HISTORY = "equity_close";

    /**
     * 책 하나의 정의.
     *
     * @param weights 벤치마크의 종목 → 비중(%). 규칙·Claude 책은 비어 있음(무엇을 살지는 결정이 정함)
     */
    record Definition(String code, String kind, String label, Map<String, Double> weights) {
        boolean benchmark() {
            return KIND_BENCHMARK.equals(kind);
        }
    }

    /** 화면에 보이는 순서이기도 합니다. */
    static final List<Definition> ALL = List.of(
            new Definition(RULES, KIND_RULES, "규칙 책", Map.of()),
            new Definition(CLAUDE, KIND_CLAUDE, "Claude 재량 책", Map.of()),
            new Definition(BENCH_SPY, KIND_BENCHMARK, "SPY 100%", weights("SPY", 100.0)),
            new Definition(BENCH_MIX, KIND_BENCHMARK, "SPY 60% · SHY 40%", weights("SPY", 60.0, "SHY", 40.0)));

    static Optional<Definition> find(String code) {
        return ALL.stream().filter(d -> d.code().equals(code)).findFirst();
    }

    /** 통계(초과·베타)의 비교 기준 책. SPY 100% 책 자신은 기준이 없습니다(null). */
    static String benchmarkOf(String code) {
        return BENCH_SPY.equals(code) ? null : BENCH_SPY;
    }

    /** 정의 순서(모르는 코드는 뒤, 코드 순). */
    static final Comparator<String> ORDER = Comparator.<String>comparingInt(code -> {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).code().equals(code)) {
                return i;
            }
        }
        return ALL.size();
    }).thenComparing(Comparator.naturalOrder());

    private static Map<String, Double> weights(Object... tickerAndPct) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < tickerAndPct.length; i += 2) {
            out.put((String) tickerAndPct[i], (Double) tickerAndPct[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(out);
    }
}
