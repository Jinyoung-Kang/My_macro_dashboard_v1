package com.macrodash.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 13F 저장본을 트리 없이 열로 읽는 두 메서드({@link StoreRepository#readSnapshotMeta}, {@link StoreRepository#readQuarterHoldings}) —
 * 실제 테스트 DB(macrodash_test)의 JSONB 풀기를 확인합니다. 테스트 이름표({@code t.…})의 저장본만 만들고 지웁니다.
 */
class StoreRepositoryHoldingsDbTest {

    private static final String NAME = "t.sec.13f.0000000001.q30";

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
        jdbc.update("DELETE FROM snapshots WHERE name LIKE 't.%'");
    }

    private void put(String payload) {
        jdbc.update("INSERT INTO snapshots (name, payload) VALUES (?, ?::jsonb)", NAME, payload);
    }

    @Test
    @DisplayName("분기 순서를 지키고, 보유 없는 분기도 한 줄, 숫자가 아닌 평가액·주식 수는 null")
    void unpacksQuartersAndHoldings() {
        put("""
                {"cik":"0000000001","complete":true,"quarters":[
                  {"filingDate":"2026-08-14","reportDate":"2026-06-30","filerCik":"0000000001","holdings":[
                    {"name":"A","cusip":"000000AA1","ticker":"AAA","value":1000.5,"shares":10},
                    {"name":"B","cusip":"000000BB2","ticker":null,"value":"모름","shares":null}]},
                  {"filingDate":"2026-05-15","reportDate":"2026-03-31","holdings":[]},
                  {"filingDate":"2026-02-14","reportDate":"2025-12-31","holdings":[
                    {"name":"A","cusip":"000000AA1","ticker":"AAA","value":900,"shares":9}]}]}
                """);

        List<StoreRepository.HoldingRow> rows = repository.readQuarterHoldings(NAME);

        assertThat(rows).extracting(StoreRepository.HoldingRow::quarterIndex).containsExactly(0, 0, 1, 2);
        assertThat(rows.get(0)).isEqualTo(new StoreRepository.HoldingRow(0, "2026-08-14", "2026-06-30", "0000000001",
                "000000AA1", "AAA", 1000.5, 10.0));
        assertThat(rows.get(1).ticker()).isNull();
        assertThat(rows.get(1).value()).as("숫자가 아니면 0이 아니라 null").isNull();
        assertThat(rows.get(2)).as("보유 없는 분기도 한 줄")
                .isEqualTo(new StoreRepository.HoldingRow(1, "2026-05-15", "2026-03-31", null, null, null, null, null));
        assertThat(rows.get(3).value()).isEqualTo(900.0);
    }

    @Test
    @DisplayName("메타 읽기는 큰 배열 키를 빼고 돌려주고, 없는 저장본·배열이 아닌 quarters는 빈 결과")
    void metaDropsTheArray() {
        assertThat(repository.readSnapshotMeta(NAME, "quarters")).isEmpty();
        assertThat(repository.readQuarterHoldings(NAME)).isEmpty();

        put("{\"cik\":\"0000000001\",\"complete\":false,\"quarters\":\"고장\"}");
        Snapshot meta = repository.readSnapshotMeta(NAME, "quarters").orElseThrow();
        assertThat(meta.payload().has("quarters")).isFalse();
        assertThat(meta.payload().path("complete").asBoolean(true)).isFalse();
        assertThat(meta.collectedAt()).isNotNull();
        assertThat(repository.readQuarterHoldings(NAME)).as("배열이 아니면 빈 배열로 봄").isEmpty();
    }
}
