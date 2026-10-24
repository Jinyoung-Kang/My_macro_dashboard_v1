package com.macrodash.analytics;

import com.macrodash.analytics.TrackRecord13F.Criteria;
import com.macrodash.analytics.TrackRecord13F.Filing;
import com.macrodash.analytics.TrackRecord13F.Holding13F;
import com.macrodash.analytics.TrackRecord13F.Metric;
import com.macrodash.analytics.TrackRecord13F.Period;
import com.macrodash.analytics.TrackRecord13F.Ranked;
import com.macrodash.analytics.TrackRecord13F.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 13F 공시일 기준 복제 트랙레코드. 기대값은 손으로 세운 식입니다 — 가격은 시작·끝 날짜에만 점을 찍은 계열(floor 조회라
 * 그 사이 점이 필요 없음)로 두어 구간 수익률을 눈으로 셀 수 있게 했습니다. 공시일 당일·기준일 뒤 날짜에는 엉뚱한 값(999 등)을
 * 덫으로 넣어, 그 값을 쓰면 결과가 크게 틀리게 했습니다.
 */
class TrackRecord13FTest {

    private static final double TOL = 1e-9;
    private static final String CIK = "0000000001";

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static Holding13F h(String ticker, double value) {
        return new Holding13F(ticker, value);
    }

    private static Filing filing(String filed, String report, Holding13F... holdings) {
        return new Filing(d(filed), report == null ? null : d(report), List.of(holdings));
    }

    /** "날짜", 값, "날짜", 값 … 짝으로 만든 계열. */
    private static DailySeries series(Object... dateValuePairs) {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        for (int i = 0; i < dateValuePairs.length; i += 2) {
            map.put(d((String) dateValuePairs[i]), ((Number) dateValuePairs[i + 1]).doubleValue());
        }
        return DailySeries.of(map);
    }

    private static Result compute(List<Filing> filings, Map<String, DailySeries> prices, DailySeries spy,
                                  String asOf, Criteria criteria) {
        return TrackRecord13F.compute(CIK, filings, prices::get, spy, d(asOf), criteria);
    }

    private static double pct(double fraction) {
        return fraction * 100.0;
    }

