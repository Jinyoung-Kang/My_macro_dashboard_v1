package com.macrodash.analytics;

import com.macrodash.analytics.GateIndicator.Inputs;
import com.macrodash.analytics.GateIndicator.Result;
import com.macrodash.analytics.GateIndicator.Thresholds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateIndicatorTest {

    private static final Thresholds T = Thresholds.defaults();

    /** 여러 거래일을 차례로 평가하고 마지막 결과를 돌려줍니다(직전 streaks를 다음 날로 넘김). */
    private static Result run(Inputs... days) {
        Map<String, Integer> previous = null;
        Result result = null;
        for (Inputs day : days) {
            result = GateIndicator.evaluate(day, previous, T);
            previous = result.streaks();
        }
        return result;
    }

    @Test
    @DisplayName("기본 임계치는 OS 원문 값: 4.80 · 4.75 · 95 · 90 · 155 · 3일")
    void defaults() {
        assertThat(T).isEqualTo(new Thresholds(4.80, 4.75, 95, 90, 155, 3));
    }

    @Test
    @DisplayName("조건 경계: G1은 미만(4.80은 거짓), G2·G3·G4는 이하, G5는 이상")
    void conditionBoundaries() {
        Result atLimits = GateIndicator.evaluate(new Inputs(4.80, 95.0, 90.0, 155.0), null, T);
        assertThat(atLimits.conditions()).containsEntry("G1", false)
                .containsEntry("G2", false)
                .containsEntry("G3", true)
                .containsEntry("G4", true)
                .containsEntry("G5", true);

        Result full = GateIndicator.evaluate(new Inputs(4.75, 95.01, 90.01, 154.99), null, T);
        assertThat(full.conditions()).containsEntry("G1", true)
                .containsEntry("G2", true)
                .containsEntry("G3", false)
                .containsEntry("G4", false)
                .containsEntry("G5", false);
        assertThat(full.conditions()).containsOnlyKeys("G1", "G2", "G3", "G4", "G5");
    }

    @Test
    @DisplayName("연속 일수: 참이면 +1, 거짓이면 0으로 초기화")
    void streaksIncrementAndReset() {
        Map<String, Integer> previous = Map.of("G1", 2, "G2", 5, "G3", 0, "G4", 7, "G5", 1);

        Result r = GateIndicator.evaluate(new Inputs(4.78, 90.0, 91.0, 150.0), previous, T);

        assertThat(r.streaks()).containsEntry("G1", 3)    // 4.78 < 4.80 → 2 + 1
                .containsEntry("G2", 0)                   // 4.78 > 4.75 → 초기화
                .containsEntry("G3", 1)                   // 0 + 1
                .containsEntry("G4", 0)                   // 91 > 90 → 초기화
                .containsEntry("G5", 0);
    }

    @Test
    @DisplayName("L1: 10Y가 4.80 밑에서 3거래일 연속 — 2일째까지는 L0")
    void stageOne() {
        Inputs day = new Inputs(4.78, 90.0, 85.0, 150.0);   // G1 참, G2 거짓

        assertThat(run(day).level()).isEqualTo("L0");
        assertThat(run(day, day).level()).isEqualTo("L0");
        Result third = run(day, day, day);
        assertThat(third.level()).isEqualTo("L1");
        // G3·G4도 3일 연속이지만 G2가 0이라 L2가 아님
        assertThat(third.streaks()).containsEntry("G3", 3).containsEntry("G4", 3).containsEntry("G2", 0);
    }

    @Test
    @DisplayName("L2: G2·G3·G4가 모두 3일 이상 연속 — G5(USD/JPY)가 거짓이어도 단계에는 영향 없음")
    void stageTwo() {
        Inputs day = new Inputs(4.70, 90.0, 85.0, 140.0);   // G5 거짓

        Result r = run(day, day, day);

        assertThat(r.level()).isEqualTo("L2");
        assertThat(r.conditions()).containsEntry("G5", false);
    }

    @Test
    @DisplayName("L2 중 하나라도 확인이 덜 됐으면 L1로 — 유가가 이틀 전에야 95 밑으로")
    void stageTwoNeedsAllThree() {
        Inputs calm = new Inputs(4.70, 90.0, 85.0, 150.0);
        Inputs hotBrent = new Inputs(4.70, 96.0, 85.0, 150.0);

        Result r = run(calm, hotBrent, calm, calm);   // G3 연속 2

        assertThat(r.streaks()).containsEntry("G2", 4).containsEntry("G3", 2);
        assertThat(r.level()).isEqualTo("L1");
    }

    @Test
    @DisplayName("10Y가 다시 4.80 이상이면 연속이 끊겨 L0")
    void resetsToStageZero() {
        Inputs low = new Inputs(4.78, 90.0, 85.0, 150.0);
        Inputs high = new Inputs(4.85, 90.0, 85.0, 150.0);

        Result r = run(low, low, low, high);

        assertThat(r.level()).isEqualTo("L0");
        assertThat(r.streaks()).containsEntry("G1", 0);
    }

    @Test
    @DisplayName("입력이 하나라도 없으면 UNKNOWN(L0가 아님) — 그 조건은 null, 연속도 null")
    void missingInputIsUnknown() {
        Inputs calm = new Inputs(4.70, 90.0, 85.0, 150.0);
        Inputs noBrent = new Inputs(4.70, null, 85.0, 150.0);

        Result r = run(calm, calm, calm, noBrent);   // 직전까지 L2

        assertThat(r.level()).isEqualTo("UNKNOWN");
        assertThat(r.missing()).containsExactly("brent");
        assertThat(r.conditions()).containsEntry("G3", null).containsEntry("G2", true);
        assertThat(r.streaks()).containsEntry("G3", null).containsEntry("G2", 4);
    }

    @Test
    @DisplayName("단계에 쓰지 않는 USD/JPY가 빠져도 UNKNOWN, NaN도 없는 값")
    void anyMissingInputIsUnknown() {
        Result noYen = GateIndicator.evaluate(new Inputs(4.70, 90.0, 85.0, null), Map.of("G2", 9, "G3", 9, "G4", 9), T);
        assertThat(noYen.level()).isEqualTo("UNKNOWN");
        assertThat(noYen.missing()).containsExactly("usdjpy");

        Result allMissing = GateIndicator.evaluate(new Inputs(Double.NaN, null, null, null), null, T);
        assertThat(allMissing.level()).isEqualTo("UNKNOWN");
        assertThat(allMissing.missing()).containsExactly("us10y", "brent", "wti", "usdjpy");
        assertThat(allMissing.conditions().values()).containsOnlyNulls();
    }

    @Test
    @DisplayName("모르는 날 뒤에는 1부터 다시 센다 — 모르는 날을 참으로 이어 세지 않음")
    void unknownDayBreaksStreak() {
        Inputs calm = new Inputs(4.70, 90.0, 85.0, 150.0);
        Inputs noBrent = new Inputs(4.70, null, 85.0, 150.0);

        Result r = run(calm, calm, noBrent, calm);

        assertThat(r.streaks()).containsEntry("G3", 1).containsEntry("G2", 4);
        assertThat(r.level()).isEqualTo("L1");   // G3가 1이라 L2 아님, G1은 4일 연속
    }

    @Test
    @DisplayName("직전 streaks가 null·빠진 키·null 값이면 0에서 시작")
    void previousStreaksTolerated() {
        Map<String, Integer> partial = new HashMap<>();
        partial.put("G1", null);
        partial.put("G2", 2);

        Result r = GateIndicator.evaluate(new Inputs(4.70, 90.0, 85.0, 150.0), partial, T);

        assertThat(r.streaks()).containsEntry("G1", 1).containsEntry("G2", 3).containsEntry("G3", 1);
    }

    @Test
    @DisplayName("확인 일수 1이면 그날로 단계, 0 이하 임계치는 거절")
    void customConfirmDays() {
        Thresholds oneDay = new Thresholds(4.80, 4.75, 95, 90, 155, 1);

        assertThat(GateIndicator.evaluate(new Inputs(4.70, 90.0, 85.0, 150.0), null, oneDay).level()).isEqualTo("L2");
        assertThat(GateIndicator.evaluate(new Inputs(4.78, 90.0, 85.0, 150.0), null, oneDay).level()).isEqualTo("L1");
        assertThatThrownBy(() -> new Thresholds(4.80, 4.75, 95, 90, 155, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결과 맵은 바꿀 수 없다")
    void resultIsImmutable() {
        Result r = GateIndicator.evaluate(new Inputs(4.70, 90.0, 85.0, 150.0), null, T);

        assertThatThrownBy(() -> r.streaks().put("G1", 99)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> r.missing().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
}
