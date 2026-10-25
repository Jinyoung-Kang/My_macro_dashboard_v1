package com.macrodash.feature.paper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.macrodash.feature.paper.PaperFixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 재무 섹션 8b의 표시 — 듀퐁 배수의 자릿수(보이는 세 항의 곱 ≈ roePct), 계산 메모의 순서·개념 코드 줄이기(80자 안에 뜻이 큰 메모),
 * 일부러 비운 비율의 사유. 계산 자체는 {@code FundamentalsTest}가 손계산으로 고정합니다.
 */
class FundamentalsSectionTest {

    private static final double M = 1_000_000.0;
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 23);

    /** 저장본 한 행(obsDate 포함). facts는 (개념, 시작|null, 끝, 값)을 되풀이합니다. */
    private static JsonNode filing(String filed, String form, Object... facts) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.putArray("cik").add("0000000099");
        ArrayNode list = payload.putArray("facts");
        for (int i = 0; i < facts.length; i += 4) {
            String concept = (String) facts[i];
            int colon = concept.indexOf(':');
            ObjectNode f = list.addObject();
            f.put("t", concept.substring(0, colon));
            f.put("c", concept.substring(colon + 1));
            f.put("u", "USD");
            if (facts[i + 1] == null) {
                f.putNull("s");
            } else {
                f.put("s", (String) facts[i + 1]);
            }
            f.put("e", (String) facts[i + 2]);
            f.put("v", ((Number) facts[i + 3]).doubleValue());
            f.put("f", form);
            f.put("a", "0000000099-" + filed);
        }
        payload.put("obsDate", filed);
        return payload;
    }

    private static Map<String, Object> row(Map<String, Object> section, int index) {
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) section.get("rows");
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < FundamentalsSection.COLUMNS.size(); i++) {
            out.put(FundamentalsSection.COLUMNS.get(i), rows.get(index).get(i));
        }
        return out;
    }

    private static Map<String, Object> section(String ticker, List<JsonNode> rows) {
        return FundamentalsSection.section(AS_OF, List.of(new FundamentalsSection.Target(ticker, FundamentalsSection.HOLDING)),
                0, List.of(), Map.of(ticker, rows));
    }

    /** JPM 모양(FundamentalsTest.jpmLike와 같은 값) — 자산 회전율 0.038배, 1년 전 자본 없음. */
    private static List<JsonNode> bank() {
        List<JsonNode> rows = new ArrayList<>();
        rows.add(filing("2025-02-14", "10-K",
                "us-gaap:RevenuesNetOfInterestExpense", "2024-01-01", "2024-12-31", 175_000 * M));
        rows.add(filing("2025-08-05", "10-Q",
                "us-gaap:RevenuesNetOfInterestExpense", "2025-01-01", "2025-06-30", 89_000 * M,
                "us-gaap:NetIncomeLoss", "2025-01-01", "2025-06-30", 30_000 * M,
                "us-gaap:Assets", null, "2025-06-30", 4_552_482 * M));
        rows.add(filing("2026-02-13", "10-K",
                "us-gaap:RevenuesNetOfInterestExpense", "2025-01-01", "2025-12-31", 180_000 * M,
                "us-gaap:NetIncomeLoss", "2025-01-01", "2025-12-31", 57_048 * M));
        rows.add(filing("2026-08-06", "10-Q",
                "us-gaap:RevenuesNetOfInterestExpense", "2026-01-01", "2026-06-30", 92_000 * M,
                "us-gaap:NetIncomeLoss", "2026-01-01", "2026-06-30", 37_649 * M,
                "us-gaap:Assets", null, "2026-06-30", 5_015_069 * M,
                "us-gaap:StockholdersEquity", null, "2026-06-30", 374_598 * M));
        return rows;
    }

    @Test
    @DisplayName("은행(자산 회전율 0.038배) — 패킷에 보이는 netMarginPct × assetTurnoverX × leverageX가 roePct와 맞는다(0.04배로 +5% 되지 않음)")
    void bankDupontProductMatchesRoe() {
        Map<String, Object> r = row(section("JPM", bank()), 0);

        double margin = ((BigDecimal) r.get("netMarginPct")).doubleValue();
        double turnover = ((BigDecimal) r.get("assetTurnoverX")).doubleValue();
        double leverage = ((BigDecimal) r.get("leverageX")).doubleValue();
        double roe = ((BigDecimal) r.get("roePct")).doubleValue();
        // 183,000 ÷ 4,783,775.5 = 0.038254 → 유효숫자 3자리 0.0383(2자리면 0.04)
        assertThat(r.get("assetTurnoverX")).isEqualTo(new BigDecimal("0.0383"));
        assertThat(r.get("leverageX")).isEqualTo(new BigDecimal("12.77"));
        assertThat(roe).isEqualTo(17.3);
        // 35.4 × 0.0383 × 12.77 = 17.31 — 반올림 차이만(소수 1자리 ROE의 반 칸 안)
        assertThat(margin * turnover * leverage).isCloseTo(roe, within(0.05));
    }

    @Test
    @DisplayName("배수 자릿수 — 1배 이상은 소수 2자리, 1배 미만은 유효숫자 3자리, 없으면 null")
    void multipleDigits() {
        assertThat(FundamentalsSection.multiple(1.306)).isEqualTo(new BigDecimal("1.31"));
        assertThat(FundamentalsSection.multiple(12.7704)).isEqualTo(new BigDecimal("12.77"));
        assertThat(FundamentalsSection.multiple(0.20973)).isEqualTo(new BigDecimal("0.21"));
        assertThat(FundamentalsSection.multiple(0.038254)).isEqualTo(new BigDecimal("0.0383"));
        assertThat(FundamentalsSection.multiple(0.0)).isEqualTo(BigDecimal.ZERO);
        assertThat(FundamentalsSection.multiple(null)).isNull();
        assertThat(FundamentalsSection.multiple(Double.NaN)).isNull();
    }

    @Test
    @DisplayName("메모 80자 — '평균에 최신 값만'이 긴 개념 이름의 '값 없음' 메모보다 앞에 남고, 개념 이름은 범례 코드로 줄인다")
    void notesKeepAverageFallbackAndCompactConcepts() {
        // 설비투자는 10-K에만 태그(분기 기준일에는 값 없음), 1년 전 자본 없음.
        List<JsonNode> rows = List.of(
                filing("2025-08-05", "10-Q",
                        "us-gaap:NetCashProvidedByUsedInOperatingActivities", "2025-01-01", "2025-06-30", 45 * M,
                        "us-gaap:Assets", null, "2025-06-30", 900 * M),
                filing("2026-02-10", "10-K",
                        "us-gaap:PaymentsToAcquirePropertyPlantAndEquipment", "2025-01-01", "2025-12-31", 40 * M,
                        "us-gaap:NetCashProvidedByUsedInOperatingActivities", "2025-01-01", "2025-12-31", 100 * M),
                filing("2026-08-05", "10-Q",
                        "us-gaap:NetCashProvidedByUsedInOperatingActivities", "2026-01-01", "2026-06-30", 50 * M,
                        "us-gaap:Assets", null, "2026-06-30", 1_000 * M,
                        "us-gaap:StockholdersEquity", null, "2026-06-30", 400 * M));
        Map<String, Object> section = section("XYZ", rows);
        String notes = (String) row(section, 0).get("notes");

        // 두 메모를 이으면 88자라 끝은 잘리지만(…), 개념 이름이 코드로 줄어 '값 없음' 메모의 필드·개념까지는 남습니다.
        assertThat(notes).startsWith("equity 평균: 1년 전 시점 값이 없어 최신 값만 씀; capex: 최신 기간 2026-06-30 값 없음(capex1")
                .doesNotContain("us-gaap:")
                .endsWith("…")
                .hasSize(FundamentalsSection.MAX_NOTE_CHARS);
        @SuppressWarnings("unchecked")
        Map<String, String> legend = (Map<String, String>) section.get("conceptLegend");
        assertThat(legend).as("메모에만 나온 코드도 범례에")
                .containsEntry("capex1", "us-gaap:PaymentsToAcquirePropertyPlantAndEquipment");
    }

    @Test
    @DisplayName("순이익 ≤ 0이면 fcfConversionPct가 비는 이유가 패킷 메모에 있다(자료 없음이 아님)")
    void negativeNetIncomeReasonInPacket() {
        List<JsonNode> rows = List.of(
                filing("2026-02-10", "10-K",
                        "us-gaap:NetIncomeLoss", "2025-01-01", "2025-12-31", -500 * M,
                        "us-gaap:NetCashProvidedByUsedInOperatingActivities", "2025-01-01", "2025-12-31", 200 * M,
                        "us-gaap:PaymentsToAcquirePropertyPlantAndEquipment", "2025-01-01", "2025-12-31", 50 * M));
        Map<String, Object> r = row(section("LOSS", rows), 0);
        assertThat(r.get("netIncomeTtmUsdM")).isEqualTo(new BigDecimal("-500"));
        assertThat(r.get("fcfTtmUsdM")).isEqualTo(new BigDecimal("150"));
        assertThat(r.get("fcfConversionPct")).isNull();
        assertThat((String) r.get("notes")).isEqualTo("fcfConversion: 순이익 ≤ 0 — 전환율 의미 없음");
    }

    /** 어닝 서프라이즈 저장본 한 행(수집기 payload + 저장소가 붙이는 obsDate). 통화가 null이면 칸을 비웁니다. */
    private static JsonNode eps(String reportedAt, String periodEnd, double actual, double estimate, String currency) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("obsDate", reportedAt.substring(0, 10));
        p.put("periodEnd", periodEnd);
        p.put("actual", actual);
        p.put("estimate", estimate);
        p.put("surprisePct", Math.round((actual - estimate) / Math.abs(estimate) * 10_000) / 100.0);
        if (currency == null) {
            p.putNull("currency");
        } else {
            p.put("currency", currency);
        }
        p.put("smallBase", Math.abs(estimate) < 0.05);
        p.put("reportedAt", reportedAt);
        return p;
    }

    @Test
    @DisplayName("서프라이즈 머리말: 통화를 모르면 epsCurrency에 null(USD로 읽히지 않게), USD 줄은 적지 않음, 작은 추정치는 티커/분기 말 — 줄 순서")
    void earningsHeader() {
        List<FundamentalsSection.Target> shown = List.of(new FundamentalsSection.Target("AAA", FundamentalsSection.HOLDING),
                new FundamentalsSection.Target("UNK", FundamentalsSection.CANDIDATE),
                new FundamentalsSection.Target("TINY", FundamentalsSection.CANDIDATE),
                new FundamentalsSection.Target("NONE", FundamentalsSection.WATCHLIST));
        Map<String, List<JsonNode>> earnings = Map.of(
                "AAA", List.of(eps("2026-07-30T16:30:00-04:00", "2026-06-27", 2.02, 1.89243, "USD")),
                "UNK", List.of(eps("2026-08-05T07:00:00-04:00", "2026-06-30", 0.5, 0.4, null)),
                "TINY", List.of(eps("2026-07-23T16:01:00-04:00", "2026-06-30", 0.23, 0.00705, "USD"),
                        eps("2026-04-23T16:01:00-04:00", "2026-03-31", 0.15, 0.08264, "USD")));
        FundamentalsSection.Built built = FundamentalsSection.build(AS_OF, shown, 0, List.of(), Map.of(), earnings,
                java.time.Instant.parse("2026-10-23T22:30:00Z"));
        Map<String, Object> section = built.section();

        Map<String, Object> currency = new LinkedHashMap<>();
        currency.put("UNK", null);
        assertThat(section.get("epsCurrency")).isEqualTo(currency);
        assertThat(section.get("epsSmallBase")).isEqualTo(List.of("TINY/2026-06-30"));
        assertThat(row(section, 2).get("eps").toString()).isEqualTo("[[2026-07-23, 2026-06-30, 0.23, 0.0071, 3162.4], "
                + "[2026-04-23, 2026-03-31, 0.15, 0.0826, 81.5]]");
        assertThat(row(section, 3).get("eps")).as("행이 없는 종목은 빈 목록").isEqualTo(List.of());
        assertThat(built.earnings().hasEarnings()).isTrue();
        assertThat(built.earnings().earningsRows()).isEqualTo(3);

        FundamentalsSection.Built missing = FundamentalsSection.build(AS_OF, shown, 0, List.of(), Map.of(), null, null);
        assertThat(row(missing.section(), 0).get("eps")).as("데이터셋이 없으면 null").isNull();
        assertThat(missing.section()).doesNotContainKeys("epsCurrency", "epsSmallBase");
        assertThat(missing.earnings().hasEarnings()).isNull();
        assertThat((String) missing.section().get("epsNote")).contains("아직 없어");
    }
}
