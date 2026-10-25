package com.macrodash.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 어닝 서프라이즈 최근 4분기 고르기(2단계 명세 C1·C3). 발표 시각(미국 동부)·분기 말·통화와 '추정치가 빠진 분기가 있는 종목'은
 * 2026-10-25 단계 0에서 yfinance 1.7.0 quoteSummary {@code earnings.earningsChart}로 확인한 모양 그대로이고(GOOGL·INTC 16:01 장후,
 * BIDU 04:00·05:00 장전·CNY, SPCX 3분기 중 추정치 2개, PBR 19:55 ET), EPS 숫자는 예시값입니다(원본 값 확인은 수집기 고정본 시험 몫).
 */
class EarningsHistoryTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime DECISION = LocalTime.of(7, 30);
    private static final LocalDate INITIAL_AS_OF = LocalDate.parse("2026-10-23");

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    /** 수집기와 같은 공식 — 저장본의 surprisePct를 만들 때만 씀. */
    private static Double pct(Double actual, Double estimate) {
        return actual == null || estimate == null || estimate == 0.0 ? null
                : (actual - estimate) / Math.abs(estimate) * 100;
    }

    /** 발표 시각(미국 동부 현지 시각 글자)으로 저장 행 하나 — obs_date는 그 동부 날짜(수집기 규칙). */
    private static EarningsHistory.Report report(String reportedEt, String periodEnd, Double actual, Double estimate,
                                                 String currency) {
        LocalDateTime local = LocalDateTime.parse(reportedEt);
        return new EarningsHistory.Report(local.toLocalDate(), periodEnd == null ? null : d(periodEnd), actual,
                estimate, pct(actual, estimate), currency, estimate == null ? null : Math.abs(estimate) < 0.05,
                local.atZone(ET).toInstant());
    }

    private static EarningsHistory.Report usd(String reportedEt, String periodEnd, Double actual, Double estimate) {
        return report(reportedEt, periodEnd, actual, estimate, "USD");
    }

    /** 단계 0의 GOOGL 발표 시각 4개 + 기준일(10-23) 뒤에 받은 다음 분기(10-28 장후). */
    private static List<EarningsHistory.Report> googl() {
        return List.of(
                usd("2025-10-29T16:01", "2025-09-30", 2.87, 2.33),
                usd("2026-02-04T16:01", "2025-12-31", 2.82, 2.63),
                usd("2026-04-29T16:01", "2026-03-31", 2.65, 2.51),
                usd("2026-07-22T16:01", "2026-06-30", 2.75, 2.69),
                usd("2026-10-28T16:01", "2026-09-30", 3.01, 2.84));
    }

    /** 단계 0의 INTC 발표 시각 — 첫 분기가 추정치 0.01의 작은 기준. 다음 분기(10-29)는 기준일 뒤. */
    private static List<EarningsHistory.Report> intc() {
        return List.of(
                usd("2025-10-23T16:01", "2025-09-30", 0.23, 0.01),
                usd("2026-01-22T16:01", "2025-12-31", 0.15, 0.08),
                usd("2026-04-23T16:01", "2026-03-31", 0.13, 0.01),
                usd("2026-07-23T16:01", "2026-06-30", 0.10, 0.09),
                usd("2026-10-29T16:01", "2026-09-30", 0.18, 0.12));
    }

    /** 단계 0의 BIDU — 장전 발표, EPS가 CNY. */
    private static List<EarningsHistory.Report> bidu() {
        return List.of(
                report("2025-11-18T04:00", "2025-09-30", 12.53, 11.36, "CNY"),
                report("2026-02-26T04:00", "2025-12-31", 13.41, 12.80, "CNY"),
                report("2026-05-18T05:00", "2026-03-31", 11.92, 12.20, "CNY"),
                report("2026-08-18T05:00", "2026-06-30", 12.05, 11.50, "CNY"));
    }

    /** 단계 0의 SPCX — 3분기뿐이고 첫 분기는 추정치 없음, 마지막 발표가 기준일 사흘 전(10-20 장후). */
    private static List<EarningsHistory.Report> spcx() {
        return List.of(
                usd("2026-05-07T07:04", "2026-03-31", -0.42, null),
                usd("2026-08-04T16:01", "2026-06-30", -0.31, -0.35),
                usd("2026-10-20T16:00", "2026-09-30", -0.18, -0.27));
    }

    private static List<LocalDate> reported(EarningsHistory.Result r) {
        return r.entries().stream().map(EarningsHistory.Entry::reported).toList();
    }

    private static Instant knownBy(LocalDate asOf) {
        return NewsEvents.window(asOf, DECISION).to();
    }

    @Test
    @DisplayName("초기 결정(기준일 10-23) — 기준일 뒤 발표는 패킷에 없다(명세 §5-3)")
    void initialPacketExcludesReportsAfterAsOf() {
        for (Instant cut : Arrays.asList(null, knownBy(INITIAL_AS_OF))) {
            EarningsHistory.Result g = EarningsHistory.compute(googl(), INITIAL_AS_OF, cut);
            assertThat(g.available()).isTrue();
            assertThat(reported(g)).containsExactly(d("2026-07-22"), d("2026-04-29"), d("2026-02-04"), d("2025-10-29"));

            EarningsHistory.Result i = EarningsHistory.compute(intc(), INITIAL_AS_OF, cut);
            assertThat(reported(i)).containsExactly(d("2026-07-23"), d("2026-04-23"), d("2026-01-22"), d("2025-10-23"));

            // SPCX의 10-20 장후 발표는 기준일 전이라 들어감 — 3분기뿐이면 3칸(채우지 않음).
            EarningsHistory.Result s = EarningsHistory.compute(spcx(), INITIAL_AS_OF, cut);
            assertThat(reported(s)).containsExactly(d("2026-10-20"), d("2026-08-04"), d("2026-05-07"));

            for (EarningsHistory.Result r : List.of(g, i, s)) {
                assertThat(r.entries()).allSatisfy(e -> assertThat(e.reported()).isBeforeOrEqualTo(INITIAL_AS_OF));
            }
        }
    }

    @Test
    @DisplayName("기준일 장 마감 뒤(16:01 ET) 발표는 넣는다 — 결정은 다음 날 07:30 KST")
    void afterCloseOnAsOfIsIncluded() {
        LocalDate asOf = d("2025-10-23");
        EarningsHistory.Result dateRule = EarningsHistory.compute(intc(), asOf);
        assertThat(reported(dateRule)).containsExactly(asOf);

        // 결정 시각 = 2025-10-24 07:30 KST = 10-23 18:30 EDT — 16:01 발표는 그 전.
        assertThat(knownBy(asOf)).isEqualTo(LocalDateTime.parse("2025-10-23T18:30").atZone(ET).toInstant());
        EarningsHistory.Result withCut = EarningsHistory.compute(intc(), asOf, knownBy(asOf));
        assertThat(reported(withCut)).containsExactly(asOf);

        // 하루 앞 기준일에는 아직 없음 — 데이터셋은 있으니 null이 아니라 빈 목록.
        EarningsHistory.Result before = EarningsHistory.compute(intc(), d("2025-10-22"), knownBy(d("2025-10-22")));
        assertThat(before.available()).isTrue();
        assertThat(before.entries()).isEmpty();
        assertThat(before.compact()).isEmpty();
    }

    @Test
    @DisplayName("기준일 저녁 결정 시각 뒤(19:55 ET) 발표는 결정 시각을 주면 뺀다 — 다시 만든 패킷에 섞이지 않게")
    void lateEveningReportAfterDecisionIsExcludedWithKnownBy() {
        List<EarningsHistory.Report> pbr = List.of(
                usd("2026-05-11T20:00", "2026-03-31", 0.68, 0.71),
                usd("2026-08-06T19:55", "2026-06-30", 0.74, 0.70));
        LocalDate asOf = d("2026-08-06");

        assertThat(reported(EarningsHistory.compute(pbr, asOf))).containsExactly(d("2026-08-06"), d("2026-05-11"));
        assertThat(reported(EarningsHistory.compute(pbr, asOf, knownBy(asOf)))).containsExactly(d("2026-05-11"));
        // 다음 거래일 결정에는 들어감.
        LocalDate next = d("2026-08-07");
        assertThat(reported(EarningsHistory.compute(pbr, next, knownBy(next))))
                .containsExactly(d("2026-08-06"), d("2026-05-11"));

        // 발표 시각을 모르는 행은 날짜 규칙만 — 기준일 당일이면 넣음.
        EarningsHistory.Report noTime = new EarningsHistory.Report(asOf, d("2026-06-30"), 0.74, 0.70, 5.7, "USD",
                false, null);
        assertThat(reported(EarningsHistory.compute(List.of(noTime), asOf, knownBy(asOf)))).containsExactly(asOf);
    }

    @Test
    @DisplayName("발표일 최신순 4분기 — 이력이 쌓여도 4칸, 입력 순서와 무관")
    void newestFirstAtMostFourRegardlessOfInputOrder() {
        List<EarningsHistory.Report> history = new ArrayList<>(googl());
        history.add(usd("2025-07-23T16:01", "2025-06-30", 2.31, 2.18));
        history.add(usd("2025-04-24T16:01", "2025-03-31", 2.81, 2.01));
        LocalDate asOf = d("2026-12-31");

        EarningsHistory.Result expected = EarningsHistory.compute(history, asOf);
        assertThat(reported(expected))
                .containsExactly(d("2026-10-28"), d("2026-07-22"), d("2026-04-29"), d("2026-02-04"));
        for (long seed = 1; seed <= 20; seed++) {
            List<EarningsHistory.Report> shuffled = new ArrayList<>(history);
            Collections.shuffle(shuffled, new Random(seed));
            EarningsHistory.Result r = EarningsHistory.compute(shuffled, asOf);
            assertThat(r).isEqualTo(expected);
            assertThat(r.compact()).isEqualTo(expected.compact());
        }
    }

    @Test
    @DisplayName("같은 분기가 두 발표일로 쌓이면 기준일까지 보인 가장 늦은 발표 하나만")
    void samePeriodKeepsLatestVisibleReport() {
        List<EarningsHistory.Report> rows = new ArrayList<>(intc().subList(0, 4));
        // 출처가 2026-06-30 분기의 발표일·추정치를 바꿔 보여 다른 obs_date로 한 행이 더 쌓인 경우.
        rows.add(usd("2026-07-30T16:01", "2026-06-30", 0.11, 0.09));

        EarningsHistory.Result between = EarningsHistory.compute(rows, d("2026-07-24"));
        assertThat(reported(between)).containsExactly(d("2026-07-23"), d("2026-04-23"), d("2026-01-22"),
                d("2025-10-23"));
        assertThat(between.entries().get(0).actual()).isEqualTo(0.10);

        EarningsHistory.Result after = EarningsHistory.compute(rows, d("2026-08-03"));
        assertThat(reported(after)).containsExactly(d("2026-07-30"), d("2026-04-23"), d("2026-01-22"),
                d("2025-10-23"));
        assertThat(after.entries().get(0).actual()).isEqualTo(0.11);
        assertThat(after.entries()).extracting(EarningsHistory.Entry::periodEnd).doesNotHaveDuplicates();

        // 분기 말을 모르는 행은 묶지 않음(어느 분기인지 모름) — 다른 발표일이면 각각 한 칸.
        List<EarningsHistory.Report> unknownPeriod = List.of(
                new EarningsHistory.Report(d("2026-07-23"), null, 0.10, 0.09, 11.1, "USD", false, null),
                new EarningsHistory.Report(d("2026-07-30"), null, 0.11, 0.09, 22.2, "USD", false, null));
        EarningsHistory.Result r = EarningsHistory.compute(unknownPeriod, d("2026-08-03"));
        assertThat(reported(r)).containsExactly(d("2026-07-30"), d("2026-07-23"));
        assertThat(r.compact().get(0).get(1)).isNull();
    }

    @Test
    @DisplayName("추정치가 없거나 0인 분기는 surprisePct=null — 저장값이 있어도 버리고 0으로 채우지 않는다")
    void missingEstimateGivesNullSurprise() {
        EarningsHistory.Result s = EarningsHistory.compute(spcx(), INITIAL_AS_OF);
        EarningsHistory.Entry first = s.entries().get(2);
        assertThat(first.reported()).isEqualTo(d("2026-05-07"));
        assertThat(first.actual()).isEqualTo(-0.42);
        assertThat(first.estimate()).isNull();
        assertThat(first.surprisePct()).isNull();
        assertThat(first.smallBase()).isFalse();
        assertThat(s.compact().get(2)).containsExactly("2026-05-07", "2026-03-31", new BigDecimal("-0.42"), null, null);
        // 추정치가 있는 분기는 저장값(수집기 계산) 그대로 반올림 — (−0.18 + 0.27) / 0.27 = 33.3%.
        assertThat(s.compact().get(0).get(4)).isEqualTo(new BigDecimal("33.3"));

        LocalDate day = d("2026-05-07");
        List<EarningsHistory.Report> odd = List.of(
                // 추정치가 없는데 저장본에 서프라이즈율이 있음(형이 바뀐 응답 등).
                new EarningsHistory.Report(day, d("2026-03-31"), -0.42, null, 12.3, "USD", true, null),
                // 추정치 0 — 나눌 기준이 없음.
                new EarningsHistory.Report(day.minusDays(91), d("2025-12-31"), 0.02, 0.0, 999.0, "USD", null, null),
                // 실제치 없음.
                new EarningsHistory.Report(day.minusDays(182), d("2025-09-30"), null, 0.30, 5.0, "USD", false, null),
                // 비유한 수는 null.
                new EarningsHistory.Report(day.minusDays(273), d("2025-06-30"), Double.NaN, 0.30, Double.NaN, "USD",
                        false, null));
        EarningsHistory.Result r = EarningsHistory.compute(odd, day);
        assertThat(r.entries()).extracting(EarningsHistory.Entry::surprisePct).containsOnlyNulls();
        assertThat(r.entries().get(0).smallBase()).isFalse();
        assertThat(r.entries().get(1).estimate()).isEqualTo(0.0);
        assertThat(r.entries().get(1).smallBase()).isTrue();
        assertThat(r.entries().get(3).actual()).isNull();
        assertThat(r.compact().get(1)).containsExactly("2026-02-05", "2025-12-31", new BigDecimal("0.02"),
                BigDecimal.ZERO, null);
    }

    @Test
    @DisplayName("smallBase — |추정치| < 0.05면 표시, 경계 0.05는 아님, 추정치 없으면 아님")
    void smallBaseMarksTinyEstimates() {
        EarningsHistory.Result i = EarningsHistory.compute(intc(), INITIAL_AS_OF);
        assertThat(i.entries()).extracting(EarningsHistory.Entry::smallBase).containsExactly(false, true, false, true);
        assertThat(i.smallBaseKeys("INTC")).containsExactly("INTC/2026-03-31", "INTC/2025-09-30");
        // 작은 기준의 서프라이즈율은 그대로 싣되(부풀어 보임) 머리말 표시로 읽는 쪽이 가림 — (0.23 − 0.01) / 0.01 = 2,200%.
        assertThat(i.compact().get(3).get(4)).isEqualTo(new BigDecimal("2200"));

        LocalDate day = d("2026-07-23");
        List<EarningsHistory.Report> rows = List.of(
                // 저장 표시가 빠져도 같은 기준으로 붙임.
                new EarningsHistory.Report(day, d("2026-06-30"), 0.03, -0.04, 175.0, "USD", null, null),
                // 경계값 0.05는 작은 기준이 아님(엄격한 <).
                new EarningsHistory.Report(day.minusDays(91), d("2026-03-31"), 0.06, 0.05, 20.0, "USD", null, null),
                // 수집기가 붙인 표시는 존중.
                new EarningsHistory.Report(day.minusDays(182), d("2025-12-31"), 0.6, 0.5, 20.0, "USD", true, null),
                // 추정치가 없으면 기준 자체가 없어 표시하지 않음.
                new EarningsHistory.Report(day.minusDays(273), d("2025-09-30"), 0.6, null, null, "USD", true, null));
        EarningsHistory.Result r = EarningsHistory.compute(rows, day);
        assertThat(r.entries()).extracting(EarningsHistory.Entry::smallBase).containsExactly(true, false, true, false);
        assertThat(r.smallBaseKeys("X")).containsExactly("X/2026-06-30", "X/2025-12-31");

        // 분기 말을 모르면 발표일로 키를 만듦.
        EarningsHistory.Result noPeriod = EarningsHistory.compute(
                List.of(new EarningsHistory.Report(day, null, 0.03, 0.01, 200.0, "USD", null, null)), day);
        assertThat(noPeriod.smallBaseKeys("X")).containsExactly("X/2026-07-23");
        assertThat(EarningsHistory.compute(googl(), INITIAL_AS_OF).smallBaseKeys("GOOGL")).isEmpty();
    }

    @Test
    @DisplayName("통화 — BIDU는 CNY 그대로(환산 없음), USD·모름은 따로 적지 않음, 섞이면 둘 다")
    void nonUsdCurrency() {
        EarningsHistory.Result b = EarningsHistory.compute(bidu(), INITIAL_AS_OF);
        assertThat(b.entries()).hasSize(4);
        assertThat(b.currency()).isEqualTo("CNY");
        assertThat(b.nonUsdCurrency()).isEqualTo("CNY");
        assertThat(b.compact().get(0)).containsExactly("2026-08-18", "2026-06-30", new BigDecimal("12.05"),
                new BigDecimal("11.5"), new BigDecimal("4.8"));
        // 장전(05:00 ET) 발표 — 기준일 당일이면 넣음.
        assertThat(reported(EarningsHistory.compute(bidu(), d("2026-08-18"), knownBy(d("2026-08-18")))).get(0))
                .isEqualTo(d("2026-08-18"));

        EarningsHistory.Result g = EarningsHistory.compute(googl(), INITIAL_AS_OF);
        assertThat(g.currency()).isEqualTo("USD");
        assertThat(g.nonUsdCurrency()).isNull();

        LocalDate day = d("2026-08-18");
        EarningsHistory.Result unknown = EarningsHistory.compute(List.of(
                new EarningsHistory.Report(day, d("2026-06-30"), 1.0, 0.9, 11.1, null, false, null),
                new EarningsHistory.Report(day.minusDays(91), d("2026-03-31"), 1.0, 0.9, 11.1, "  ", false, null)),
                day);
        assertThat(unknown.currency()).isNull();
        assertThat(unknown.nonUsdCurrency()).isNull();
        assertThat(unknown.entries()).extracting(EarningsHistory.Entry::currency).containsOnlyNulls();

        EarningsHistory.Result mixed = EarningsHistory.compute(List.of(
                new EarningsHistory.Report(day, d("2026-06-30"), 1.0, 0.9, 11.1, " CNY ", false, null),
                new EarningsHistory.Report(day.minusDays(91), d("2026-03-31"), 1.0, 0.9, 11.1, "USD", false, null)),
                day);
        assertThat(mixed.entries().get(0).currency()).isEqualTo("CNY");
        assertThat(mixed.currency()).isEqualTo("CNY,USD");
        assertThat(mixed.nonUsdCurrency()).isEqualTo("CNY,USD");
    }

    @Test
    @DisplayName("데이터셋 없음(null) ≠ 이 종목 0건(빈 목록) — 패킷 칸과 측정 태그")
    void missingDatasetIsNotEmpty() {
        EarningsHistory.Result missing = EarningsHistory.compute(null, INITIAL_AS_OF);
        assertThat(missing.available()).isFalse();
        assertThat(missing.entries()).isEmpty();
        assertThat(missing.compact()).isNull();
        assertThat(missing.currency()).isNull();
        assertThat(missing.smallBaseKeys("AAPL")).isEmpty();

        EarningsHistory.Result none = EarningsHistory.compute(List.of(), INITIAL_AS_OF);
        assertThat(none.available()).isTrue();
        assertThat(none.compact()).isEmpty();

        assertThat(EarningsHistory.measure(false, List.of(missing, missing)))
                .isEqualTo(new EarningsHistory.Measure(null, null));
        assertThat(EarningsHistory.measure(true, List.of(none, none)))
                .isEqualTo(new EarningsHistory.Measure(false, 0));
        assertThat(EarningsHistory.measure(true, List.of())).isEqualTo(new EarningsHistory.Measure(false, 0));
        assertThat(EarningsHistory.measure(true, null)).isEqualTo(new EarningsHistory.Measure(false, 0));

        // 초기 결정 패킷의 네 종목 + 행 없는 종목 하나 → 분기가 있는 줄 4개.
        List<EarningsHistory.Result> section = new ArrayList<>();
        for (List<EarningsHistory.Report> rows : List.of(googl(), intc(), bidu(), spcx())) {
            section.add(EarningsHistory.compute(rows, INITIAL_AS_OF, knownBy(INITIAL_AS_OF)));
        }
        section.add(none);
        assertThat(EarningsHistory.measure(true, section)).isEqualTo(new EarningsHistory.Measure(true, 4));
    }

    @Test
    @DisplayName("패킷 칸 — 열 순서·ISO 날짜·반올림(EPS 4자리·% 1자리)·지수 표기 없음")
    void compactRows() {
        assertThat(EarningsHistory.COLUMNS).containsExactly("reported", "periodEnd", "actual", "estimate",
                "surprisePct");
        EarningsHistory.Result g = EarningsHistory.compute(googl(), INITIAL_AS_OF);
        List<List<Object>> rows = g.compact();
        assertThat(rows).hasSize(4).allSatisfy(row -> assertThat(row).hasSize(EarningsHistory.COLUMNS.size()));
        // (2.75 − 2.69) / 2.69 = 2.2304…%
        assertThat(rows.get(0)).containsExactly("2026-07-22", "2026-06-30", new BigDecimal("2.75"),
                new BigDecimal("2.69"), new BigDecimal("2.2"));

        LocalDate day = d("2026-07-22");
        EarningsHistory.Result r = EarningsHistory.compute(List.of(
                new EarningsHistory.Report(day, d("2026-06-30"), 10.0, 0.0004, -1.25, "USD", null, null),
                new EarningsHistory.Report(day.minusDays(91), d("2026-03-31"), 1.123456, -0.00004, 0.0, "USD", null,
                        null)), day);
        List<List<Object>> c = r.compact();
        assertThat(c.get(0).get(2)).hasToString("10");
        assertThat(c.get(0).get(3)).hasToString("0.0004");
        assertThat(c.get(0).get(4)).hasToString("-1.3");
        assertThat(c.get(1).get(2)).hasToString("1.1235");
        // 4자리 밑의 추정치는 0이 되지만 원래 값은 0이 아니라 서프라이즈율이 남고 smallBase가 붙음.
        assertThat(c.get(1).get(3)).hasToString("0");
        assertThat(c.get(1).get(4)).hasToString("0");
        assertThat(r.entries().get(1).smallBase()).isTrue();
        assertThat(rows).isUnmodifiable();
        assertThat(rows.get(0)).isUnmodifiable();
    }

    @Test
    @DisplayName("발표일 없는 행·null 행은 버리고, 기준일이 없으면 실패")
    void undatedRowsAreDropped() {
        List<EarningsHistory.Report> rows = new ArrayList<>(bidu());
        rows.add(null);
        rows.add(new EarningsHistory.Report(null, d("2026-09-30"), 13.0, 12.0, 8.3, "CNY", false, null));
        EarningsHistory.Result r = EarningsHistory.compute(rows, d("2026-12-31"));
        assertThat(reported(r)).containsExactly(d("2026-08-18"), d("2026-05-18"), d("2026-02-26"), d("2025-11-18"));

        assertThatThrownBy(() -> EarningsHistory.compute(bidu(), null)).isInstanceOf(NullPointerException.class);
    }
}
