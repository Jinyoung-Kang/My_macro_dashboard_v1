package com.macrodash.analytics;

import com.macrodash.analytics.Signal13F.Allocation;
import com.macrodash.analytics.Signal13F.Candidate;
import com.macrodash.analytics.Signal13F.Contribution;
import com.macrodash.analytics.Signal13F.Event;
import com.macrodash.analytics.Signal13F.FilerQuarter;
import com.macrodash.analytics.Signal13F.Mode;
import com.macrodash.analytics.Signal13F.Plan;
import com.macrodash.analytics.Signal13F.Row;
import com.macrodash.analytics.Signal13F.Signals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 규칙 책의 13F 신호와 배분(D27 섞기 판). 기관 총액을 100·1,000·10,000처럼 둥글게 두어 비중(%)과 확신도를 눈으로 셀 수 있게
 * 했고, 기관 가중·점수·확신도 비례 목표 비중은 손으로 계산한 분수와 대조합니다. 경계값(+30% 정확히·+29%, 목표 1% 정확히)과
 * "모르는 값"(주식 수 null·직전 분기 공시 없음)이 신호를 만들지 않는지를 따로 봅니다.
 */
class Signal13FTest {

    private static final double TOL = 1e-9;
    private static final LocalDate REPORT = LocalDate.of(2026, 6, 30);
    private static final LocalDate FILED = LocalDate.of(2026, 8, 14);
    /** 기본 확대 기준(R2)과 확신도 상한(blend.convictionCapPct). */
    private static final double ADD = 30;
    private static final double CAP = 10;

    private static Row row(String ticker, double value, Double shares) {
        return new Row(ticker, value, shares);
    }

    private static FilerQuarter filer(String cik, Integer rankA, Integer rankB, double weight, double total,
                                      List<Row> current, List<Row> previous) {
        return new FilerQuarter(cik, rankA, rankB, weight, REPORT, FILED, total, current, previous);
    }

    private static FilerQuarter filer(String cik, double weight, double total, List<Row> current, List<Row> previous) {
        return filer(cik, null, null, weight, total, current, previous);
    }

    /** 직전 분기가 비어 있어(공시는 있음) 이번 보유가 모두 NEW인 기관 — 값이 곧 비중(총액 100). */
    private static FilerQuarter newBuyer(String cik, double weight, Object... tickerValuePairs) {
        List<Row> current = new ArrayList<>();
        for (int i = 0; i < tickerValuePairs.length; i += 2) {
            current.add(row((String) tickerValuePairs[i], ((Number) tickerValuePairs[i + 1]).doubleValue(), 1.0));
        }
        return filer(cik, weight, 100, current, List.of());
    }

    private static Signals collect(List<FilerQuarter> filers, int minHolders) {
        return Signal13F.collect(filers, minHolders, ADD, CAP);
    }

    private static Event only(Signals signals, String ticker) {
        List<Event> found = signals.events().stream().filter(e -> e.ticker().equals(ticker)).toList();
        assertThat(found).as(ticker + " 이벤트").hasSize(1);
        return found.get(0);
    }

    private static boolean hasEvent(Signals signals, String ticker) {
        return signals.events().stream().anyMatch(e -> e.ticker().equals(ticker));
    }

    private static Candidate candidateOf(Signals signals, String ticker) {
        return signals.buyCandidates().stream().filter(c -> c.ticker().equals(ticker)).findFirst().orElseThrow();
    }

    // ── 기관 가중 ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("기관 가중 5·4(2026-10-23 풀): A 5/15…1/15 × 0.5 + B 4/10…1/10 × 0.5, 두 그룹에 모두 있는 Duquesne은 둘 다 받는다")
    void blendsTwoGroupsWithOverlap() {
        Map<String, Double> w = Signal13F.blendWeights(
                List.of("DUQ", "APP", "GRN", "VIK", "COA"), List.of("DUQ", "BRI", "FIS", "BRK"), 0.5);

        // 순서: 그룹 A 순서 뒤에 그룹 B에만 있는 기관(선정 행 selected_ciks와 같음).
        assertThat(w.keySet()).containsExactly("DUQ", "APP", "GRN", "VIK", "COA", "BRI", "FIS", "BRK");
        assertThat(w.get("DUQ")).isCloseTo(0.5 * 5 / 15 + 0.5 * 4 / 10, within(TOL));   // 11/30 ≈ 0.3667
        assertThat(w.get("DUQ")).isCloseTo(11.0 / 30, within(TOL));
        assertThat(w.get("APP")).isCloseTo(2.0 / 15, within(TOL));
        assertThat(w.get("GRN")).isCloseTo(1.0 / 10, within(TOL));
        assertThat(w.get("VIK")).isCloseTo(1.0 / 15, within(TOL));
        assertThat(w.get("COA")).isCloseTo(1.0 / 30, within(TOL));
        assertThat(w.get("BRI")).isCloseTo(3.0 / 20, within(TOL));
        assertThat(w.get("FIS")).isCloseTo(1.0 / 10, within(TOL));
        assertThat(w.get("BRK")).isCloseTo(1.0 / 20, within(TOL));
        assertThat(sum(w)).isCloseTo(1.0, within(TOL));

        // g = 0.7: Duquesne = 0.7 × 1/3 + 0.3 × 0.4.
        Map<String, Double> tilted = Signal13F.blendWeights(
                List.of("DUQ", "APP", "GRN", "VIK", "COA"), List.of("DUQ", "BRI", "FIS", "BRK"), 0.7);
        assertThat(tilted.get("DUQ")).isCloseTo(0.7 / 3 + 0.3 * 0.4, within(TOL));
        assertThat(tilted.get("BRK")).isCloseTo(0.3 * 0.1, within(TOL));
        assertThat(sum(tilted)).isCloseTo(1.0, within(TOL));
    }

