package com.macrodash.feature.paper;

import com.macrodash.analytics.CalendarWindow;
import com.macrodash.analytics.NewsEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static com.macrodash.feature.paper.PaperFixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결정 기록의 측정 태그(2단계 명세 §6) — 키 순서 고정, 아직 없는 항목은 null(0·false가 아님), 저장본이 없으면 hasCalendar=false·
 * calendarItems=null(0건과 구분). 사건(B6): hasEvents = 실은 건수·eventIds = entity 목록, 섹션이 사건을 고르지 않았으면(끔·자료 없음)
 * 둘 다 null.
 */
class DecisionMeasureTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 23);

    private static String json(DecisionMeasure m) {
        return MAPPER.writeValueAsString(PacketBuilder.toNode(m.toMap()));
    }

    private static CalendarWindow.Stored stored(List<CalendarWindow.MacroEvent> events) {
        return new CalendarWindow.Stored(events, "ok", Map.of(), null, null, null, null, Map.of(), null, null, null);
    }

    @Test
    @DisplayName("일정 섹션이 있으면 hasCalendar=true·줄 수, 사건·서프라이즈는 아직 null")
    void calendarPresent() {
        CalendarWindow.Result r = CalendarWindow.compute(stored(List.of(
                new CalendarWindow.MacroEvent("FOMC 회의", LocalDate.of(2026, 10, 28), CalendarWindow.FOMC, null, "u",
                        false, null),
                new CalendarWindow.MacroEvent("GDP", LocalDate.of(2026, 10, 29), CalendarWindow.RELEASE, 53, "f",
                        null, null))), AS_OF, List.of());
        DecisionMeasure m = DecisionMeasure.ofCalendar(r);
        assertThat(m).isEqualTo(new DecisionMeasure(true, 2, null, null, null));
        assertThat(json(m)).isEqualTo(
                "{\"hasCalendar\":true,\"calendarItems\":2,\"hasEvents\":null,\"eventIds\":null,\"hasEarnings\":null}");
        assertThat(DecisionMeasure.KEY).isEqualTo("measure");
    }

    @Test
    @DisplayName("저장본은 있는데 창 안 0건 — hasCalendar=true·calendarItems=0")
    void calendarEmptyWindow() {
        DecisionMeasure m = DecisionMeasure.ofCalendar(CalendarWindow.compute(stored(List.of()), AS_OF, List.of()));
        assertThat(json(m)).isEqualTo(
                "{\"hasCalendar\":true,\"calendarItems\":0,\"hasEvents\":null,\"eventIds\":null,\"hasEarnings\":null}");
    }

    @Test
    @DisplayName("저장본 없음·결과 없음 — hasCalendar=false·calendarItems=null")
    void calendarMissing() {
        String expected =
                "{\"hasCalendar\":false,\"calendarItems\":null,\"hasEvents\":null,\"eventIds\":null,\"hasEarnings\":null}";
        assertThat(json(DecisionMeasure.ofCalendar(CalendarWindow.compute(null, AS_OF, List.of("AMZN")))))
                .isEqualTo(expected);
        assertThat(json(DecisionMeasure.ofCalendar(null))).isEqualTo(expected);
    }

    // ------------------------------------------------------------------ 사건(B6)
    private static final NewsEvents.Window WINDOW = NewsEvents.window(AS_OF, LocalTime.of(7, 30));
    private static final Instant IN_WINDOW = Instant.parse("2026-10-23T15:00:00Z");

    /** 창 끝 30분 전에 받은 창 밖 행 — 수집이 창 끝까지 돌았음(NewsEvents.STALE 아님). */
    private static final NewsEvents.Headline FRESH = new NewsEvents.Headline("zzzz", "Weekly wrap", null, "Investing.com",
            "investing_stock", WINDOW.from().minusSeconds(3_600), WINDOW.to().minusSeconds(1_800));

    private static NewsEvents.Headline headline(String entity, String title) {
        return new NewsEvents.Headline(entity, title, null, "Investing.com", "investing_economy", IN_WINDOW,
                Instant.parse("2026-10-20T00:00:00Z"));
    }

    @Test
    @DisplayName("사건: 실은 건수와 entity 목록(섹션 순서) — 걸린 것이 없으면 0과 빈 목록(골랐는데 없음)")
    void eventsPicked() {
        DecisionMeasure base = DecisionMeasure.ofCalendar(null);
        NewsEvents.Result two = NewsEvents.compute(List.of(headline("aaaa", "Fed holds rates"),
                headline("bbbb", "Oil jumps on OPEC cut"), headline("cccc", "Celebrity chef opens bistro")), WINDOW,
                NewsEvents.Settings.defaults(), null, t -> t);
        DecisionMeasure m = base.withEvents(two);
        assertThat(m.hasEvents()).isEqualTo(2);
        assertThat(m.eventIds()).containsExactly("aaaa", "bbbb");
        assertThat(json(m)).isEqualTo("{\"hasCalendar\":false,\"calendarItems\":null,\"hasEvents\":2,"
                + "\"eventIds\":[\"aaaa\",\"bbbb\"],\"hasEarnings\":null}");

        NewsEvents.Result none = NewsEvents.compute(List.of(headline("cccc", "Celebrity chef opens bistro"), FRESH),
                WINDOW, NewsEvents.Settings.defaults(), null, t -> t);
        assertThat(json(base.withEvents(none))).contains("\"hasEvents\":0,\"eventIds\":[]");
    }

    @Test
    @DisplayName("사건(회귀 B-FP-1): 걸린 것이 없는데 창 끝 3시간 넘게 새로 받은 제목이 없으면(수집 중단) 0이 아니라 null")
    void eventsUnknownWhenCollectionStopped() {
        NewsEvents.Result stale = NewsEvents.compute(List.of(headline("cccc", "Celebrity chef opens bistro")), WINDOW,
                NewsEvents.Settings.defaults(), null, t -> t);
        assertThat(stale.coverage()).isEqualTo(NewsEvents.STALE);
        DecisionMeasure m = DecisionMeasure.ofCalendar(null).withEvents(stale);
        assertThat(m.hasEvents()).isNull();
        assertThat(m.eventIds()).isNull();
    }

    @Test
    @DisplayName("사건: 설정으로 끔·저장본 없음·결과 없음이면 hasEvents·eventIds 모두 null(0건과 구분)")
    void eventsNotPicked() {
        DecisionMeasure base = DecisionMeasure.ofCalendar(null);
        NewsEvents.Result disabled = NewsEvents.compute(List.of(headline("aaaa", "Fed holds rates")), WINDOW,
                new NewsEvents.Settings(false, 10, List.of("Fed"), null), null, t -> t);
        NewsEvents.Result missing = NewsEvents.compute(List.of(), WINDOW, NewsEvents.Settings.defaults(), null,
                t -> t);
        for (DecisionMeasure m : List.of(base.withEvents(disabled), base.withEvents(missing), base.withEvents(null))) {
            assertThat(m.hasEvents()).isNull();
            assertThat(m.eventIds()).isNull();
            assertThat(json(m)).contains("\"hasEvents\":null,\"eventIds\":null");
        }
    }
}
