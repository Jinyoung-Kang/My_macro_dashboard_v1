package com.macrodash.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 미국 증시 달력. 휴장일·조기 폐장 기대값은 NYSE 공지(2025~2027)와 2024년 실제 휴장일, 13F 기한은 SEC 영업일 규칙으로
 * 손으로 센 값입니다.
 */
class UsMarketCalendarTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static List<LocalDate> dates(String... iso) {
        List<LocalDate> out = new ArrayList<>();
        for (String s : iso) {
            out.add(d(s));
        }
        return out;
    }

    /** 그해 휴장일 전부(정확히 이것만). */
    private static List<LocalDate> holidaysOf(int year) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate day = LocalDate.of(year, 1, 1); day.getYear() == year; day = day.plusDays(1)) {
            if (UsMarketCalendar.isHoliday(day)) {
                out.add(day);
            }
        }
        return out;
    }

    private static List<LocalDate> earlyClosesOf(int year) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate day = LocalDate.of(year, 1, 1); day.getYear() == year; day = day.plusDays(1)) {
            if (UsMarketCalendar.closeTime(day).equals(LocalTime.of(13, 0))) {
                out.add(day);
            }
        }
        return out;
    }

    @Test
    @DisplayName("NYSE 휴장일 2024~2027 — 그해 휴장일이 정확히 이 목록(빠짐도 덤도 없음)")
    void holidaysPerYear() {
        assertThat(holidaysOf(2024)).containsExactlyElementsOf(dates(
                "2024-01-01", "2024-01-15", "2024-02-19", "2024-03-29", "2024-05-27",
                "2024-06-19", "2024-07-04", "2024-09-02", "2024-11-28", "2024-12-25"));
        // 2025-01-09(카터 애도일 임시 휴장)은 규칙 밖이라 목록에 없습니다(클래스 주석의 한계).
        assertThat(holidaysOf(2025)).containsExactlyElementsOf(dates(
                "2025-01-01", "2025-01-20", "2025-02-17", "2025-04-18", "2025-05-26",
                "2025-06-19", "2025-07-04", "2025-09-01", "2025-11-27", "2025-12-25"));
        assertThat(holidaysOf(2026)).containsExactlyElementsOf(dates(
                "2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03", "2026-05-25",
                "2026-06-19", "2026-07-03", "2026-09-07", "2026-11-26", "2026-12-25"));
        assertThat(holidaysOf(2027)).containsExactlyElementsOf(dates(
                "2027-01-01", "2027-01-18", "2027-02-15", "2027-03-26", "2027-05-31",
                "2027-06-18", "2027-07-05", "2027-09-06", "2027-11-25", "2027-12-24"));
    }

    @Test
    @DisplayName("신정이 토요일이면 전년 12-31(금)은 개장 — 화면 규칙('YYYY-01-00')과 같은 결과")
    void newYearOnSaturdayIsNotObservedOnFriday() {
        // 2022-01-01, 2028-01-01이 토요일.
        assertThat(UsMarketCalendar.isTradingDay(d("2021-12-31"))).isTrue();
        assertThat(UsMarketCalendar.isTradingDay(d("2027-12-31"))).isTrue();
        assertThat(UsMarketCalendar.isTradingDay(d("2022-01-03"))).isTrue();
        assertThat(holidaysOf(2022)).doesNotContain(d("2021-12-31"), d("2022-01-03"));
        // 신정이 일요일이면 월요일에 쉽니다(2023-01-02).
        assertThat(UsMarketCalendar.isHoliday(d("2023-01-02"))).isTrue();
        // 크리스마스가 일요일이면 월요일(2022-12-26).
        assertThat(UsMarketCalendar.isHoliday(d("2022-12-26"))).isTrue();
    }

    @Test
    @DisplayName("연방 공휴일이지만 증시는 여는 날(컬럼버스·재향군인), 증시만 쉬는 날(성금요일)")
    void federalHolidaysVersusMarketHolidays() {
        assertThat(UsMarketCalendar.isTradingDay(d("2026-10-12"))).isTrue();     // 컬럼버스 데이
        assertThat(UsMarketCalendar.isTradingDay(d("2026-11-11"))).isTrue();     // 재향군인의 날
        assertThat(UsMarketCalendar.isFederalHoliday(d("2026-10-12"))).isTrue();
        assertThat(UsMarketCalendar.isFederalHoliday(d("2026-11-11"))).isTrue();

        assertThat(UsMarketCalendar.isTradingDay(d("2026-04-03"))).isFalse();    // 성금요일
        assertThat(UsMarketCalendar.isFederalHoliday(d("2026-04-03"))).isFalse();
        assertThat(UsMarketCalendar.isSecBusinessDay(d("2026-04-03"))).isTrue();

        // 연방: 신정이 토요일(2028-01-01)이면 전년 12-31(금)이 관측 휴일 — 증시와 다릅니다.
        assertThat(UsMarketCalendar.isFederalHoliday(d("2027-12-31"))).isTrue();
        assertThat(UsMarketCalendar.isSecBusinessDay(d("2027-12-31"))).isFalse();
        // 재향군인의 날이 토요일(2028-11-11)이면 금요일 관측.
        assertThat(UsMarketCalendar.isFederalHoliday(d("2028-11-10"))).isTrue();
    }

    @Test
    @DisplayName("주말은 휴장일 목록에 없지만 거래일도 아니다")
    void weekends() {
        assertThat(UsMarketCalendar.isHoliday(d("2026-10-24"))).isFalse();      // 토
        assertThat(UsMarketCalendar.isTradingDay(d("2026-10-24"))).isFalse();
        assertThat(UsMarketCalendar.isTradingDay(d("2026-10-25"))).isFalse();   // 일
        assertThat(UsMarketCalendar.isTradingDay(d("2026-10-23"))).isTrue();    // 금
    }

    @Test
    @DisplayName("다음·직전 거래일은 엄격히 뒤·앞이고 주말·휴장일을 건너뛴다")
    void nextAndPrevious() {
        assertThat(UsMarketCalendar.nextTradingDay(d("2026-10-23"))).isEqualTo(d("2026-10-26"));   // 금 → 월
        assertThat(UsMarketCalendar.nextTradingDay(d("2026-10-24"))).isEqualTo(d("2026-10-26"));   // 토 → 월
        assertThat(UsMarketCalendar.nextTradingDay(d("2026-10-26"))).isEqualTo(d("2026-10-27"));   // 거래일이어도 다음 날
        assertThat(UsMarketCalendar.nextTradingDay(d("2026-11-25"))).isEqualTo(d("2026-11-27"));   // 추수감사절
        assertThat(UsMarketCalendar.nextTradingDay(d("2026-12-31"))).isEqualTo(d("2027-01-04"));   // 신정·주말
        assertThat(UsMarketCalendar.previousTradingDay(d("2026-10-26"))).isEqualTo(d("2026-10-23"));
        assertThat(UsMarketCalendar.previousTradingDay(d("2026-04-06"))).isEqualTo(d("2026-04-02"));  // 성금요일
        assertThat(UsMarketCalendar.previousTradingDay(d("2026-07-06"))).isEqualTo(d("2026-07-02"));  // 7-3 대체 휴장
        assertThat(UsMarketCalendar.previousTradingDay(d("2027-01-04"))).isEqualTo(d("2026-12-31"));
    }

    @Test
    @DisplayName("addTradingDays: 다음 거래일을 1로 센다, n=0이면 그대로, 음수는 거절")
    void addTradingDays() {
        assertThat(UsMarketCalendar.addTradingDays(d("2026-10-23"), 0)).isEqualTo(d("2026-10-23"));
        assertThat(UsMarketCalendar.addTradingDays(d("2026-10-24"), 0)).isEqualTo(d("2026-10-24"));   // 토요일도 그대로
        assertThat(UsMarketCalendar.addTradingDays(d("2026-10-23"), 1)).isEqualTo(d("2026-10-26"));
        assertThat(UsMarketCalendar.addTradingDays(d("2026-10-24"), 1)).isEqualTo(d("2026-10-26"));
        // 10-26(1) … 10-30(5), 11-02(6) … 11-06(10), 11-09(11) … 11-13(15; 11-11 재향군인의 날은 개장), 11-16(16) … 11-20(20)
        assertThat(UsMarketCalendar.addTradingDays(d("2026-10-23"), 20)).isEqualTo(d("2026-11-20"));
        // 주문 만료(기준 거래일 + 3): 추수감사절을 건너뜀 — 11-24(1), 11-25(2), 11-27(3)
        assertThat(UsMarketCalendar.addTradingDays(d("2026-11-23"), 3)).isEqualTo(d("2026-11-27"));
        assertThatThrownBy(() -> UsMarketCalendar.addTradingDays(d("2026-10-23"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tradingDaysBetween: (from, to] 구간, from ≥ to면 0")
    void tradingDaysBetween() {
        assertThat(UsMarketCalendar.tradingDaysBetween(d("2026-10-23"), d("2026-11-20"))).isEqualTo(20);
        assertThat(UsMarketCalendar.tradingDaysBetween(d("2026-10-23"), d("2026-10-23"))).isZero();
        assertThat(UsMarketCalendar.tradingDaysBetween(d("2026-10-26"), d("2026-10-23"))).isZero();
        assertThat(UsMarketCalendar.tradingDaysBetween(d("2026-10-24"), d("2026-10-25"))).isZero();   // 토 → 일
        assertThat(UsMarketCalendar.tradingDaysBetween(d("2026-10-24"), d("2026-10-26"))).isEqualTo(1);
        assertThat(UsMarketCalendar.tradingDaysBetween(d("2026-10-22"), d("2026-10-23"))).isEqualTo(1);
        // 2026년 거래일 = 365 − 주말 104 − 휴장 10 = 251
        assertThat(UsMarketCalendar.tradingDaysBetween(d("2025-12-31"), d("2026-12-31"))).isEqualTo(251);
    }

    @Test
    @DisplayName("addTradingDays와 tradingDaysBetween은 서로 역이다(2024~2027 모든 날)")
    void addAndBetweenAreInverse() {
        for (LocalDate day = d("2024-01-01"); day.isBefore(d("2028-01-01")); day = day.plusDays(1)) {
            for (int n : new int[] {0, 1, 3, 20}) {
                LocalDate later = UsMarketCalendar.addTradingDays(day, n);
                assertThat(UsMarketCalendar.tradingDaysBetween(day, later)).as("%s + %d", day, n).isEqualTo(n);
                if (n > 0) {
                    assertThat(UsMarketCalendar.isTradingDay(later)).isTrue();
                }
            }
            if (UsMarketCalendar.isTradingDay(day)) {
                assertThat(UsMarketCalendar.nextTradingDay(UsMarketCalendar.previousTradingDay(day))).isEqualTo(day);
            }
        }
    }

    @Test
    @DisplayName("조기 폐장(13:00) — 7-3·추수감사절 다음 날·12-24, 그날이 휴장이면 없음")
    void earlyCloses() {
        assertThat(earlyClosesOf(2024)).containsExactlyElementsOf(dates("2024-07-03", "2024-11-29", "2024-12-24"));
        assertThat(earlyClosesOf(2025)).containsExactlyElementsOf(dates("2025-07-03", "2025-11-28", "2025-12-24"));
        // 2026-07-03은 대체 휴장 → 7월 조기 폐장 없음(07-02는 정규 마감).
        assertThat(earlyClosesOf(2026)).containsExactlyElementsOf(dates("2026-11-27", "2026-12-24"));
        // 2027-07-03은 토요일, 12-24는 대체 휴장 → 추수감사절 다음 날만.
        assertThat(earlyClosesOf(2027)).containsExactlyElementsOf(dates("2027-11-26"));
        assertThat(UsMarketCalendar.closeTime(d("2026-10-23"))).isEqualTo(LocalTime.of(16, 0));
        assertThat(UsMarketCalendar.closeTime(d("2026-07-02"))).isEqualTo(LocalTime.of(16, 0));
        assertThat(UsMarketCalendar.closeTime(d("2027-12-23"))).isEqualTo(LocalTime.of(16, 0));
    }

    @Test
    @DisplayName("마감 순간은 서머타임을 따른다 — EDT 20:00Z, EST 21:00Z, 조기 폐장 EST 18:00Z")
    void closeInstantAcrossDst() {
        assertThat(UsMarketCalendar.closeInstant(d("2026-10-30"))).isEqualTo(Instant.parse("2026-10-30T20:00:00Z"));
        assertThat(UsMarketCalendar.closeInstant(d("2026-11-02"))).isEqualTo(Instant.parse("2026-11-02T21:00:00Z"));
        // 2027-03-14 서머타임 시작
        assertThat(UsMarketCalendar.closeInstant(d("2027-03-12"))).isEqualTo(Instant.parse("2027-03-12T21:00:00Z"));
        assertThat(UsMarketCalendar.closeInstant(d("2027-03-15"))).isEqualTo(Instant.parse("2027-03-15T20:00:00Z"));
        assertThat(UsMarketCalendar.closeInstant(d("2026-11-27"))).isEqualTo(Instant.parse("2026-11-27T18:00:00Z"));
        assertThat(UsMarketCalendar.closeInstant(d("2025-07-03"))).isEqualTo(Instant.parse("2025-07-03T17:00:00Z"));
    }

    @Test
    @DisplayName("lastCompletedSession: 마감 시각 ≤ now인 가장 최근 세션")
    void lastCompletedSession() {
        assertThat(UsMarketCalendar.lastCompletedSession(Instant.parse("2026-10-23T20:00:00Z"))).isEqualTo(d("2026-10-23"));
        assertThat(UsMarketCalendar.lastCompletedSession(Instant.parse("2026-10-23T19:59:59Z"))).isEqualTo(d("2026-10-22"));
        // UTC로는 다음 날이지만 뉴욕은 아직 10-23 밤
        assertThat(UsMarketCalendar.lastCompletedSession(Instant.parse("2026-10-24T03:00:00Z"))).isEqualTo(d("2026-10-23"));
        assertThat(UsMarketCalendar.lastCompletedSession(Instant.parse("2026-10-24T15:00:00Z"))).isEqualTo(d("2026-10-23"));
        assertThat(UsMarketCalendar.lastCompletedSession(Instant.parse("2026-10-26T13:00:00Z"))).isEqualTo(d("2026-10-23"));
        // 조기 폐장: 13:00 EST = 18:00Z
        assertThat(UsMarketCalendar.lastCompletedSession(Instant.parse("2026-11-27T18:00:00Z"))).isEqualTo(d("2026-11-27"));
        assertThat(UsMarketCalendar.lastCompletedSession(Instant.parse("2026-11-27T17:59:59Z"))).isEqualTo(d("2026-11-25"));
        // 서머타임 끝난 뒤: 16:00 EST = 21:00Z
        assertThat(UsMarketCalendar.lastCompletedSession(Instant.parse("2026-11-02T20:30:00Z"))).isEqualTo(d("2026-10-30"));
        assertThat(UsMarketCalendar.lastCompletedSession(Instant.parse("2026-11-02T21:00:00Z"))).isEqualTo(d("2026-11-02"));
    }

    @Test
    @DisplayName("currentOrLastSession: 09:30 ET에 시작한 세션 중 가장 최근(장 마감 뒤도 오늘)")
    void currentOrLastSession() {
        assertThat(UsMarketCalendar.currentOrLastSession(Instant.parse("2026-10-23T13:30:00Z"))).isEqualTo(d("2026-10-23"));
        assertThat(UsMarketCalendar.currentOrLastSession(Instant.parse("2026-10-23T13:29:59Z"))).isEqualTo(d("2026-10-22"));
        assertThat(UsMarketCalendar.currentOrLastSession(Instant.parse("2026-10-23T22:00:00Z"))).isEqualTo(d("2026-10-23"));
        assertThat(UsMarketCalendar.currentOrLastSession(Instant.parse("2026-10-24T15:00:00Z"))).isEqualTo(d("2026-10-23"));
        assertThat(UsMarketCalendar.currentOrLastSession(Instant.parse("2026-10-26T12:00:00Z"))).isEqualTo(d("2026-10-23"));
        assertThat(UsMarketCalendar.currentOrLastSession(Instant.parse("2026-11-26T15:00:00Z"))).isEqualTo(d("2026-11-25"));
        // EST: 09:30 = 14:30Z
        assertThat(UsMarketCalendar.currentOrLastSession(Instant.parse("2026-11-02T14:30:00Z"))).isEqualTo(d("2026-11-02"));
        assertThat(UsMarketCalendar.currentOrLastSession(Instant.parse("2026-11-02T14:29:00Z"))).isEqualTo(d("2026-10-30"));
    }

    @Test
    @DisplayName("13F 공시 기한 = 분기 말 + 45일, 주말·연방 공휴일이면 다음 SEC 영업일")
    void thirteenFDeadlines() {
        assertThat(UsMarketCalendar.thirteenFDeadline(d("2026-09-30"))).isEqualTo(d("2026-11-16"));   // 11-14 토
        assertThat(UsMarketCalendar.thirteenFDeadline(d("2026-12-31"))).isEqualTo(d("2027-02-16"));   // 02-14 일, 02-15 대통령의 날
        assertThat(UsMarketCalendar.thirteenFDeadline(d("2027-03-31"))).isEqualTo(d("2027-05-17"));   // 05-15 토
        assertThat(UsMarketCalendar.thirteenFDeadline(d("2027-06-30"))).isEqualTo(d("2027-08-16"));   // 08-14 토
        assertThat(UsMarketCalendar.thirteenFDeadline(d("2026-06-30"))).isEqualTo(d("2026-08-14"));   // 금, 그대로
        assertThat(UsMarketCalendar.thirteenFDeadline(d("2025-12-31"))).isEqualTo(d("2026-02-17"));   // 02-14 토, 02-16 대통령의 날
    }

    @Test
    @DisplayName("재선정일 = 공시 기한 다음 거래일")
    void reselectionDays() {
        assertThat(UsMarketCalendar.reselectionDay(d("2026-09-30"))).isEqualTo(d("2026-11-17"));
        assertThat(UsMarketCalendar.reselectionDay(d("2026-12-31"))).isEqualTo(d("2027-02-17"));
        assertThat(UsMarketCalendar.reselectionDay(d("2027-03-31"))).isEqualTo(d("2027-05-18"));
        assertThat(UsMarketCalendar.reselectionDay(d("2027-06-30"))).isEqualTo(d("2027-08-17"));
        assertThat(UsMarketCalendar.reselectionDay(d("2026-06-30"))).isEqualTo(d("2026-08-17"));   // 08-14 금 → 월

        assertThat(UsMarketCalendar.isReselectionDay(d("2026-11-17"))).isTrue();
        assertThat(UsMarketCalendar.isReselectionDay(d("2027-02-17"))).isTrue();
        assertThat(UsMarketCalendar.isReselectionDay(d("2027-05-18"))).isTrue();
        assertThat(UsMarketCalendar.isReselectionDay(d("2027-08-17"))).isTrue();
        assertThat(UsMarketCalendar.isReselectionDay(d("2026-11-16"))).isFalse();   // 기한 당일
        assertThat(UsMarketCalendar.isReselectionDay(d("2026-11-18"))).isFalse();
        assertThat(UsMarketCalendar.isReselectionDay(d("2026-10-23"))).isFalse();

        // 2024~2027에서 재선정일은 해마다 정확히 네 번
        for (int year = 2024; year <= 2027; year++) {
            int count = 0;
            for (LocalDate day = LocalDate.of(year, 1, 1); day.getYear() == year; day = day.plusDays(1)) {
                if (UsMarketCalendar.isReselectionDay(day)) {
                    count++;
                    assertThat(UsMarketCalendar.isTradingDay(day)).isTrue();
                }
            }
            assertThat(count).as("year %d", year).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("latestQuarterEndReselectedBy: 재선정일 ≤ d인 가장 늦은 분기 말")
    void latestQuarterEndReselectedBy() {
        // 장부 시작일: 9월 분기는 아직(11-17), 6월 분기는 08-17에 재선정됨
        assertThat(UsMarketCalendar.latestQuarterEndReselectedBy(d("2026-10-23"))).isEqualTo(d("2026-06-30"));
        assertThat(UsMarketCalendar.latestQuarterEndReselectedBy(d("2026-11-16"))).isEqualTo(d("2026-06-30"));
        assertThat(UsMarketCalendar.latestQuarterEndReselectedBy(d("2026-11-17"))).isEqualTo(d("2026-09-30"));
        assertThat(UsMarketCalendar.latestQuarterEndReselectedBy(d("2026-11-18"))).isEqualTo(d("2026-09-30"));
        assertThat(UsMarketCalendar.latestQuarterEndReselectedBy(d("2027-02-16"))).isEqualTo(d("2026-09-30"));
        assertThat(UsMarketCalendar.latestQuarterEndReselectedBy(d("2027-02-17"))).isEqualTo(d("2026-12-31"));
        // 분기 말 당일은 그 분기가 아니라 앞 분기들 중에서
        assertThat(UsMarketCalendar.latestQuarterEndReselectedBy(d("2026-09-30"))).isEqualTo(d("2026-06-30"));
        assertThat(UsMarketCalendar.latestQuarterEndReselectedBy(d("2026-08-16"))).isEqualTo(d("2026-03-31"));   // 일요일
        assertThat(UsMarketCalendar.latestQuarterEndReselectedBy(d("2026-08-17"))).isEqualTo(d("2026-06-30"));
    }
}
