package com.macrodash.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 날짜 범위 관측 기록 읽기({@link StoreRepository#readObservationsBetween}) — 모의 매매 사건 뉴스(2단계 B, obs_date = 발행 시각의 UTC
 * 날짜)를 결정 창이 덮는 며칠만 읽는지 실제 테스트 DB(macrodash_test)로 확인합니다. 테스트 이름표 데이터셋({@value #DATASET})의 행만
 * 만들고 지웁니다.
 */
class StoreRepositoryObservationsBetweenDbTest {

    private static final String DATASET = "t.news_headline";

    private JdbcTemplate jdbc;
    private StoreRepository repository;

    @BeforeEach
    void setUp() {
        String url = System.getenv().getOrDefault("TEST_DATABASE_URL",
                "jdbc:postgresql://localhost:5432/macrodash_test");
        assertThat(url).as("테스트 DB만 씁니다").endsWith("_test");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv().getOrDefault("DATABASE_USER", "macro"),
                System.getenv().getOrDefault("DATABASE_PASSWORD", "macro")));
        repository = new StoreRepository(jdbc, new ObjectMapper());
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM observations WHERE dataset LIKE 't.%'");
    }

    private void put(String dataset, String day, String entity, String title) {
        jdbc.update("INSERT INTO observations (dataset, obs_date, entity, payload) VALUES (?, ?, ?, ?::jsonb)",
                dataset, java.sql.Date.valueOf(day), entity, "{\"title\":\"" + title + "\"}");
    }

    @Test
    @DisplayName("[from, to] 양 끝 포함(to가 없으면 끝 없음)·최신 날짜 → entity 순, payload에 obsDate·entity — 범위 밖·다른 데이터셋은 빠짐, 거꾸로 된 범위는 빈 목록")
    void readsRangeNewestFirst() {
        put(DATASET, "2026-10-24", "bbbb", "before range");
        put(DATASET, "2026-10-25", "cccc", "first day");
        put(DATASET, "2026-10-26", "eeee", "last day e");
        put(DATASET, "2026-10-26", "dddd", "last day d");
        put(DATASET, "2026-10-27", "ffff", "after range");
        put("t.other", "2026-10-26", "gggg", "other dataset");

        List<JsonNode> rows = repository.readObservationsBetween(DATASET, LocalDate.of(2026, 10, 25),
                LocalDate.of(2026, 10, 26));

        assertThat(rows).extracting(node -> node.path("entity").asString()).containsExactly("dddd", "eeee", "cccc");
        assertThat(rows).extracting(node -> node.path("obsDate").asString())
                .containsExactly("2026-10-26", "2026-10-26", "2026-10-25");
        assertThat(rows.get(0).path("title").asString()).isEqualTo("last day d");
        assertThat(repository.readObservationsBetween(DATASET, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 25)))
                .isEmpty();
        assertThat(repository.readObservationsBetween(DATASET, null, LocalDate.of(2026, 10, 25))).isEmpty();
        assertThat(repository.readObservationsBetween(DATASET, LocalDate.of(2026, 10, 26), null))
                .as("끝이 없으면 시작일부터 전부").extracting(node -> node.path("entity").asString())
                .containsExactly("ffff", "dddd", "eeee");
    }
}