    @Test
    @DisplayName("한 그룹이 모자라면 있는 순위로만 정규화(A 3곳: 3/6·2/6·1/6) — 그 그룹 몫 0.5가 줄지 않는다")
    void shortGroupNormalizesOverWhatExists() {
        Map<String, Double> w = Signal13F.blendWeights(List.of("a1", "a2", "a3"), List.of("b1", "b2", "b3", "b4"),
                0.5);

        assertThat(w.get("a1")).isCloseTo(0.25, within(TOL));
        assertThat(w.get("a2")).isCloseTo(1.0 / 6, within(TOL));
        assertThat(w.get("a3")).isCloseTo(1.0 / 12, within(TOL));
        assertThat(w.get("b1")).isCloseTo(0.2, within(TOL));
        assertThat(w.get("b4")).isCloseTo(0.05, within(TOL));
        assertThat(w.get("a1") + w.get("a2") + w.get("a3")).isCloseTo(0.5, within(TOL));
        assertThat(sum(w)).isCloseTo(1.0, within(TOL));
    }

    @Test
    @DisplayName("한 그룹이 비면 다른 그룹이 1을 다 받고(g와 무관), 둘 다 비면 빈 맵")
    void emptyGroupGivesAllToOther() {
        Map<String, Double> onlyB = Signal13F.blendWeights(List.of(), List.of("b1", "b2", "b3", "b4"), 0.5);
        assertThat(onlyB.keySet()).containsExactly("b1", "b2", "b3", "b4");
        assertThat(onlyB.values()).containsExactly(0.4, 0.3, 0.2, 0.1);

        Map<String, Double> onlyA = Signal13F.blendWeights(List.of("a1", "a2"), null, 0.2);
        assertThat(onlyA.get("a1")).isCloseTo(2.0 / 3, within(TOL));
        assertThat(onlyA.get("a2")).isCloseTo(1.0 / 3, within(TOL));

        assertThat(Signal13F.blendWeights(List.of(), List.of(), 0.5)).isEmpty();
        assertThat(Signal13F.blendWeights(null, null, 0.5)).isEmpty();
    }

    @Test
    @DisplayName("g = 1이면 그룹 B에만 있는 기관은 가중 0으로 남는다(풀에는 있음), 겹친 기관은 A 몫만")
    void groupWeightAtExtremes() {
        Map<String, Double> w = Signal13F.blendWeights(List.of("x", "y"), List.of("y", "z"), 1.0);

        assertThat(w.keySet()).containsExactly("x", "y", "z");
        assertThat(w.get("x")).isCloseTo(2.0 / 3, within(TOL));
        assertThat(w.get("y")).isCloseTo(1.0 / 3, within(TOL));
        assertThat(w.get("z")).isEqualTo(0.0);
        assertThat(sum(w)).isCloseTo(1.0, within(TOL));
    }

