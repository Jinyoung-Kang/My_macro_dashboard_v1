package com.macrodash.feature.paper;

import com.macrodash.analytics.DailySeries;
import com.macrodash.analytics.UsMarketCalendar;
import com.macrodash.feature.paper.PaperLedgerService.Outcome;
import com.macrodash.store.PaperStore.BookState;
import com.macrodash.store.PaperStore.Fill;
import com.macrodash.store.PaperStore.Lockout;
import com.macrodash.store.PaperStore.Order;
import com.macrodash.store.PaperStore.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.macrodash.feature.paper.PaperFixtures.FRI;
import static com.macrodash.feature.paper.PaperFixtures.FRI_CLOSE;
import static com.macrodash.feature.paper.PaperFixtures.MON;
import static com.macrodash.feature.paper.PaperFixtures.MON_CLOSE_10;
import static com.macrodash.feature.paper.PaperFixtures.MON_FINAL;
import static com.macrodash.feature.paper.PaperFixtures.MON_INTRADAY;
import static com.macrodash.feature.paper.PaperFixtures.SAT;
import static com.macrodash.feature.paper.PaperFixtures.TUE;
import static com.macrodash.feature.paper.PaperFixtures.TUE_FINAL;
import static com.macrodash.feature.paper.PaperFixtures.TUE_INTRADAY;
import static com.macrodash.feature.paper.PaperFixtures.activeBook;
import static com.macrodash.feature.paper.PaperFixtures.bar;
import static com.macrodash.feature.paper.PaperFixtures.order;
import static com.macrodash.feature.paper.PaperFixtures.pending;
import static com.macrodash.feature.paper.PaperFixtures.position;
import static com.macrodash.feature.paper.PaperFixtures.quotes;
import static com.macrodash.feature.paper.PaperFixtures.staleBar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 장부 서비스 — 룩어헤드 방지(설계 §10)·체결 순서·스탑·재진입 금지·분할·멱등·평가.
 */
class PaperLedgerServiceTest {

    private static final String BOOK = PaperBooks.CLAUDE;

    private FakePaperStore store;
    private PaperMarketData marketData;
    private PaperLedgerService ledger;

    @BeforeEach
    void setUp() {
        store = new FakePaperStore();
        marketData = mock(PaperMarketData.class);
        when(marketData.history(anyCollection(), any(), any())).thenReturn(Map.of());
        ledger = new PaperLedgerService(store, marketData, PaperFixtures.settings(store), PaperFixtures.MAPPER);
    }

    private List<Fill> fills() {
        return store.fillsOf(BOOK);
    }

    @Nested
    @DisplayName("체결 조건 — 룩어헤드 방지")
    class Lookahead {

        @Test
        @DisplayName("주문보다 먼저 수집한 저장본으로는 체결하지 않는다(봉 날짜가 뒤여도)")
        void observationOlderThanOrderDoesNotFill() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 100_000, null);
            long id = pending(store, BOOK, "AAA", "BUY", 10, null, null, "NEXT_OBSERVATION", FRI,
                    MON_INTRADAY.plusSeconds(60));

            ledger.process(quotes(MON_INTRADAY, bar("AAA", FRI, 100, MON, 101)));

            assertThat(fills()).isEmpty();
            assertThat(order(store, id).status()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("주말 재저장(같은 금요일 봉, 새 수집 시각)으로는 금요일 기준 주문이 체결되지 않고, 월요일 봉에서 체결된다")
        void weekendResaveDoesNotFill() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 100_000, null);
            long id = pending(store, BOOK, "AAA", "BUY", 10, null, null, "NEXT_OBSERVATION", FRI,
                    FRI_CLOSE.plusSeconds(3600));

            ledger.process(quotes(SAT, bar("AAA", FRI, 100)));
            assertThat(fills()).as("봉 날짜(금) ≤ 기준 거래일(금)").isEmpty();

