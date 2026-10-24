package com.macrodash.feature.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 모의 매매 Claude 책의 구조화 출력 호출({@link AiService#structured}) — 공식 SDK를 실제로 돌리고, Claude API 자리에 가짜
 * 서버를 둡니다(유료 호출 없음, {@link AiServiceAnthropicTest}와 같은 방식).
 *
 * <p>요청 모양(스키마·effort·시스템 캐시 블록·temperature/thinking 없음·출력 한도), 정상 응답의 텍스트와 토큰(캐시 포함),
 * 거절·출력 한도·빈 응답·HTTP 오류·연결 실패·시간 초과의 종류와 과금 표시, 키 없음, 로그에 키·주소가 남지 않음을 고정합니다.
 */
class AiServiceStructuredTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODEL = "claude-sonnet-5-5";
    private static final String KEY = "sk-ant-test-STRUCTUREDKEY";

    /** 결정 스키마를 닮은 작은 스키마 — 형 배열·pattern(역슬래시)·한글 enum·중첩 객체가 그대로 가는지 봅니다. */
    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["schemaVersion","stance","orders"],
             "properties":{
               "schemaVersion":{"const":2},
               "stance":{"enum":["RISK_ON","RISK_OFF"]},
               "orders":{"type":"array","items":{"type":"object","additionalProperties":false,
                 "required":["ticker","weight","labels"],
                 "properties":{"ticker":{"type":"string","pattern":"^[A-Z.\\\\-]{1,8}$"},
                               "weight":{"type":["number","null"]},
                               "labels":{"type":"array","items":{"enum":["사실","확인 필요"]}}}}}}}
            """;

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> apiKeys = new CopyOnWriteArrayList<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AiService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void stop() {
        logger.detachAppender(appender);
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String startServer(int status, String body) throws Exception {
        return startServer(status, body, 0);
    }

    /** /v1/messages에 status·body로 답하는 가짜 Claude API. {@code delayMillis}만큼 기다렸다 답합니다(시간 초과 시험). */
    private String startServer(int status, String body, long delayMillis) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            hits.incrementAndGet();
            requests.add(MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
            apiKeys.add(String.valueOf(exchange.getRequestHeaders().getFirst("x-api-key")));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (java.io.IOException ignored) {
                // 시간 초과 시험에서는 클라이언트가 먼저 끊습니다.
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static AiService service(String baseUrl, String key) {
        AiService service = new AiService(240, 60, 180);
        for (String field : List.of("nvidiaKey", "cerebrasKey", "cloudflareAccountId", "cloudflareToken")) {
            ReflectionTestUtils.setField(service, field, "");
        }
        ReflectionTestUtils.setField(service, "anthropicKey", key);
        service.anthropicBaseUrl = baseUrl;
        return service;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema() throws Exception {
        return MAPPER.readValue(SCHEMA, LinkedHashMap.class);
    }

    private static String message(String content, String stopReason, String extra, String usage) {
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                 "content":%s,"stop_reason":"%s","stop_sequence":null%s,
                 "usage":%s}
                """.formatted(content, stopReason, extra, usage);
    }

    private static String textBlock(String text) throws Exception {
        return "[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"},{\"type\":\"text\",\"text\":"
                + MAPPER.writeValueAsString(text) + "}]";
    }

    private static final String USAGE = "{\"input_tokens\":21000,\"output_tokens\":3100,"
            + "\"cache_read_input_tokens\":7800,\"cache_creation_input_tokens\":0}";

    private static final String ANSWER = "{\"schemaVersion\":2,\"stance\":\"RISK_ON\",\"orders\":[]}";

    @Test
    @DisplayName("정상 응답: 사고 블록 뒤의 텍스트(JSON)와 토큰 사용량(캐시 읽기·쓰기 포함)을 돌려준다")
    void returnsJsonTextAndUsage() throws Exception {
        String base = startServer(200, message(textBlock(ANSWER), "end_turn", "", USAGE));

        AiService.StructuredResult result = service(base, KEY)
                .structured(MODEL, "프레임 v1", "패킷", schema(), "high", 120);

        assertThat(result.ok()).isTrue();
        assertThat(result.text()).isEqualTo(ANSWER);
        assertThat(result.stopReason()).isEqualTo("end_turn");
        assertThat(result.errorKind()).isNull();
        assertThat(result.error()).isNull();
        assertThat(result.charged()).isTrue();
        assertThat(result.inputTokens()).isEqualTo(21_000L);
        assertThat(result.outputTokens()).isEqualTo(3_100L);
        assertThat(result.cacheReadTokens()).isEqualTo(7_800L);
        assertThat(result.cacheWriteTokens()).as("0은 API가 준 값 그대로 — 없는 값(null)과 구분").isEqualTo(0L);
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(hits.get()).isEqualTo(1);
        assertThat(apiKeys).containsExactly(KEY);
    }

    @Test
    @DisplayName("요청 본문: output_config에 스키마(그대로)·effort, 시스템은 캐시 블록, temperature·thinking·fallbacks 없음")
    void sendsSchemaEffortAndCachedSystem() throws Exception {
        String base = startServer(200, message(textBlock(ANSWER), "end_turn", "", USAGE));

        service(base, KEY).structured(MODEL, "프레임 v1 — 시스템 프롬프트", "오늘의 패킷", schema(), "xhigh", 120);

        JsonNode sent = requests.get(0);
        assertThat(sent.path("model").asString()).isEqualTo(MODEL);
        assertThat(sent.path("max_tokens").asLong()).isEqualTo(16_000L).isEqualTo(AiService.STRUCTURED_MAX_TOKENS);
        assertThat(sent.path("output_config").path("effort").asString()).isEqualTo("xhigh");
        JsonNode format = sent.path("output_config").path("format");
        assertThat(format.path("type").asString()).isEqualTo("json_schema");
        assertThat(format.path("schema")).as("Jackson 3로 읽은 Map이 키·순서·형 배열·pattern·한글까지 그대로")
                .isEqualTo(MAPPER.readTree(SCHEMA));
        assertThat(List.copyOf(format.path("schema").path("properties").propertyNames()))
                .as("키 순서도 파일 순서 — 같은 스키마면 같은 요청 본문").containsExactly("schemaVersion", "stance", "orders");
        assertThat(format.path("schema").path("properties").path("orders").path("items").path("properties")
                .path("ticker").path("pattern").asString()).isEqualTo("^[A-Z.\\-]{1,8}$");

        JsonNode system = sent.path("system");
        assertThat(system.isArray()).as("캐시 표시를 달려면 문자열이 아니라 블록 목록").isTrue();
        assertThat(system.size()).isEqualTo(1);
        assertThat(system.get(0).path("type").asString()).isEqualTo("text");
        assertThat(system.get(0).path("text").asString()).isEqualTo("프레임 v1 — 시스템 프롬프트");
        assertThat(system.get(0).path("cache_control").path("type").asString()).isEqualTo("ephemeral");

        assertThat(sent.path("messages").size()).isEqualTo(1);
        assertThat(sent.path("messages").get(0).path("role").asString()).isEqualTo("user");
        assertThat(sent.path("messages").get(0).path("content").asString()).isEqualTo("오늘의 패킷");

        assertThat(sent.has("temperature")).as("Sonnet·Haiku 5.5는 기본값이 아닌 temperature에 400").isFalse();
        assertThat(sent.has("thinking")).as("사고는 기본(adaptive)").isFalse();
        assertThat(sent.has("fallbacks")).as("다른 모델로 자동 전환하지 않음(D5)").isFalse();
    }

    @Test
    @DisplayName("effort를 비우면 high(C2 기본), 대소문자는 무시, 시스템 프롬프트가 비면 system을 보내지 않는다")
    void effortDefaultsToHighAndBlankSystemIsOmitted() throws Exception {
        String base = startServer(200, message(textBlock(ANSWER), "end_turn", "", USAGE));
        AiService service = service(base, KEY);

        service.structured(MODEL, null, "패킷", schema(), null, 60);
        service.structured(MODEL, "  ", "패킷", schema(), "MAX", 60);

        assertThat(requests.get(0).path("output_config").path("effort").asString()).isEqualTo("high");
        assertThat(requests.get(1).path("output_config").path("effort").asString()).isEqualTo("max");
        assertThat(requests.get(0).has("system")).isFalse();
        assertThat(requests.get(1).has("system")).isFalse();
    }

    @Test
    @DisplayName("안전 분류기 거절(refusal): REFUSAL·과금됨, 분류·설명을 남기고 다시 보내지 않는다")
    void refusalIsReportedWithCategory() throws Exception {
        String base = startServer(200, message("[]", "refusal",
                ",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"정책상 답할 수 없습니다\"}",
                "{\"input_tokens\":20000,\"output_tokens\":12}"));

        AiService.StructuredResult result = service(base, KEY).structured(MODEL, "프레임", "패킷", schema(), "high", 60);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorKind()).isEqualTo("REFUSAL");
        assertThat(result.stopReason()).isEqualTo("refusal");
        assertThat(result.charged()).isTrue();
        assertThat(result.error()).contains("거절", "cyber", "정책상 답할 수 없습니다");
        assertThat(result.text()).isNull();
        assertThat(result.inputTokens()).isEqualTo(20_000L);
        assertThat(result.cacheReadTokens()).as("API가 주지 않은 값은 0이 아니라 null").isNull();
        assertThat(result.cacheWriteTokens()).isNull();
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("429: RATE_LIMIT·과금 안 됨, SDK가 다시 보내지 않는다(재시도는 부른 쪽이 같은 거래일 한 번 — C7)")
    void rateLimitIsNotRetried() throws Exception {
        String base = startServer(429,
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");

        AiService.StructuredResult result = service(base, KEY).structured(MODEL, "프레임", "패킷", schema(), "high", 60);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorKind()).isEqualTo("RATE_LIMIT");
        assertThat(result.charged()).isFalse();
        assertThat(result.error()).contains("429").doesNotContain("엔진");
        assertThat(result.text()).isNull();
        assertThat(result.inputTokens()).isNull();
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("형식이 깨진 응답(텍스트 블록 없음): EMPTY·과금됨, 받은 토큰은 남긴다")
    void emptyTextIsEmpty() throws Exception {
        String base = startServer(200, message("[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"}]",
                "end_turn", "", USAGE));

        AiService.StructuredResult result = service(base, KEY).structured(MODEL, "프레임", "패킷", schema(), "high", 60);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorKind()).isEqualTo("EMPTY");
        assertThat(result.charged()).isTrue();
        assertThat(result.text()).isNull();
        assertThat(result.stopReason()).isEqualTo("end_turn");
        assertThat(result.outputTokens()).isEqualTo(3_100L);
    }

    @Test
    @DisplayName("응답 본문이 Message 모양이 아니면(200인데 content 없음) EMPTY·과금됨")
    void unreadableMessageIsEmpty() throws Exception {
        String base = startServer(200, "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\"}");

        AiService.StructuredResult result = service(base, KEY).structured(MODEL, "프레임", "패킷", schema(), "high", 60);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorKind()).isEqualTo("EMPTY");
        assertThat(result.charged()).as("모델이 이미 답했을 수 있음 — 되돌리지 않는 쪽(QA-021)").isTrue();
    }

    @Test
    @DisplayName("출력 한도(max_tokens)로 끝나면 텍스트가 있어도 MAX_TOKENS — 잘린 JSON은 원문으로만 남긴다")
    void maxTokensIsFailureEvenWithText() throws Exception {
        String base = startServer(200, message(textBlock("{\"schemaVersion\":2,\"stance\":\"RIS"), "max_tokens", "", USAGE));

        AiService.StructuredResult result = service(base, KEY).structured(MODEL, "프레임", "패킷", schema(), "high", 60);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorKind()).isEqualTo("MAX_TOKENS");
        assertThat(result.charged()).isTrue();
        assertThat(result.text()).isEqualTo("{\"schemaVersion\":2,\"stance\":\"RIS");
        assertThat(result.error()).contains("출력 한도", "16000");
    }

    @Test
    @DisplayName("HTTP 오류 상태: 401·403·404·그 밖(500·402)을 종류로 나누고, 모두 과금 안 됨, 키는 문구에 없다")
    void httpErrorsAreClassified() throws Exception {
        Map<Integer, String> expected = new LinkedHashMap<>();
        expected.put(401, "UNAUTHORIZED");
        expected.put(403, "FORBIDDEN");
        expected.put(404, "NOT_FOUND");
        expected.put(500, "HTTP");
        expected.put(402, "HTTP");
        Map<Integer, String> types = Map.of(401, "authentication_error", 403, "permission_error",
                404, "not_found_error", 500, "api_error", 402, "billing_error");

        for (Map.Entry<Integer, String> entry : expected.entrySet()) {
            String base = startServer(entry.getKey(), "{\"type\":\"error\",\"error\":{\"type\":\""
                    + types.get(entry.getKey()) + "\",\"message\":\"x\"}}");

            AiService.StructuredResult result = service(base, KEY).structured(MODEL, "프레임", "패킷", schema(), "high", 60);

            assertThat(result.errorKind()).as("HTTP %d", entry.getKey()).isEqualTo(entry.getValue());
            assertThat(result.ok()).isFalse();
            assertThat(result.charged()).as("HTTP %d", entry.getKey()).isFalse();
            assertThat(result.error()).doesNotContain(KEY);
            stop();
        }
        assertThat(hits.get()).as("상태마다 한 번씩만 — SDK 재시도 없음").isEqualTo(expected.size());
    }

    @Test
    @DisplayName("연결이 거부되면 IO·과금 안 됨, 로그에 키·요청 주소가 남지 않는다")
    void connectionRefusedIsUnchargedIo() throws Exception {
        // 서버를 띄웠다 바로 내려, 아무도 듣지 않는 포트를 얻습니다.
        String base = startServer(200, "{}");
        stop();
        appender.start();
        logger.addAppender(appender);

        AiService.StructuredResult result = service(base, KEY).structured(MODEL, "프레임", "패킷", schema(), "high", 10);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorKind()).isEqualTo("IO");
        assertThat(result.charged()).as("연결 단계에서 끝남 — 모델에 닿지 않음").isFalse();
        assertThat(result.inputTokens()).isNull();
        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(KEY).doesNotContain(base));
    }

    @Test
    @DisplayName("대기 한도를 넘기면 TIMEOUT — 응답을 기다리다 끊긴 것이라 과금된 것으로 본다")
    void slowResponseIsTimeout() throws Exception {
        String base = startServer(200, message(textBlock(ANSWER), "end_turn", "", USAGE), 3_000);

        AiService.StructuredResult result = service(base, KEY).structured(MODEL, "프레임", "패킷", schema(), "high", 1);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorKind()).isEqualTo("TIMEOUT");
        assertThat(result.charged()).isTrue();
        assertThat(result.error()).contains("1초").doesNotContain("자동 탐색");
        assertThat(result.latencyMs()).isLessThan(3_000L);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("키가 없으면 NO_KEY — 네트워크에 나가지 않고 과금도 없다")
    void missingKeyDoesNotCallOut() throws Exception {
        String base = startServer(200, message(textBlock(ANSWER), "end_turn", "", USAGE));

        AiService.StructuredResult result = service(base, "").structured(MODEL, "프레임", "패킷", schema(), "high", 60);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorKind()).isEqualTo("NO_KEY");
        assertThat(result.charged()).isFalse();
        assertThat(result.error()).contains("ANTHROPIC_API_KEY");
        assertThat(result.latencyMs()).isZero();
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("모르는 effort·빈 스키마·빈 메시지·0초 한도는 호출 전에 멈춘다(과금되는 호출을 보내지 않음)")
    void invalidArgumentsFailBeforeCalling() throws Exception {
        String base = startServer(200, message(textBlock(ANSWER), "end_turn", "", USAGE));
        AiService service = service(base, KEY);
        Map<String, Object> schema = schema();

        assertThatThrownBy(() -> service.structured(MODEL, "s", "u", schema, "extreme", 60))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("effort");
        assertThatThrownBy(() -> service.structured(MODEL, "s", "u", Map.of(), "high", 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.structured(MODEL, "s", " ", schema, "high", 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.structured("", "s", "u", schema, "high", 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.structured(MODEL, "s", "u", schema, "high", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(hits.get()).isZero();
    }
}
