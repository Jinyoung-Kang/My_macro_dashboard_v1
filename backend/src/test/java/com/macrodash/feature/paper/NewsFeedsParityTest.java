package com.macrodash.feature.paper;

import com.macrodash.analytics.NewsEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 설정 {@code news.feeds}의 기본값·고를 수 있는 값({@link NewsEvents#DEFAULT_FEEDS})과 수집기 피드 상수({@code collector/app/services/news.py}의
 * {@code FEEDS} — 저장본 payload의 {@code feed})를 대조합니다(2단계 B).
 *
 * <p>한쪽에만 고치면 조용히 틀립니다 — 수집기에만 있는 피드는 설정에서 고를 수 없고 기본값에도 없어 그 피드의 제목이 패킷에 들지
 * 않으며, 백엔드에만 있는 키는 저장본에 없어 늘 비어 있습니다.
 */
class NewsFeedsParityTest {

    private static final Path SOURCE = Path.of("../collector/app/services/news.py");

    @Test
    @DisplayName("백엔드 피드 키가 수집기 services.news.FEEDS의 키와 같고 순서도 같다")
    void feedKeysMatchCollector() throws IOException {
        assumeTrue(Files.exists(SOURCE), "collector/app/services/news.py를 찾을 수 없습니다");
        String source = Files.readString(SOURCE);

        Matcher block = Pattern.compile("\\nFEEDS\\s*(?::[^=]*)?=\\s*\\((.*?)\\n\\)", Pattern.DOTALL).matcher(source);
        assertThat(block.find()).as("FEEDS를 찾지 못했습니다").isTrue();
        List<String> collector = new ArrayList<>();
        Matcher key = Pattern.compile("Feed\\(\\s*\"([a-z_]+)\"").matcher(block.group(1).replaceAll("#[^\\n]*", ""));
        while (key.find()) {
            collector.add(key.group(1));
        }
        assertThat(collector).as("수집기 피드 키").isNotEmpty().doesNotHaveDuplicates();
        assertThat(collector).isEqualTo(NewsEvents.DEFAULT_FEEDS);
    }
}
