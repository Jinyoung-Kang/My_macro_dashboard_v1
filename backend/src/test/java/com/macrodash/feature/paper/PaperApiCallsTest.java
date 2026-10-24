package com.macrodash.feature.paper;

import com.macrodash.feature.ai.AiService;
import com.macrodash.store.PaperStore.ApiUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.macrodash.feature.paper.PaperFixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Claude 호출 예산({@link PaperApiCalls}) — 하루 상한 잡기·되돌리기, 사용량(토큰)과 추정 비용 기록(명세 C8, 계약 보충 §3).
 */
class PaperApiCallsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 27);

    private FakePaperStore store;
    private PaperSettingsService settings;
    private PaperApiCalls calls;

    @BeforeEach
    void setUp() {
        store = new FakePaperStore();
        settings = PaperFixtures.settings(store);
        store.initSettingsIfAbsent(settings.defaultsJson());
        calls = new PaperApiCalls(store, settings);
    }

    private static AiService.StructuredResult result(String errorKind, Long in, Long out, Long cacheRead,
                                                     Long cacheWrite) {
        return new AiService.StructuredResult(errorKind == null, errorKind == null ? "{}" : null, "end_turn", errorKind,
                errorKind == null ? null : "실패", errorKind == null || !"NO_KEY".equals(errorKind), in, out, cacheRead,
                cacheWrite, 1200);
    }

    @Test
    @DisplayName("상한 안에서만 잡고 몇 번째 호출인지 돌려주며, 되돌리기는 0 아래로 가지 않는다")
    void reserveWithinCap() {
        assertThat(calls.reserve(DAY, 2)).contains(1L);
        assertThat(calls.reserve(DAY, 2)).contains(2L);
        assertThat(calls.reserve(DAY, 2)).as("상한").isEmpty();
        assertThat(calls.reserve(DAY, 0)).isEmpty();
        calls.release(DAY);
        assertThat(calls.reserve(DAY, 2)).contains(2L);
        assertThat(calls.reserve(DAY.plusDays(1), 2)).as("날마다 따로").contains(1L);
    }

    @Test
    @DisplayName("추정 비용 = (입력×입력 단가 + 출력×출력 단가 + 캐시 읽기×읽기 단가 + 캐시 쓰기×쓰기 단가) ÷ 100만 — 모델 id로 단가를 고름")
    void estimateFormula() {
        PaperApiCalls.Pricing pricing = PaperApiCalls.Pricing.defaults();
        assertThat(PaperApiCalls.estimateCostUsd("claude-sonnet-5-5", 21_000L, 3_100L, 7_800L, 0L, pricing))
                .isCloseTo((21_000 * 2 + 3_100 * 10 + 7_800 * 0.10) / 1e6, within(1e-12));
        assertThat(PaperApiCalls.estimateCostUsd("claude-haiku-5-5", 1_000_000L, 1_000_000L, 1_000_000L, 1_000_000L,
                pricing)).isCloseTo(0.10 + 0.50 + 0.01 + 0.125, within(1e-12));
        assertThat(PaperApiCalls.estimateCostUsd("CLAUDE-SONNET-X", 1_000_000L, 0L, 0L, 0L, pricing))
                .as("대소문자 무시").isCloseTo(2.0, within(1e-12));
        assertThat(PaperApiCalls.estimateCostUsd("claude-opus-5-5", 1L, 1L, 1L, 1L, pricing)).as("단가 모름").isNull();
        assertThat(PaperApiCalls.estimateCostUsd(null, 1L, 1L, 1L, 1L, pricing)).isNull();
        assertThat(PaperApiCalls.estimateCostUsd("claude-sonnet-5-5", 1L, 1L, null, 0L, pricing))
                .as("토큰 하나라도 모르면 추정 안 함(0으로 채우지 않음)").isNull();
        assertThat(PaperApiCalls.estimateCostUsd("claude-sonnet-5-5", 0L, 0L, 0L, 0L, pricing)).isZero();
    }

    @Test
    @DisplayName("단가 설정: 없으면(3b 전 설정 행) 기본값 + fallbacks, 범위 밖은 그 키만 기본값, 0~1000은 그대로 — 화면 키의 최솟값·최댓값도 기본값으로 바뀌지 않음")
    void pricingFromSettings() {
        PaperApiCalls.Pricing missing = PaperApiCalls.Pricing.from(MAPPER.createObjectNode());
        assertThat(missing.sonnet()).isEqualTo(PaperApiCalls.Pricing.DEFAULT_SONNET);
        assertThat(missing.fallbacks()).containsExactly("pricing");
        assertThat(PaperApiCalls.Pricing.from(null).fallbacks()).isEmpty();
        assertThat(PaperApiCalls.Pricing.from(settings.defaultsJson()).fallbacks()).as("기본값 본문").isEmpty();

        ObjectNode body = (ObjectNode) settings.defaultsJson();
        ObjectNode sonnet = (ObjectNode) body.path("pricing").path("sonnet");
        sonnet.put("inputUsdPerMTok", 3);
        sonnet.put("outputUsdPerMTok", 1_001);
        ((ObjectNode) body.path("pricing")).remove("haiku");
        PaperApiCalls.Pricing pricing = PaperApiCalls.Pricing.from(body);
        assertThat(pricing.sonnet().inputUsdPerMTok()).isEqualTo(3);
        assertThat(pricing.sonnet().outputUsdPerMTok()).as("범위 밖 → 기본값").isEqualTo(10);
        assertThat(pricing.haiku()).isEqualTo(PaperApiCalls.Pricing.DEFAULT_HAIKU);
        assertThat(pricing.fallbacks()).containsExactly("pricing.sonnet.outputUsdPerMTok", "pricing.haiku");

        for (boolean useMax : List.of(false, true)) {
            Map<String, Object> patch = new LinkedHashMap<>();
            for (PaperSettingKeys.Spec spec : PaperSettingKeys.ALL) {
                if (spec.key().startsWith("pricing.")) {
                    patch.put(spec.key(), useMax ? spec.max() : spec.min());
                }
            }
            assertThat(patch).hasSize(8);
            settings.update(MAPPER.valueToTree(patch));
            JsonNode saved = store.settings().orElseThrow().body();
            assertThat(PaperApiCalls.Pricing.from(saved).fallbacks()).as(useMax ? "최댓값" : "최솟값").isEmpty();
        }
    }

    @Test
    @DisplayName("기록: 키 없음(NO_KEY)은 잡은 호출을 되돌리고, 응답이 있으면 토큰과 추정 비용을 더한다 — 토큰을 모르면 비용 없이 토큰만")
    void recordUsage() {
        calls.reserve(DAY, 6);
        calls.record(DAY, "claude-sonnet-5-5", result("NO_KEY", null, null, null, null));
        assertThat(store.apiUsage(DAY).orElseThrow().calls()).as("보내지 않은 호출").isZero();

        calls.reserve(DAY, 6);
        calls.record(DAY, "claude-sonnet-5-5", result(null, 21_000L, 3_100L, 7_800L, 0L));
        calls.reserve(DAY, 6);
        calls.record(DAY, "claude-sonnet-5-5", result("RATE_LIMIT", null, null, null, null));
        calls.reserve(DAY, 6);
        calls.record(DAY, "claude-haiku-5-5", result(null, 1_000L, 100L, null, null));
        ApiUsage usage = store.apiUsage(DAY).orElseThrow();
        assertThat(usage.calls()).as("실패한 호출도 셈").isEqualTo(3);
        assertThat(usage.inputTokens()).isEqualTo(22_000);
        assertThat(usage.outputTokens()).isEqualTo(3_200);
        assertThat(usage.estCostCalls()).as("추정한 호출은 하나").isEqualTo(1);
        assertThat(usage.estCostUsd()).as("21,000×2 + 3,100×10 + 7,800×0.10 = 73,780 ÷ 100만").isCloseTo(0.07378, within(1e-9));
        assertThat(calls.estimateCostUsd("claude-sonnet-5-5", result(null, 1_000_000L, 0L, 0L, 0L)))
                .isCloseTo(2.0, within(1e-12));
        assertThat(calls.estimateCostUsd("claude-sonnet-5-5", null)).isNull();
        calls.record(DAY, "claude-sonnet-5-5", null);

        // 기록 실패는 결과를 버리게 하지 않음(로그만)
        FakePaperStore broken = new FakePaperStore() {
            @Override
            public void addApiTokens(LocalDate day, Long in, Long out, Long cr, Long cw, Double cost) {
                throw new IllegalStateException("DB 끊김");
            }
        };
        new PaperApiCalls(broken, PaperFixtures.settings(broken)).record(DAY, "claude-sonnet-5-5",
                result(null, 1L, 1L, 1L, 1L));
    }

    @Test
    @DisplayName("설정 기본값에 단가 8개(설명에 확인 날짜), 화면 키는 number·0~1000·규칙 책 영향 없음")
    void pricingKeys() {
        List<PaperSettingKeys.Spec> keys = PaperSettingKeys.ALL.stream().filter(s -> s.key().startsWith("pricing."))
                .toList();
        assertThat(keys).extracting(PaperSettingKeys.Spec::key).containsExactly(
                "pricing.sonnet.inputUsdPerMTok", "pricing.sonnet.outputUsdPerMTok",
                "pricing.sonnet.cacheReadUsdPerMTok", "pricing.sonnet.cacheWriteUsdPerMTok",
                "pricing.haiku.inputUsdPerMTok", "pricing.haiku.outputUsdPerMTok",
                "pricing.haiku.cacheReadUsdPerMTok", "pricing.haiku.cacheWriteUsdPerMTok");
        for (PaperSettingKeys.Spec spec : keys) {
            assertThat(spec.type()).isEqualTo(PaperSettingKeys.Type.NUMBER);
            assertThat(spec.min()).isZero();
            assertThat(spec.max()).isEqualTo(1_000);
            assertThat(spec.affectsRules()).isFalse();
            assertThat(spec.description()).contains("2026-10-25 확인").contains("가격이 바뀌면 고칠 것");
        }
        assertThat(keys.get(4).description()).contains("100K 토큰 이하");
        JsonNode pricing = settings.defaultsJson().path("pricing");
        assertThat(pricing.path("sonnet").path("inputUsdPerMTok").asDouble()).isEqualTo(2);
        assertThat(pricing.path("sonnet").path("outputUsdPerMTok").asDouble()).isEqualTo(10);
        // 공식 가격표: Sonnet 5.5 캐시 읽기는 입력 2달러의 0.05배(0.10) — 다른 모델의 0.1배가 아님
        assertThat(pricing.path("sonnet").path("cacheReadUsdPerMTok").asDouble()).isEqualTo(0.10);
        assertThat(pricing.path("sonnet").path("cacheWriteUsdPerMTok").asDouble()).isEqualTo(2.50);
        assertThat(pricing.path("haiku").path("inputUsdPerMTok").asDouble()).isEqualTo(0.10);
        assertThat(pricing.path("haiku").path("outputUsdPerMTok").asDouble()).isEqualTo(0.50);
        assertThat(pricing.path("haiku").path("cacheReadUsdPerMTok").asDouble()).isEqualTo(0.01);
        assertThat(pricing.path("haiku").path("cacheWriteUsdPerMTok").asDouble()).isEqualTo(0.125);
    }
}