    @Test
    @DisplayName("그룹 안의 null은 건너뛰고 같은 CIK가 두 번이면 앞 순위만 — 순위 수(n)도 실제 기관 수")
    void blendIgnoresNullsAndRepeats() {
        Map<String, Double> w = Signal13F.blendWeights(Arrays.asList("x", null, "x", "y"), List.of(), 0.5);

        assertThat(w.keySet()).containsExactly("x", "y");
        assertThat(w.get("x")).isCloseTo(2.0 / 3, within(TOL));
        assertThat(w.get("y")).isCloseTo(1.0 / 3, within(TOL));

        assertThatThrownBy(() -> Signal13F.blendWeights(List.of("x"), List.of(), -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.blendWeights(List.of("x"), List.of(), 1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.blendWeights(List.of("x"), List.of(), Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double sum(Map<String, Double> weights) {
        return weights.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    // ── 이벤트 ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("NEW·+30%(경계 포함)·+29%(이벤트 없음)·CLOSED — 비중은 그 기관 13F 총액 기준")
    void classifiesNewAddedClosed() {
        List<Row> previous = List.of(
                row("AAA", 1000, 100.0),
                row("BBB", 1000, 100.0),
                row("CCC", 1000, 100.0),
                row("DDD", 2000, 50.0),
                row("ZZZ", 0, 10.0),            // 평가액 0 = 보유 아님 → 이번에 사면 NEW
                row(null, 1000, 10.0));         // 티커 없는 줄: 신호는 없고 직전 분기 합(6,000)에만 들어감
        List<Row> current = List.of(
                row("AAA", 1300, 130.0),        // +30% 정확히 → ADDED
                row("BBB", 1290, 129.0),        // +29% → 이벤트 없음
                row("CCC", 900, 90.0),          // 축소 → 이벤트 없음
                row("EEE", 500, 5.0),
                row("ZZZ", 200, 20.0));
        Signals signals = collect(List.of(filer("c1", 1.0, 10_000, current, previous)), 2);

        assertThat(signals.events()).extracting(Event::ticker).containsExactly("AAA", "DDD", "EEE", "ZZZ");

        Event added = only(signals, "AAA");
        assertThat(added.action()).isEqualTo(Signal13F.ADDED);
        assertThat(added.sharesChangePct()).isCloseTo(30.0, within(TOL));
        assertThat(added.weightPct()).isCloseTo(13.0, within(TOL));
        assertThat(added.previousWeightPct()).isCloseTo(1000.0 / 6000 * 100, within(TOL));
        assertThat(added.cik()).isEqualTo("c1");

        Event closed = only(signals, "DDD");
        assertThat(closed.action()).isEqualTo(Signal13F.CLOSED);
        assertThat(closed.sharesChangePct()).isEqualTo(-100.0);
        assertThat(closed.weightPct()).isEqualTo(0.0);
        assertThat(closed.previousWeightPct()).isCloseTo(2000.0 / 6000 * 100, within(TOL));

        Event fresh = only(signals, "EEE");
        assertThat(fresh.action()).isEqualTo(Signal13F.NEW);
        assertThat(fresh.sharesChangePct()).isNull();
        assertThat(fresh.previousWeightPct()).isNull();
        assertThat(fresh.weightPct()).isCloseTo(5.0, within(TOL));
        assertThat(only(signals, "ZZZ").action()).isEqualTo(Signal13F.NEW);

        assertThat(signals.closedBy()).containsOnlyKeys("DDD");
        assertThat(signals.closedBy().get("DDD")).containsExactly("c1");
        assertThat(signals.notes()).isEmpty();
    }

    @Test
    @DisplayName("정확히 기준만큼 늘었는데 나눗셈 끝자리로 기준보다 작게 계산돼도 확대로 본다(125→161주, 기준 28.8%)")
    void exactThresholdSurvivesRounding() {
        // (161−125)/125 = 0.288이지만 double로는 28.8/100보다 아주 조금 작게 나옵니다.
        assertThat((161.0 - 125.0) / 125.0).isLessThan(28.8 / 100.0);
        FilerQuarter f = filer("c1", 1.0, 1000, List.of(row("AAA", 161, 161.0)), List.of(row("AAA", 125, 125.0)));

        assertThat(only(Signal13F.collect(List.of(f), 1, 28.8, CAP), "AAA").action()).isEqualTo(Signal13F.ADDED);
        assertThat(Signal13F.collect(List.of(f), 1, 28.9, CAP).events()).isEmpty();
    }

    @Test
    @DisplayName("이번 분기 평가액 0인 줄은 보유가 아니다 — 0% 비중 NEW를 만들지 않고, 직전 보유였으면 CLOSED")
    void zeroValueCurrentIsNotHeld() {
        FilerQuarter f = filer("c1", 1.0, 1000,
                List.of(row("AAA", 0, 10.0), row("BBB", 0, 5.0)),
                List.of(row("BBB", 100, 5.0)));
        Signals signals = collect(List.of(f), 1);

        assertThat(hasEvent(signals, "AAA")).isFalse();
        assertThat(only(signals, "BBB").action()).isEqualTo(Signal13F.CLOSED);
    }

    @Test
    @DisplayName("직전 분기 공시가 없는 기관은 이벤트 없이 메모만 — 빈 직전 분기(공시는 있음)와 구분")
    void missingPreviousQuarter() {
        FilerQuarter noPrevious = filer("c2", 2, null, 0.3, 100, List.of(row("AAA", 50, 10.0)), null);
        FilerQuarter emptyPrevious = filer("c3", null, 1, 0.2, 100, List.of(row("AAA", 50, 10.0)), List.of());
        Signals signals = collect(List.of(noPrevious, emptyPrevious), 1);

        assertThat(signals.events()).extracting(Event::cik).containsExactly("c3");
        assertThat(signals.notes()).containsExactly("c2(A2): 직전 분기 공시 없음");
        // c2의 보유를 신규로 세지 않았으므로 매수 기관은 c3 하나이고, 점수도 c3 몫(0.2 × 확신도 1)뿐입니다.
        assertThat(signals.buyCandidates()).singleElement().satisfies(c -> {
            assertThat(c.buyerCiks()).containsExactly("c3");
            assertThat(c.score()).isCloseTo(0.2, within(TOL));
        });
    }

    @Test
    @DisplayName("같은 티커 여러 줄(클래스 A·C가 같은 티커로 매핑)은 평가액·주식 수를 더해 한 종목으로 본다")
    void mergesRowsOfSameTicker() {
        // 합치지 않으면 100주→100주(0%)와 100주→40주로 읽혀 확대가 보이지 않습니다. 합치면 100 → 140주(+40%).
        FilerQuarter f = filer("c1", 1.0, 10_000,
                List.of(row("GOOGL", 1100, 100.0), row(" GOOGL ", 440, 40.0)),
                List.of(row("GOOGL", 1000, 100.0)));
        Event event = only(collect(List.of(f), 1), "GOOGL");

        assertThat(event.action()).isEqualTo(Signal13F.ADDED);
        assertThat(event.sharesChangePct()).isCloseTo(40.0, within(TOL));
        assertThat(event.weightPct()).isCloseTo(15.4, within(TOL));
    }

    @Test
    @DisplayName("합친 줄 중 하나라도 주식 수를 모르면 확대를 판정하지 않는다(0으로 메우지 않음)")
    void unknownSharesBlockAdded() {
        FilerQuarter oneUnknown = filer("c1", 0.5, 10_000,
                List.of(row("GOOGL", 1100, 100.0), row("GOOGL", 900, null)),
                List.of(row("GOOGL", 1000, 100.0)));
        FilerQuarter previousUnknown = filer("c2", 0.5, 10_000,
                List.of(row("MSFT", 3000, 300.0)),
                List.of(row("MSFT", 1000, null)));
        Signals signals = collect(List.of(oneUnknown, previousUnknown), 1);

        assertThat(signals.events()).isEmpty();
        assertThat(signals.buyCandidates()).isEmpty();
    }

    // ── 주식 분할 ───────────────────────────────────────────────────────────────────────────

    /** 직전 보고일(03-31)과 이번 보고일(06-30)의 수정 종가. 다른 티커·날짜는 모름(null). */
    private static Signal13F.AdjustedCloses closes(String ticker, double previousClose, double currentClose) {
        LocalDate previousReport = LocalDate.of(2026, 3, 31);
        return (t, date) -> !t.equals(ticker) ? null
                : date.equals(previousReport) ? Double.valueOf(previousClose)
                : date.equals(REPORT) ? Double.valueOf(currentClose) : null;
    }

    @Test
    @DisplayName("두 분기 사이 2:1 분할 — 손대지 않은 보유의 주식 수 두 배를 확대로 읽지 않는다(가짜 동시 매수 없음)")
    void splitIsNotAnAdd() {
        // 평가액·비중은 그대로인데 주식 수만 10→20, 20→40. 수정주가는 두 날 모두 5(분할을 과거로 맞춘 값).
        List<FilerQuarter> filers = List.of(
                filer("A", 0.6, 1000, List.of(row("XYZ", 100, 20.0), row("OTH", 900, 10.0)),
                        List.of(row("XYZ", 100, 10.0), row("OTH", 900, 10.0))),
                filer("B", 0.4, 1000, List.of(row("XYZ", 50, 40.0), row("OTH", 950, 10.0)),
                        List.of(row("XYZ", 50, 20.0), row("OTH", 950, 10.0))));

        Signals adjusted = Signal13F.collect(filers, 2, ADD, CAP, closes("XYZ", 5, 5));
        assertThat(adjusted.events()).as("분할만 있었음").isEmpty();
        assertThat(adjusted.buyCandidates()).isEmpty();
        assertThat(adjusted.notes()).containsExactly(
                "XYZ 2:1 분할 — 직전 분기 주식 수를 분할 뒤 기준으로 맞춰 확대를 판정(기관 2곳)");

        // 수정주가를 모르면 보정하지 않습니다(예전과 같은 대조 — 이 경우가 보정이 막는 가짜 신호).
        Signals unadjusted = Signal13F.collect(filers, 2, ADD, CAP, (t, d) -> null);
        assertThat(unadjusted.buyCandidates()).extracting(Candidate::ticker).containsExactly("XYZ");
        assertThat(collect(filers, 2).buyCandidates()).extracting(Candidate::ticker).containsExactly("XYZ");
    }

    @Test
    @DisplayName("분할 종목의 진짜 확대는 분할 뒤 기준으로 센다 — 2:1 분할 뒤 10→30주는 +50%, 1:10 병합 뒤 1000→130주는 +30%")
    void addAcrossSplitIsMeasuredAfterSplit() {
        // 2:1 분할 + 실제로 1.5배 늘림: 1주당 13F 가격 10 → 5.5(시장 +10%), 수정주가 5 → 5.5.
        FilerQuarter split = filer("A", 1.0, 1000, List.of(row("XYZ", 165, 30.0)), List.of(row("XYZ", 100, 10.0)));
        Event added = only(Signal13F.collect(List.of(split), 1, ADD, CAP, closes("XYZ", 5, 5.5)), "XYZ");
        assertThat(added.action()).isEqualTo(Signal13F.ADDED);
        assertThat(added.sharesChangePct()).isCloseTo(50.0, within(TOL));

        // 1:10 병합 + 1.3배: 1주당 13F 가격 1 → 10, 수정주가는 10 → 10. 보정하지 않으면 −87%라 이벤트가 없습니다.
        FilerQuarter reverse = filer("A", 1.0, 10_000, List.of(row("XYZ", 1300, 130.0)),
                List.of(row("XYZ", 1000, 1000.0)));
        Event merged = only(Signal13F.collect(List.of(reverse), 1, ADD, CAP, closes("XYZ", 10, 10)), "XYZ");
        assertThat(merged.action()).isEqualTo(Signal13F.ADDED);
        assertThat(merged.sharesChangePct()).isCloseTo(30.0, within(TOL));
        assertThat(collect(List.of(reverse), 1).events()).isEmpty();
    }

    @Test
    @DisplayName("분할이 아닌 가격 변화(±10% 안의 배당 조정, 13F 가격과 수정주가가 같이 움직임)는 보정하지 않는다")
    void priceMoveIsNotASplit() {
        // 주가가 40% 오르고 주식 수를 두 배로: 13F 1주당 10 → 14, 수정주가 9.8 → 14(배당으로 과거가 2% 낮게 다시 쓰임).
        FilerQuarter f = filer("A", 1.0, 1000, List.of(row("XYZ", 280, 20.0)), List.of(row("XYZ", 100, 10.0)));
        Signals signals = Signal13F.collect(List.of(f), 1, ADD, CAP, closes("XYZ", 9.8, 14));

        assertThat(only(signals, "XYZ").sharesChangePct()).isCloseTo(100.0, within(TOL));
        assertThat(signals.notes()).isEmpty();
        assertThat(Signal13F.splitBetween(100, 10.0, 280, 20.0, null, 14.0)).isEmpty();
        assertThat(Signal13F.splitBetween(100, null, 280, 20.0, 9.8, 14.0)).isEmpty();
        assertThat(Signal13F.splitBetween(100, 10.0, 100, 20.0, 5.0, 5.0)).hasValueSatisfying(r ->
                assertThat(r.label()).isEqualTo("2:1"));
    }

    // ── 확신도·점수·정렬 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("확신도 = min(기관 내 비중, 상한)/상한 — 상한 이상은 1, 상한을 바꾸면 비율이 바뀐다(점수 = 가중 × 확신도)")
    void convictionIsCapped() {
        // 총액 100이라 값이 곧 비중: AAA 25%, CCC 10%(상한과 같음), BBB 4%.
        FilerQuarter f = newBuyer("f", 0.5, "AAA", 25, "BBB", 4, "CCC", 10);

        Signals capTen = Signal13F.collect(List.of(f), 1, ADD, 10);
        assertThat(capTen.buyCandidates()).extracting(Candidate::ticker).containsExactly("AAA", "CCC", "BBB");
        assertThat(candidateOf(capTen, "AAA").contributions()).singleElement().satisfies(c -> {
            assertThat(c.cik()).isEqualTo("f");
            assertThat(c.filerWeight()).isEqualTo(0.5);
            assertThat(c.conviction()).isEqualTo(1.0);
            assertThat(c.points()).isCloseTo(0.5, within(TOL));
        });
        assertThat(candidateOf(capTen, "AAA").score()).isCloseTo(0.5, within(TOL));
        // 점수에는 상한을 거친 값만, 합산 비중은 원래 비중 그대로(참고).
        assertThat(candidateOf(capTen, "AAA").combinedWeightPct()).isCloseTo(25.0, within(TOL));
        assertThat(candidateOf(capTen, "CCC").contributions().get(0).conviction()).isCloseTo(1.0, within(TOL));
        assertThat(candidateOf(capTen, "BBB").contributions().get(0).conviction()).isCloseTo(0.4, within(TOL));
        assertThat(candidateOf(capTen, "BBB").score()).isCloseTo(0.2, within(TOL));

        Signals capTwenty = Signal13F.collect(List.of(f), 1, ADD, 20);
        assertThat(candidateOf(capTwenty, "AAA").contributions().get(0).conviction()).isEqualTo(1.0);
        assertThat(candidateOf(capTwenty, "CCC").contributions().get(0).conviction()).isCloseTo(0.5, within(TOL));
        assertThat(candidateOf(capTwenty, "BBB").score()).isCloseTo(0.5 * 0.2, within(TOL));

        Signals capFifty = Signal13F.collect(List.of(f), 1, ADD, 50);
        assertThat(candidateOf(capFifty, "AAA").score()).isCloseTo(0.5 * 0.5, within(TOL));
    }

    @Test
    @DisplayName("점수 손계산 — 두 기관(가중 0.6·0.4)·세 종목: AAA 0.72, BBB 0.70, CCC 0.44")
    void scoresHandComputed() {
        // F1(총액 1,000): AAA 신규 12% → 확신도 1, BBB 신규 5% → 0.5, CCC 100→150주(+50%) 2% → 0.2.
        FilerQuarter f1 = filer("F1", 0.6, 1000,
                List.of(row("AAA", 120, 12.0), row("BBB", 50, 5.0), row("CCC", 20, 150.0)),
                List.of(row("CCC", 10, 100.0)));
        // F2(총액 1,000): AAA 신규 3% → 0.3, BBB 신규 10% → 1, CCC 100→200주(+100%) 8% → 0.8.
        FilerQuarter f2 = filer("F2", 0.4, 1000,
                List.of(row("AAA", 30, 3.0), row("BBB", 100, 10.0), row("CCC", 80, 200.0)),
                List.of(row("CCC", 50, 100.0)));
        Signals signals = collect(List.of(f2, f1), 2);

        // AAA = 0.6×1 + 0.4×0.3, BBB = 0.6×0.5 + 0.4×1, CCC = 0.6×0.2 + 0.4×0.8.
        assertThat(signals.buyCandidates()).extracting(Candidate::ticker).containsExactly("AAA", "BBB", "CCC");
        assertThat(candidateOf(signals, "AAA").score()).isCloseTo(0.72, within(TOL));
        assertThat(candidateOf(signals, "BBB").score()).isCloseTo(0.70, within(TOL));
        assertThat(candidateOf(signals, "CCC").score()).isCloseTo(0.44, within(TOL));

        // 기여는 기관 가중 ↓ 순(입력 순서가 아니라) — buyerCiks·events와 같은 자리.
        Candidate aaa = candidateOf(signals, "AAA");
        assertThat(aaa.buyerCiks()).containsExactly("F1", "F2");
        assertThat(aaa.events()).extracting(Event::cik).containsExactly("F1", "F2");
        assertThat(aaa.contributions()).extracting(Contribution::cik).containsExactly("F1", "F2");
        assertThat(aaa.contributions().get(0).points()).isCloseTo(0.6, within(TOL));
        assertThat(aaa.contributions().get(1).conviction()).isCloseTo(0.3, within(TOL));
        assertThat(aaa.contributions().get(1).points()).isCloseTo(0.12, within(TOL));

        Candidate ccc = candidateOf(signals, "CCC");
        assertThat(ccc.events()).extracting(Event::action).containsExactly(Signal13F.ADDED, Signal13F.ADDED);
        assertThat(ccc.events()).extracting(Event::sharesChangePct).containsExactly(50.0, 100.0);
        assertThat(ccc.contributions()).extracting(Contribution::conviction)
                .satisfiesExactly(c -> assertThat(c).isCloseTo(0.2, within(TOL)),
                        c -> assertThat(c).isCloseTo(0.8, within(TOL)));
        assertThat(ccc.combinedWeightPct()).isCloseTo(10.0, within(TOL));
        assertThat(signals.events().get(0).cik()).isEqualTo("F1");
    }

    @Test
    @DisplayName("후보 정렬: 점수 ↓ → 기관 수 ↓ → 티커 ↑ — 같은 점수(1위+5위 = 2위+4위)가 끝자리 잡음으로 뒤집히지 않는다")
    void candidateOrdering() {
        // 그룹 A 5곳 × 0.5(그룹 B 1곳은 매수 없음): a1 1/6, a2 2/15, a3 1/10, a4 1/15, a5 1/30.
        Map<String, Double> w = Signal13F.blendWeights(List.of("a1", "a2", "a3", "a4", "a5"), List.of("b1"), 0.5);
        List<FilerQuarter> filers = List.of(
                newBuyer("a5", w.get("a5"), "DDD", 10, "BBB", 10, "AAA", 5),
                newBuyer("a3", w.get("a3"), "DDD", 10, "AAA", 5),
                newBuyer("a1", w.get("a1"), "ZZZ", 10, "BBB", 10),
                newBuyer("a4", w.get("a4"), "DDD", 10, "CCC", 10, "AAA", 5),
                newBuyer("a2", w.get("a2"), "ZZZ", 10, "CCC", 10));
        Signals signals = collect(filers, 2);

        // ZZZ = a1+a2 = 0.3. DDD = a3+a4+a5 = 0.2(3곳). BBB = a1+a5, CCC = a2+a4 = 0.2(2곳) — 티커 순.
        // AAA = (a3+a4+a5) × 확신도 0.5 = 0.1 — 3곳이어도 점수가 먼저.
        assertThat(signals.buyCandidates()).extracting(Candidate::ticker)
                .containsExactly("ZZZ", "DDD", "BBB", "CCC", "AAA");
        // 수학적으로 같은 0.2인데 double로는 BBB가 아주 조금 작습니다 — 그대로 비교하면 CCC가 BBB를 앞질렀을 것.
        assertThat(candidateOf(signals, "BBB").score()).isLessThan(candidateOf(signals, "CCC").score());
        assertThat(candidateOf(signals, "BBB").score()).isCloseTo(0.2, within(TOL));
        assertThat(candidateOf(signals, "DDD").buyerCiks()).containsExactly("a3", "a4", "a5");
        assertThat(candidateOf(signals, "AAA").score()).isCloseTo(0.1, within(TOL));
        // 이벤트도 가중 순(입력 순서가 아니라)으로 나옵니다.
        assertThat(signals.events().get(0).cik()).isEqualTo("a1");
    }

    @Test
    @DisplayName("매수 후보 = NEW·ADDED한 서로 다른 기관 수 ≥ minHolders (CLOSED는 세지 않음)")
    void minHolders() {
        FilerQuarter c1 = filer("c1", 0.5, 100, List.of(row("AAA", 10, 10.0), row("BBB", 5, 5.0)), List.of());
        FilerQuarter c2 = filer("c2", 0.3, 100, List.of(row("AAA", 20, 150.0)), List.of(row("AAA", 10, 100.0)));
        FilerQuarter c3 = filer("c3", 0.2, 100, List.of(), List.of(row("BBB", 10, 10.0)));

        Signals two = collect(List.of(c3, c2, c1), 2);
        assertThat(two.buyCandidates()).singleElement().satisfies(c -> {
            assertThat(c.ticker()).isEqualTo("AAA");
            assertThat(c.buyerCiks()).containsExactly("c1", "c2");
            assertThat(c.score()).isCloseTo(0.5 * 1 + 0.3 * 1, within(TOL));
            assertThat(c.combinedWeightPct()).isCloseTo(30.0, within(TOL));
            assertThat(c.events()).extracting(Event::action).containsExactly(Signal13F.NEW, Signal13F.ADDED);
        });
        assertThat(two.closedBy()).containsOnlyKeys("BBB");

        assertThat(collect(List.of(c1, c2, c3), 1).buyCandidates())
                .extracting(Candidate::ticker).containsExactly("AAA", "BBB");
        assertThat(collect(List.of(c1, c2, c3), 3).buyCandidates()).isEmpty();
    }

    @Test
    @DisplayName("가중 0인 기관(g = 1의 그룹 B 전용)도 매수 기관 수에는 들지만 점수에는 0을 더한다")
    void zeroWeightFilerVotesWithoutPoints() {
        FilerQuarter a = newBuyer("a", 1.0, "AAA", 10);
        FilerQuarter b = newBuyer("b", 0.0, "AAA", 10);
        Signals signals = collect(List.of(a, b), 2);

        assertThat(signals.buyCandidates()).singleElement().satisfies(c -> {
            assertThat(c.buyerCiks()).containsExactly("a", "b");
            assertThat(c.score()).isCloseTo(1.0, within(TOL));
            assertThat(c.contributions().get(1).points()).isEqualTo(0.0);
        });
    }

    @Test
    @DisplayName("closedBy: 티커 → CLOSED한 기관(가중 순), 기관 수와 상관없이 모두")
    void closedBy() {
        FilerQuarter c3 = filer("c3", 0.1, 100, List.of(), List.of(row("XXX", 10, 1.0), row("YYY", 10, 1.0)));
        FilerQuarter c1 = filer("c1", 0.4, 100, List.of(), List.of(row("XXX", 10, 1.0)));
        Signals signals = collect(List.of(c3, c1), 2);

        assertThat(signals.closedBy().keySet()).containsExactly("XXX", "YYY");
        assertThat(signals.closedBy().get("XXX")).containsExactly("c1", "c3");
        assertThat(signals.closedBy().get("YYY")).containsExactly("c3");
        assertThat(signals.buyCandidates()).isEmpty();
    }

    @Test
    @DisplayName("같은 CIK가 두 번 오면 가중이 큰 것만 쓴다 — 한 기관이 두 표를 내지 않게")
    void duplicateFilerCountsOnce() {
        FilerQuarter first = filer("c1", 1, null, 0.4, 100, List.of(row("AAA", 10, 1.0)), List.of());
        FilerQuarter again = filer("c1", null, 4, 0.1, 100, List.of(row("AAA", 20, 1.0)), List.of());
        Signals signals = collect(List.of(again, first), 2);

        assertThat(signals.buyCandidates()).isEmpty();
        assertThat(signals.events()).singleElement().satisfies(e -> assertThat(e.weightPct()).isEqualTo(10.0));
        assertThat(signals.notes()).containsExactly("c1(B4): 같은 기관이 두 번 들어와 뒤의 것은 쓰지 않음");
    }

    @Test
    @DisplayName("입력 검증과 빈 입력")
    void validation() {
        assertThatThrownBy(() -> collect(List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.collect(List.of(), 2, 0, CAP)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.collect(List.of(), 2, Double.NaN, CAP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.collect(List.of(), 2, ADD, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.collect(List.of(), 2, ADD, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.collect(List.of(), 2, ADD, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        // 가중을 모르는 기관을 임의 값으로 넣지 않게 — 음수·NaN·무한대는 만들 수 없습니다.
        assertThatThrownBy(() -> filer("c1", -0.1, 100, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> filer("c1", Double.NaN, 100, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> filer("c1", Double.POSITIVE_INFINITY, 100, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        Signals empty = Signal13F.collect(null, 2, ADD, CAP);
        assertThat(empty.events()).isEmpty();
        assertThat(empty.buyCandidates()).isEmpty();
        assertThat(empty.closedBy()).isEmpty();

        Set<String> u = Set.of();
        assertThatThrownBy(() -> Signal13F.allocate(List.of(), 0, 10, 20, null, u, null, Mode.CONVICTION, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.allocate(List.of(), 10, 0, 20, null, u, null, Mode.CONVICTION, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.allocate(List.of(), 10, 101, 20, null, u, null, Mode.CONVICTION, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.allocate(List.of(), 10, 10, 0, null, u, null, Mode.CONVICTION, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.allocate(List.of(), 10, 10, 101, null, u, null, Mode.CONVICTION, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.allocate(List.of(), 10, 10, 20, null, u, null, Mode.CONVICTION, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.allocate(List.of(), 10, 10, 20, null, u, null, Mode.CONVICTION,
                Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.allocate(List.of(), 10, 10, 20, null, null, null, Mode.CONVICTION, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signal13F.allocate(List.of(), 10, 10, 20, null, u, null, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 배분 ────────────────────────────────────────────────────────────────────────────────

    /** 점수만 다른 후보 — c1(가중 0.3) 신규 확신도 1 + c3(가중 0.1) +35% 확신도 0.5. 배분은 score만 봅니다. */
    private static Candidate candidate(String ticker, double score) {
        Event a = new Event("c1", ticker, Signal13F.NEW, null, 12.0, null);
        Event b = new Event("c3", ticker, Signal13F.ADDED, 35.0, 5.0, 3.0);
        return new Candidate(ticker, List.of("c1", "c3"), score,
                List.of(new Contribution("c1", 0.3, 1.0, 0.3), new Contribution("c3", 0.1, 0.5, 0.05)), 17.0,
                List.of(a, b));
    }

    private static List<Candidate> candidates(double score, String... tickers) {
        return Arrays.stream(tickers).map(t -> candidate(t, score)).toList();
    }

    private static Plan conviction(List<Candidate> sorted, int maxNames, Set<String> universe) {
        return Signal13F.allocate(sorted, maxNames, 10, 20, Set.of("XLK"), universe, Set.of(), Mode.CONVICTION, 1.0);
    }

    private static Map<String, Double> targets(Plan plan) {
        Map<String, Double> out = new LinkedHashMap<>();
        plan.allocations().forEach(a -> out.put(a.ticker(), a.targetWeightPct()));
        return out;
    }

    @Test
    @DisplayName("확신도 비례: 최고 점수 = 상한, 나머지는 상한 × 점수/최고, ETF는 ETF 상한, 1% 미만은 '확신도 낮음'")
    void convictionProportional() {
        List<Candidate> sorted = List.of(candidate("TSM", 0.9), candidate("AAA", 0.5), candidate("BBB", 0.25),
                candidate("XLK", 0.2), candidate("CCC", 0.06), candidate("DDD", 0.05), candidate("EEE", 0.04));
        Plan plan = conviction(sorted, 10, Set.of("AAA", "BBB", "XLK", "CCC", "DDD", "EEE"));

        // 최고 점수는 고른 종목 중 최고(AAA 0.5) — 유니버스 밖 TSM(0.9)을 기준으로 삼으면 아무도 상한을 받지 못합니다.
        Map<String, Double> t = targets(plan);
        assertThat(t.keySet()).containsExactly("AAA", "BBB", "XLK", "CCC", "DDD");
        assertThat(t.get("AAA")).isCloseTo(10.0, within(TOL));
        assertThat(t.get("BBB")).isCloseTo(5.0, within(TOL));
        assertThat(t.get("XLK")).isCloseTo(20.0 * 0.2 / 0.5, within(TOL));   // 8 — ETF 상한 20%
        assertThat(t.get("CCC")).isCloseTo(1.2, within(TOL));
        assertThat(t.get("DDD")).isCloseTo(1.0, within(TOL));                 // 정확히 1%는 남김
        assertThat(plan.skipped()).containsExactly(
                Map.entry("TSM", Signal13F.SKIP_OUTSIDE_UNIVERSE),
                Map.entry("EEE", Signal13F.SKIP_LOW_CONVICTION));             // 0.8%
        // 합이 100% 미만 — 남는 현금은 서비스가 SHY로.
        assertThat(plan.allocations().stream().mapToDouble(Allocation::targetWeightPct).sum())
                .isCloseTo(25.2, within(TOL));
    }

    @Test
    @DisplayName("목표가 수학적으로 정확히 1%인데 끝자리로 0.9999999999999999가 돼도 빼지 않는다(10 × 0.09 / 0.9)")
    void minTargetBoundarySurvivesRounding() {
        assertThat(10 * 0.09 / 0.9).isLessThan(1.0);
        Plan plan = conviction(List.of(candidate("AAA", 0.9), candidate("BBB", 0.09)), 10, Set.of("AAA", "BBB"));

        assertThat(targets(plan).get("BBB")).isCloseTo(1.0, within(TOL));
        assertThat(plan.skipped()).isEmpty();
    }

    @Test
    @DisplayName("'확신도 낮음'은 상위 N 자리를 차지한 채 빠진다 — 그 뒤(점수가 더 낮은) 후보가 대신 올라오지 않음")
    void lowConvictionKeepsItsSlot() {
        // XLK는 ETF 상한(20%)이라 목표가 1.2%로 1%를 넘지만, 점수 순으로 상위 2 밖입니다.
        List<Candidate> sorted = List.of(candidate("AAA", 0.5), candidate("BBB", 0.04), candidate("XLK", 0.03));
        Plan plan = conviction(sorted, 2, Set.of("AAA", "BBB", "XLK"));

        assertThat(plan.allocations()).extracting(Allocation::ticker).containsExactly("AAA");
        assertThat(plan.skipped()).containsExactly(
                Map.entry("BBB", Signal13F.SKIP_LOW_CONVICTION),
                Map.entry("XLK", "상위 2 밖"));
    }

    @Test
    @DisplayName("점수가 모두 0이면 CONVICTION은 전부 '확신도 낮음'(0으로 나누지 않음), EQUAL은 그대로 산다")
    void zeroScores() {
        List<Candidate> sorted = candidates(0.0, "AAA", "BBB");
        Set<String> universe = Set.of("AAA", "BBB");

        Plan byConviction = Signal13F.allocate(sorted, 10, 10, 20, Set.of(), universe, Set.of(), Mode.CONVICTION, 0);
        assertThat(byConviction.allocations()).isEmpty();
        assertThat(byConviction.skipped()).containsExactly(
                Map.entry("AAA", Signal13F.SKIP_LOW_CONVICTION), Map.entry("BBB", Signal13F.SKIP_LOW_CONVICTION));

        Plan equal = Signal13F.allocate(sorted, 10, 10, 20, Set.of(), universe, Set.of(), Mode.EQUAL, 1.0);
        assertThat(targets(equal)).containsExactly(Map.entry("AAA", 10.0), Map.entry("BBB", 10.0));
    }

    @Test
    @DisplayName("EQUAL: 목표 = min(100/maxNames, 상한) — 점수와 무관, ETF는 ETF 상한, 확신도 낮음 없음")
    void equalWeightWithCap() {
        List<Candidate> twelve = new ArrayList<>(candidates(0.5, "T01", "T02", "T03", "T04", "T05", "T06", "T07",
                "T08", "T09", "T10", "T11"));
        twelve.add(candidate("XLK", 0.001));
        Set<String> universe = Set.of("T01", "T02", "T03", "T04", "T05", "T06", "T07", "T08", "T09", "T10", "T11",
                "XLK");

        Plan ten = Signal13F.allocate(twelve, 10, 10, 20, Set.of("XLK"), universe, Set.of(), Mode.EQUAL, 1.0);
        assertThat(ten.allocations()).hasSize(10).allSatisfy(a -> assertThat(a.targetWeightPct()).isEqualTo(10.0));
        assertThat(ten.skipped()).containsExactly(Map.entry("T11", "상위 10 밖"), Map.entry("XLK", "상위 10 밖"));

        Plan twenty = Signal13F.allocate(twelve, 20, 10, 20, Set.of("XLK"), universe, Set.of(), Mode.EQUAL, 1.0);
        assertThat(twenty.allocations()).hasSize(12).allSatisfy(a -> assertThat(a.targetWeightPct()).isEqualTo(5.0));
        assertThat(twenty.skipped()).isEmpty();

        // maxNames 4: 개별 종목은 상한 10%, ETF는 min(25, 20) = 20% — 점수 0.001이어도 같은 규칙.
        List<Candidate> mixed = List.of(candidate("XLK", 0.001), candidate("T01", 0.5));
        Plan four = Signal13F.allocate(mixed, 4, 10, 20, Set.of("XLK"), universe, Set.of(), Mode.EQUAL, 1.0);
        assertThat(targets(four)).containsExactly(Map.entry("XLK", 20.0), Map.entry("T01", 10.0));

        // 후보가 상한보다 적으면 고른 것만큼만 — 60%는 현금(서비스가 SHY).
        Plan few = Signal13F.allocate(candidates(0.5, "T01", "T02", "T03", "T04"), 10, 10, 20, null, universe,
                null, Mode.EQUAL, 1.0);
        assertThat(few.allocations().stream().mapToDouble(Allocation::targetWeightPct).sum())
                .isCloseTo(40.0, within(TOL));
    }

    @Test
    @DisplayName("유니버스 밖·재진입 금지 후보는 건너뛰고 자리를 차지하지 않는다 — 다음 후보가 올라옴")
    void skipsOutsideUniverseAndLockedOut() {
        List<Candidate> sorted = List.of(candidate("AAA", 0.5), candidate("TSM", 0.45), candidate("BBB", 0.4),
                candidate("CCC", 0.3), candidate("DDD", 0.2), candidate("AAA", 0.1));
        Plan plan = conviction(sorted, 2, Set.of("AAA", "BBB", "CCC", "DDD"));
        Plan locked = Signal13F.allocate(sorted, 2, 10, 20, Set.of(), Set.of("AAA", "BBB", "CCC", "DDD"),
                Set.of("BBB"), Mode.CONVICTION, 1.0);

        assertThat(plan.allocations()).extracting(Allocation::ticker).containsExactly("AAA", "BBB");
        assertThat(targets(locked)).containsOnlyKeys("AAA", "CCC");
        assertThat(targets(locked).get("CCC")).isCloseTo(10.0 * 0.3 / 0.5, within(TOL));
        assertThat(locked.skipped()).containsExactly(
                Map.entry("TSM", Signal13F.SKIP_OUTSIDE_UNIVERSE),
                Map.entry("BBB", Signal13F.SKIP_LOCKED_OUT),
                Map.entry("DDD", "상위 2 밖"),
                Map.entry("AAA", Signal13F.SKIP_DUPLICATE));
        assertThat(Signal13F.allocate(sorted, 2, 10, 20, null, Set.of("AAA"), null, Mode.EQUAL, 1.0).allocations())
                .extracting(Allocation::ticker).containsExactly("AAA");
    }

    @Test
    @DisplayName("배분 사유에 점수와 기관마다 행동·가중·확신도가 남는다")
    void allocationReasonNamesBuyers() {
        Allocation allocation = conviction(List.of(candidate("AAA", 0.35)), 10, Set.of("AAA")).allocations().get(0);

        assertThat(allocation.reason()).isEqualTo(
                "2곳 동시 매수(점수 0.3500): c1 신규(가중 0.3000 × 확신도 1.00), c3 +35.0%(가중 0.1000 × 확신도 0.50)");
    }

    @Test
    @DisplayName("blendWeights → collect → allocate 한 줄기: 두 그룹 겹친 기관(DUQ)의 가중 2/3이 점수와 목표 비중에 들어간다")
    void endToEnd() {
        // A = [DUQ, APP], B = [DUQ, BRI], g = 0.5 → DUQ 0.5×2/3 + 0.5×2/3 = 2/3, APP 1/6, BRI 1/6.
        Map<String, Double> w = Signal13F.blendWeights(List.of("DUQ", "APP"), List.of("DUQ", "BRI"), 0.5);
        assertThat(w.get("DUQ")).isCloseTo(2.0 / 3, within(TOL));
        FilerQuarter duq = filer("DUQ", 1, 1, w.get("DUQ"), 100, List.of(row("NVDA", 4, 1.0)), List.of());
        FilerQuarter app = filer("APP", 2, null, w.get("APP"), 100, List.of(row("MSFT", 10, 1.0)), List.of());
        // BRI(총액 1,000): NVDA 100→130주(+30%) 5% → 확신도 0.5, MSFT 신규 10% → 1.
        FilerQuarter bri = filer("BRI", null, 2, w.get("BRI"), 1000,
                List.of(row("NVDA", 50, 130.0), row("MSFT", 100, 10.0)), List.of(row("NVDA", 30, 100.0)));
        Signals signals = collect(List.of(bri, app, duq), 2);

        // NVDA = 2/3 × 0.4 + 1/6 × 0.5 = 0.35, MSFT = 1/6 × 1 + 1/6 × 1 = 1/3.
        assertThat(signals.buyCandidates()).extracting(Candidate::ticker).containsExactly("NVDA", "MSFT");
        assertThat(candidateOf(signals, "NVDA").score()).isCloseTo(0.35, within(TOL));
        assertThat(candidateOf(signals, "MSFT").score()).isCloseTo(1.0 / 3, within(TOL));
        // 가중이 같은 APP·BRI는 CIK 순.
        assertThat(candidateOf(signals, "MSFT").buyerCiks()).containsExactly("APP", "BRI");

        Set<String> universe = Set.of("NVDA", "MSFT");
        Plan plan = Signal13F.allocate(signals.buyCandidates(), 10, 10, 20, Set.of(), universe, Set.of(),
                Mode.CONVICTION, 1.0);
        assertThat(plan.allocations()).extracting(Allocation::ticker).containsExactly("NVDA", "MSFT");
        assertThat(plan.allocations().get(0).targetWeightPct()).isCloseTo(10.0, within(TOL));
        assertThat(plan.allocations().get(1).targetWeightPct()).isCloseTo(10.0 * (1.0 / 3) / 0.35, within(TOL));
        assertThat(plan.allocations().get(0).reason()).isEqualTo(
                "2곳 동시 매수(점수 0.3500): DUQ 신규(가중 0.6667 × 확신도 0.40), BRI +30.0%(가중 0.1667 × 확신도 0.50)");

        Plan equal = Signal13F.allocate(signals.buyCandidates(), 10, 10, 20, Set.of(), universe, Set.of(),
                Mode.EQUAL, 1.0);
        assertThat(targets(equal)).containsExactly(Map.entry("NVDA", 10.0), Map.entry("MSFT", 10.0));
    }
}