            ledger.process(quotes(MON_INTRADAY, bar("AAA", FRI, 100, MON, 101)));
            assertThat(fills()).hasSize(1);
            Fill fill = fills().get(0);
            assertThat(fill.observationDate()).isEqualTo(MON);
            assertThat(fill.rawPrice()).isEqualTo(101);
            assertThat(fill.price()).isCloseTo(101 * 1.0005, offset(1e-9));
            assertThat(fill.observedAt()).isEqualTo(MON_INTRADAY);
            assertThat(fill.priceSource()).isEqualTo("paper.quotes");
            assertThat(fill.backfill()).isFalse();
            assertThat(order(store, id).status()).isEqualTo("FILLED");
            assertThat(order(store, id).filledAt()).isEqualTo(MON_INTRADAY);
        }

        @Test
        @DisplayName("NEXT_CLOSE는 장중·마감 15분 전 관측치로 체결되지 않고 확정 종가 관측치로 체결된다")
        void nextCloseWaitsForFinalClose() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 100_000, null);
            pending(store, BOOK, "AAA", "BUY", 10, null, null, "NEXT_CLOSE", FRI, SAT);

            ledger.process(quotes(MON_INTRADAY, bar("AAA", FRI, 100, MON, 101)));
            ledger.process(quotes(MON_CLOSE_10, bar("AAA", FRI, 100, MON, 102)));
            assertThat(fills()).isEmpty();

            ledger.process(quotes(MON_FINAL, bar("AAA", FRI, 100, MON, 103)));
            assertThat(fills()).singleElement().satisfies(f -> {
                assertThat(f.rawPrice()).isEqualTo(103);
                assertThat(f.observedAt()).isEqualTo(MON_FINAL);
            });
        }

        @Test
        @DisplayName("이어 쓴(isStale) 봉으로는 체결하지 않는다")
        void staleBarDoesNotFill() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 100_000, null);
            pending(store, BOOK, "AAA", "BUY", 10, null, null, "NEXT_OBSERVATION", FRI, SAT);

            ledger.process(quotes(MON_INTRADAY, staleBar("AAA", FRI, 100, MON, 101), bar("SPY", FRI, 500, MON, 501)));

            assertThat(fills()).isEmpty();
        }

        @Test
        @DisplayName("같은 관측치를 두 번 넣으면 한 번만 처리한다(멱등)")
        void sameObservationTwiceIsOnce() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 100_000, null);
            pending(store, BOOK, "AAA", "BUY", 10, null, null, "NEXT_OBSERVATION", FRI, SAT);
            var observation = quotes(MON_INTRADAY, bar("AAA", FRI, 100, MON, 101));

            assertThat(ledger.hasNewObservation(observation)).isTrue();
            Outcome first = ledger.process(observation);
            BookState afterFirst = store.states.get(BOOK);
            Outcome second = ledger.process(observation);

            assertThat(first.filled()).isEqualTo(1);
            assertThat(first.booksProcessed()).containsExactly(BOOK);
            assertThat(second.booksProcessed()).isEmpty();
            assertThat(second.skippedReason()).contains("이미 처리한 관측치");
            assertThat(fills()).hasSize(1);
            assertThat(store.states.get(BOOK)).isEqualTo(afterFirst);
            assertThat(ledger.hasNewObservation(observation)).isFalse();
        }
    }

    @Nested
    @DisplayName("체결 수량·순서")
    class Sizing {

        @Test
        @DisplayName("목표 비중 매수: 체결 시점 순자산 × 비중 ÷ (관측가 × 1.0005) 내림, 현금이 줄고 보유가 생긴다")
        void targetWeightBuy() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 100_000, null);
            pending(store, BOOK, "AAA", "BUY", null, 10.0, null, "NEXT_OBSERVATION", FRI, SAT);

            ledger.process(quotes(MON_INTRADAY, bar("AAA", FRI, 99, MON, 100)));

            // 10,000 / 100.05 = 99.95 → 99주, 99 × 100.05 = 9,904.95
            Fill fill = fills().get(0);
            assertThat(fill.qty()).isEqualTo(99);
            assertThat(fill.gross()).isEqualTo(9_904.95);
            BookState state = store.states.get(BOOK);
            assertThat(state.cash()).isEqualTo(90_095.05);
            assertThat(state.nav()).isCloseTo(99_995.05, offset(1e-6));
            assertThat(state.lastProcessedCollectedAt()).isEqualTo(MON_INTRADAY);
            Position position = store.positions(BOOK).get(0);
            assertThat(position.qty()).isEqualTo(99);
            assertThat(position.avgCost()).isEqualTo(100.05);
            assertThat(position.highWatermark()).as("최고가는 체결가부터").isEqualTo(100.05);
            assertThat(position.openedOn()).isEqualTo(MON);
            // 장중 체결 — 분할 기준은 그 봉(지연 시세)이 아니라 직전 거래일 확정 종가
            assertThat(position.fillRefDate()).isEqualTo(FRI);
            assertThat(position.fillRefClose()).isEqualTo(99);
        }

        @Test
        @DisplayName("확정 종가로 체결한 보유는 그 봉이 분할 기준이다")
        void finalFillReferenceIsThatBar() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 100_000, null);
            pending(store, BOOK, "AAA", "BUY", 10, null, null, "NEXT_CLOSE", FRI, SAT);

            ledger.process(quotes(MON_FINAL, bar("AAA", FRI, 99, MON, 100)));

            Position position = store.positions(BOOK).get(0);
            assertThat(position.fillRefDate()).isEqualTo(MON);
            assertThat(position.fillRefClose()).isEqualTo(100);
        }

        @Test
        @DisplayName("현금이 모자라면 살 수 있는 만큼만 사고, 주문 근거에 cashLimited·요청·체결 수량을 남긴다(실행 기록에도)")
        void cashLimitedBuy() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 10_000, null);
            long id = store.insertOrder(new Order(0, BOOK, null, "BBB", "BUY", 1_000, null, null, "NEXT_OBSERVATION",
                    "CLAUDE", "PENDING", null, PaperFixtures.MAPPER.valueToTree(Map.of("note", "신호")), FRI, SAT,
                    FRI.plusDays(7), null));

            Outcome outcome = ledger.process(quotes(MON_INTRADAY, bar("BBB", FRI, 50, MON, 50)));

            // 10,000 / 50.025 = 199.9 → 199주
            assertThat(fills()).singleElement().satisfies(f -> assertThat(f.qty()).isEqualTo(199));
            assertThat(store.states.get(BOOK).cash()).isEqualTo(45.025);   // 10,000 − 199 × 50.025
            assertThat(outcome.notes()).anyMatch(note -> note.contains("cashLimited") && note.contains("199"));
            var reason = order(store, id).reason();
            assertThat(reason.path("cashLimited").asBoolean()).isTrue();
            assertThat(reason.path("requestedQty").asInt()).isEqualTo(1_000);
            assertThat(reason.path("filledQty").asInt()).isEqualTo(199);
            assertThat(reason.path("note").asString()).as("원래 근거는 남김").isEqualTo("신호");
        }

        @Test
        @DisplayName("현금이 넉넉하면 주문 근거를 바꾸지 않는다")
        void fullBuyKeepsReason() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 100_000, null);
            long id = pending(store, BOOK, "BBB", "BUY", 10, null, null, "NEXT_OBSERVATION", FRI, SAT);

            ledger.process(quotes(MON_INTRADAY, bar("BBB", FRI, 50, MON, 50)));

            assertThat(order(store, id).reason()).isNull();
        }

        @Test
        @DisplayName("같은 관측치에서 매도를 먼저 체결하고, 그 현금으로 매수한다")
        void sellsBeforeBuys() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 1_000, null);
            position(store, BOOK, "AAA", 100, 100, 100, 100.0, FRI, 100);
            // 매수 주문을 먼저 만들어도(생성 순) 매도가 먼저입니다.
            pending(store, BOOK, "BBB", "BUY", null, 100.0, null, "NEXT_OBSERVATION", FRI, SAT);
            pending(store, BOOK, "AAA", "SELL", null, null, null, "NEXT_OBSERVATION", FRI, SAT.plusSeconds(1));

            ledger.process(quotes(MON_INTRADAY, bar("AAA", FRI, 100, MON, 100), bar("BBB", FRI, 50, MON, 50)));

            // 매도: 100 × 99.95 = 9,995 → 현금 10,995. 매수: 10,995 / 50.025 = 219.79 → 219주 = 10,955.475
            assertThat(fills()).extracting(Fill::side, Fill::ticker, Fill::qty)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("SELL", "AAA", 100),
                            org.assertj.core.groups.Tuple.tuple("BUY", "BBB", 219));
            assertThat(store.positions(BOOK)).extracting(Position::ticker).containsExactly("BBB");
            assertThat(store.states.get(BOOK).cash()).isEqualTo(39.525);
        }

        @Test
        @DisplayName("일부 매도: 비율 × 보유 내림, 보유가 없으면 취소한다")
        void partialSellAndMissingPosition() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 0, null);
            position(store, BOOK, "AAA", 10, 100, 100, 100.0, FRI, 100);
            pending(store, BOOK, "AAA", "SELL", null, null, 0.35, "NEXT_OBSERVATION", FRI, SAT);
            long missing = pending(store, BOOK, "ZZZ", "SELL", null, null, null, "NEXT_OBSERVATION", FRI, SAT);

            ledger.process(quotes(MON_INTRADAY, bar("AAA", FRI, 100, MON, 100), bar("ZZZ", FRI, 10, MON, 10)));

            assertThat(fills()).singleElement().satisfies(f -> assertThat(f.qty()).isEqualTo(3));
            assertThat(store.positions(BOOK).get(0).qty()).isEqualTo(7);
            assertThat(order(store, missing).status()).isEqualTo("CANCELLED");
            assertThat(order(store, missing).rejectReason()).contains("보유가 없습니다");
        }

        @Test
        @DisplayName("기한이 지난 대기 주문은 체결 전에 만료된다")
        void expiredBeforeFill() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 100_000, null);
            long id = store.insertOrder(new Order(0, BOOK, null, "AAA", "BUY", 10, null, null, "NEXT_OBSERVATION",
                    "CLAUDE", "PENDING", null, null, FRI, SAT, FRI, null));

            Outcome outcome = ledger.process(quotes(MON_INTRADAY, bar("AAA", FRI, 100, MON, 101)));

            assertThat(outcome.expired()).isEqualTo(1);
            assertThat(order(store, id).status()).isEqualTo("EXPIRED");
            assertThat(fills()).isEmpty();
        }
    }

    @Nested
    @DisplayName("트레일링 스탑·재진입 금지")
    class Stops {

        @Test
        @DisplayName("최고가 대비 −8%에 닿은 관측치로 전량 매도(STOP·IMMEDIATE·FILLED)하고 20거래일 재진입 금지, 그 뒤 매수 주문은 취소")
        void stopSellsAndLocksOut() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 0, null);
            position(store, BOOK, "AAA", 100, 95, 100, 100.0, FRI, 100);

            Outcome outcome = ledger.process(quotes(MON_INTRADAY, bar("AAA", FRI, 100, MON, 91.9)));

            assertThat(outcome.stopped()).isEqualTo(1);
            Order stop = store.ordersOf(BOOK).get(0);
            assertThat(stop.source()).isEqualTo("STOP");
            assertThat(stop.timing()).isEqualTo("IMMEDIATE");
            assertThat(stop.status()).isEqualTo("FILLED");
            assertThat(stop.qty()).isEqualTo(100);
            assertThat(stop.asOfTradingDate()).isEqualTo(MON);
            assertThat(stop.reason().path("trigger").asString()).isEqualTo("TRAILING_STOP");
            Fill fill = fills().get(0);
            assertThat(fill.price()).isCloseTo(91.9 * 0.9995, offset(1e-9));
            assertThat(fill.orderId()).isEqualTo(stop.id());
            assertThat(store.positions(BOOK)).isEmpty();
            assertThat(store.states.get(BOOK).cash()).isEqualTo(PaperLedgerService.gross(100, fill.price()));

            // 관측일 + 20거래일 = 2026-11-23까지(그날 포함) 다시 사지 않음
            assertThat(UsMarketCalendar.addTradingDays(MON, 20)).isEqualTo(LocalDate.of(2026, 11, 23));
            assertThat(store.lockouts(BOOK)).extracting(Lockout::ticker, Lockout::untilTradingDate)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("AAA", LocalDate.of(2026, 11, 23)));

            long rebuy = pending(store, BOOK, "AAA", "BUY", 1, null, null, "NEXT_OBSERVATION", MON, MON_FINAL);
            ledger.process(quotes(TUE_INTRADAY, bar("AAA", MON, 91.9, TUE, 93)));
            assertThat(order(store, rebuy).status()).isEqualTo("CANCELLED");
            assertThat(order(store, rebuy).rejectReason()).contains("재진입 금지");
            assertThat(fills()).hasSize(1);
        }

        @Test
        @DisplayName("같은 관측치에서 스탑이 매수보다 먼저 — 스탑에 걸린 종목의 대기 매수는 재진입 금지로 취소되고, 매도 대금은 다른 매수에 쓴다")
        void stopRunsBeforeBuysInSameObservation() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 0, null);
            position(store, BOOK, "AAA", 50, 95, 100, 100.0, FRI, 100);
            long rebuy = pending(store, BOOK, "AAA", "BUY", 10, null, null, "NEXT_OBSERVATION", FRI, SAT);
            long other = pending(store, BOOK, "BBB", "BUY", 10, null, null, "NEXT_OBSERVATION", FRI, SAT);

            Outcome outcome = ledger.process(quotes(MON_INTRADAY, bar("AAA", FRI, 100, MON, 91),
                    bar("BBB", FRI, 50, MON, 50)));

            // 매수가 먼저였다면 AAA 10주를 91.0455에 사고 곧바로 60주 전량을 스탑으로 팔았습니다(슬리피지만 두 번).
            assertThat(outcome.stopped()).isEqualTo(1);
            assertThat(order(store, rebuy).status()).isEqualTo("CANCELLED");
            assertThat(order(store, rebuy).rejectReason()).contains("재진입 금지");
            assertThat(fills()).extracting(Fill::side, Fill::ticker, Fill::qty)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("SELL", "AAA", 50),
                            org.assertj.core.groups.Tuple.tuple("BUY", "BBB", 10));
            assertThat(order(store, other).status()).as("스탑 매도 대금으로 체결").isEqualTo("FILLED");
            assertThat(store.positions(BOOK)).extracting(Position::ticker).containsExactly("BBB");
            // 50 × 90.9545 − 10 × 50.025
            assertThat(store.states.get(BOOK).cash()).isCloseTo(PaperLedgerService.gross(50, 91 * 0.9995)
                    - PaperLedgerService.gross(10, 50.025), offset(1e-6));
        }

        @Test
        @DisplayName("최고가는 관측치마다 올라가고, 스탑가 위면 팔지 않는다")
        void watermarkRises() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 0, null);
            position(store, BOOK, "AAA", 10, 95, 100, 100.0, FRI, 100);

            ledger.process(quotes(MON_INTRADAY, bar("AAA", FRI, 100, MON, 110)));
            assertThat(store.positions(BOOK).get(0).highWatermark()).isEqualTo(110);
            ledger.process(quotes(MON_FINAL, bar("AAA", FRI, 100, MON, 101.5)));   // 110 × 0.92 = 101.2 위

            assertThat(store.positions(BOOK)).hasSize(1);
            assertThat(fills()).isEmpty();
        }

        @Test
        @DisplayName("현금성(SHY)·벤치마크 책·이어 쓴 봉에는 스탑을 걸지 않는다")
        void noStopForCashLikeBenchmarkOrStale() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 0, null);
            position(store, BOOK, "SHY", 100, 82, 100, 100.0, FRI, 100);
            position(store, BOOK, "BBB", 100, 82, 100, 100.0, FRI, 100);
            activeBook(store, PaperBooks.BENCH_SPY, PaperBooks.KIND_BENCHMARK, 0, null);
            position(store, PaperBooks.BENCH_SPY, "SPY", 100, 82, 100, 100.0, FRI, 100);

            Outcome outcome = ledger.process(quotes(MON_INTRADAY, bar("SHY", FRI, 100, MON, 50),
                    bar("SPY", FRI, 100, MON, 50), staleBar("BBB", FRI, 100, MON, 50)));

            assertThat(outcome.stopped()).isZero();
            assertThat(store.positions(BOOK)).hasSize(2);
            assertThat(store.positions(BOOK)).filteredOn(p -> p.ticker().equals("BBB")).singleElement()
                    .satisfies(p -> {
                        assertThat(p.priceStale()).isTrue();
                        assertThat(p.lastPrice()).as("이어 쓴 봉으로 가격을 바꾸지 않음").isEqualTo(100.0);
                    });
            assertThat(store.positions(PaperBooks.BENCH_SPY)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("분할")
    class Splits {

        @Test
        @DisplayName("2:1 분할: 기준일 종가가 절반으로 다시 쓰이면 수량 2배·평단·최고가 절반 — 스탑보다 먼저라 가짜 스탑이 없다")
        void twoForOneBeforeStop() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 0, null);
            position(store, BOOK, "XYZ", 100, 200, 210, 200.0, FRI, 200);

            Outcome outcome = ledger.process(quotes(TUE_INTRADAY, bar("XYZ", FRI, 100, MON, 101, TUE, 102)));

            assertThat(outcome.splits()).isEqualTo(1);
            assertThat(outcome.stopped()).as("분할 전 최고가 210과 비교했다면 102 ≤ 193.2로 스탑").isZero();
            Position p = store.positions(BOOK).get(0);
            assertThat(p.qty()).isEqualTo(200);
            assertThat(p.avgCost()).isEqualTo(100);
            assertThat(p.highWatermark()).isEqualTo(105);
            assertThat(p.lastPrice()).isEqualTo(102);
            assertThat(p.fillRefClose()).isEqualTo(100);
            assertThat(p.splitNote()).contains("2:1").contains("100주 → 200주");
            assertThat(outcome.notes()).anyMatch(note -> note.contains("XYZ") && note.contains("2:1"));
        }

        @Test
        @DisplayName("기준일이 3개월 저장본에 없으면 10년 이력에서 찾는다")
        void referenceFromHistory() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 0, null);
            LocalDate old = LocalDate.of(2026, 6, 1);
            position(store, BOOK, "XYZ", 10, 300, 300, 300.0, old, 300);
            when(marketData.history(anyCollection(), eq(old), eq(old))).thenReturn(Map.of("XYZ",
                    new DailySeries(new int[]{(int) old.toEpochDay()}, new double[]{100})));

            ledger.process(quotes(MON_INTRADAY, bar("XYZ", FRI, 101, MON, 102)));

            Position p = store.positions(BOOK).get(0);
            assertThat(p.qty()).as("3:1").isEqualTo(30);
            assertThat(p.splitNote()).contains("3:1");
        }

        @Test
        @DisplayName("기준일 종가를 어디서도 못 찾으면 가격 없음으로 두고 가격·스탑을 건드리지 않는다")
        void missingReferenceMarksStale() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 0, null);
            position(store, BOOK, "XYZ", 10, 300, 300, 300.0, LocalDate.of(2026, 6, 1), 300);

            Outcome outcome = ledger.process(quotes(MON_INTRADAY, bar("XYZ", FRI, 101, MON, 102)));

            Position p = store.positions(BOOK).get(0);
            assertThat(p.priceStale()).isTrue();
            assertThat(p.lastPrice()).isEqualTo(300.0);
            assertThat(outcome.stopped()).isZero();
            assertThat(outcome.notes()).anyMatch(note -> note.contains("분할 확인 기준일"));
        }

        @Test
        @DisplayName("배당 조정(±10% 안)은 분할이 아니고, 확정 종가 관측치에서 기준을 그날로 옮긴다")
        void dividendDriftReanchors() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 0, null);
            position(store, BOOK, "AAA", 10, 100, 100, 100.0, FRI, 100);

            ledger.process(quotes(MON_FINAL, bar("AAA", FRI, 99, MON, 98)));

            Position p = store.positions(BOOK).get(0);
            assertThat(p.qty()).isEqualTo(10);
            assertThat(p.splitNote()).isNull();
            assertThat(p.fillRefDate()).isEqualTo(MON);
            assertThat(p.fillRefClose()).isEqualTo(98);
        }

        @Test
        @DisplayName("장중 관측치는 기준을 옮기지 않는다(장중 값과 종가가 벌어져 가짜 분할로 읽히지 않게)")
        void intradayDoesNotReanchor() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 0, null);
            position(store, BOOK, "AAA", 10, 100, 100, 100.0, FRI, 100);

            ledger.process(quotes(MON_INTRADAY, bar("AAA", FRI, 100, MON, 95)));

            Position p = store.positions(BOOK).get(0);
            assertThat(p.fillRefDate()).isEqualTo(FRI);
            assertThat(p.fillRefClose()).isEqualTo(100);
            assertThat(p.lastPrice()).isEqualTo(95);
        }
    }

    @Nested
    @DisplayName("평가·순자산·책 상태")
    class Valuation {

        @Test
        @DisplayName("확정 종가 관측치면 최빈 봉 날짜의 순자산을 확정으로 남기고, 장중이면 남기지 않는다")
        void navOnlyOnFinalClose() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 1_000, null);
            position(store, BOOK, "AAA", 10, 100, 100, 100.0, FRI, 100);

            ledger.process(quotes(MON_INTRADAY, bar("AAA", FRI, 100, MON, 101)));
            assertThat(store.navSeries(BOOK)).isEmpty();

            ledger.process(quotes(MON_FINAL, bar("AAA", FRI, 100, MON, 102), bar("BBB", FRI, 5, MON, 5),
                    staleBar("CCC", FRI, 1)));
            assertThat(store.navSeries(BOOK)).singleElement().satisfies(row -> {
                assertThat(row.tradingDate()).isEqualTo(MON);
                assertThat(row.pricesFinal()).isTrue();
                assertThat(row.nav()).isEqualTo(2_020);
                assertThat(row.exposurePct()).isCloseTo(1_020 / 2_020.0 * 100, offset(1e-4));
            });
            assertThat(store.states.get(BOOK).lastNavTradingDate()).isEqualTo(MON);
        }

        @Test
        @DisplayName("보유가 이어 쓴(장중) 봉으로 평가되면 그날 순자산은 미확정으로 쓰고, 온전한 확정 관측치가 그 줄을 확정으로 바꾼다")
        void staleHoldingMakesNavProvisional() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 1_000, null);
            position(store, BOOK, "AAPL", 10, 100, 100, 100.0, FRI, 100);
            ledger.process(quotes(MON_INTRADAY, bar("AAPL", FRI, 100, MON, 99)));   // 15:20 같은 장중 값 99

            // 16:20 수집에서 AAPL만 실패해 장중 봉을 이어 씀 — 다수 티커는 월요일 확정 봉
            ledger.process(quotes(MON_FINAL, staleBar("AAPL", FRI, 100, MON, 99), bar("SPY", FRI, 500, MON, 505),
                    bar("QQQ", FRI, 400, MON, 401)));
            assertThat(store.navSeries(BOOK)).singleElement().satisfies(row -> {
                assertThat(row.tradingDate()).isEqualTo(MON);
                assertThat(row.pricesFinal()).as("AAPL을 장중 값으로 평가").isFalse();
                assertThat(row.nav()).isEqualTo(1_990);
            });

            // 화요일 새벽(개장 전) 재수집에서 AAPL 월요일 종가를 받음 — 같은 세션이라 그 줄을 확정으로 바꿈
            ledger.process(quotes(java.time.Instant.parse("2026-10-27T10:00:00Z"), bar("AAPL", FRI, 100, MON, 98),
                    bar("SPY", FRI, 500, MON, 505)));
            assertThat(store.navSeries(BOOK)).singleElement().satisfies(row -> {
                assertThat(row.pricesFinal()).isTrue();
                assertThat(row.nav()).isEqualTo(1_980);
            });
        }

        @Test
        @DisplayName("현금만 든 책·모든 보유가 그 세션 확정 봉이면 확정, 봉을 못 받은 보유가 있으면 미확정")
        void navFinalNeedsEveryHolding() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 1_000, null);
            activeBook(store, PaperBooks.RULES, PaperBooks.KIND_RULES, 1_000, null);
            position(store, PaperBooks.RULES, "QQQ", 1, 400, 400, 400.0, FRI, 400);

            ledger.process(quotes(MON_FINAL, bar("SPY", FRI, 500, MON, 505)));

            assertThat(store.navSeries(BOOK)).singleElement().satisfies(row -> assertThat(row.pricesFinal()).isTrue());
            assertThat(store.navSeries(PaperBooks.RULES)).singleElement()
                    .satisfies(row -> assertThat(row.pricesFinal()).as("QQQ 봉 없음 — 마지막 값으로 평가").isFalse());
        }

        @Test
        @DisplayName("다음 날 개장 직후 관측치(봉 과반이 아직 전날)는 전날 확정 순자산을 덮지 않는다 — 개장 전 재저장은 덮어도 됨")
        void nextSessionDoesNotOverwritePreviousNav() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 1_000, null);
            position(store, BOOK, "AAA", 10, 100, 100, 100.0, FRI, 100);
            ledger.process(quotes(MON_FINAL, bar("AAA", FRI, 100, MON, 102), bar("BBB", FRI, 5, MON, 5)));
            assertThat(store.navSeries(BOOK)).singleElement().satisfies(row -> assertThat(row.nav()).isEqualTo(2_020));

            // 화요일 09:31 ET — AAA만 화요일 장중 봉(95, 스탑가 93.84 위), 나머지 둘은 아직 월요일 봉
            ledger.process(quotes(java.time.Instant.parse("2026-10-27T13:31:00Z"), bar("AAA", MON, 102, TUE, 95),
                    bar("BBB", FRI, 5, MON, 5), bar("CCC", FRI, 1, MON, 1)));

            assertThat(store.navSeries(BOOK)).singleElement().satisfies(row -> {
                assertThat(row.tradingDate()).isEqualTo(MON);
                assertThat(row.nav()).as("화요일 장중가로 다시 쓰지 않음").isEqualTo(2_020);
            });
            assertThat(store.states.get(BOOK).nav()).as("지금 평가는 따라감").isEqualTo(1_950);
            assertThat(store.states.get(BOOK).lastNavTradingDate()).isEqualTo(MON);

            ledger.process(quotes(TUE_FINAL, bar("AAA", MON, 102, TUE, 96), bar("BBB", MON, 5, TUE, 5)));
            assertThat(store.navSeries(BOOK)).extracting(row -> row.tradingDate()).containsExactly(MON, TUE);
        }

        @Test
        @DisplayName("다음 세션 장중 체결이 섞인 관측치는 남은 보유가 모두 지난 세션 확정 봉이어도 지난 세션 순자산을 다시 쓰지 않는다")
        void nextSessionFillDoesNotRewritePreviousNav() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 1_000, null);
            position(store, BOOK, "AAA", 10, 100, 100, 100.0, FRI, 100);
            position(store, BOOK, "BBB", 10, 5, 5, 5.0, FRI, 5);
            ledger.process(quotes(MON_FINAL, bar("AAA", FRI, 100, MON, 102), bar("BBB", FRI, 5, MON, 5)));
            assertThat(store.navSeries(BOOK)).singleElement().satisfies(row -> assertThat(row.nav()).isEqualTo(2_070));

            // 화요일 09:31 ET — 월요일 기준 매도 주문이 BBB의 화요일 장중 봉(8)으로 체결되고, 남은 AAA는 월요일 확정 봉 그대로.
            // 보유만 보면 '모두 월요일 확정'이지만, 이 평가에는 화요일 장중 체결 현금이 섞여 있어 월요일 줄이 될 수 없습니다.
            pending(store, BOOK, "BBB", "SELL", null, null, null, "NEXT_OBSERVATION", MON, MON_FINAL.plusSeconds(60));
            ledger.process(quotes(java.time.Instant.parse("2026-10-27T13:31:00Z"), bar("AAA", FRI, 100, MON, 102),
                    bar("BBB", MON, 5, TUE, 8)));

            assertThat(fills()).as("매도는 체결됨").hasSize(1);
            assertThat(store.navSeries(BOOK)).singleElement().satisfies(row -> {
                assertThat(row.tradingDate()).isEqualTo(MON);
                assertThat(row.nav()).as("화요일 장중 체결로 월요일 줄을 다시 쓰지 않음").isEqualTo(2_070);
            });
        }

        @Test
        @DisplayName("일시정지 책은 사고팔지 않지만 가격·평가는 따라간다")
        void pausedBookMarksToMarketOnly() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 1_000, null);
            store.updateBookStatus(BOOK, PaperBooks.PAUSED);
            position(store, BOOK, "AAA", 10, 100, 100, 100.0, FRI, 100);
            long id = pending(store, BOOK, "BBB", "BUY", 1, null, null, "NEXT_OBSERVATION", FRI, SAT);

            ledger.process(quotes(MON_FINAL, bar("AAA", FRI, 100, MON, 50), bar("BBB", FRI, 10, MON, 10)));

            assertThat(fills()).as("스탑도 체결도 없음").isEmpty();
            assertThat(order(store, id).status()).isEqualTo("PENDING");
            assertThat(store.positions(BOOK).get(0).lastPrice()).isEqualTo(50);
            assertThat(store.states.get(BOOK).nav()).isEqualTo(1_500);
            assertThat(store.navSeries(BOOK)).hasSize(1);
        }

        @Test
        @DisplayName("시작 전 책은 현금만 평가하고 순자산 기록을 남기지 않는다")
        void notStartedBookKeepsCashOnly() {
            activeBook(store, PaperBooks.RULES, PaperBooks.KIND_RULES, 100_000, null);
            store.updateBookStatus(PaperBooks.RULES, PaperBooks.NOT_STARTED);
            store.updateBookStartedOn(PaperBooks.RULES, null);

            Outcome outcome = ledger.process(quotes(MON_FINAL, bar("AAA", FRI, 100, MON, 101)));

            assertThat(outcome.booksProcessed()).containsExactly(PaperBooks.RULES);
            assertThat(store.navSeries(PaperBooks.RULES)).isEmpty();
            BookState state = store.states.get(PaperBooks.RULES);
            assertThat(state.nav()).isEqualTo(100_000);
            assertThat(state.lastProcessedCollectedAt()).isEqualTo(MON_FINAL);
        }

        @Test
        @DisplayName("가격을 한 번도 못 받은 보유는 평단으로 평가하고 가격 없음으로 둔다(0으로 평가하지 않음)")
        void neverPricedHoldingUsesAverageCost() {
            activeBook(store, BOOK, PaperBooks.KIND_CLAUDE, 0, null);
            position(store, BOOK, "QQQ", 10, 400, 400, null, FRI, 400);

            ledger.process(quotes(MON_FINAL, bar("AAA", FRI, 100, MON, 101)));

            assertThat(store.states.get(BOOK).nav()).isEqualTo(4_000);
            assertThat(store.positions(BOOK).get(0).priceStale()).isTrue();
        }

        @Test
        @DisplayName("한 책에서 오류가 나면 그 책만 되돌리고 다른 책은 처리한다 — 같은 관측치로는 다시 시도하지 않고 새 관측치에서 다시")
        void oneBookFailureIsIsolated() {
            FakePaperStore failing = new FakePaperStore() {
                @Override
                public void upsertState(BookState s) {
                    if (s.bookCode().equals(PaperBooks.CLAUDE) && s.lastProcessedCollectedAt() != null) {
                        throw new IllegalStateException("디스크 가득 참");
                    }
                    super.upsertState(s);
                }
            };
            PaperLedgerService service = new PaperLedgerService(failing, marketData, PaperFixtures.settings(failing),
                    PaperFixtures.MAPPER);
            activeBook(failing, PaperBooks.CLAUDE, PaperBooks.KIND_CLAUDE, 100_000, null);
            activeBook(failing, PaperBooks.BENCH_SPY, PaperBooks.KIND_BENCHMARK, 100_000, null);
            pending(failing, PaperBooks.CLAUDE, "AAA", "BUY", 10, null, null, "NEXT_OBSERVATION", FRI, SAT);
            pending(failing, PaperBooks.BENCH_SPY, "AAA", "BUY", 10, null, null, "NEXT_OBSERVATION", FRI, SAT);

            Outcome outcome = service.process(quotes(MON_INTRADAY, bar("AAA", FRI, 100, MON, 101)));

            assertThat(outcome.booksProcessed()).containsExactly(PaperBooks.BENCH_SPY);
            assertThat(outcome.errors()).singleElement().asString().startsWith("claude: ").contains("디스크 가득 참");
            assertThat(failing.fillsOf(PaperBooks.CLAUDE)).as("되돌림").isEmpty();
            assertThat(failing.ordersOf(PaperBooks.CLAUDE).get(0).status()).isEqualTo("PENDING");
            assertThat(failing.fillsOf(PaperBooks.BENCH_SPY)).hasSize(1);
            assertThat(failing.states.get(PaperBooks.CLAUDE).lastProcessedCollectedAt()).isNull();

            // 같은 관측치: 실패한 책만 남았으니 새 관측치가 아니고, 넣어도 다시 돌리지 않음(같은 오류를 5분마다 쌓지 않게)
            var same = quotes(MON_INTRADAY, bar("AAA", FRI, 100, MON, 101));
            assertThat(service.hasNewObservation(same)).isFalse();
            assertThat(service.failedBooks(same)).containsExactly(PaperBooks.CLAUDE);
            Outcome again = service.process(same);
            assertThat(again.errors()).isEmpty();
            assertThat(again.booksProcessed()).isEmpty();
            assertThat(again.skippedReason()).contains("claude").contains("새 관측치에서 다시 시도");

            // 새 관측치: 다시 시도(여전히 실패하면 다시 오류 — 관측치마다 한 번)
            var next = quotes(MON_FINAL, bar("AAA", FRI, 100, MON, 102));
            assertThat(service.hasNewObservation(next)).isTrue();
            assertThat(service.process(next).errors()).singleElement().asString().startsWith("claude: ");

            // 수동 실행처럼 기억을 지우면 같은 관측치로 한 번 더
            service.forgetFailures();
            assertThat(service.hasNewObservation(next)).isTrue();
        }

        @Test
        @DisplayName("테이블이 없거나 책이 없으면 아무것도 하지 않고 이유를 돌려준다")
        void skipsWithoutTablesOrBooks() {
            assertThat(ledger.process(quotes(MON_FINAL, bar("AAA", MON, 1))).skippedReason()).contains("초기화 전");
            store.ready = false;
            assertThat(ledger.process(quotes(MON_FINAL, bar("AAA", MON, 1))).skippedReason()).contains("테이블");
            verify(marketData, never()).history(anyCollection(), any(), any());
        }
    }
}
