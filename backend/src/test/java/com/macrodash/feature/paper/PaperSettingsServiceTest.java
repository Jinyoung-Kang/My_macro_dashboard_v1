package com.macrodash.feature.paper;

import com.macrodash.support.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.macrodash.feature.paper.PaperFixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설정 쓰기(명세 S1~S3, 설계 §4-4) — 알려진 키만·형·범위 검사, 바뀐 키·버전·이력, 규칙 책 영향 경고, 화면용 키 설명.
 * 저장소는 메모리 가짜({@link FakePaperStore})이고 실제 SQL은 {@code PaperStoreDbTest}가 봅니다.
 */
class PaperSettingsServiceTest {

    private FakePaperStore store;
    private PaperSettingsService service;

    @BeforeEach
    void setUp() {
        store = new FakePaperStore();
        service = new PaperSettingsService(store, MAPPER);
        service.ensureInitialized();
    }

    private Map<String, Object> update(Object... keyAndValue) {
        Map<String, Object> patch = new LinkedHashMap<>();
        for (int i = 0; i < keyAndValue.length; i += 2) {
            patch.put((String) keyAndValue[i], keyAndValue[i + 1]);
        }
        return service.update(MAPPER.valueToTree(patch));
    }

    private JsonNode body() {
        return store.settings().orElseThrow().body();
    }

    @Test
    @DisplayName("점 경로·묶음 둘 다 받아 바뀐 키만 반영 — 버전 +1·이력, 규칙 책 영향 키면 경고, 다음 읽기부터 새 값")
    void updatesKnownKeysAndBumpsVersion() {
        Map<String, Object> out = update("slippageBp", 10, "trackRecord.windowYears", 5,
                "blend", Map.of("groupB", Map.of("topN", 3)), "model", "claude-haiku-5-5");

        assertThat(out.get("ok")).isEqualTo(true);
        assertThat(out.get("version")).isEqualTo(2);
        assertThat(out.get("changedKeys")).isEqualTo(List.of("slippageBp", "trackRecord.windowYears",
                "blend.groupB.topN", "model"));
        assertThat(out.get("warning")).isEqualTo("규칙 계좌는 동결된 대조군입니다 — 이 변경은 실험 조건을 바꿉니다");
        assertThat(body().path("slippageBp").asDouble()).isEqualTo(10);
        assertThat(body().path("trackRecord").path("windowYears").asInt()).isEqualTo(5);
        assertThat(body().path("trackRecord").path("topN").asInt()).as("다른 키는 그대로").isEqualTo(5);
        assertThat(body().path("blend").path("groupB").path("topN").asInt()).isEqualTo(3);
        assertThat(body().path("blend").path("groupB").path("windowYears").asInt()).isEqualTo(2);
        // 캐시 없이 다음 읽기부터 새 값(S1)
        assertThat(service.current().version()).isEqualTo(2);
        assertThat(service.current().settings().slippageBp()).isEqualTo(10);
        assertThat(service.current().settings().blend().groupBTopN()).isEqualTo(3);
        assertThat(ClaudeBookService.ClaudeSettings.from(body()).model()).isEqualTo("claude-haiku-5-5");

        Map<String, Object> claudeOnly = update("effort", "MEDIUM", "apiDailyCallCap", 3);
        assertThat(claudeOnly.get("version")).isEqualTo(3);
        assertThat(claudeOnly.get("warning")).as("Claude 책만 읽는 키는 경고 없음").isNull();
        assertThat(body().path("effort").asString()).as("정해진 표기로").isEqualTo("medium");

        JsonNode history = MAPPER.valueToTree(service.history().get("history"));
        assertThat(history).extracting(h -> h.path("version").asInt()).containsExactly(3, 2, 1);
        assertThat(history.get(0).path("changedKeys").toString()).contains("effort", "apiDailyCallCap");
        assertThat(history.get(2).path("changedKeys")).as("처음 값").isEmpty();
        assertThat(history.get(1).path("body").path("slippageBp").asDouble()).isEqualTo(10);
    }

