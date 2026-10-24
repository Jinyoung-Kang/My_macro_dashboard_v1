package com.macrodash.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DailySeriesTest {

    private static final LocalDate THU = LocalDate.of(2026, 10, 22);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 23);
    private static final LocalDate SAT = LocalDate.of(2026, 10, 24);
    private static final LocalDate MON = LocalDate.of(2026, 10, 26);

    /** 목·금·월 세 점(주말 없음). */
    private static DailySeries threeDays() {
        return new DailySeries(
                new int[] {(int) THU.toEpochDay(), (int) FRI.toEpochDay(), (int) MON.toEpochDay()},
                new double[] {10.0, 11.0, 12.0});
    }

    @Test
    @DisplayName("길이가 다르거나 오름차순이 아니면(같은 날 두 번 포함) 거절")
    void rejectsMalformedArrays() {
        int fri = (int) FRI.toEpochDay();
        assertThatThrownBy(() -> new DailySeries(new int[] {fri}, new double[] {1.0, 2.0}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DailySeries(new int[] {fri, fri - 1}, new double[] {1.0, 2.0}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DailySeries(new int[] {fri, fri}, new double[] {1.0, 2.0}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DailySeries(null, new double[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("넘긴 배열을 나중에 바꿔도 계열은 그대로(복사해 보관)")
    void copiesInput() {
        int[] days = {(int) THU.toEpochDay(), (int) FRI.toEpochDay()};
        double[] values = {10.0, 11.0};
        DailySeries series = new DailySeries(days, values);
        values[1] = 999.0;
        days[1] = (int) MON.toEpochDay();

        assertThat(series.valueOn(FRI)).isEqualTo(11.0);
        assertThat(series.valueOn(MON)).isNull();
    }

    @Test
    @DisplayName("빈 계열: 날짜·값은 null, 크기 0")
    void empty() {
        DailySeries empty = new DailySeries(new int[0], new double[0]);

        assertThat(empty.size()).isZero();
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.firstDate()).isNull();
        assertThat(empty.lastDate()).isNull();
        assertThat(empty.lastValue()).isNull();
        assertThat(empty.valueOn(FRI)).isNull();
        assertThat(empty.floorValue(FRI)).isNull();
        assertThat(empty.floorDate(FRI)).isNull();
        assertThat(empty.until(FRI).size()).isZero();
        assertThat(DailySeries.of(null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("of: null 값은 건너뛰고(0으로 채우지 않음) 역순 맵도 오름차순으로 담는다")
    void ofSkipsNullsAndSorts() {
        NavigableMap<LocalDate, Double> map = new TreeMap<>(Collections.reverseOrder());
        map.put(MON, 12.0);
        map.put(FRI, null);
        map.put(THU, 10.0);

        DailySeries series = DailySeries.of(map);

        assertThat(series.size()).isEqualTo(2);
        assertThat(series.dateAt(0)).isEqualTo(THU);
        assertThat(series.dateAt(1)).isEqualTo(MON);
        assertThat(series.valueOn(FRI)).isNull();
        assertThat(series.floorValue(FRI)).isEqualTo(10.0);
    }

    @Test
    @DisplayName("first/last·dateAt/valueAt")
    void accessors() {
        DailySeries series = threeDays();

        assertThat(series.size()).isEqualTo(3);
        assertThat(series.firstDate()).isEqualTo(THU);
        assertThat(series.lastDate()).isEqualTo(MON);
        assertThat(series.lastValue()).isEqualTo(12.0);
        assertThat(series.dateAt(1)).isEqualTo(FRI);
        assertThat(series.valueAt(1)).isEqualTo(11.0);
        assertThatThrownBy(() -> series.valueAt(3)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("valueOn은 정확히 그날만, floor는 그날 또는 그 전 가장 가까운 날")
    void exactAndFloorLookup() {
        DailySeries series = threeDays();

        assertThat(series.valueOn(FRI)).isEqualTo(11.0);
        assertThat(series.valueOn(SAT)).isNull();                          // 주말은 점이 없음
        assertThat(series.floorValue(SAT)).isEqualTo(11.0);
        assertThat(series.floorDate(SAT)).isEqualTo(FRI);
        assertThat(series.floorValue(MON)).isEqualTo(12.0);
        assertThat(series.floorValue(MON.plusYears(1))).isEqualTo(12.0);   // 마지막 뒤 → 마지막
        assertThat(series.floorValue(THU.minusDays(1))).isNull();          // 첫 점 앞 → 없음
        assertThat(series.floorDate(THU.minusDays(1))).isNull();
        assertThat(series.floorValue(LocalDate.MAX)).isEqualTo(12.0);
        assertThat(series.valueOn(LocalDate.MAX)).isNull();
        assertThat(series.floorValue(LocalDate.MIN)).isNull();
    }

    @Test
    @DisplayName("until은 그날(포함)까지 자른다 — 룩어헤드 방지")
    void untilCutsInclusive() {
        DailySeries series = threeDays();

        DailySeries toFriday = series.until(FRI);
        assertThat(toFriday.size()).isEqualTo(2);
        assertThat(toFriday.lastDate()).isEqualTo(FRI);
        assertThat(toFriday.valueOn(MON)).isNull();

        assertThat(series.until(SAT).lastDate()).isEqualTo(FRI);          // 점이 없는 날 → 그 전까지
        assertThat(series.until(THU.minusDays(1)).isEmpty()).isTrue();
        assertThat(series.until(MON).size()).isEqualTo(3);
        assertThat(series.size()).isEqualTo(3);                             // 원본은 그대로
    }
}
