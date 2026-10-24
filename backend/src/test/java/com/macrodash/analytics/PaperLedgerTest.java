package com.macrodash.analytics;

import com.macrodash.analytics.PaperLedger.Side;
import com.macrodash.analytics.PaperLedger.Valuation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PaperLedgerTest {

    @Test
    @DisplayName("체결가: 매수는 bp만큼 비싸게, 매도는 싸게(5bp)")
    void fillPriceAppliesSlippageAgainstUs() {
        assertThat(PaperLedger.fillPrice(100.0, Side.BUY, 5)).isCloseTo(100.05, within(1e-9));
        assertThat(PaperLedger.fillPrice(100.0, Side.SELL, 5)).isCloseTo(99.95, within(1e-9));
        assertThat(PaperLedger.fillPrice(500.0, Side.BUY, 5)).isCloseTo(500.25, within(1e-9));
        assertThat(PaperLedger.fillPrice(42.0, Side.BUY, 0)).isEqualTo(42.0);
    }

    @Test
    @DisplayName("체결가: 가격 0 이하·NaN, 슬리피지 음수·10000bp 이상은 거절")
    void fillPriceRejectsBadInput() {
        assertThatThrownBy(() -> PaperLedger.fillPrice(0.0, Side.BUY, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaperLedger.fillPrice(-1.0, Side.SELL, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaperLedger.fillPrice(Double.NaN, Side.BUY, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaperLedger.fillPrice(100.0, Side.BUY, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaperLedger.fillPrice(100.0, Side.SELL, 10_000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("벤치마크 사후 체결: 100,000 / (500 × 1.0005) = 199.9 → 199주")
    void benchmarkBackfillQuantity() {
        double price = PaperLedger.fillPrice(500.0, Side.BUY, 5);

        int qty = PaperLedger.buyQty(100_000, price);

        assertThat(qty).isEqualTo(199);
        // 199주 = 99,549.75달러, 200주면 100,050달러로 현금 초과
        assertThat(qty * price).isCloseTo(99_549.75, within(1e-6));
    }

    @Test
    @DisplayName("매수 수량은 십진수로 내림 — 0.3/0.1은 double로 2.999…지만 3주")
    void buyQtyFloorsInDecimal() {
        assertThat(0.3 / 0.1).isLessThan(3.0);                 // 이것이 double 나눗셈을 쓰지 않는 이유
        assertThat(PaperLedger.buyQty(0.3, 0.1)).isEqualTo(3);
        assertThat(PaperLedger.buyQty(3010.0, 30.1)).isEqualTo(100);
        assertThat(PaperLedger.buyQty(1000.0, 30.0)).isEqualTo(33);
        assertThat(PaperLedger.buyQty(5.0, 10.0)).isZero();     // 한 주도 못 삼
    }

    @Test
    @DisplayName("매수 수량: 금액·가격이 0 이하면 0, NaN은 거절")
    void buyQtyNonPositive() {
        assertThat(PaperLedger.buyQty(0.0, 10.0)).isZero();
        assertThat(PaperLedger.buyQty(-100.0, 10.0)).isZero();
        assertThat(PaperLedger.buyQty(100.0, 0.0)).isZero();
        assertThat(PaperLedger.buyQty(100.0, -5.0)).isZero();
        assertThatThrownBy(() -> PaperLedger.buyQty(Double.NaN, 10.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaperLedger.buyQty(100.0, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("매도 수량: null이면 전량, 비율 내림, 1 미만이면 전량, 보유를 넘지 않음")
    void sellQty() {
        assertThat(PaperLedger.sellQty(10, null)).isEqualTo(10);
        assertThat(PaperLedger.sellQty(10, 0.5)).isEqualTo(5);
        assertThat(PaperLedger.sellQty(10, 0.35)).isEqualTo(3);     // 3.5 → 3
        assertThat(PaperLedger.sellQty(3, 0.2)).isEqualTo(3);       // 0.6 → 0 → 1 미만이라 전량
        assertThat(PaperLedger.sellQty(10, 1.0)).isEqualTo(10);
        assertThat(PaperLedger.sellQty(10, 1.5)).isEqualTo(10);     // 보유를 넘지 않음
        assertThat(PaperLedger.sellQty(0, 0.5)).isZero();           // 팔 것이 없음
        // 100 × 0.29는 double로 28.999…지만 십진수로 29주
        assertThat(100 * 0.29).isLessThan(29.0);
        assertThat(PaperLedger.sellQty(100, 0.29)).isEqualTo(29);
    }

    @Test
    @DisplayName("매도 비율 0 이하·NaN은 거절 — '1 미만이면 전량'으로 '안 팖'이 '전량 매도'가 되지 않게")
    void sellQtyRejectsNonPositiveFraction() {
        assertThatThrownBy(() -> PaperLedger.sellQty(10, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaperLedger.sellQty(10, -0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaperLedger.sellQty(10, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("평단: 수량 가중 평균, 새 보유면 체결가")
    void averageCost() {
        assertThat(PaperLedger.averageCost(10, 100.0, 10, 110.0)).isCloseTo(105.0, within(1e-12));
        assertThat(PaperLedger.averageCost(3, 10.0, 1, 14.0)).isCloseTo(11.0, within(1e-12));   // (30 + 14) / 4
        assertThat(PaperLedger.averageCost(0, 0.0, 5, 50.0)).isEqualTo(50.0);
        assertThat(PaperLedger.averageCost(7, 12.5, 0, 99.0)).isEqualTo(12.5);
        assertThatThrownBy(() -> PaperLedger.averageCost(0, 0.0, 0, 50.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaperLedger.averageCost(-1, 10.0, 1, 50.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("평가: equity = Σ 수량×가격, nav = 현금 + equity, 노출 = equity/nav×100")
    void valuation() {
        Map<String, Integer> qty = new LinkedHashMap<>();
        qty.put("AAA", 10);
        qty.put("BBB", 5);
        Map<String, Double> price = Map.of("AAA", 20.5, "BBB", 100.0);

        Valuation v = PaperLedger.value(1000.0, qty, price);

        assertThat(v.cash()).isEqualTo(1000.0);
        assertThat(v.equityValue()).isEqualTo(705.0);
        assertThat(v.nav()).isEqualTo(1705.0);
        assertThat(v.exposurePct()).isCloseTo(705.0 / 1705.0 * 100.0, within(1e-12));   // 41.3489…%
    }

    @Test
    @DisplayName("평가 합은 십진수 — 0.1 + 0.2가 0.30000000000000004가 되지 않음")
    void valuationSumsInDecimal() {
        Valuation v = PaperLedger.value(0.0, Map.of("A", 1, "B", 1), Map.of("A", 0.1, "B", 0.2));

        assertThat(v.equityValue()).isEqualTo(0.3);
        assertThat(v.exposurePct()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("평가: 보유가 없으면 노출 0, nav ≤ 0이면 노출 0, 수량 0 항목은 가격이 없어도 됨")
    void valuationEdges() {
        Valuation cashOnly = PaperLedger.value(100_000.0, Map.of(), Map.of());
        assertThat(cashOnly.equityValue()).isZero();
        assertThat(cashOnly.nav()).isEqualTo(100_000.0);
        assertThat(cashOnly.exposurePct()).isZero();

        Valuation broke = PaperLedger.value(0.0, Map.of("ZERO", 0), Map.of());
        assertThat(broke.nav()).isZero();
        assertThat(broke.exposurePct()).isZero();
    }

    @Test
    @DisplayName("평가: 보유 티커의 가격이 없으면 거절 — 0으로 평가하지 않음")
    void valuationRequiresEveryPrice() {
        assertThatThrownBy(() -> PaperLedger.value(0.0, Map.of("AAA", 10, "BBB", 1), Map.of("AAA", 20.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BBB");
        assertThatThrownBy(() -> PaperLedger.value(0.0, Map.of("AAA", -1), Map.of("AAA", 20.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("round4: 소수 4자리 HALF_UP — 0.00145는 0.0015(Math.round 방식은 0.0014)")
    void round4() {
        assertThat(Math.round(0.00145 * 1e4) / 1e4).isEqualTo(0.0014);   // 피하는 방식
        assertThat(PaperLedger.round4(0.00145)).isEqualTo(0.0015);
        assertThat(PaperLedger.round4(2.71828)).isEqualTo(2.7183);
        assertThat(PaperLedger.round4(123.45674999)).isEqualTo(123.4567);
        assertThat(PaperLedger.round4(-1.00005)).isEqualTo(-1.0001);     // 0에서 먼 쪽(PostgreSQL NUMERIC과 같음)
        assertThat(PaperLedger.round4(0.00004)).isEqualTo(0.0);
        assertThat(PaperLedger.round4(99_549.75)).isEqualTo(99_549.75);
        assertThatThrownBy(() -> PaperLedger.round4(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }
}