    @Test
    @DisplayName("지금 값과 같으면 바뀐 키가 아니다 — 5와 5.0도 같음, 바뀐 것이 없으면 저장하지 않고 버전 그대로")
    void sameValuesDoNotSave() {
        Map<String, Object> out = update("slippageBp", 5, "trailingStopPct", 8.0, "model", "claude-sonnet-5-5");

        assertThat(out.get("version")).isEqualTo(1);
        assertThat(out.get("changedKeys")).isEqualTo(List.of());
        assertThat(out.get("warning")).isNull();
        assertThat(store.settingsHistory).hasSize(1);

        // 기본값 본문을 통째로 보내도 모든 값이 검사를 지나고 바뀐 키가 없음(기본값이 쓰기 범위 안)
        Map<String, Object> defaults = service.update(service.defaultsJson());
        assertThat(defaults.get("changedKeys")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("모르는 키·형·범위·정수·모델·시각이 하나라도 틀리면 아무것도 바꾸지 않고 400(사유를 모두)")
    void rejectsInvalidPatches() {
        assertThatThrownBy(() -> update("slippageBp", 7, "nope", 1)).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("모르는 설정 키: nope");
        assertThatThrownBy(() -> update("trackRecord", Map.of("windowYears", 9))).hasMessageContaining(
                "trackRecord.windowYears: 1~7 범위");
        assertThatThrownBy(() -> update("maxPositions", 12.5)).hasMessageContaining("정수");
        assertThatThrownBy(() -> update("slippageBp", "5")).hasMessageContaining("숫자");
        assertThatThrownBy(() -> update("model", "claude-opus-5-5")).hasMessageContaining("claude-sonnet-5-5")
                .hasMessageContaining("claude-haiku-5-5");
        assertThatThrownBy(() -> update("effort", "turbo")).hasMessageContaining("effort");
        assertThatThrownBy(() -> update("decisionTimeKst", "7:30")).hasMessageContaining("HH:mm");
        assertThatThrownBy(() -> update("parkCashInShy", "yes")).hasMessageContaining("true·false");
        assertThatThrownBy(() -> update("cashLikeTickers", List.of("SHY", "bad ticker"))).hasMessageContaining("티커");
        assertThatThrownBy(() -> update("trackRecord.candidateCiks", List.of())).hasMessageContaining("비면");
        assertThatThrownBy(() -> update("blend.allocation", "RANDOM")).hasMessageContaining("CONVICTION");
        assertThatThrownBy(() -> update("slippageBp", 7, "trailingStopPct", 0, "lockoutTradingDays", -1))
                .hasMessageContaining("trailingStopPct").hasMessageContaining("lockoutTradingDays");
        assertThatThrownBy(() -> service.update(MAPPER.createObjectNode())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.update(null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.update(MAPPER.valueToTree(List.of(1)))).isInstanceOf(
                InvalidRequestException.class);
        assertThatThrownBy(() -> update("gate", 4.8)).as("묶음 자리에 값").hasMessageContaining("모르는 설정 키: gate");

        assertThat(store.settings().orElseThrow().version()).as("아무것도 바꾸지 않음").isEqualTo(1);
        assertThat(body().path("slippageBp").asDouble()).isEqualTo(5);
    }

    @Test
    @DisplayName("마감 기한이 결정 시각보다 이르면 400 — 저장된 값과 합쳐서 본다")
    void deadlineMustNotPrecedeDecisionTime() {
        assertThatThrownBy(() -> update("decisionDeadlineKst", "07:00")).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("decisionDeadlineKst");
        assertThat(update("decisionTimeKst", "06:30", "decisionDeadlineKst", "07:00").get("version")).isEqualTo(2);
    }

    @Test
    @DisplayName("값을 정해진 모양으로 — 티커 대문자·중복 제거, CIK 10자리, 정수 키는 정수, 선택 값은 정해진 표기")
    void normalizesValues() {
        update("universeExtraTickers", List.of("xlk", " SHY ", "XLK", "BRK.B"),
                "trackRecord.candidateCiks", List.of("1067983", 1061768, "0001067983"),
                "trackRecord.metric", "sharpe", "maxPositions", 15.0, "blend.allocation", "equal");

        assertThat(body().path("universeExtraTickers").toString()).isEqualTo("[\"XLK\",\"SHY\",\"BRK.B\"]");
        assertThat(body().path("trackRecord").path("candidateCiks").toString())
                .isEqualTo("[\"0001067983\",\"0001061768\"]");
        assertThat(body().path("trackRecord").path("metric").asString()).isEqualTo("SHARPE");
        assertThat(body().path("maxPositions").isInt()).isTrue();
        assertThat(body().path("blend").path("allocation").asString()).isEqualTo("EQUAL");
    }

    @Test
    @DisplayName("묶음이 없는 옛 행(단계 2 전 — blend·rules 없음)에도 그 아래 키를 쓸 수 있다")
    void writesIntoMissingGroups() {
        tools.jackson.databind.node.ObjectNode old = (tools.jackson.databind.node.ObjectNode) body();
        old.remove("blend");
        old.remove("rules");

        Map<String, Object> out = update("blend.groupWeightA", 0.7, "rules.parkCashThresholdPct", 3);

        assertThat(out.get("changedKeys")).isEqualTo(List.of("blend.groupWeightA", "rules.parkCashThresholdPct"));
        assertThat(body().path("blend").path("groupWeightA").asDouble()).isEqualTo(0.7);
        assertThat(body().path("rules").path("parkCashThresholdPct").asDouble()).isEqualTo(3);
    }

    @Test
    @DisplayName("설정 행이 없으면 먼저 기본값으로 버전 1을 만든 뒤 바꾼다(이력에 처음 값과 바꾼 값이 따로)")
    void createsVersionOneBeforeFirstChange() {
        store = new FakePaperStore();
        service = new PaperSettingsService(store, MAPPER);

        assertThat(update("slippageBp", 9).get("version")).isEqualTo(2);
        assertThat(store.settingsHistory).containsOnlyKeys(1, 2);
        assertThat(store.settingsHistory.get(1).body().path("slippageBp").asDouble()).isEqualTo(5);
    }

    @Test
    @DisplayName("화면용 키 설명 — 기본값 키 전부(gate·blend·rules·trackRecord 포함)가 있고, 형·범위·선택지·규칙 책 영향을 싣는다")
    void viewDescribesEveryDefaultKey() {
        Map<String, Object> view = service.view();
        assertThat(view.get("available")).isEqualTo(true);
        assertThat(view.get("version")).isEqualTo(1);
        JsonNode keys = MAPPER.valueToTree(view.get("keys"));
        List<String> listed = new ArrayList<>();
        keys.forEach(k -> listed.add(k.path("key").asString()));

        List<String> leaves = new ArrayList<>();
        collectLeaves(service.defaultsJson(), "", leaves);
        assertThat(listed).as("기본값 본문의 키 = 화면에서 바꿀 수 있는 키").containsExactlyInAnyOrderElementsOf(leaves);

        JsonNode model = keys.get(listed.indexOf("model"));
        assertThat(model.path("type").asString()).isEqualTo("string");
        assertThat(model.path("options").toString()).contains("claude-sonnet-5-5", "claude-haiku-5-5");
        assertThat(model.path("affectsRules").asBoolean()).isFalse();
        JsonNode window = keys.get(listed.indexOf("trackRecord.windowYears"));
        assertThat(window.path("type").asString()).isEqualTo("integer");
        assertThat(window.path("min").asInt()).isEqualTo(1);
        assertThat(window.path("max").asInt()).isEqualTo(7);
        assertThat(window.path("affectsRules").asBoolean()).isTrue();
        for (String key : List.of("trailingStopPct", "maxWeightStockPct", "maxWeightEtfPct", "minHolders",
                "rulesMaxNames", "blend.allocation", "trackRecord.candidateCiks")) {
            assertThat(keys.get(listed.indexOf(key)).path("affectsRules").asBoolean()).as(key).isTrue();
        }
        assertThat(keys.get(listed.indexOf("gate.us10yStage1")).path("affectsRules").asBoolean())
                .as("게이트는 참고 지표 — 규칙 책 입력이 아님(D19)").isFalse();
        assertThat(keys.get(listed.indexOf("decisionTimeKst")).path("type").asString()).isEqualTo("time");
        assertThat(keys.get(listed.indexOf("cashLikeTickers")).path("type").asString()).isEqualTo("list");

        store.ready = false;
        assertThat(service.view().get("available")).isEqualTo(false);
        assertThat(service.history().get("available")).isEqualTo(false);
        assertThat(service.update(MAPPER.valueToTree(Map.of("slippageBp", 6))).get("ok")).isEqualTo(false);
    }

    @Test
    @DisplayName("쓰기 범위는 읽는 쪽의 범위 안 — 모든 숫자 키를 최솟값·최댓값으로 저장해도 어느 읽기도 기본값으로 바꾸지 않는다")
    void writableRangesAreReadableWithoutFallbacks() {
        for (boolean useMax : List.of(false, true)) {
            Map<String, Object> patch = new LinkedHashMap<>();
            for (PaperSettingKeys.Spec spec : PaperSettingKeys.ALL) {
                if (spec.min() != null) {
                    double v = useMax ? spec.max() : spec.min();
                    patch.put(spec.key(), spec.type() == PaperSettingKeys.Type.INTEGER ? (Object) (int) v : v);
                }
            }
            // 마감 기한은 결정 시각보다 늦게(숫자 키가 아님)
            service.update(MAPPER.valueToTree(patch));
            JsonNode body = body();
            PaperSettings paper = PaperSettings.from(body);
            List<String> fallbacks = new ArrayList<>(paper.fallbacks());
            fallbacks.addAll(paper.blend().fallbacks());
            fallbacks.addAll(paper.rules().fallbacks());
            fallbacks.addAll(RuleBookService.RuleSettings.from(body).fallbacks());
            fallbacks.addAll(PaperSelectionService.TrackRecordSettings.from(body).fallbacks());
            fallbacks.addAll(ClaudeBookService.ClaudeSettings.from(body).fallbacks());
            assertThat(fallbacks).as(useMax ? "최댓값" : "최솟값").isEmpty();
            PacketBuilder.Limits limits = PacketBuilder.Limits.from(body);
            assertThat(limits.maxPositions()).isEqualTo(body.path("maxPositions").asInt());
            assertThat(limits.maxDiscretionaryOrdersPerMonth())
                    .isEqualTo(body.path("maxDiscretionaryOrdersPerMonth").asInt());
            assertThat(limits.minHoldTradingDaysForSell()).isEqualTo(body.path("minHoldTradingDaysForSell").asInt());
            assertThat(limits.minOrderNotional()).isEqualTo(body.path("minOrderNotional").asDouble());
        }
    }

    private static void collectLeaves(JsonNode node, String prefix, List<String> out) {
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            if (field.getValue().isObject()) {
                collectLeaves(field.getValue(), path, out);
            } else {
                out.add(path);
            }
        }
    }
}
