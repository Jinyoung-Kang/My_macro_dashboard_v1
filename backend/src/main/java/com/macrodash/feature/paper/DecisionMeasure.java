package com.macrodash.feature.paper;

import com.macrodash.analytics.CalendarWindow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 🧪 결정 기록의 측정 태그(2단계 명세 §6) — 그 결정의 패킷에 일정·사건·서프라이즈가 들어 있었는지. 8주 뒤 결정 로그에서 "일정이
 * 논거에 인용됐나·재량 주문이 늘었나"를 볼 때, 패킷 원문을 다시 열지 않고 이 태그로 결정을 가릅니다.
 *
 * <p>패킷을 만들 때 정하고(같은 패킷이면 같은 태그) 결정 기록의 응답 JSON에 {@code measure}로 옮깁니다. 아직 만들지 않은 항목은
 * {@code null}입니다 — {@code false}·0이면 "그 섹션이 있었고 비었다"로 읽혀, 섹션이 생기기 전 결정과 섞입니다.
 *
 * @param hasCalendar   {@code 1b_calendar}에 일정 저장본이 있었는지(창 안 0건이어도 true — 저장본이 없으면 false)
 * @param calendarItems 그 섹션의 줄 수(거시 + 실적). 저장본이 없으면 null(0건과 구분)
 * @param hasEvents     사건 뉴스 건수(기능 B) — 아직 없음(null)
 * @param hasEarnings   어닝 서프라이즈가 들어 있었는지(기능 C) — 아직 없음(null)
 */
record DecisionMeasure(boolean hasCalendar, Integer calendarItems, Integer hasEvents, Boolean hasEarnings) {

    /** 결정 기록 응답 JSON의 키. */
    static final String KEY = "measure";

    /** 일정 섹션만 있는 지금(기능 A)의 태그 — 사건·서프라이즈는 null. */
    static DecisionMeasure ofCalendar(CalendarWindow.Result calendar) {
        boolean has = calendar != null && calendar.available();
        return new DecisionMeasure(has, has ? calendar.rows().size() : null, null, null);
    }

    /**
     * 기록용 맵 — 키 순서를 고정하고 null 값도 남깁니다(패킷의 {@code toNode}가 넣은 순서 그대로 직렬화). 키가 빠지면 "태그가
     * 없던 판"과 "항목이 아직 없음"을 구분하지 못합니다.
     */
    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hasCalendar", hasCalendar);
        out.put("calendarItems", calendarItems);
        out.put("hasEvents", hasEvents);
        out.put("hasEarnings", hasEarnings);
        return out;
    }
}
