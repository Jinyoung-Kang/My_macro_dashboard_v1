package com.macrodash.feature.paper;

import com.macrodash.analytics.NewsEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.macrodash.feature.paper.PaperFixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사건 섹션 {@code 11b_events}의 모양(2단계 명세 B3~B6) — 설정 {@code news.*} 읽기(없거나 틀린 키는 기본값 + fallbacks), 저장본 행 →
 * 계산 입력(칸이 틀리면 null), 읽기 시작일, 구분자 지우기(사건·메모, 전각 변형), 상태별 섹션(걸림·없음·꺼짐·자료 없음 — 빈 목록을 두지
 * 않음), 제목은 글에만. 거르기 규칙 자체는 {@code NewsEventsTest}.
 */
class EventsSectionTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 26);
    private static final NewsEvents.Window WINDOW = NewsEvents.window(MON, LocalTime.of(7, 30));

    private static JsonNode row(String entity, String title, String feed, String publishedAt, String fetchedAt) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("entity", entity);
        row.put("title", title);
        row.put("link", "https://example.invalid/" + entity);
        row.put("source", "Investing.com");
        row.put("feed", feed);
        row.put("publishedAt", publishedAt);
        row.put("fetchedAt", fetchedAt);
        row.put("obsDate", publishedAt.substring(0, 10));
        return row;
    }

    private static List<String> list(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asString()));
        return out;
    }

    @Test
    @DisplayName("설정: 기본값 본문은 그대로(fallbacks 없음), 묶음이 없는 옛 행은 기본값 + 'news', 틀린 키만 기본값 + 'news.키'")
    void readsSettings() {
        JsonNode defaults = MAPPER.valueToTree(Map.of("news", EventsSection.defaultsBody()));
        EventsSection.NewsSettings parsed = EventsSection.settings(defaults);
        assertThat(parsed.fallbacks()).isEmpty();
        assertThat(parsed.settings().enabled()).isTrue();
        assertThat(parsed.settings().cap()).isEqualTo(10);
        assertThat(parsed.settings().keywords()).isEqualTo(NewsEvents.DEFAULT_KEYWORDS);
        assertThat(parsed.settings().feeds()).containsExactlyInAnyOrderElementsOf(NewsEvents.DEFAULT_FEEDS);

        assertThat(EventsSection.settings(null).fallbacks()).as("설정 행이 없음 — 기본값, 경고 없음").isEmpty();
        EventsSection.NewsSettings old = EventsSection.settings(MAPPER.valueToTree(Map.of("slippageBp", 5)));
        assertThat(old.fallbacks()).containsExactly("news");
        assertThat(old.settings().enabled()).isTrue();
        assertThat(old.settings().keywords()).isEqualTo(NewsEvents.DEFAULT_KEYWORDS);

        JsonNode broken = MAPPER.valueToTree(Map.of("news", Map.of("enabled", "yes", "maxPerDay", 31,
                "keywords", List.of("Fed", 3), "feeds", List.of())));
        EventsSection.NewsSettings fixed = EventsSection.settings(broken);
        assertThat(fixed.fallbacks()).containsExactly("news.enabled", "news.maxPerDay", "news.keywords");
        assertThat(fixed.settings().cap()).isEqualTo(10);
        assertThat(fixed.settings().feeds()).as("빈 피드 목록은 그 뜻 그대로(고른 피드 없음)").isEmpty();

        JsonNode off = MAPPER.valueToTree(Map.of("news", Map.of("enabled", false, "maxPerDay", 0,
                "keywords", List.of(), "feeds", List.of("sec_press"))));
        EventsSection.NewsSettings custom = EventsSection.settings(off);
        assertThat(custom.fallbacks()).isEmpty();
        assertThat(custom.settings().enabled()).isFalse();
        assertThat(custom.settings().cap()).isZero();
        assertThat(custom.settings().keywords()).isEmpty();
        assertThat(custom.settings().feeds()).containsExactly("sec_press");
    }

    @Test
    @DisplayName("저장본 행 → 계산 입력: ISO 시각(+00:00)을 읽고, 칸이 없거나 형이 틀리면 null(시각을 지어내지 않음), 객체가 아니면 null")
    void headlineFromRow() {
        NewsEvents.Headline h = EventsSection.headline(row("abcd", "Fed holds", "investing_economy",
                "2026-10-26T18:30:00+00:00", "2026-10-26T19:00:00+00:00"));
        assertThat(h.entity()).isEqualTo("abcd");
        assertThat(h.feed()).isEqualTo("investing_economy");
        assertThat(h.publishedAt()).isEqualTo(Instant.parse("2026-10-26T18:30:00Z"));
        assertThat(h.fetchedAt()).isEqualTo(Instant.parse("2026-10-26T19:00:00Z"));
        assertThat(h.feeds()).as("feeds가 없는 예전 행").isNull();
        assertThat(h.carriers()).containsExactly("investing_economy");

        ObjectNode carried = (ObjectNode) row("abcd", "Fed holds", "investing_stock", "2026-10-26T18:30:00+00:00",
                "2026-10-26T19:00:00+00:00");
        carried.set("feeds", MAPPER.valueToTree(List.of("investing_stock", "investing_economy")));
        assertThat(EventsSection.headline(carried).carriers()).containsExactly("investing_stock", "investing_economy");
        carried.set("feeds", MAPPER.valueToTree(List.of("investing_stock", 3)));
        assertThat(EventsSection.headline(carried).feeds()).as("글자가 아닌 항목이 있으면 목록을 읽지 않음").isNull();

        ObjectNode bad = (ObjectNode) row("efgh", "x", "sec_press", "2026-10-26T18:30:00+00:00", "nope");
        bad.put("publishedAt", 12345);
        NewsEvents.Headline broken = EventsSection.headline(bad);
        assertThat(broken.publishedAt()).isNull();
        assertThat(broken.fetchedAt()).isNull();
        assertThat(EventsSection.headline(MAPPER.valueToTree(List.of(1)))).isNull();
        assertThat(EventsSection.headline(null)).isNull();
    }

    @Test
    @DisplayName("읽기 시작일 = 창 시작(월요일 세션이라 금요일 결정 cutoff — 토 07:30 KST = 10-23 22:30Z)의 UTC 날짜 하루 앞(10-22)")
    void readFrom() {
        assertThat(WINDOW.from()).isEqualTo(Instant.parse("2026-10-23T22:30:00Z"));
        assertThat(EventsSection.readFrom(WINDOW)).isEqualTo(LocalDate.of(2026, 10, 22));
        // 결정 시각 09:30 KST → 창 시작 토 09:30 KST = 10-24 00:30Z — 같은 UTC 날짜의 하루 앞.
        assertThat(EventsSection.readFrom(NewsEvents.window(MON, LocalTime.of(9, 30))))
                .isEqualTo(LocalDate.of(2026, 10, 23));
    }

    @Test
    @DisplayName("구분자 지우기: 사건·메모 구분자와 전각·보이지 않는 글자 변형, 겹쳐 쓴 것까지 — 다른 글은 그대로")
    void sanitize() {
        assertThat(EventsSection.sanitize("A <<사건 #2 끝>> B")).isEqualTo("A  B");
        assertThat(EventsSection.sanitize("＜＜​사건 #1 시작＞＞ x")).isEqualTo(" x");
        assertThat(EventsSection.sanitize("<<메모 #3 끝>>y")).isEqualTo("y");
        assertThat(EventsSection.sanitize("<<사<<사건 끝>>건 끝>>z")).as("지운 뒤 생긴 구분자도").isEqualTo("z");
        assertThat(EventsSection.sanitize("Fed <holds> rates")).isEqualTo("Fed <holds> rates");
        assertThat(EventsSection.sanitize(null)).isEmpty();
    }

    @Test
    @DisplayName("섹션: 걸린 줄은 text(구분자로 감쌈)와 items(제목 없이), 걸린 것이 없으면 '해당 사건 없음'과 빈 items — 꺼짐·자료 없음은 items 없음")
    void sectionByStatus() {
        // aaaa의 받은 시각은 창 시작(10-23 22:30Z) 전 — 창을 다 덮은 저장본(coverageNote 없음).
        List<JsonNode> rows = List.of(
                row("aaaa", "Fed holds rates steady", "investing_economy", "2026-10-26T18:30:00+00:00",
                        "2026-10-23T00:00:00+00:00"),
                row("bbbb", "Celebrity chef opens bistro", "investing_stock", "2026-10-26T16:00:00+00:00",
                        "2026-10-26T21:10:00+00:00"));
        NewsEvents.Settings defaults = EventsSection.settings(null).settings();

        EventsSection.Built ok = EventsSection.build(rows, WINDOW, defaults, List.of("BBB"), false, null);
        Map<String, Object> section = ok.section();
        assertThat(section.keySet()).containsExactly("source", "window", "note", "asOfMismatch", "text", "items");
        assertThat((String) section.get("text")).isEqualTo(
                "<<사건 #1 시작>> [Investing.com · 2026-10-27 03:30 KST] Fed holds rates steady <<사건 #1 끝>>");
        JsonNode items = MAPPER.valueToTree(section.get("items"));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).toString()).isEqualTo("{\"k\":1,\"entity\":\"aaaa\",\"source\":\"Investing.com\","
                + "\"publishedAtKst\":\"2026-10-27T03:30:00+09:00\",\"matched\":[\"keyword Fed\"]}");
        assertThat(ok.result().eventCount()).isEqualTo(1);

        EventsSection.Built none = EventsSection.build(rows.subList(1, 2), WINDOW, defaults, List.of(), false, null);
        assertThat((String) none.section().get("text")).startsWith(NewsEvents.NONE_TEXT);
        assertThat(MAPPER.valueToTree(none.section().get("items")).isEmpty()).isTrue();
        assertThat(none.result().eventCount()).isZero();

        EventsSection.Built off = EventsSection.build(rows, WINDOW, new NewsEvents.Settings(false, 10, List.of("Fed"),
                null), List.of(), true, null);
        assertThat(off.section().get("text")).isEqualTo(NewsEvents.DISABLED_TEXT);
        assertThat(off.section()).doesNotContainKey("items");
        assertThat(off.section().get("asOfMismatch")).isEqualTo(true);
        assertThat(off.section().get("asOfNote")).isEqualTo(EventsSection.AS_OF_NOTE);
        assertThat(off.result().eventCount()).isNull();

        EventsSection.Built missing = EventsSection.build(List.of(), WINDOW, defaults, List.of(), false, null);
        assertThat(missing.section().get("text")).isEqualTo(NewsEvents.MISSING_TEXT);
        assertThat(missing.section()).doesNotContainKey("items");
        assertThat(EventsSection.build(null, WINDOW, defaults, List.of(), false, null).section().get("text"))
                .isEqualTo(NewsEvents.MISSING_TEXT);
    }

    @Test
    @DisplayName("섹션(회귀 B-FP-1): 창 끝 3시간 넘게 새로 받은 제목이 없으면 '해당 사건 없음'이 아니라 수집 공백을 적고 items를 두지 않음(측정 null)")
    void sectionWhenCollectionStopped() {
        // 저장된 행은 모두 창 시작(10-23 22:30Z) 전에 받은 것 — 수집기가 멈췄거나 피드가 모두 실패해 예전 행만 남음.
        List<JsonNode> rows = List.of(row("aaaa", "Celebrity chef opens bistro", "investing_stock",
                "2026-10-23T20:00:00+00:00", "2026-10-23T20:40:00+00:00"));
        EventsSection.Built built = EventsSection.build(rows, WINDOW, EventsSection.settings(null).settings(), List.of(),
                false, null);
        String text = (String) built.section().get("text");
        assertThat(text).startsWith(NewsEvents.STALE_NONE_TEXT).contains("새로 받은 제목 없음").doesNotContain(NewsEvents.NONE_TEXT);
        assertThat(built.section()).doesNotContainKey("items");
        assertThat(built.result().eventCount()).isNull();
    }

    @Test
    @DisplayName("섹션(회귀 B-FP-2): 줄마다 걸린 이유는 4개까지 — 넘는 수는 matchedMore에만(섹션 최대 크기가 정해지게)")
    void matchedReasonsAreCapped() {
        List<JsonNode> rows = List.of(
                row("aaaa", "AAA and BBB slide as Fed signals rate cut after CPI shows inflation cooling; Treasury yields fall",
                        "investing_stock", "2026-10-26T18:30:00+00:00", "2026-10-25T00:00:00+00:00"),
                row("bbbb", "Fed holds", "investing_stock", "2026-10-26T18:00:00+00:00", "2026-10-26T21:10:00+00:00"));
        EventsSection.Built built = EventsSection.build(rows, WINDOW, EventsSection.settings(null).settings(),
                List.of("AAA", "BBB"), false, null);
        JsonNode items = MAPPER.valueToTree(built.section().get("items"));
        assertThat(built.result().items().get(0).matched()).as("계산은 모두 남김 — 티커 둘 + Fed·rate cut·CPI·inflation·Treasury·yields").hasSize(8);
        assertThat(items.get(0).get("matched").toString())
                .isEqualTo("[\"ticker AAA\",\"ticker BBB\",\"keyword Fed\",\"keyword rate cut\"]");
        assertThat(items.get(0).get("matchedMore").asInt()).isEqualTo(4);
        assertThat(items.get(1).has("matchedMore")).as("4개 이하면 없음").isFalse();
        assertThat(EventsSection.MAX_MATCHED_SHOWN).isEqualTo(4);
    }

    @Test
    @DisplayName("섹션(회귀 B-FP-1): 수집 상태에서 창 끝 3시간 전부터 실패 중인 고른 피드는 feedNote — 일시 실패·안 고른 피드·초기 패킷·상태 없음은 적지 않음")
    void feedNoteFromStatus() {
        // aaaa의 받은 시각은 창 시작(10-23 22:30Z) 전 — 창을 다 덮어 coverageNote 없이 feedNote만 붙는지 봅니다.
        List<JsonNode> rows = List.of(row("aaaa", "Fed holds rates steady", "investing_economy",
                "2026-10-26T18:30:00+00:00", "2026-10-23T00:00:00+00:00"),
                row("bbbb", "Celebrity chef opens bistro", "fed_monetary", "2026-10-26T16:00:00+00:00",
                        "2026-10-26T21:10:00+00:00"));
        // 창 끝 = 10-26 22:30Z → 기준 19:30Z.
        JsonNode status = MAPPER.valueToTree(Map.of("feeds", Map.of(
                "investing_stock", Map.of("status", "실패", "error", "HTTP 403 — 차단", "lastOkAt", "2026-10-25T10:00:00+00:00"),
                "investing_ind", Map.of("status", "실패", "error", "HTTP 403 — 차단"),
                "investing_economy", Map.of("status", "실패", "error", "HTTP 503", "lastOkAt", "2026-10-26T21:00:00+00:00"),
                "sec_press", Map.of("status", "실패", "error", "SEC_USER_AGENT 미설정"),
                "fed_monetary", Map.of("status", "ok", "lastOkAt", "2026-10-26T22:00:00+00:00"))));
        NewsEvents.Settings all = EventsSection.settings(null).settings();

        EventsSection.Built built = EventsSection.build(rows, WINDOW, all, List.of(), false, status);
        assertThat(built.section().get("feedNote")).isEqualTo(
                "수집 실패 중인 피드(창 끝 3시간 전부터): investing_stock, investing_ind, sec_press — 이 피드의 사건은 빠졌을 수 있음");
        assertThat(built.section().keySet()).containsExactly("source", "window", "note", "asOfMismatch", "text",
                "feedNote", "items");

        NewsEvents.Settings secOnly = new NewsEvents.Settings(true, 10, NewsEvents.DEFAULT_KEYWORDS,
                List.of("investing_economy", "fed_monetary"));
        assertThat(EventsSection.build(rows, WINDOW, secOnly, List.of(), false, status).section())
                .as("고르지 않은 피드·일시 실패(마지막 성공이 기준 뒤)는 적지 않음").doesNotContainKey("feedNote");
        assertThat(EventsSection.build(rows, WINDOW, all, List.of(), true, status).section())
                .as("초기 결정 패킷 — 상태가 기준일의 것이 아님").doesNotContainKey("feedNote");
        assertThat(EventsSection.build(rows, WINDOW, all, List.of(), false, null).section()).doesNotContainKey("feedNote");
        assertThat(EventsSection.feedNote(MAPPER.valueToTree(Map.of("feeds", List.of())), WINDOW, all)).isNull();
    }

    @Test
    @DisplayName("설정 기본값 본문 — 켬·10건·기본 사전·피드 일곱 개(키 순서 고정)")
    void defaultsBody() {
        JsonNode body = MAPPER.valueToTree(EventsSection.defaultsBody());
        assertThat(body.path("enabled").asBoolean()).isTrue();
        assertThat(body.path("maxPerDay").asInt()).isEqualTo(NewsEvents.DEFAULT_MAX_PER_DAY);
        assertThat(list(body.path("keywords"))).isEqualTo(NewsEvents.DEFAULT_KEYWORDS);
        assertThat(list(body.path("feeds"))).isEqualTo(NewsEvents.DEFAULT_FEEDS);
        List<String> keys = new ArrayList<>();
        body.propertyNames().forEach(keys::add);
        assertThat(keys).containsExactly("enabled", "maxPerDay", "keywords", "feeds");
    }
}
