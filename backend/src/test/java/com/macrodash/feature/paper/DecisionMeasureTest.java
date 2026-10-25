package com.macrodash.feature.paper;

import com.macrodash.analytics.CalendarWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.macrodash.feature.paper.PaperFixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결정 기록의 측정 태그(2단계 명세 §6) — 키 순서 고정, 아직 없는 항목은 null(0·false가 아님), 저장본이 없으면 hasCalendar=false·
 * calendarItems=null(0건과 구분).
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
        assertThat(m).isEqualTo(new DecisionMeasure(true, 2, null, null));
        assertThat(json(m)).isEqualTo("{\"hasCalendar\":true,\"calendarItems\":2,\"hasEvents\":null,\"hasEarnings\":null}");
        assertThat(DecisionMeasure.KEY).isEqualTo("measure");
    }

    @Test
    @DisplayName("저장본은 있는데 창 안 0건 — hasCalendar=true·calendarItems=0")
    void calendarEmptyWindow() {
        DecisionMeasure m = DecisionMeasure.ofCalendar(CalendarWindow.compute(stored(List.of()), AS_OF, List.of()));
        assertThat(json(m)).isEqualTo("{\"hasCalendar\":true,\"calendarItems\":0,\"hasEvents\":null,\"hasEarnings\":null}");
    }

    @Test
    @DisplayName("저장본 없음·결과 없음 — hasCalendar=false·calendarItems=null")
    void calendarMissing() {
        String expected = "{\"hasCalendar\":false,\"calendarItems\":null,\"hasEvents\":null,\"hasEarnings\":null}";
        assertThat(json(DecisionMeasure.ofCalendar(CalendarWindow.compute(null, AS_OF, List.of("AMZN")))))
                .isEqualTo(expected);
        assertThat(json(DecisionMeasure.ofCalendar(null))).isEqualTo(expected);
    }
}
