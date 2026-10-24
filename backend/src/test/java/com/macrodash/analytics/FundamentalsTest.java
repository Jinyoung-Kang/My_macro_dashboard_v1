package com.macrodash.analytics;

import com.macrodash.analytics.Fundamentals.Fact;
import com.macrodash.analytics.Fundamentals.Known;
import com.macrodash.analytics.Fundamentals.Quarter;
import com.macrodash.analytics.Fundamentals.Result;
import com.macrodash.analytics.Fundamentals.Row;
import com.macrodash.analytics.Fundamentals.Span;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 재무(D28) 순수 계산 — 값은 전부 손으로 계산한 식을 그대로 적었습니다. 고정 자료(AAPL·JPM 모양)의 숫자는 단계 0-b 측정
 * (2026-10-25)에서 본 그 시점 값이고, 측정에 없던 칸(전년 누계 등)은 모양만 맞춘 값입니다.
 */
class FundamentalsTest {

    private static final double M = 1_000_000.0;
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 23);

    /** 공시일별 사실 묶음을 만드는 도우미 — 같은 날 두 공시는 수집기처럼 한 행에 합칩니다. */
    private static final class Filings {
        private final Map<LocalDate, List<Fact>> byDay = new TreeMap<>();
        private LocalDate day;
        private String form;
        private String accn;
        private String unit = "USD";

        Filings on(String filed, String form, String accn) {
            this.day = LocalDate.parse(filed);
            this.form = form;
            this.accn = accn;
            this.unit = "USD";
            return this;
        }

        Filings unit(String unit) {
            this.unit = unit;
            return this;
        }

        Filings flow(String concept, String start, String end, double value) {
            return add(concept, unit, start, end, value);
        }

        Filings stock(String concept, String end, double value) {
            return add(concept, unit, null, end, value);
        }

        Filings shares(String concept, String start, String end, double value) {
            return add(concept, "shares", start, end, value);
        }

        private Filings add(String concept, String u, String start, String end, double value) {
            int colon = concept.indexOf(':');
            byDay.computeIfAbsent(day, d -> new ArrayList<>()).add(new Fact(concept.substring(0, colon),
                    concept.substring(colon + 1), u, start == null ? null : LocalDate.parse(start),
                    LocalDate.parse(end), value, form, accn));
            return this;
        }

        List<Row> rows() {
            List<Row> out = new ArrayList<>();
            byDay.forEach((d, facts) -> out.add(new Row(d, facts)));
            return out;
        }
    }

    private static final String REV_ASC = "us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax";
    private static final String NI = "us-gaap:NetIncomeLoss";
    private static final String OCF = "us-gaap:NetCashProvidedByUsedInOperatingActivities";
    private static final String CAPEX = "us-gaap:PaymentsToAcquirePropertyPlantAndEquipment";
    private static final String BUYBACK = "us-gaap:PaymentsForRepurchaseOfCommonStock";
    private static final String DIVIDEND = "us-gaap:PaymentsOfDividends";
    private static final String ASSETS = "us-gaap:Assets";
    private static final String EQUITY = "us-gaap:StockholdersEquity";
    private static final String RECEIVABLES = "us-gaap:AccountsReceivableNetCurrent";
    private static final String INVENTORY = "us-gaap:InventoryNet";
    private static final String LTD = "us-gaap:LongTermDebt";
    private static final String DEI_SHARES = "dei:EntityCommonStockSharesOutstanding";
    private static final String DILUTED = "us-gaap:WeightedAverageNumberOfDilutedSharesOutstanding";

    /**
     * AAPL 모양 — 52/53주 회계연도(9월 마지막 토요일 결산), 10-Q 현금흐름은 9개월 누계뿐. FY2026 3분기 10-Q(2026-07-31)가
     * 기준일 2026-10-23에 알 수 있던 최신 공시입니다.
     */
    private static List<Row> aaplLike() {
        Filings f = new Filings();
        f.on("2024-08-02", "10-Q", "0000320193-24-000081")
                .flow(REV_ASC, "2023-10-01", "2024-06-29", 296_105 * M);
        f.on("2024-11-01", "10-K", "0000320193-24-000123")
                .flow(REV_ASC, "2023-10-01", "2024-09-28", 391_035 * M);
        f.on("2025-08-01", "10-Q", "0000320193-25-000073")
                .flow(REV_ASC, "2024-09-29", "2025-06-28", 313_695 * M)
                .flow(REV_ASC, "2025-03-30", "2025-06-28", 94_036 * M)
                .flow(NI, "2024-09-29", "2025-06-28", 84_544 * M)
                .flow(OCF, "2024-09-29", "2025-06-28", 81_754 * M)
                .flow(CAPEX, "2024-09-29", "2025-06-28", 9_473 * M)
                .flow(BUYBACK, "2024-09-29", "2025-06-28", 69_904 * M)
                .flow(DIVIDEND, "2024-09-29", "2025-06-28", 11_566 * M)
                .stock(ASSETS, "2025-06-28", 331_495 * M)
                .stock(EQUITY, "2025-06-28", 65_830 * M)
                .stock(RECEIVABLES, "2025-06-28", 27_557 * M)
                .stock(INVENTORY, "2025-06-28", 5_925 * M)
                .shares(DEI_SHARES, null, "2025-07-18", 14_935_826_000.0);
        f.on("2025-10-31", "10-K", "0000320193-25-000079")
                .flow(REV_ASC, "2024-09-29", "2025-09-27", 416_161 * M)
                .flow(NI, "2024-09-29", "2025-09-27", 112_010 * M)
                .flow(OCF, "2024-09-29", "2025-09-27", 111_482 * M)
                .flow(CAPEX, "2024-09-29", "2025-09-27", 12_715 * M)
                .flow(BUYBACK, "2024-09-29", "2025-09-27", 90_711 * M)
                .flow(DIVIDEND, "2024-09-29", "2025-09-27", 15_421 * M);
        f.on("2026-07-31", "10-Q", "0000320193-26-000020")
                .flow(REV_ASC, "2025-09-28", "2026-06-27", 364_357 * M)
                .flow(REV_ASC, "2026-03-29", "2026-06-27", 109_417 * M)
                .flow(NI, "2025-09-28", "2026-06-27", 101_464 * M)
                .flow(NI, "2026-03-29", "2026-06-27", 29_789 * M)
                .flow(OCF, "2025-09-28", "2026-06-27", 116_996 * M)
                .flow(CAPEX, "2025-09-28", "2026-06-27", 6_799 * M)
                .flow(BUYBACK, "2025-09-28", "2026-06-27", 62_094 * M)
                .flow(DIVIDEND, "2025-09-28", "2026-06-27", 11_778 * M)
                .stock(ASSETS, "2026-06-27", 383_266 * M)
                .stock(EQUITY, "2026-06-27", 107_520 * M)
                .stock(RECEIVABLES, "2026-06-27", 31_398 * M)
                .stock(INVENTORY, "2026-06-27", 11_092 * M)
                .stock(LTD, "2026-06-27", 82_300 * M)
                .shares(DEI_SHARES, null, "2026-07-17", 14_594_180_000.0)
                .shares(DILUTED, "2026-03-29", "2026-06-27", 14_714_676_000.0)
                .shares(DILUTED, "2025-09-28", "2026-06-27", 14_750_302_000.0);
        return f.rows();
    }

    @Test
    @DisplayName("기간은 시작·끝 길이로만 나눈다 — 52/53주(363·370일)·14주 분기(98일)·COST 9개월(251일)·12~17주 분기·24~28주·36~40주 누계")
    void spanByDuration() {
        assertThat(Fundamentals.span(LocalDate.parse("2024-09-29"), LocalDate.parse("2025-09-27"))).isEqualTo(Span.YEAR);
        assertThat(Fundamentals.span(LocalDate.parse("2022-09-25"), LocalDate.parse("2023-09-30"))).isEqualTo(Span.YEAR);
        assertThat(Fundamentals.span(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"))).isEqualTo(Span.YEAR);
        assertThat(Fundamentals.span(LocalDate.parse("2025-09-28"), LocalDate.parse("2026-06-27")))
                .isEqualTo(Span.NINE_MONTHS);
        assertThat(Fundamentals.span(LocalDate.parse("2025-09-01"), LocalDate.parse("2026-05-10")))
                .isEqualTo(Span.NINE_MONTHS); // 251일
        assertThat(Fundamentals.span(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-30"))).isEqualTo(Span.HALF);
        assertThat(Fundamentals.span(LocalDate.parse("2026-03-29"), LocalDate.parse("2026-06-27"))).isEqualTo(Span.QUARTER);
        assertThat(Fundamentals.span(LocalDate.parse("2022-09-25"), LocalDate.parse("2023-01-01"))).isEqualTo(Span.QUARTER);

        // 12-12-12-16주(COST)·16-12-12-12주(KR) 달력 — 16주 분기(111일)·24주 누계(167일)·28주 누계(195일)도 분류됩니다.
        assertThat(Fundamentals.span(LocalDate.parse("2025-05-12"), LocalDate.parse("2025-08-31")))
                .isEqualTo(Span.QUARTER); // COST 16주 4분기
        assertThat(Fundamentals.span(LocalDate.parse("2026-02-01"), LocalDate.parse("2026-05-23")))
                .isEqualTo(Span.QUARTER); // KR 16주 1분기
        assertThat(Fundamentals.span(LocalDate.parse("2025-09-01"), LocalDate.parse("2026-02-15")))
                .isEqualTo(Span.HALF); // COST 24주 누계
        assertThat(Fundamentals.span(LocalDate.parse("2026-02-01"), LocalDate.parse("2026-08-15")))
                .isEqualTo(Span.HALF); // KR 28주 누계
        assertThat(Fundamentals.span(LocalDate.parse("2025-02-02"), LocalDate.parse("2025-11-08")))
                .isEqualTo(Span.NINE_MONTHS); // KR 40주 누계(279일)

        LocalDate s = LocalDate.parse("2026-01-01");
        assertThat(Fundamentals.span(s, s.plusDays(79))).isNull();
        assertThat(Fundamentals.span(s, s.plusDays(80))).isEqualTo(Span.QUARTER);
        assertThat(Fundamentals.span(s, s.plusDays(120))).isEqualTo(Span.QUARTER); // 17주 + α
        assertThat(Fundamentals.span(s, s.plusDays(121))).isNull();
        assertThat(Fundamentals.span(s, s.plusDays(159))).isNull();
        assertThat(Fundamentals.span(s, s.plusDays(160))).isEqualTo(Span.HALF);
        assertThat(Fundamentals.span(s, s.plusDays(200))).isEqualTo(Span.HALF);
        assertThat(Fundamentals.span(s, s.plusDays(201))).isNull();
        assertThat(Fundamentals.span(s, s.plusDays(239))).isNull();
        assertThat(Fundamentals.span(s, s.plusDays(240))).isEqualTo(Span.NINE_MONTHS);
        assertThat(Fundamentals.span(s, s.plusDays(290))).isEqualTo(Span.NINE_MONTHS);
        assertThat(Fundamentals.span(s, s.plusDays(291))).isNull();
        assertThat(Fundamentals.span(s, s.plusDays(349))).isNull();
        assertThat(Fundamentals.span(s, s.plusDays(350))).isEqualTo(Span.YEAR);
        assertThat(Fundamentals.span(s, s.plusDays(380))).isEqualTo(Span.YEAR);
        assertThat(Fundamentals.span(s, s.plusDays(381))).isNull();
        assertThat(Fundamentals.span(null, s)).isNull(); // 시점 사실
    }

    @Test
    @DisplayName("AAPL 모양 — 9개월 누계로 TTM(직전 연간 + 당해 누계 − 전년 누계), 듀퐁 세 항의 곱 = ROE, 빈 표시 없음")
    void aaplLikeTtmAndDupont() {
        Result r = Fundamentals.compute(aaplLike(), AS_OF);

        assertThat(r.latestPeriodEnd()).isEqualTo(LocalDate.parse("2026-06-27"));
        assertThat(r.filed()).isEqualTo(LocalDate.parse("2026-07-31"));
        assertThat(r.form()).isEqualTo("10-Q");
        assertThat(r.currency()).isEqualTo("USD");
        assertThat(r.taxonomy()).isEqualTo("us-gaap"); // dei(표지 주식수)는 분류체계 표시에서 뺌
        assertThat(r.isStale()).isFalse();

        // 매출 TTM = FY2025 416,161 + 9M FY2026 364,357 − 9M FY2025 313,695 = 466,823(백만)
        assertThat(r.revenueTtm()).isCloseTo(466_823 * M, within(1e-3));
        // 1년 전 TTM(2025-06-28) = FY2024 391,035 + 9M FY2025 313,695 − 9M FY2024 296,105 = 408,625 → +14.24%
        assertThat(r.revenueYoY()).isCloseTo((466_823.0 / 408_625.0 - 1) * 100, within(1e-9));
        // 순이익 TTM = 112,010 + 101,464 − 84,544 = 128,930
        assertThat(r.netIncomeTtm()).isCloseTo(128_930 * M, within(1e-3));
        assertThat(r.netMargin()).isCloseTo(128_930.0 / 466_823.0 * 100, within(1e-9)); // 27.62%
        // 영업현금흐름 TTM = 111,482 + 116,996 − 81,754 = 146,724 (10-Q는 9개월 누계뿐)
        assertThat(r.ocfTtm()).isCloseTo(146_724 * M, within(1e-3));
        // 설비투자 TTM = 12,715 + 6,799 − 9,473 = 10,041 → FCF 136,683
        assertThat(r.capexTtm()).isCloseTo(10_041 * M, within(1e-3));
        assertThat(r.fcfTtm()).isCloseTo(136_683 * M, within(1e-3));
        assertThat(r.fcfConversion()).isCloseTo(136_683.0 / 128_930.0 * 100, within(1e-9)); // 106.01%
        assertThat(r.fcfConversionReason()).isNull();
        // 자사주 90,711 + 62,094 − 69,904 = 82,901 / 배당 15,421 + 11,778 − 11,566 = 15,633
        assertThat(r.buybacksTtm()).isCloseTo(82_901 * M, within(1e-3));
        assertThat(r.dividendsTtm()).isCloseTo(15_633 * M, within(1e-3));

        // 평균 자산 = (383,266 + 331,495)/2 = 357,380.5, 평균 자본 = (107,520 + 65,830)/2 = 86,675
        assertThat(r.assetTurnover()).isCloseTo(466_823.0 / 357_380.5, within(1e-12)); // 1.31배
        assertThat(r.leverage()).isCloseTo(357_380.5 / 86_675.0, within(1e-12)); // 4.12배
        assertThat(r.roe()).isCloseTo(128_930.0 / 86_675.0 * 100, within(1e-9)); // 148.75%
        assertThat(r.roe()).isCloseTo(r.netMargin() * r.assetTurnover() * r.leverage(), within(1e-9));

        assertThat(r.receivablesYoY()).isCloseTo((31_398.0 / 27_557.0 - 1) * 100, within(1e-9));
        assertThat(r.inventoryYoY()).isCloseTo((11_092.0 / 5_925.0 - 1) * 100, within(1e-9));
        // 표지 주식수 2026-07-17 vs 2025-07-18(1년 전과 가장 가까운 날) — 희석 가중평균보다 end가 늦어 dei를 씀
        assertThat(r.sharesYoY()).isCloseTo((14_594_180_000.0 / 14_935_826_000.0 - 1) * 100, within(1e-9));
        assertThat(r.isProxy()).isFalse();
        assertThat(r.longTermDebt()).isCloseTo(82_300 * M, within(1e-3));
        assertThat(r.debtToEquity()).isCloseTo(82_300.0 / 107_520.0 * 100, within(1e-9));

        assertThat(r.conceptsUsed()).containsExactly(
                Map.entry("revenue", REV_ASC), Map.entry("netIncome", NI), Map.entry("ocf", OCF),
                Map.entry("capex", CAPEX), Map.entry("assets", ASSETS), Map.entry("equity", EQUITY),
                Map.entry("receivables", RECEIVABLES), Map.entry("inventory", INVENTORY),
                Map.entry("longTermDebt", LTD), Map.entry("buybacks", BUYBACK), Map.entry("dividends", DIVIDEND),
                Map.entry("shares", DEI_SHARES));
        assertThat(r.notes()).isEmpty();
    }

    /** 달력 회계연도 회사의 순이익 — 3개월 사실(Q2·Q3·다음 해 Q1)과 9개월 누계·연간. */
    private static Filings calendarCompany(boolean withPriorQ1) {
        Filings f = new Filings();
        if (withPriorQ1) {
            f.on("2025-05-01", "10-Q", "q1-25").flow(NI, "2025-01-01", "2025-03-31", 85 * M);
        }
        f.on("2025-08-01", "10-Q", "q2-25").flow(NI, "2025-04-01", "2025-06-30", 100 * M);
        f.on("2025-11-01", "10-Q", "q3-25")
                .flow(NI, "2025-07-01", "2025-09-30", 100 * M)
                .flow(NI, "2025-01-01", "2025-09-30", 290 * M);
        f.on("2026-02-15", "10-K", "fy-25").flow(NI, "2025-01-01", "2025-12-31", 400 * M);
        f.on("2026-05-01", "10-Q", "q1-26").flow(NI, "2026-01-01", "2026-03-31", 120 * M);
        return f;
    }

    @Test
    @DisplayName("4분기는 연간 − 9개월 누계로 만든다 — 전년 1분기가 없어 연간 기준이 안 되면 최근 4개 분기 합")
    void q4DerivedFromAnnualMinusNineMonths() {
        List<Row> rows = calendarCompany(false).rows();

        NavigableMap<LocalDate, Quarter> quarters = Fundamentals.quarters(known(rows, NI));
        // Q4 2025 = FY 400 − 9M 290 = 110, 기간은 9개월 끝 다음 날부터
        assertThat(quarters.get(LocalDate.parse("2025-12-31")))
                .isEqualTo(new Quarter(LocalDate.parse("2025-10-01"), LocalDate.parse("2025-12-31"), 110 * M, true));
        assertThat(quarters.get(LocalDate.parse("2025-09-30")).derived()).isFalse(); // 3개월 사실이 있으면 그것

        Result r = Fundamentals.compute(rows, AS_OF);
        assertThat(r.latestPeriodEnd()).isEqualTo(LocalDate.parse("2026-03-31"));
        // TTM = Q1'26 120 + Q4'25 110 + Q3'25 100 + Q2'25 100 = 430
        assertThat(r.netIncomeTtm()).isCloseTo(430 * M, within(1e-6));
        assertThat(r.notes()).isEmpty();
    }

    @Test
    @DisplayName("누계 차감 — Q2 = 6M − Q1, Q3 = 9M − 6M, Q4 = FY − 9M, 두 TTM 방법이 같으면 표시 없음")
    void ytdSubtractionForCashFlows() {
        Filings f = new Filings();
        f.on("2025-05-01", "10-Q", "a").flow(OCF, "2025-01-01", "2025-03-31", 30 * M);
        f.on("2025-08-01", "10-Q", "b").flow(OCF, "2025-01-01", "2025-06-30", 70 * M);
        f.on("2025-11-01", "10-Q", "c").flow(OCF, "2025-01-01", "2025-09-30", 120 * M);
        f.on("2026-02-15", "10-K", "d").flow(OCF, "2025-01-01", "2025-12-31", 170 * M);
        f.on("2026-05-01", "10-Q", "e").flow(OCF, "2026-01-01", "2026-03-31", 35 * M);
        List<Row> rows = f.rows();

        NavigableMap<LocalDate, Quarter> q = Fundamentals.quarters(known(rows, OCF));
        assertThat(q.get(LocalDate.parse("2025-03-31")).value()).isEqualTo(30 * M);
        assertThat(q.get(LocalDate.parse("2025-06-30"))).isEqualTo(
                new Quarter(LocalDate.parse("2025-04-01"), LocalDate.parse("2025-06-30"), 40 * M, true));
        assertThat(q.get(LocalDate.parse("2025-09-30"))).isEqualTo(
                new Quarter(LocalDate.parse("2025-07-01"), LocalDate.parse("2025-09-30"), 50 * M, true));
        assertThat(q.get(LocalDate.parse("2025-12-31"))).isEqualTo(
                new Quarter(LocalDate.parse("2025-10-01"), LocalDate.parse("2025-12-31"), 50 * M, true));

        Result r = Fundamentals.compute(rows, AS_OF);
        // 연간 기준 170 + 35 − 30 = 175 = 분기 합 35 + 50 + 50 + 40
        assertThat(r.ocfTtm()).isCloseTo(175 * M, within(1e-6));
        assertThat(r.notes()).isEmpty();
    }

    @Test
    @DisplayName("연간 기준과 분기 합이 둘 다 되는데 다르면 연간 기준 + 표시")
    void annualBasisWinsOverQuarterSum() {
        Result r = Fundamentals.compute(calendarCompany(true).rows(), AS_OF);
        // 연간 기준 400 + 120 − 85 = 435, 분기 합 430(1분기+2분기+3분기 285 ≠ 9개월 누계 290)
        assertThat(r.netIncomeTtm()).isCloseTo(435 * M, within(1e-6));
        assertThat(r.notes()).containsExactly("netIncome TTM: 연간 기준과 최근 4개 분기 합이 달라 연간 기준을 씀");
    }

    /** GPRO 모양 — 2025년 3분기 순이익이 2026-10-19 정정(10-Q/A)으로 바뀜, 2026-11-05 공시는 기준일 뒤. */
    private static List<Row> restated() {
        Filings f = new Filings();
        f.on("2024-11-07", "10-Q", "q3-24")
                .flow(NI, "2024-01-01", "2024-09-30", -250 * M)
                .flow(OCF, "2024-01-01", "2024-09-30", -40 * M)
                .flow(CAPEX, "2024-01-01", "2024-09-30", 6 * M);
        f.on("2025-03-01", "10-K", "fy-24")
                .flow(NI, "2024-01-01", "2024-12-31", -432 * M)
                .flow(OCF, "2024-01-01", "2024-12-31", -60 * M)
                .flow(CAPEX, "2024-01-01", "2024-12-31", 8 * M);
        f.on("2025-11-06", "10-Q", "q3-25")
                .flow(NI, "2025-01-01", "2025-09-30", -84_383_000)
                .flow(NI, "2025-07-01", "2025-09-30", -21_252_000)
                .flow(OCF, "2025-01-01", "2025-09-30", -30 * M)
                .flow(CAPEX, "2025-01-01", "2025-09-30", 5 * M);
        f.on("2026-10-19", "10-Q/A", "q3-25-a")
                .flow(NI, "2025-01-01", "2025-09-30", -90_549_000)
                .flow(NI, "2025-07-01", "2025-09-30", -27_418_000);
        f.on("2026-11-05", "10-Q", "q3-26")
                .flow(NI, "2026-01-01", "2026-09-30", -10 * M)
                .flow(OCF, "2026-01-01", "2026-09-30", 1 * M);
        return f.rows();
    }

    @Test
    @DisplayName("시점 규칙 — 기준일 뒤 정정은 무시, 기준일 전 정정이 원래 값을 이긴다, 기준일 뒤 공시의 새 기간도 무시")
    void restatementRespectsFiledDate() {
        // 정정 전날: 원래 10-Q 값. TTM = −432 + (−84.383) − (−250) = −266.383(백만)
        Result before = Fundamentals.compute(restated(), LocalDate.parse("2026-10-18"));
        assertThat(before.netIncomeTtm()).isCloseTo(-266_383_000.0, within(1e-6));
        assertThat(before.filed()).isEqualTo(LocalDate.parse("2025-11-06"));
        assertThat(before.form()).isEqualTo("10-Q");

        // 정정 뒤: 10-Q/A 값. TTM = −432 + (−90.549) − (−250) = −272.549
        Result after = Fundamentals.compute(restated(), AS_OF);
        assertThat(after.netIncomeTtm()).isCloseTo(-272_549_000.0, within(1e-6));
        assertThat(after.filed()).isEqualTo(LocalDate.parse("2026-10-19"));
        assertThat(after.form()).isEqualTo("10-Q/A");
        // 2026-11-05 공시(2026-09-30 기간)는 기준일 뒤라 최신 기간이 그대로
        assertThat(after.latestPeriodEnd()).isEqualTo(LocalDate.parse("2025-09-30"));
    }

    @Test
    @DisplayName("순이익 ≤ 0이면 FCF 전환율은 null + 사유(FCF 자체는 냄)")
    void negativeNetIncomeHasNoConversion() {
        Result r = Fundamentals.compute(restated(), AS_OF);
        // OCF TTM = −60 + (−30) − (−40) = −50, capex TTM = 8 + 5 − 6 = 7 → FCF −57
        assertThat(r.ocfTtm()).isCloseTo(-50 * M, within(1e-6));
        assertThat(r.capexTtm()).isCloseTo(7 * M, within(1e-6));
        assertThat(r.fcfTtm()).isCloseTo(-57 * M, within(1e-6));
        assertThat(r.fcfConversion()).isNull();
        assertThat(r.fcfConversionReason()).isEqualTo("순이익 ≤ 0 — 전환율 의미 없음");
        // 자료는 있는데 비율이 뜻이 없어 비운 값 — 패킷의 '빈 값 = 자료 없음'과 다르므로 메모로도 냅니다(사유 칸은 패킷 열이 아님).
        assertThat(r.notes()).contains("fcfConversion: 순이익 ≤ 0 — 전환율 의미 없음");
        // 매출 사실이 없으면 매출 기반 지표는 0이 아니라 null
        assertThat(r.revenueTtm()).isNull();
        assertThat(r.netMargin()).isNull();
        assertThat(r.conceptsUsed()).doesNotContainKey("revenue");
    }

    private static final String REVENUES = "us-gaap:Revenues";
    private static final String RNOIE = "us-gaap:RevenuesNetOfInterestExpense";
    private static final String LTD_INCL = "us-gaap:LongTermDebtAndCapitalLeaseObligationsIncludingCurrentMaturities";

    /** JPM 모양 — {@code Revenues}는 10-K에만, 분기·연간 모두 {@code RevenuesNetOfInterestExpense}. 설비투자 없음. */
    private static List<Row> jpmLike() {
        Filings f = new Filings();
        f.on("2024-02-16", "10-K", "fy-23").stock(LTD, "2023-12-31", 391_825 * M);
        f.on("2024-08-02", "10-Q", "q2-24").flow(RNOIE, "2024-01-01", "2024-06-30", 85_000 * M);
        f.on("2025-02-14", "10-K", "fy-24")
                .flow(REVENUES, "2024-01-01", "2024-12-31", 177_556 * M)
                .flow(RNOIE, "2024-01-01", "2024-12-31", 175_000 * M);
        f.on("2025-08-05", "10-Q", "q2-25")
                .flow(RNOIE, "2025-01-01", "2025-06-30", 89_000 * M)
                .flow(NI, "2025-01-01", "2025-06-30", 30_000 * M)
                .flow(OCF, "2025-01-01", "2025-06-30", -110_000 * M)
                .stock(ASSETS, "2025-06-30", 4_552_482 * M); // 1년 전 자본은 일부러 뺌
        f.on("2026-02-13", "10-K", "0001628280-26-008131")
                .flow(REVENUES, "2025-01-01", "2025-12-31", 182_447 * M)
                .flow(RNOIE, "2025-01-01", "2025-12-31", 180_000 * M)
                .flow(NI, "2025-01-01", "2025-12-31", 57_048 * M)
                .flow(OCF, "2025-01-01", "2025-12-31", -42_012 * M);
        f.on("2026-08-06", "10-Q", "0001628280-26-054343")
                .flow(RNOIE, "2026-01-01", "2026-06-30", 92_000 * M)
                .flow(RNOIE, "2026-04-01", "2026-06-30", 47_000 * M)
                .flow(NI, "2026-01-01", "2026-06-30", 37_649 * M)
                .flow(NI, "2026-04-01", "2026-06-30", 21_155 * M)
                .flow(OCF, "2026-01-01", "2026-06-30", -237_044 * M)
                .stock(ASSETS, "2026-06-30", 5_015_069 * M)
                .stock(EQUITY, "2026-06-30", 374_598 * M)
                .stock(LTD_INCL, "2026-06-30", 460_523 * M);
        return f.rows();
    }

    @Test
    @DisplayName("JPM 모양 — 가장 최근 end의 개념을 고르고 시계열 안에서 섞지 않는다(Revenues 연간 값을 끌어오지 않음)")
    void mostRecentEndConceptWithoutMixing() {
        Result r = Fundamentals.compute(jpmLike(), AS_OF);

        assertThat(r.latestPeriodEnd()).isEqualTo(LocalDate.parse("2026-06-30"));
        assertThat(r.conceptsUsed()).containsEntry("revenue", RNOIE).containsEntry("longTermDebt", LTD_INCL);
        // RNOIE만으로 180,000 + 92,000 − 89,000 = 183,000 (Revenues FY 182,447을 섞으면 185,447이 됨)
        assertThat(r.revenueTtm()).isCloseTo(183_000 * M, within(1e-3));
        // 1년 전 = 175,000 + 89,000 − 85,000 = 179,000
        assertThat(r.revenueYoY()).isCloseTo((183_000.0 / 179_000.0 - 1) * 100, within(1e-9));
        assertThat(r.netIncomeTtm()).isCloseTo((57_048 + 37_649 - 30_000) * M, within(1e-3)); // 64,697
        assertThat(r.ocfTtm()).isCloseTo((-42_012 - 237_044 + 110_000) * M, within(1e-3)); // −169,056
        assertThat(r.longTermDebt()).isCloseTo(460_523 * M, within(1e-3)); // 2023년에 멈춘 LongTermDebt가 아님
    }

    @Test
    @DisplayName("설비투자가 없으면 FCF·전환율은 0이 아니라 null + 사유, 1년 전 자본이 없으면 최신 자본으로 평균 + 표시")
    void missingCapexAndAverageFallback() {
        Result r = Fundamentals.compute(jpmLike(), AS_OF);

        assertThat(r.capexTtm()).isNull();
        assertThat(r.fcfTtm()).isNull();
        assertThat(r.fcfConversion()).isNull();
        assertThat(r.fcfConversionReason()).isEqualTo("설비투자 자료 없음 — FCF 없음");
        assertThat(r.buybacksTtm()).isNull();
        assertThat(r.dividendsTtm()).isNull();
        assertThat(r.receivablesYoY()).isNull();
        assertThat(r.conceptsUsed()).doesNotContainKeys("capex", "buybacks", "dividends", "receivables");

        double avgAssets = (5_015_069.0 + 4_552_482.0) / 2; // 4,783,775.5
        double equity = 374_598.0; // 1년 전 자본이 없어 최신 값만
        assertThat(r.assetTurnover()).isCloseTo(183_000.0 / avgAssets, within(1e-12));
        assertThat(r.leverage()).isCloseTo(avgAssets / equity, within(1e-12)); // 12.77배
        assertThat(r.roe()).isCloseTo(64_697.0 / equity * 100, within(1e-9)); // 17.27%
        assertThat(r.roe()).isCloseTo(r.netMargin() * r.assetTurnover() * r.leverage(), within(1e-9));
        assertThat(r.debtToEquity()).isCloseTo(460_523.0 / equity * 100, within(1e-9));
        assertThat(r.notes()).containsExactly("equity 평균: 1년 전 시점 값이 없어 최신 값만 씀");
    }

    @Test
    @DisplayName("end가 같은 후보는 후보 순서대로(Revenues가 RevenueFromContract…보다 앞)")
    void tieGoesToCandidateOrder() {
        Filings f = new Filings();
        f.on("2026-02-20", "10-K", "x")
                .flow(REV_ASC, "2025-01-01", "2025-12-31", 900 * M)
                .flow(REVENUES, "2025-01-01", "2025-12-31", 1_000 * M);
        Result r = Fundamentals.compute(f.rows(), AS_OF);
        assertThat(r.conceptsUsed()).containsEntry("revenue", REVENUES);
        assertThat(r.revenueTtm()).isCloseTo(1_000 * M, within(1e-6));
    }

    /** SAP 모양 — IFRS·EUR, 20-F 연간만. 주식수는 가중평균(대용치)뿐. */
    private static List<Row> eurIfrs() {
        Filings f = new Filings();
        f.on("2025-03-12", "20-F", "fy-24").unit("EUR")
                .flow("ifrs-full:Revenue", "2024-01-01", "2024-12-31", 34_176 * M)
                .flow("ifrs-full:ProfitLossAttributableToOwnersOfParent", "2024-01-01", "2024-12-31", 3_150 * M)
                .stock("ifrs-full:Assets", "2024-12-31", 74_000 * M)
                .stock("ifrs-full:EquityAttributableToOwnersOfParent", "2024-12-31", 43_000 * M)
                .shares("ifrs-full:AdjustedWeightedAverageShares", "2024-01-01", "2024-12-31", 1_168 * M);
        f.on("2026-03-10", "20-F", "fy-25").unit("EUR")
                .flow("ifrs-full:Revenue", "2025-01-01", "2025-12-31", 36_800 * M)
                .flow("ifrs-full:ProfitLossAttributableToOwnersOfParent", "2025-01-01", "2025-12-31", 7_000 * M)
                .flow("ifrs-full:CashFlowsFromUsedInOperatingActivities", "2025-01-01", "2025-12-31", 8_000 * M)
                .flow("ifrs-full:PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities",
                        "2025-01-01", "2025-12-31", 900 * M)
                .flow("ifrs-full:PaymentsToAcquireOrRedeemEntitysShares", "2025-01-01", "2025-12-31", 1_500 * M)
                .flow("ifrs-full:DividendsPaidClassifiedAsFinancingActivities", "2025-01-01", "2025-12-31",
                        2_700 * M)
                .stock("ifrs-full:Assets", "2025-12-31", 75_000 * M)
                .stock("ifrs-full:EquityAttributableToOwnersOfParent", "2025-12-31", 44_000 * M)
                .stock("ifrs-full:LongtermBorrowings", "2025-12-31", 7_000 * M)
                .shares("ifrs-full:AdjustedWeightedAverageShares", "2025-01-01", "2025-12-31", 1_166 * M);
        return f.rows();
    }

    @Test
    @DisplayName("EUR·IFRS — 금액은 전부 null, 비율·성장률·듀퐁은 낸다, 가중평균 주식수는 isProxy")
    void nonUsdHasRatiosOnly() {
        Result r = Fundamentals.compute(eurIfrs(), AS_OF);

        assertThat(r.currency()).isEqualTo("EUR");
        assertThat(r.taxonomy()).isEqualTo("ifrs-full");
        assertThat(r.latestPeriodEnd()).isEqualTo(LocalDate.parse("2025-12-31"));
        assertThat(r.form()).isEqualTo("20-F");
        assertThat(r.isStale()).isFalse();
        assertThat(r.revenueTtm()).isNull();
        assertThat(r.netIncomeTtm()).isNull();
        assertThat(r.ocfTtm()).isNull();
        assertThat(r.capexTtm()).isNull();
        assertThat(r.fcfTtm()).isNull();
        assertThat(r.buybacksTtm()).isNull();
        assertThat(r.dividendsTtm()).isNull();
        assertThat(r.longTermDebt()).isNull();

        assertThat(r.revenueYoY()).isCloseTo((36_800.0 / 34_176.0 - 1) * 100, within(1e-9)); // 7.68%
        assertThat(r.netMargin()).isCloseTo(7_000.0 / 36_800.0 * 100, within(1e-9));
        assertThat(r.fcfConversion()).isCloseTo((8_000.0 - 900.0) / 7_000.0 * 100, within(1e-9));
        assertThat(r.assetTurnover()).isCloseTo(36_800.0 / 74_500.0, within(1e-12));
        assertThat(r.leverage()).isCloseTo(74_500.0 / 43_500.0, within(1e-12));
        assertThat(r.roe()).isCloseTo(7_000.0 / 43_500.0 * 100, within(1e-9));
        assertThat(r.roe()).isCloseTo(r.netMargin() * r.assetTurnover() * r.leverage(), within(1e-9));
        assertThat(r.debtToEquity()).isCloseTo(7_000.0 / 44_000.0 * 100, within(1e-9));
        assertThat(r.sharesYoY()).isCloseTo((1_166.0 / 1_168.0 - 1) * 100, within(1e-9));
        assertThat(r.isProxy()).isTrue();
        assertThat(r.conceptsUsed()).containsEntry("revenue", "ifrs-full:Revenue")
                .containsEntry("shares", "ifrs-full:AdjustedWeightedAverageShares");
        assertThat(r.notes()).containsExactly(
                "보고 통화 EUR — 금액은 비우고 비율·성장률만 냄(환율·ADS 비율 없이 금액을 비교하지 않게)");
    }

    @Test
    @DisplayName("보고 통화 — 최근 연도만 실린 USD 환산값이 아니라 사실이 더 많은 CNY(중국 ADR 모양)")
    void reportingCurrencyIsNotConvenienceUsd() {
        Filings f = new Filings();
        f.on("2025-06-26", "20-F", "fy-25").unit("CNY")
                .flow(REVENUES, "2024-04-01", "2025-03-31", 996_347 * M);
        f.on("2026-06-25", "20-F", "fy-26").unit("CNY")
                .flow(REVENUES, "2025-04-01", "2026-03-31", 1_050_000 * M)
                .unit("USD")
                .flow(REVENUES, "2025-04-01", "2026-03-31", 146_000 * M);
        Result r = Fundamentals.compute(f.rows(), AS_OF);
        assertThat(r.currency()).isEqualTo("CNY");
        assertThat(r.revenueTtm()).isNull();
        assertThat(r.revenueYoY()).isCloseTo((1_050_000.0 / 996_347.0 - 1) * 100, within(1e-9));
    }

    @Test
    @DisplayName("isStale — 최신 기간 end가 기준일보다 15개월 넘게 앞섬(TSM 모양: IFRS 2025 연보 없음, dei만 들어옴)")
    void staleAfterFifteenMonths() {
        Filings tsm = new Filings();
        tsm.on("2025-04-17", "20-F", "fy-24").unit("TWD")
                .flow("ifrs-full:Revenue", "2024-01-01", "2024-12-31", 2_894_308 * M)
                .stock("ifrs-full:Assets", "2024-12-31", 6_691_938 * M);
        tsm.on("2026-04-16", "20-F", "fy-25")
                .shares(DEI_SHARES, null, "2025-12-31", 25_932 * M);
        Result r = Fundamentals.compute(tsm.rows(), AS_OF);
        assertThat(r.latestPeriodEnd()).isEqualTo(LocalDate.parse("2024-12-31")); // 표지 주식수는 기준 기간이 아님
        assertThat(r.isStale()).isTrue();

        Filings edge = new Filings();
        edge.on("2025-09-01", "10-Q", "q").stock(ASSETS, "2025-07-31", 10 * M);
        // 2025-07-31 + 15개월 = 2026-10-31 — 그날까지는 신선, 다음 날부터 오래됨
        assertThat(Fundamentals.compute(edge.rows(), LocalDate.parse("2026-10-31")).isStale()).isFalse();
        assertThat(Fundamentals.compute(edge.rows(), LocalDate.parse("2026-11-01")).isStale()).isTrue();
    }

    @Test
    @DisplayName("주식수 — 발행주식수가 없으면 희석 가중평균(최근 분기 값)으로 대신하고 isProxy, end가 같으면 발행주식수가 우선")
    void proxySharesFromDilutedWeightedAverage() {
        Filings f = new Filings();
        f.on("2025-08-01", "10-Q", "q2-25")
                .shares(DILUTED, "2025-04-01", "2025-06-30", 1_050 * M)
                .shares(DILUTED, "2025-01-01", "2025-06-30", 1_060 * M)
                .stock(ASSETS, "2025-06-30", 500 * M);
        f.on("2026-08-01", "10-Q", "q2-26")
                .shares(DILUTED, "2026-04-01", "2026-06-30", 1_000 * M)
                .shares(DILUTED, "2026-01-01", "2026-06-30", 1_010 * M)
                .stock(ASSETS, "2026-06-30", 520 * M);
        Result proxy = Fundamentals.compute(f.rows(), AS_OF);
        assertThat(proxy.isProxy()).isTrue();
        assertThat(proxy.conceptsUsed()).containsEntry("shares", DILUTED);
        // 3개월 가중평균끼리: 1,000 / 1,050 − 1 = −4.76% (가중평균은 누계 차감하지 않음)
        assertThat(proxy.sharesYoY()).isCloseTo((1_000.0 / 1_050.0 - 1) * 100, within(1e-9));

        Filings withOutstanding = new Filings();
        withOutstanding.on("2026-08-01", "10-Q", "q2-26")
                .shares(DILUTED, "2026-04-01", "2026-06-30", 1_000 * M)
                .shares("us-gaap:CommonStockSharesOutstanding", null, "2026-06-30", 990 * M);
        Result actual = Fundamentals.compute(withOutstanding.rows(), AS_OF);
        assertThat(actual.isProxy()).isFalse();
        assertThat(actual.conceptsUsed()).containsEntry("shares", "us-gaap:CommonStockSharesOutstanding");
    }

    @Test
    @DisplayName("결정성 — 행·사실 순서를 섞어도 같은 결과, 같은 날 같은 키는 정정(/A)이 이김")
    void deterministicRegardlessOfOrder() {
        List<Row> base = new ArrayList<>(aaplLike());
        // 같은 날 원본과 정정이 함께 들어온 경우(수집기는 한 행에 합침) — 순서와 무관하게 /A 값
        base.add(new Row(LocalDate.parse("2026-07-31"), List.of(
                new Fact("us-gaap", "InventoryNet", "USD", null, LocalDate.parse("2026-06-27"), 11_000 * M,
                        "10-Q/A", "0000320193-26-000021"))));
        Result expected = Fundamentals.compute(base, AS_OF);
        assertThat(expected.inventoryYoY()).isCloseTo((11_000.0 / 5_925.0 - 1) * 100, within(1e-9));

        for (long seed = 1; seed <= 20; seed++) {
            Random random = new Random(seed);
            List<Row> shuffled = new ArrayList<>();
            for (Row row : base) {
                List<Fact> facts = new ArrayList<>(row.facts());
                Collections.shuffle(facts, random);
                shuffled.add(new Row(row.filed(), facts));
            }
            Collections.shuffle(shuffled, random);
            Result r = Fundamentals.compute(shuffled, AS_OF);
            assertThat(r).isEqualTo(expected);
            assertThat(r.notes()).containsExactlyElementsOf(expected.notes());
            assertThat(r.conceptsUsed().keySet()).containsExactlyElementsOf(expected.conceptsUsed().keySet());
        }
    }

    @Test
    @DisplayName("사실이 없으면 전부 null(0 아님) + 표시, 기준일이 없으면 거절")
    void emptyIsNullNotZero() {
        Result r = Fundamentals.compute(List.of(), AS_OF);
        assertThat(r.latestPeriodEnd()).isNull();
        assertThat(r.revenueTtm()).isNull();
        assertThat(r.roe()).isNull();
        assertThat(r.sharesYoY()).isNull();
        assertThat(r.conceptsUsed()).isEmpty();
        assertThat(r.isStale()).isFalse();
        assertThat(r.notes()).containsExactly("기준일 2026-10-23까지 낸 재무 사실이 없음");

        // 기준일 뒤 공시뿐이면 같은 결과
        assertThat(Fundamentals.compute(aaplLike(), LocalDate.parse("2024-08-01")).latestPeriodEnd()).isNull();
        assertThat(Fundamentals.compute(null, AS_OF).notes()).hasSize(1);
        assertThatThrownBy(() -> Fundamentals.compute(List.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("최신 기간 값이 없는 필드는 옛 값을 대신 넣지 않고 null + 표시(AMZN 자사주 모양)")
    void laggingFieldIsNullNotOldValue() {
        Filings f = new Filings();
        f.on("2025-02-07", "10-K", "fy-24")
                .flow(BUYBACK, "2024-01-01", "2024-12-31", 0.5 * M)
                .flow(OCF, "2024-01-01", "2024-12-31", 100 * M);
        f.on("2026-02-06", "10-K", "fy-25").flow(OCF, "2025-01-01", "2025-12-31", 140 * M);
        Result r = Fundamentals.compute(f.rows(), AS_OF);
        assertThat(r.ocfTtm()).isCloseTo(140 * M, within(1e-6));
        assertThat(r.buybacksTtm()).isNull();
        assertThat(r.conceptsUsed()).doesNotContainKey("buybacks");
        assertThat(r.notes()).containsExactly(
                "buybacks: 최신 기간 2025-12-31 값 없음(us-gaap:PaymentsForRepurchaseOfCommonStock 최신 end 2024-12-31)");
    }

    // ------------------------------------------------------------------ 12-12-12-16주·16-12-12-12주 회계 달력(D28-CALC-1)
    /**
     * COST 모양 — 12/12/12/16주 회계 달력(8월 마지막 일요일 결산). 10-Q 손익은 3개월 + 누계, 현금흐름은 누계뿐. 2분기 10-Q
     * (2026-03-11) 뒤의 기준일에는 24주 누계(167일)와 16주 4분기(111일)가 있어야 TTM이 됩니다(금액은 모양만 맞춘 값, 백만).
     */
    private static List<Row> costLike() {
        Filings f = new Filings();
        f.on("2024-12-18", "10-Q", "q1-25")
                .flow(REV_ASC, "2024-09-02", "2024-11-24", 60_000 * M)
                .flow(OCF, "2024-09-02", "2024-11-24", 3_000 * M);
        f.on("2025-03-12", "10-Q", "q2-25")
                .flow(REV_ASC, "2024-11-25", "2025-02-16", 62_000 * M)
                .flow(REV_ASC, "2024-09-02", "2025-02-16", 122_000 * M)
                .flow(OCF, "2024-09-02", "2025-02-16", 6_000 * M);
        f.on("2025-06-04", "10-Q", "q3-25")
                .flow(REV_ASC, "2025-02-17", "2025-05-11", 61_000 * M)
                .flow(REV_ASC, "2024-09-02", "2025-05-11", 183_000 * M)
                .flow(OCF, "2024-09-02", "2025-05-11", 9_000 * M);
        f.on("2025-10-08", "10-K", "fy-25")
                .flow(REV_ASC, "2024-09-02", "2025-08-31", 263_000 * M)
                .flow(OCF, "2024-09-02", "2025-08-31", 12_000 * M);
        f.on("2025-12-17", "10-Q", "q1-26")
                .flow(REV_ASC, "2025-09-01", "2025-11-23", 65_000 * M)
                .flow(OCF, "2025-09-01", "2025-11-23", 3_200 * M);
        f.on("2026-03-11", "10-Q", "q2-26")
                .flow(REV_ASC, "2025-11-24", "2026-02-15", 67_000 * M)
                .flow(REV_ASC, "2025-09-01", "2026-02-15", 132_000 * M)
                .flow(OCF, "2025-09-01", "2026-02-15", 6_500 * M);
        return f.rows();
    }

    @Test
    @DisplayName("COST 모양(12-12-12-16주) — 24주 누계(167일)로 TTM, 16주 4분기(111일 = FY − 36주)로 분기 합, 둘이 같음")
    void costTwentyFourWeekYtd() {
        List<Row> rows = costLike();
        // 16주 4분기 = FY 263,000 − 36주 183,000 = 80,000(2025-05-12 ~ 2025-08-31)
        assertThat(Fundamentals.quarters(known(rows, REV_ASC)).get(LocalDate.parse("2025-08-31")))
                .isEqualTo(new Quarter(LocalDate.parse("2025-05-12"), LocalDate.parse("2025-08-31"), 80_000 * M, true));

        Result r = Fundamentals.compute(rows, LocalDate.parse("2026-04-01"));
        assertThat(r.latestPeriodEnd()).isEqualTo(LocalDate.parse("2026-02-15"));
        // 매출 TTM = FY25 263,000 + 24주 FY26 132,000 − 24주 FY25 122,000 = 273,000
        //          = 2분기 67,000 + 1분기 65,000 + 16주 4분기 80,000 + 3분기 61,000
        assertThat(r.revenueTtm()).isCloseTo(273_000 * M, within(1e-3));
        // 영업현금흐름(누계뿐) TTM = 12,000 + 6,500 − 6,000 = 12,500 = 3,300 + 3,200 + 3,000 + 3,000
        assertThat(r.ocfTtm()).isCloseTo(12_500 * M, within(1e-3));
        assertThat(r.notes()).as("두 TTM 방법이 같아 표시 없음").isEmpty();
    }

    /**
     * KR 모양 — 16/12/12/12주 회계 달력(1월 마지막 토요일 결산). 1분기가 16주(111일), 상반기 누계가 28주(195일)라, 1분기
     * 10-Q(6월 말)부터 3분기 10-Q(12월 중순)까지는 이 두 기간이 분류돼야 흐름 지표가 생깁니다. 자산이 기준 기간을 정합니다.
     */
    private static List<Row> krLike() {
        Filings f = new Filings();
        f.on("2025-06-27", "10-Q", "q1-25")
                .flow(REV_ASC, "2025-02-02", "2025-05-24", 45_000 * M)
                .stock(ASSETS, "2025-05-24", 50_000 * M);
        f.on("2025-09-19", "10-Q", "q2-25")
                .flow(REV_ASC, "2025-05-25", "2025-08-16", 34_000 * M)
                .flow(REV_ASC, "2025-02-02", "2025-08-16", 79_000 * M)
                .stock(ASSETS, "2025-08-16", 51_000 * M);
        f.on("2025-12-12", "10-Q", "q3-25")
                .flow(REV_ASC, "2025-08-17", "2025-11-08", 34_000 * M)
                .flow(REV_ASC, "2025-02-02", "2025-11-08", 113_000 * M);
        f.on("2026-03-31", "10-K", "fy-25")
                .flow(REV_ASC, "2025-02-02", "2026-01-31", 148_000 * M)
                .stock(ASSETS, "2026-01-31", 52_000 * M);
        f.on("2026-06-26", "10-Q", "q1-26")
                .flow(REV_ASC, "2026-02-01", "2026-05-23", 46_000 * M)
                .stock(ASSETS, "2026-05-23", 53_000 * M);
        f.on("2026-09-18", "10-Q", "q2-26")
                .flow(REV_ASC, "2026-05-24", "2026-08-15", 35_000 * M)
                .flow(REV_ASC, "2026-02-01", "2026-08-15", 81_000 * M)
                .stock(ASSETS, "2026-08-15", 54_000 * M);
        return f.rows();
    }

    @Test
    @DisplayName("KR 모양(16-12-12-12주) — 16주 1분기(111일)·28주 누계(195일)로 TTM, 1분기·2분기 기준일 모두")
    void krSixteenWeekFirstQuarter() {
        // 1분기 기준(10-Q 2026-06-26 뒤): TTM = FY25 148,000 + 16주 46,000 − 16주 45,000 = 149,000
        //   = 1분기 46,000 + 4분기(FY − 40주 = 35,000) + 3분기 34,000 + 2분기 34,000
        Result q1 = Fundamentals.compute(krLike(), LocalDate.parse("2026-07-15"));
        assertThat(q1.latestPeriodEnd()).isEqualTo(LocalDate.parse("2026-05-23"));
        assertThat(q1.revenueTtm()).isCloseTo(149_000 * M, within(1e-3));
        // 평균 자산 = (53,000 + 1년 전 2025-05-24의 50,000)/2 = 51,500
        assertThat(q1.assetTurnover()).isCloseTo(149_000.0 / 51_500.0, within(1e-12));
        assertThat(q1.notes()).isEmpty();

        // 초기 결정 기준일(2026-10-23, 최신 10-Q = 2026-08-15 분기): TTM = 148,000 + 28주 81,000 − 28주 79,000 = 150,000
        Result q2 = Fundamentals.compute(krLike(), AS_OF);
        assertThat(q2.latestPeriodEnd()).isEqualTo(LocalDate.parse("2026-08-15"));
        assertThat(q2.revenueTtm()).isCloseTo(150_000 * M, within(1e-3));
        assertThat(q2.assetTurnover()).isCloseTo(150_000.0 / ((54_000.0 + 51_000.0) / 2), within(1e-12));
        assertThat(q2.notes()).isEmpty();
    }

    // ------------------------------------------------------------------ 전년 누계만 재작성(D28-CALC-2)
    /**
     * 2025년 10-Q들이 중단영업 재분류로 2024년 비교 열(분기 25,000 → 20,000, 9개월 75,000 → 60,000)을 다시 냄. 연간(FY2024
     * 100,000)은 다음 10-K 전이라 옛 기준 그대로(8-K 재작성은 수집하지 않음). 계속영업 기준 TTM은 약 83,000입니다.
     */
    private static List<Row> restatedPriorYtd() {
        Filings f = new Filings();
        f.on("2024-02-15", "10-K", "fy-23").flow(REV_ASC, "2023-01-01", "2023-12-31", 95_000 * M);
        f.on("2024-05-01", "10-Q", "q1-24").flow(REV_ASC, "2024-01-01", "2024-03-31", 25_000 * M);
        f.on("2024-08-01", "10-Q", "q2-24")
                .flow(REV_ASC, "2024-04-01", "2024-06-30", 25_000 * M)
                .flow(REV_ASC, "2024-01-01", "2024-06-30", 50_000 * M);
        f.on("2024-11-01", "10-Q", "q3-24")
                .flow(REV_ASC, "2024-07-01", "2024-09-30", 25_000 * M)
                .flow(REV_ASC, "2024-01-01", "2024-09-30", 75_000 * M);
        f.on("2025-02-15", "10-K", "fy-24").flow(REV_ASC, "2024-01-01", "2024-12-31", 100_000 * M);
        f.on("2025-05-01", "10-Q", "q1-25")
                .flow(REV_ASC, "2025-01-01", "2025-03-31", 21_000 * M)
                .flow(REV_ASC, "2024-01-01", "2024-03-31", 20_000 * M);
        f.on("2025-08-01", "10-Q", "q2-25")
                .flow(REV_ASC, "2025-04-01", "2025-06-30", 21_000 * M)
                .flow(REV_ASC, "2025-01-01", "2025-06-30", 42_000 * M)
                .flow(REV_ASC, "2024-04-01", "2024-06-30", 20_000 * M)
                .flow(REV_ASC, "2024-01-01", "2024-06-30", 40_000 * M);
        f.on("2025-11-01", "10-Q", "q3-25")
                .flow(REV_ASC, "2025-07-01", "2025-09-30", 21_000 * M)
                .flow(REV_ASC, "2025-01-01", "2025-09-30", 63_000 * M)
                .flow(REV_ASC, "2024-07-01", "2024-09-30", 20_000 * M)
                .flow(REV_ASC, "2024-01-01", "2024-09-30", 60_000 * M);
        return f.rows();
    }

    @Test
    @DisplayName("전년 누계만 연간 공시 뒤 재작성됐으면 TTM을 내지 않는다 — 연간(옛) + 누계(새) − 전년 누계(새) 103,000은 섞인 값, 분기 합도 같은 값이라 대조로 못 잡음")
    void restatedPriorYtdIsNotMixedIntoTtm() {
        List<Row> rows = restatedPriorYtd();
        // 4분기 = FY2024(옛 기준, 2025-02-15) − 9개월(새 기준, 2025-11-01) = 40,000은 섞인 분기라 버립니다.
        NavigableMap<LocalDate, Quarter> quarters = Fundamentals.quarters(known(rows, REV_ASC));
        assertThat(quarters.get(LocalDate.parse("2024-12-31"))).isNull();
        assertThat(quarters.get(LocalDate.parse("2025-09-30")).value()).isEqualTo(21_000 * M);

        Result r = Fundamentals.compute(rows, LocalDate.parse("2025-11-10"));
        assertThat(r.latestPeriodEnd()).isEqualTo(LocalDate.parse("2025-09-30"));
        assertThat(r.revenueTtm()).as("섞인 103,000(약 24% 과대)을 내지 않음").isNull();
        assertThat(r.revenueYoY()).isNull();
        assertThat(r.conceptsUsed()).doesNotContainKey("revenue");
        assertThat(r.notes()).containsExactly("revenue: 전년 누계가 연간 공시 뒤 재작성됨 — 기준이 섞여 TTM을 내지 않음");

        // 다음 10-K(2026-02-15)가 FY2024를 새 기준(80,000)으로 다시 내면 연간 사실이 그 기준일에 바로 있어 다시 계산합니다.
        List<Row> later = new ArrayList<>(rows);
        later.add(new Row(LocalDate.parse("2026-02-15"), List.of(
                new Fact("us-gaap", REV_ASC.substring(8), "USD", LocalDate.parse("2025-01-01"),
                        LocalDate.parse("2025-12-31"), 84_000 * M, "10-K", "fy-25"),
                new Fact("us-gaap", REV_ASC.substring(8), "USD", LocalDate.parse("2024-01-01"),
                        LocalDate.parse("2024-12-31"), 80_000 * M, "10-K", "fy-25"))));
        Result next = Fundamentals.compute(later, LocalDate.parse("2026-03-01"));
        assertThat(next.revenueTtm()).isCloseTo(84_000 * M, within(1e-3));
        assertThat(next.revenueYoY()).as("1년 전 TTM = 새 기준 FY2024 80,000").isCloseTo((84_000.0 / 80_000.0 - 1) * 100,
                within(1e-9));
    }

    @Test
    @DisplayName("연말 재분류 — 10-K가 전년도 같은 날 고쳤으면 연간은 새 기준이라, 뒤에 고친 전년 1분기와 섞이지 않아 TTM을 낸다")
    void yearEndRestatementKeepsTtm() {
        Filings f = new Filings();
        f.on("2025-02-15", "10-K", "fy-24").flow(REV_ASC, "2024-01-01", "2024-12-31", 100_000 * M);
        f.on("2025-05-01", "10-Q", "q1-25").flow(REV_ASC, "2025-01-01", "2025-03-31", 25_000 * M);
        f.on("2025-08-01", "10-Q", "q2-25")
                .flow(REV_ASC, "2025-04-01", "2025-06-30", 25_000 * M)
                .flow(REV_ASC, "2025-01-01", "2025-06-30", 50_000 * M);
        f.on("2025-11-01", "10-Q", "q3-25")
                .flow(REV_ASC, "2025-07-01", "2025-09-30", 25_000 * M)
                .flow(REV_ASC, "2025-01-01", "2025-09-30", 75_000 * M);
        // 연말에 사업부를 중단영업으로 — 10-K가 FY2025를 새 기준으로 내고 FY2024도 같은 날 고침.
        f.on("2026-02-15", "10-K", "fy-25")
                .flow(REV_ASC, "2025-01-01", "2025-12-31", 84_000 * M)
                .flow(REV_ASC, "2024-01-01", "2024-12-31", 80_000 * M);
        // 1분기 10-Q가 전년 1분기 비교 열을 새 기준으로 고침(연간 공시보다 늦은 공시일).
        f.on("2026-05-01", "10-Q", "q1-26")
                .flow(REV_ASC, "2026-01-01", "2026-03-31", 22_000 * M)
                .flow(REV_ASC, "2025-01-01", "2025-03-31", 21_000 * M);

        Result r = Fundamentals.compute(f.rows(), LocalDate.parse("2026-05-10"));
        // 연간 기준 = FY2025(새) 84,000 + 1분기(새) 22,000 − 전년 1분기(새) 21,000 = 85,000
        assertThat(r.revenueTtm()).isCloseTo(85_000 * M, within(1e-3));
        // 분기 합은 옛 기준 2·3분기와 '새 연간 − 옛 9개월' 4분기가 섞여 81,000 — 연간 기준을 쓰고 표시합니다.
        assertThat(r.notes()).containsExactly("revenue TTM: 연간 기준과 최근 4개 분기 합이 달라 연간 기준을 씀");
    }

    // ------------------------------------------------------------------ 주식 분할(D28-CALC-3)
    @Test
    @DisplayName("주식수 1년 변화 — 10:1 분할·1:10 병합 모양이면 비율 대신 null + 표시(대규모 희석·소각으로 읽히지 않게)")
    void splitIsNotDilution() {
        Filings split = new Filings();
        split.on("2025-07-25", "10-Q", "q2-25")
                .stock(ASSETS, "2025-06-30", 500 * M)
                .shares(DEI_SHARES, null, "2025-07-20", 2_450_000_000.0);
        split.on("2026-07-24", "10-Q", "q2-26")
                .stock(ASSETS, "2026-06-30", 520 * M)
                .shares(DEI_SHARES, null, "2026-07-19", 24_300_000_000.0);
        Result r = Fundamentals.compute(split.rows(), AS_OF);
        assertThat(r.sharesYoY()).as("+891.8%가 아님").isNull();
        assertThat(r.notes()).containsExactly("shares 1년 변화 9.92배: 분할·병합(또는 대규모 발행·소각) 추정이라 비율을 내지 않음");

        Filings reverse = new Filings();
        reverse.on("2025-07-25", "10-Q", "q2-25").shares(DEI_SHARES, null, "2025-07-20", 2_450_000_000.0);
        reverse.on("2026-07-24", "10-Q", "q2-26").shares(DEI_SHARES, null, "2026-07-19", 245_000_000.0);
        assertThat(Fundamentals.compute(reverse.rows(), AS_OF).sharesYoY()).as("−90%가 아님").isNull();

        // 보통의 변화(자사주 −3.5%, 증자 +30%)는 그대로 냅니다.
        Filings normal = new Filings();
        normal.on("2025-07-25", "10-Q", "q2-25").shares(DEI_SHARES, null, "2025-07-20", 1_000 * M);
        normal.on("2026-07-24", "10-Q", "q2-26").shares(DEI_SHARES, null, "2026-07-19", 1_300 * M);
        assertThat(Fundamentals.compute(normal.rows(), AS_OF).sharesYoY()).isCloseTo(30.0, within(1e-9));
    }

    // ------------------------------------------------------------------ 메모 순서(D28-CALC-5)
    @Test
    @DisplayName("메모는 뜻이 큰 것부터 — 평균에 최신 값만 씀(값의 뜻이 바뀜) → 일부러 비운 값 → 자료가 없어 빈 값(긴 개념 이름)")
    void notesPutMeaningFirst() {
        Filings f = new Filings();
        // 설비투자는 10-K에만 태그(분기 기준일에는 값 없음), 1년 전 자본 없음, 순이익 적자.
        f.on("2026-02-10", "10-K", "fy-25")
                .flow(CAPEX, "2025-01-01", "2025-12-31", 40 * M)
                .flow(OCF, "2025-01-01", "2025-12-31", 100 * M)
                .flow(NI, "2025-01-01", "2025-12-31", -30 * M)
                .stock(ASSETS, "2025-06-30", 900 * M);
        f.on("2026-08-05", "10-Q", "q2-26")
                .flow(OCF, "2026-01-01", "2026-06-30", 50 * M)
                .flow(NI, "2026-01-01", "2026-06-30", -10 * M)
                .stock(ASSETS, "2026-06-30", 1_000 * M)
                .stock(EQUITY, "2026-06-30", 400 * M);
        f.on("2025-08-05", "10-Q", "q2-25")
                .flow(OCF, "2025-01-01", "2025-06-30", 45 * M)
                .flow(NI, "2025-01-01", "2025-06-30", -20 * M);
        Result r = Fundamentals.compute(f.rows(), AS_OF);
        assertThat(r.notes()).containsExactly(
                "equity 평균: 1년 전 시점 값이 없어 최신 값만 씀",
                "capex: 최신 기간 2026-06-30 값 없음(us-gaap:PaymentsToAcquirePropertyPlantAndEquipment 최신 end 2025-12-31)");
    }

    /** 한 개념의 '그 시점' 사실(계산기 내부 표현) — 분기 표를 직접 확인할 때. */
    private static List<Known> known(List<Row> rows, String concept) {
        return Fundamentals.pointInTime(rows, AS_OF).stream().filter(k -> k.concept().equals(concept)).toList();
    }
}
