package com.macrodash.analytics;

import com.macrodash.analytics.SplitDetector.Ratio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SplitDetectorTest {

    @Test
    @DisplayName("2:1 분할 — 참조일 종가 200이 100으로 다시 쓰임")
    void twoForOne() {
        assertThat(SplitDetector.detect(200.0, 100.0)).contains(new Ratio(2, 1));
    }

    @Test
    @DisplayName("3:2 분할 — 150 → 100")
    void threeForTwo() {
        assertThat(SplitDetector.detect(150.0, 100.0)).contains(new Ratio(3, 2));
    }

    @Test
    @DisplayName("1:10 병합(역분할) — 1.00 → 10.00")
    void oneForTenReverse() {
        assertThat(SplitDetector.detect(1.0, 10.0)).contains(new Ratio(1, 10));
    }

    @Test
    @DisplayName("±10% 안(배당 조정 등)은 분할이 아니다 — 1.05, 경계 1.1·0.9 포함")
    void insideBandIsNotASplit() {
        assertThat(SplitDetector.detect(105.0, 100.0)).isEmpty();
        assertThat(SplitDetector.detect(110.0, 100.0)).isEmpty();
        assertThat(SplitDetector.detect(90.0, 100.0)).isEmpty();
        assertThat(SplitDetector.detect(100.0, 100.0)).isEmpty();
    }

    @Test
    @DisplayName("그 밖의 흔한 비율: 3:1, 5:4, 1:8, 20:1, 1:40")
    void otherRatios() {
        assertThat(SplitDetector.detect(300.0, 100.0)).contains(new Ratio(3, 1));
        assertThat(SplitDetector.detect(125.0, 100.0)).contains(new Ratio(5, 4));
        assertThat(SplitDetector.detect(1.0, 8.0)).contains(new Ratio(1, 8));
        assertThat(SplitDetector.detect(2000.0, 100.0)).contains(new Ratio(20, 1));
        // 가장 단순한 분수부터 보는 방식(분모 순)이면 1/39(오차 2.6%)가 먼저 잡힙니다 — 수렴분수는 정확한 1/40을 줍니다.
        assertThat(SplitDetector.detect(1.0, 40.0)).contains(new Ratio(1, 40));
        // 0.07/2.8의 역수는 double로 39.99999999999999 — 내림 오차를 올려 주지 않으면 1:39가 됩니다.
        assertThat(1.0 / (0.07 / 2.8)).isLessThan(40.0);
        assertThat(SplitDetector.detect(0.07, 2.8)).contains(new Ratio(1, 40));
    }

    @Test
    @DisplayName("배당 조정 잡음이 낀 2:1(r = 2.04)은 2:1 — '분자·분모 ≤ 50 중 가장 가까운 분수'면 49:24가 된다")
    void noisyRatioReadsAsSimpleFraction() {
        assertThat(Math.abs(49.0 / 24 - 2.04)).isLessThan(Math.abs(2.0 - 2.04));   // 가장 가까운 분수는 49/24
        assertThat(SplitDetector.detect(204.0, 100.0)).contains(new Ratio(2, 1));
        assertThat(SplitDetector.detect(98.0, 1000.0)).contains(new Ratio(1, 10));    // 0.098
        assertThat(SplitDetector.detect(102.5, 1000.0)).contains(new Ratio(1, 10));   // 0.1025
        assertThat(SplitDetector.detect(294.0, 100.0)).contains(new Ratio(3, 1));     // 2.94
    }

    @Test
    @DisplayName("분자·분모 50을 넘거나 값이 0 이하·NaN이면 empty")
    void outOfRangeOrInvalid() {
        assertThat(SplitDetector.detect(10_000.0, 100.0)).isEmpty();   // 100:1
        assertThat(SplitDetector.detect(1.0, 100.0)).isEmpty();        // 1:100
        assertThat(SplitDetector.detect(0.0, 100.0)).isEmpty();
        assertThat(SplitDetector.detect(100.0, 0.0)).isEmpty();
        assertThat(SplitDetector.detect(Double.NaN, 100.0)).isEmpty();
        assertThat(SplitDetector.detect(100.0, Double.POSITIVE_INFINITY)).isEmpty();
    }

    @Test
    @DisplayName("수량은 내림(단주 버림), 가격은 분모/분자 배")
    void adjustQtyAndPrice() {
        assertThat(SplitDetector.adjustQty(100, new Ratio(2, 1))).isEqualTo(200);
        assertThat(SplitDetector.adjustQty(100, new Ratio(3, 2))).isEqualTo(150);
        assertThat(SplitDetector.adjustQty(101, new Ratio(3, 2))).isEqualTo(151);    // 151.5 → 151
        assertThat(SplitDetector.adjustQty(95, new Ratio(1, 10))).isEqualTo(9);      // 9.5 → 9
        assertThat(SplitDetector.adjustQty(5, new Ratio(1, 10))).isZero();           // 0.5주 → 0(정리는 서비스가)

        assertThat(SplitDetector.adjustPrice(200.0, new Ratio(2, 1))).isCloseTo(100.0, within(1e-12));
        assertThat(SplitDetector.adjustPrice(150.0, new Ratio(3, 2))).isCloseTo(100.0, within(1e-12));
        assertThat(SplitDetector.adjustPrice(1.0, new Ratio(1, 10))).isCloseTo(10.0, within(1e-12));
    }

    @Test
    @DisplayName("Ratio: 값·표기, 0 이하 거절")
    void ratio() {
        assertThat(new Ratio(3, 2).value()).isEqualTo(1.5);
        assertThat(new Ratio(1, 10).value()).isCloseTo(0.1, within(1e-15));
        assertThat(new Ratio(2, 1).label()).isEqualTo("2:1");
        assertThatThrownBy(() -> new Ratio(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ratio(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