    @Test
    @DisplayName("기본 기준: 7년 창, 구간 24개, 최소 커버리지 50%, 평균 70%")
    void defaultCriteria() {
        assertThat(Criteria.defaults()).isEqualTo(new Criteria(7, 24, 0.5, 0.7));
        assertThatThrownBy(() -> new Criteria(0, 24, 0.5, 0.7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Criteria(7, -1, 0.5, 0.7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Criteria(7, 24, 50, 0.7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Criteria(7, 24, 0.5, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("두 공시·세 종목 손계산: 구간 수익률 +5.5%·−5%, 누적·CAGR·변동성·샤프·MDD·SPY 초과")
    void twoFilingsThreeTickersByHand() {
        // F1 2025-02-14(금) → 다음 거래일은 02-17(대통령의 날)을 건너 02-18. F2 2025-05-15(목) → 05-16. 기준일 08-15(금).
        List<Filing> filings = List.of(
                filing("2025-02-14", "2024-12-31", h("AAA", 600), h("BBB", 300), h("CCC", 100)),
                filing("2025-05-15", "2025-03-31", h("AAA", 500), h("CCC", 500)));
        Map<String, DailySeries> prices = Map.of(
                "AAA", series("2025-02-14", 999, "2025-02-18", 100, "2025-05-15", 888, "2025-05-16", 110,
                        "2025-08-15", 121),
                "BBB", series("2025-02-18", 50, "2025-05-16", 45, "2025-08-15", 60),
                "CCC", series("2025-02-18", 20, "2025-05-16", 25, "2025-08-15", 20));
        DailySeries spy = series("2025-02-18", 600, "2025-05-16", 630, "2025-08-15", 661.5);

        Result r = compute(filings, prices, spy, "2025-08-15", Criteria.defaults());

        assertThat(r.periods()).hasSize(2);
        Period p1 = r.periods().get(0);
        Period p2 = r.periods().get(1);
        assertThat(p1.start()).isEqualTo(d("2025-02-18"));
        assertThat(p1.end()).isEqualTo(d("2025-05-16"));
        assertThat(p2.start()).isEqualTo(d("2025-05-16"));
        assertThat(p2.end()).isEqualTo(d("2025-08-15"));
        assertThat(p1.reportDate()).isEqualTo(d("2024-12-31"));
        // R1 = 0.6×(110/100−1) + 0.3×(45/50−1) + 0.1×(25/20−1) = 0.06 − 0.03 + 0.025 = 0.055
        assertThat(p1.returnPct()).isCloseTo(5.5, within(TOL));
        // R2 = 0.5×(121/110−1) + 0.5×(20/25−1) = 0.05 − 0.10 = −0.05
        assertThat(p2.returnPct()).isCloseTo(-5.0, within(TOL));
        assertThat(p1.spyReturnPct()).isCloseTo(5.0, within(TOL));
        assertThat(p2.spyReturnPct()).isCloseTo(5.0, within(TOL));
        assertThat(p1.coverage()).isCloseTo(1.0, within(TOL));
        assertThat(p1.coveredTickers()).isEqualTo(3);
        assertThat(p1.holdings()).isEqualTo(3);

        assertThat(r.periodsUsed()).isEqualTo(2);
        assertThat(r.periodsTotal()).isEqualTo(2);
        assertThat(r.firstFilingDate()).isEqualTo(d("2025-02-14"));
        assertThat(r.lastFilingDate()).isEqualTo(d("2025-05-15"));
        double cum = 1.055 * 0.95 - 1.0;                       // 0.00225
        assertThat(r.cumReturnPct()).isCloseTo(pct(cum), within(TOL));
        // 02-18 → 05-16 = 87일, 05-16 → 08-15 = 91일 → 178일
        assertThat(ChronoUnit.DAYS.between(d("2025-02-18"), d("2025-08-15"))).isEqualTo(178);
        assertThat(r.cagrPct()).isCloseTo(pct(Math.pow(1.0 + cum, 365.0 / 178) - 1.0), within(TOL));
        // 평균 0.0025, 편차 ±0.0525 → 표본 sd = 0.0525·√2. 평균 구간 일수 89.
        double sd = 0.0525 * Math.sqrt(2.0);
        assertThat(r.volPct()).isCloseTo(pct(sd * Math.sqrt(365.0 / 89)), within(TOL));
        assertThat(r.sharpe()).isCloseTo(0.0025 / sd * Math.sqrt(365.0 / 89), within(TOL));
        assertThat(r.mddPct()).isCloseTo(-5.0, within(TOL));   // 1.055 → 1.00225 = −5%
        assertThat(r.spyCumReturnPct()).isCloseTo(10.25, within(TOL));
        assertThat(r.excessVsSpyPct()).isCloseTo(pct(cum) - 10.25, within(TOL));
        assertThat(r.coverageMin()).isCloseTo(1.0, within(TOL));
        assertThat(r.coverageAvg()).isCloseTo(1.0, within(TOL));
        assertThat(r.eligible()).isFalse();
        assertThat(r.ineligibleReason()).isEqualTo("구간 2개 < 24");
    }

    @Test
    @DisplayName("룩어헤드 없음: 공시 당일이 아니라 다음 거래일 종가에 사고, 기준일 뒤 공시·가격은 쓰지 않는다")
    void buysOnNextTradingDayAndIgnoresFuture() {
        List<Filing> filings = List.of(
                filing("2025-02-14", "2024-09-30", h("AAA", 100)),
                filing("2025-03-14", "2024-12-31", h("BBB", 100)),    // 기준일 당일 공시 — 사는 날은 03-17
                filing("2025-04-01", "2025-03-31", h("CCC", 100)));   // 기준일 뒤 공시 — 아직 모름
        Map<String, DailySeries> prices = Map.of(
                "AAA", series("2025-02-14", 50, "2025-02-18", 100, "2025-03-14", 110, "2025-03-17", 999),
                "BBB", series("2025-03-14", 10, "2025-03-17", 999),
                "CCC", series("2025-03-14", 10, "2025-04-02", 999));

        Result fri = compute(filings, prices, null, "2025-03-14", Criteria.defaults());
        // 기준일이 일요일이어도 마지막 거래일(03-14)에서 끝낸다 — 03-17(월) 가격은 그날 아직 없다.
        Result sun = compute(filings, prices, null, "2025-03-16", Criteria.defaults());

        for (Result r : List.of(fri, sun)) {
            assertThat(r.periods()).hasSize(1);
            Period p = r.periods().get(0);
            assertThat(p.filingDate()).isEqualTo(d("2025-02-14"));
            assertThat(p.start()).isEqualTo(d("2025-02-18"));
            assertThat(p.end()).isEqualTo(d("2025-03-14"));
            assertThat(p.returnPct()).isCloseTo(10.0, within(TOL));   // 공시 당일 50에 샀다면 +120%
            assertThat(r.lastFilingDate()).isEqualTo(d("2025-03-14"));
            assertThat(r.spyCumReturnPct()).isNull();                  // SPY 없음 → 비교하지 않음
            assertThat(r.excessVsSpyPct()).isNull();
        }
    }

    @Test
    @DisplayName("같은 날 낸 두 공시는 길이 0 구간을 만들지 않고 뒤 분기의 보유를 든다")
    void sameDayFilingsKeepLaterQuarter() {
        List<Filing> filings = List.of(   // 입력 순서가 섞여 있어도 공시일·보고일 순으로 정렬
                filing("2025-02-14", "2024-12-31", h("AAA", 100)),
                filing("2025-02-14", "2024-09-30", h("BBB", 100)));
        Map<String, DailySeries> prices = Map.of(
                "AAA", series("2025-02-18", 100, "2025-03-14", 105),
                "BBB", series("2025-02-18", 100, "2025-03-14", 50));

        Result r = compute(filings, prices, null, "2025-03-14", Criteria.defaults());

        assertThat(r.periodsTotal()).isEqualTo(1);
        assertThat(r.periods().get(0).reportDate()).isEqualTo(d("2024-12-31"));
        assertThat(r.periods().get(0).returnPct()).isCloseTo(5.0, within(TOL));
    }

    @Test
    @DisplayName("커버리지 재정규화: 티커 없는·가격 없는 보유는 빼고 남은 평가액으로 다시 나눈다. 커버 0이면 수익률 null")
    void coverageRenormalizes() {
        List<Filing> filings = List.of(
                filing("2025-02-14", "2024-12-31", h("AAA", 300), h(null, 100), h("CCC", 600)),   // CCC는 가격 없음
                filing("2025-05-15", "2025-03-31", h("ZZZ", 100)));                               // 전부 가격 없음
        Map<String, DailySeries> prices = Map.of("AAA", series("2025-02-18", 100, "2025-05-16", 120));

        Result r = compute(filings, prices, null, "2025-08-15", Criteria.defaults());

        Period p1 = r.periods().get(0);
        Period p2 = r.periods().get(1);
        assertThat(p1.coverage()).isCloseTo(0.3, within(TOL));
        assertThat(p1.returnPct()).isCloseTo(20.0, within(TOL));   // AAA 비중 100%로 재정규화(0.3×20% = 6%가 아님)
        assertThat(p1.coveredTickers()).isEqualTo(1);
        assertThat(p1.holdings()).isEqualTo(3);
        assertThat(p2.coverage()).isZero();
        assertThat(p2.returnPct()).isNull();                       // 0%로 채우지 않음
        assertThat(p2.coveredTickers()).isZero();

        assertThat(r.periodsUsed()).isEqualTo(1);                  // 수익률 없는 구간은 누적·구간 수에서 빠짐
        assertThat(r.periodsTotal()).isEqualTo(2);
        assertThat(r.coverageMin()).isZero();                      // 커버리지는 창 안 모든 구간 기준
        assertThat(r.coverageAvg()).isCloseTo(0.15, within(TOL));
        assertThat(r.cumReturnPct()).isCloseTo(20.0, within(TOL));
        assertThat(r.cagrPct()).isCloseTo(pct(Math.pow(1.2, 365.0 / 87) - 1.0), within(TOL));   // 02-18 → 05-16
        assertThat(r.volPct()).isNull();                           // 구간 1개로는 변동성이 없음
        assertThat(r.sharpe()).isNull();
        assertThat(r.mddPct()).isZero();
    }

    @Test
    @DisplayName("7년 창: 창 시작 전에 끝난 구간은 빼고, 걸친 첫 구간은 창 시작일 가격으로 자른다")
    void windowClipsFirstPeriod() {
        // 기준일 2026-10-23 → 창 시작 2019-10-23(수).
        List<Filing> filings = List.of(
                filing("2019-05-15", "2019-03-31", h("AAA", 100)),    // 05-16 → 08-15: 창 시작 전에 끝남
                filing("2019-08-14", "2019-06-30", h("AAA", 100)),    // 08-15 → 11-15: 10-23부터
                filing("2019-11-14", "2019-09-30", h("AAA", 100)));   // 11-15 → 기준일
        Map<String, DailySeries> prices = Map.of("AAA", series("2019-05-16", 5, "2019-08-15", 10,
                "2019-10-23", 20, "2019-11-15", 22, "2026-10-23", 44));
        DailySeries spy = series("2019-10-23", 300, "2019-11-15", 330, "2026-10-23", 660);

        Result r = compute(filings, prices, spy, "2026-10-23", Criteria.defaults());

        assertThat(r.periodsTotal()).isEqualTo(2);
        assertThat(r.firstFilingDate()).isEqualTo(d("2019-08-14"));
        Period first = r.periods().get(0);
        assertThat(first.start()).isEqualTo(d("2019-10-23"));
        assertThat(first.returnPct()).isCloseTo(10.0, within(TOL));   // 08-15(10)부터였다면 +120%
        assertThat(r.periods().get(1).returnPct()).isCloseTo(100.0, within(TOL));
        assertThat(r.cumReturnPct()).isCloseTo(120.0, within(TOL));
        // 2019-10-23 → 2026-10-23 = 7×365 + 윤일 2 = 2557일
        assertThat(ChronoUnit.DAYS.between(d("2019-10-23"), d("2026-10-23"))).isEqualTo(2557);
        assertThat(r.cagrPct()).isCloseTo(pct(Math.pow(2.2, 365.0 / 2557) - 1.0), within(TOL));
        assertThat(r.spyCumReturnPct()).isCloseTo(pct(1.1 * 2.0 - 1.0), within(TOL));
        assertThat(r.excessVsSpyPct()).isCloseTo(0.0, within(TOL));
    }

    @Test
    @DisplayName("자격 미달 사유 3종(구간·최소·평균 커버리지)을 '; '로, 기준을 넘으면 사유 null")
    void ineligibleReasons() {
        List<Filing> filings = List.of(
                filing("2025-02-14", "2024-12-31", h("AAA", 40), h(null, 60)),    // 40%
                filing("2025-05-15", "2025-03-31", h("AAA", 90), h(null, 10)),    // 90%
                filing("2025-08-14", "2025-06-30", h("AAA", 60), h(null, 40)));   // 60% → 평균 63.3%
        Map<String, DailySeries> prices = Map.of("AAA", series("2025-02-18", 100, "2025-05-16", 100,
                "2025-08-15", 100, "2025-10-24", 100));

        Result strict = compute(filings, prices, null, "2025-10-24", Criteria.defaults());
        Result loose = compute(filings, prices, null, "2025-10-24", new Criteria(7, 3, 0.4, 0.6));

        assertThat(strict.eligible()).isFalse();
        assertThat(strict.ineligibleReason())
                .isEqualTo("구간 3개 < 24; 최소 커버리지 40% < 50%; 평균 커버리지 63% < 70%");
        assertThat(strict.coverageMin()).isCloseTo(0.4, within(TOL));
        assertThat(strict.coverageAvg()).isCloseTo(1.9 / 3, within(TOL));
        assertThat(loose.eligible()).isTrue();                    // 기준과 같으면 통과(≥)
        assertThat(loose.ineligibleReason()).isNull();
    }

    @Test
    @DisplayName("사유의 %는 내림 — 49.7%를 '50% < 50%'로 보이지 않는다")
    void reasonPercentRoundsDown() {
        List<Filing> filings = List.of(filing("2025-02-14", "2024-12-31", h("AAA", 497), h(null, 503)));
        Map<String, DailySeries> prices = Map.of("AAA", series("2025-02-18", 100, "2025-03-14", 100));

        Result r = compute(filings, prices, null, "2025-03-14", new Criteria(7, 1, 0.5, 0.0));

        assertThat(r.ineligibleReason()).isEqualTo("최소 커버리지 49% < 50%");
    }

    @Test
    @DisplayName("공시가 없거나 전부 기준일 뒤면 '공시 없음', 수치는 0이 아니라 null")
    void noFilings() {
        Result none = compute(List.of(), Map.of(), null, "2026-10-23", Criteria.defaults());
        Result future = compute(List.of(filing("2026-11-14", "2026-09-30", h("AAA", 1))), Map.of(), null,
                "2026-10-23", Criteria.defaults());

        for (Result r : List.of(none, future)) {
            assertThat(r.cik()).isEqualTo(CIK);
            assertThat(r.eligible()).isFalse();
            assertThat(r.ineligibleReason()).isEqualTo("공시 없음");
            assertThat(r.periodsUsed()).isZero();
            assertThat(r.periodsTotal()).isZero();
            assertThat(r.periods()).isEmpty();
            assertThat(r.cumReturnPct()).isNull();
            assertThat(r.cagrPct()).isNull();
            assertThat(r.mddPct()).isNull();
            assertThat(r.coverageMin()).isNull();
            assertThat(r.lastFilingDate()).isNull();
        }
    }

    @Test
    @DisplayName("구간 중 상장폐지는 마지막 가격으로 끝냄. 시작 가격은 7일까지만 거슬러 찾고, 시작 전에 사라진·뒤에 상장한 종목은 빠짐")
    void delistedInPeriodUsesLastPrice() {
        List<Filing> filings = List.of(filing("2025-02-14", "2024-12-31",
                h("AAA", 400), h("DEAD", 400), h("EDGE", 200), h("OLD8", 100), h("GONE", 100), h("STALE", 100),
                h("NEWBIE", 100)));
        Map<String, DailySeries> prices = new HashMap<>();
        prices.put("AAA", series("2025-02-18", 100, "2025-05-16", 110));
        prices.put("DEAD", series("2025-02-18", 20, "2025-03-20", 30));     // 03-20 뒤 상장폐지 → 30에 판 것으로
        prices.put("EDGE", series("2025-02-11", 40, "2025-05-16", 44));     // 시작 7일 전 종가 — 인정(경계)
        prices.put("OLD8", series("2025-02-10", 40, "2025-05-16", 44));     // 8일 전 — 시작 가격 없음
        prices.put("GONE", series("2025-01-31", 10, "2025-02-07", 10));     // 시작 전에 사라짐
        prices.put("STALE", series("2025-02-12", 10));                      // 시작가는 7일 안이지만 끝 가격이 시작 전
        prices.put("NEWBIE", series("2025-02-20", 10, "2025-05-16", 20));   // 시작 뒤 상장

        Result r = compute(filings, prices, null, "2025-05-16", Criteria.defaults());

        Period p = r.periods().get(0);
        // (400×0.1 + 400×0.5 + 200×0.1) / 1000 = 0.26, 커버리지 1000/1400
        assertThat(p.returnPct()).isCloseTo(26.0, within(TOL));
        assertThat(p.coverage()).isCloseTo(1000.0 / 1400.0, within(TOL));
        assertThat(p.coveredTickers()).isEqualTo(3);
        assertThat(p.holdings()).isEqualTo(7);
    }

    @Test
    @DisplayName("SPY: 구간마다 같은 날짜로 비교, 한 구간이라도 SPY가 없으면 누적·초과는 null")
    void spyMissingInOnePeriod() {
        List<Filing> filings = List.of(
                filing("2025-02-14", "2024-12-31", h("AAA", 100)),
                filing("2025-05-15", "2025-03-31", h("AAA", 100)));
        Map<String, DailySeries> prices = Map.of("AAA",
                series("2025-02-18", 100, "2025-05-16", 110, "2025-08-15", 121));
        DailySeries spy = series("2025-02-18", 600, "2025-05-16", 630);   // 05-16 뒤 SPY 점 없음

        Result r = compute(filings, prices, spy, "2025-08-15", Criteria.defaults());

        assertThat(r.periods().get(0).spyReturnPct()).isCloseTo(5.0, within(TOL));
        assertThat(r.periods().get(1).spyReturnPct()).isNull();
        assertThat(r.cumReturnPct()).isCloseTo(21.0, within(TOL));
        assertThat(r.spyCumReturnPct()).isNull();   // 첫 구간 SPY만으로 비교하면 기관과 다른 기간
        assertThat(r.excessVsSpyPct()).isNull();
    }

    @Test
    @DisplayName("같은 티커 두 줄은 평가액을 더해 한 종목으로 센다")
    void mergesDuplicateTickers() {
        List<Filing> filings = List.of(filing("2025-02-14", "2024-12-31",
                h("AAA", 300), h("BBB", 500), h("AAA", 200)));
        Map<String, DailySeries> prices = Map.of(
                "AAA", series("2025-02-18", 100, "2025-03-14", 110),
                "BBB", series("2025-02-18", 50, "2025-03-14", 40));

        Result r = compute(filings, prices, null, "2025-03-14", Criteria.defaults());

        Period p = r.periods().get(0);
        assertThat(p.returnPct()).isCloseTo(pct(0.5 * 0.1 + 0.5 * -0.2), within(TOL));   // AAA 500 : BBB 500
        assertThat(p.coveredTickers()).isEqualTo(2);
        assertThat(p.holdings()).isEqualTo(3);
        assertThat(p.coverage()).isCloseTo(1.0, within(TOL));
    }

    @Test
    @DisplayName("가격 조회는 티커마다 한 번(모르는 티커 포함)")
    void looksUpEachTickerOnce() {
        List<Filing> filings = List.of(
                filing("2025-02-14", "2024-12-31", h("AAA", 1), h("NOPE", 1)),
                filing("2025-05-15", "2025-03-31", h("AAA", 1), h("NOPE", 1)));
        Map<String, Integer> calls = new HashMap<>();
        DailySeries aaa = series("2025-02-18", 100, "2025-08-15", 110);

        TrackRecord13F.compute(CIK, filings, t -> {
            calls.merge(t, 1, Integer::sum);
            return "AAA".equals(t) ? aaa : null;
        }, null, d("2025-08-15"), Criteria.defaults());

        assertThat(calls).containsExactlyInAnyOrderEntriesOf(Map.of("AAA", 1, "NOPE", 1));
    }

    private static Result ranked(String cik, boolean eligible, Double cagr, Double excess, Double sharpe) {
        return new Result(cik, 24, 24, null, null, null, cagr, null, null, sharpe, null, excess, 0.9, 0.9,
                eligible, eligible ? null : "구간 0개 < 24", List.of());
    }

    @Test
    @DisplayName("순위: 자격 있는 기관만 내림차순, null은 뒤로, 동률은 CIK 오름차순, 1부터 차례로")
    void rankTiesAndNulls() {
        List<Result> results = List.of(
                ranked("0003", true, 10.0, 1.0, 0.5),
                ranked("0001", true, 10.0, 2.0, null),
                ranked("0002", true, 5.0, 3.0, 1.0),
                ranked("0004", true, null, null, 2.0),
                ranked("0005", false, 50.0, 50.0, 5.0));   // 자격 없음 — 값이 커도 순위에 없음

        assertThat(order(TrackRecord13F.rank(results, Metric.CAGR)))
                .containsExactly("0001:1", "0003:2", "0002:3", "0004:4");
        assertThat(order(TrackRecord13F.rank(results, Metric.EXCESS_VS_SPY)))
                .containsExactly("0002:1", "0001:2", "0003:3", "0004:4");
        assertThat(order(TrackRecord13F.rank(results, Metric.SHARPE)))
                .containsExactly("0004:1", "0002:2", "0003:3", "0001:4");
        assertThat(TrackRecord13F.rank(List.of(), Metric.CAGR)).isEmpty();
    }

    private static List<String> order(List<Ranked> ranked) {
        return ranked.stream().map(r -> r.result().cik() + ":" + r.rank()).toList();
    }

    // ── 단계 0 원형과 대조 ────────────────────────────────────────────────────────────────────────

    /** 2024-01-02 ~ 기준일의 거래일(UsMarketCalendar). */
    private static List<LocalDate> tradingDays(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (UsMarketCalendar.isTradingDay(day)) {
                out.add(day);
            }
        }
        return out;
    }

    /** 거래일 i번째(from부터 0)에 f(i)를 찍은 계열. */
    private static DailySeries daily(List<LocalDate> days, int from, IntToDoubleFunction f) {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        for (int i = from; i < days.size(); i++) {
            map.put(days.get(i), f.applyAsDouble(i - from));
        }
        return DailySeries.of(map);
    }

    /**
     * 단계 0 원형({@code trackrecord_proto.py}, 2026-10-24)의 구간·통계 반복문을 그대로 옮긴 것 — 자바 구현과 따로 짠 기준.
     * 원형과 계약(이 클래스)이 다른 곳: ① 원형은 끝 가격에도 7일 제한을 둬 구간 중 상장폐지를 '가격 없음'으로 뺐고(계약은 마지막
     * 가격), ② 다음 영업일을 SPY 거래일로 셌고(계약은 UsMarketCalendar), ③ 연율화 평균 구간 일수를 (전체 일수 / 구간 수)로,
     * ④ 구간 수 사유를 수익률 없는 구간까지 세었습니다. 아래 자료는 상장폐지가 없고 모든 구간에 수익률이 있어 네 차이가 결과를
     * 바꾸지 않도록 골랐습니다(상장폐지는 {@link #delistedInPeriodUsesLastPrice}가 따로 봄).
     */
    private static final class Prototype {
        final DailySeries spy;
        final Map<String, DailySeries> series;
        final Set<LocalDate> trading = new HashSet<>();
        final LocalDate lastSpyDay;

        Prototype(Map<String, DailySeries> series, DailySeries spy) {
            this.series = series;
            this.spy = spy;
            for (int i = 0; i < spy.size(); i++) {
                trading.add(spy.dateAt(i));
            }
            this.lastSpyDay = spy.lastDate();
        }

        LocalDate nextBusinessDay(LocalDate d) {
            LocalDate n = d.plusDays(1);
            while (n.getDayOfWeek() == DayOfWeek.SATURDAY || n.getDayOfWeek() == DayOfWeek.SUNDAY
                    || (!n.isAfter(lastSpyDay) && !trading.contains(n))) {
                n = n.plusDays(1);
            }
            return n;
        }

        Double priceOn(DailySeries s, LocalDate d) {
            if (s == null || s.floorDate(d) == null || ChronoUnit.DAYS.between(s.floorDate(d), d) > 7) {
                return null;
            }
            return s.floorValue(d);
        }

        /** {start, end, ret, spy, coverage}의 목록. ret은 null일 수 있음. */
        List<Object[]> periods(List<Filing> filings, LocalDate asOf, LocalDate windowStart) {
            List<Object[]> out = new ArrayList<>();
            for (int i = 0; i < filings.size(); i++) {
                Filing q = filings.get(i);
                LocalDate start = nextBusinessDay(q.filingDate());
                LocalDate end = i + 1 < filings.size() ? nextBusinessDay(filings.get(i + 1).filingDate()) : asOf;
                if (!end.isAfter(windowStart) || !start.isBefore(end)) {
                    continue;
                }
                start = start.isBefore(windowStart) ? windowStart : start;
                double total = 0.0;
                Map<String, double[]> by = new LinkedHashMap<>();
                for (Holding13F h : q.holdings()) {
                    total += h.value();
                    String t = h.ticker();
                    Double p0 = t == null ? null : priceOn(series.get(t), start);
                    Double p1 = t == null ? null : priceOn(series.get(t), end);
                    if (t != null && p0 != null && p1 != null) {
                        by.computeIfAbsent(t, k -> new double[] {0.0, p0, p1})[0] += h.value();
                    }
                }
                double covValue = by.values().stream().mapToDouble(v -> v[0]).sum();
                double cov = total != 0 ? covValue / total : 0;
                Double r = null;
                if (covValue != 0) {
                    double sum = 0.0;
                    for (double[] v : by.values()) {
                        sum += v[0] / covValue * (v[2] / v[1] - 1);
                    }
                    r = sum;
                }
                double rs = priceOn(spy, end) / priceOn(spy, start) - 1;
                out.add(new Object[] {start, end, r, rs, cov});
            }
            return out;
        }
    }

    @Test
    @DisplayName("단계 0 원형 식과 대조: 11개 공시·3종목(1종목은 중간 상장)·2년 창의 구간과 통계가 같다")
    void matchesStepZeroPrototype() {
        LocalDate asOf = d("2026-10-23");
        Criteria criteria = new Criteria(2, 4, 0.5, 0.7);
        List<LocalDate> days = tradingDays(d("2024-01-02"), asOf);
        int cccListing = days.indexOf(d("2025-03-03"));
        assertThat(days).hasSize(707);   // 아래 파이썬 대조값을 낸 거래일 수와 같아야 함
        // 사칙연산만 쓴 가격(파이썬과 같은 순서로 계산해 같은 double) — 130·170거래일마다 계단식으로 내려 손실 구간·낙폭을 만든다.
        DailySeries spy = daily(days, 0, n -> 400 + 0.15 * n + 0.7 * (n % 4) - 30 * ((n / 170) % 2));
        Map<String, DailySeries> prices = Map.of(
                "AAA", daily(days, 0, n -> 50 + 0.05 * n + 0.3 * (n % 5) - 8 * ((n / 130) % 2)),
                "BBB", daily(days, 0, n -> 120 - 0.03 * n + 0.5 * (n % 3)),
                "CCC", daily(days, cccListing, m -> 30 + 0.04 * m - 0.2 * (m % 6)));
        String[][] dates = {
                {"2024-02-14", "2023-12-31"}, {"2024-05-15", "2024-03-31"}, {"2024-08-14", "2024-06-30"},
                {"2024-11-14", "2024-09-30"}, {"2025-02-14", "2024-12-31"}, {"2025-05-15", "2025-03-31"},
                {"2025-08-14", "2025-06-30"}, {"2025-11-14", "2025-09-30"}, {"2026-02-13", "2025-12-31"},
                {"2026-05-15", "2026-03-31"}, {"2026-08-14", "2026-06-30"}};
        List<Filing> filings = new ArrayList<>();
        for (int k = 0; k < dates.length; k++) {
            List<Holding13F> holdings = new ArrayList<>();
            holdings.add(h("AAA", 500 + 50 * k));
            holdings.add(h("BBB", 300 - 20 * k));
            if (k >= 3) {
                holdings.add(h("CCC", 200));   // 2025-03-03 상장 전 두 구간은 가격이 없어 커버리지가 떨어짐
            }
            holdings.add(h(null, 100 + 10 * (k % 3)));
            filings.add(new Filing(d(dates[k][0]), d(dates[k][1]), holdings));
        }

        Result r = TrackRecord13F.compute(CIK, filings, prices::get, spy, asOf, criteria);
        List<Object[]> proto = new Prototype(prices, spy).periods(filings, asOf, asOf.minusYears(2));

        assertThat(r.periods()).hasSize(proto.size()).hasSize(9);
        List<Double> rets = new ArrayList<>();
        List<Double> covs = new ArrayList<>();
        double growth = 1.0;
        double spyGrowth = 1.0;
        double peak = 1.0;
        double mdd = 0.0;
        for (int i = 0; i < proto.size(); i++) {
            Object[] expected = proto.get(i);
            Period actual = r.periods().get(i);
            assertThat(actual.start()).as("구간 %d 시작", i).isEqualTo(expected[0]);
            assertThat(actual.end()).as("구간 %d 끝", i).isEqualTo(expected[1]);
            assertThat(actual.returnPct()).as("구간 %d 수익률", i).isCloseTo(pct((Double) expected[2]), within(TOL));
            assertThat(actual.spyReturnPct()).as("구간 %d SPY", i).isCloseTo(pct((Double) expected[3]), within(TOL));
            assertThat(actual.coverage()).as("구간 %d 커버리지", i).isCloseTo((Double) expected[4], within(TOL));
            double ret = (Double) expected[2];
            rets.add(ret);
            covs.add((Double) expected[4]);
            growth *= 1 + ret;
            spyGrowth *= 1 + (Double) expected[3];
            peak = Math.max(peak, growth);
            mdd = Math.min(mdd, growth / peak - 1);
        }
        // 원형의 통계 식(cum·cagr·vol·sharpe·mdd).
        LocalDate protoStart = (LocalDate) proto.get(0)[0];
        LocalDate protoEnd = (LocalDate) proto.get(proto.size() - 1)[1];
        long totalDays = ChronoUnit.DAYS.between(protoStart, protoEnd);
        double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double sd = Math.sqrt(rets.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / (rets.size() - 1));
        double avgDays = (double) totalDays / rets.size();
        assertThat(r.cumReturnPct()).isCloseTo(pct(growth - 1), within(TOL));
        assertThat(r.cagrPct()).isCloseTo(pct(Math.pow(growth, 365.0 / totalDays) - 1), within(TOL));
        assertThat(r.volPct()).isCloseTo(pct(sd * Math.sqrt(365 / avgDays)), within(TOL));
        assertThat(r.sharpe()).isCloseTo(mean / sd * Math.sqrt(365 / avgDays), within(TOL));
        assertThat(r.mddPct()).isCloseTo(pct(mdd), within(TOL));
        assertThat(r.spyCumReturnPct()).isCloseTo(pct(spyGrowth - 1), within(TOL));
        assertThat(r.coverageMin()).isCloseTo(covs.stream().mapToDouble(Double::doubleValue).min().orElseThrow(),
                within(TOL));
        assertThat(r.coverageAvg()).isCloseTo(covs.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                within(TOL));
        assertThat(r.periods().get(0).start()).isEqualTo(d("2024-10-23"));   // 창 시작으로 잘림
        assertThat(r.periodsUsed()).isEqualTo(9);
        // 같은 자료를 파이썬 원형 반복문(trackrecord_proto.py에서 복사, 2026-10-24 실행)에 돌린 값 — 자바로 옮긴 위
        // Prototype이 원형과 같은지까지 고정합니다.
        assertThat(r.cumReturnPct()).isCloseTo(38.788740610548444, within(TOL));
        assertThat(r.cagrPct()).isCloseTo(17.808633219534652, within(TOL));
        assertThat(r.volPct()).isCloseTo(13.4960769592632, within(TOL));
        assertThat(r.sharpe()).isCloseTo(1.2927938752656978, within(TOL));
        assertThat(r.mddPct()).isCloseTo(-2.9830182302145847, within(TOL));
        assertThat(r.spyCumReturnPct()).isCloseTo(26.635047428856716, within(TOL));
        assertThat(r.coverageMin()).isCloseTo(0.7478991596638656, within(TOL));
        assertThat(r.coverageAvg()).isCloseTo(0.8753587731324863, within(TOL));
    }
}
