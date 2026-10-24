package com.macrodash.feature.paper;

import com.macrodash.analytics.Fundamentals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 재무 계산의 개념 후보({@link Fundamentals#CANDIDATES})와 수집기가 추출하는 개념({@code collector/app/services/sec_fundamentals.py}의
 * {@code CONCEPTS})을 대조합니다(D28, {@code TrackRecordCandidatesParityTest}와 같은 목적).
 *
 * <p>한쪽에만 후보를 더하면 조용히 틀립니다 — 계산에만 있으면 수집기가 그 개념을 저장하지 않아 그 후보를 쓰는 회사의 칸이 늘 빈
 * 값이고, 수집기에만 있으면 받아 쌓기만 하고 패킷에는 나오지 않습니다. 어느 쪽도 오류를 내지 않습니다.
 */
class FundamentalsConceptsParityTest {

    private static final Path SERVICE = Path.of("../collector/app/services/sec_fundamentals.py");

    @Test
    @DisplayName("계산의 개념 후보 전부(분류체계:개념)가 수집기 CONCEPTS와 같은 집합이다")
    void candidatesMatchCollectorConcepts() throws IOException {
        assumeTrue(Files.exists(SERVICE), "collector/app/services/sec_fundamentals.py를 찾을 수 없습니다");
        String source = Files.readString(SERVICE);

        Matcher block = Pattern.compile("\\nCONCEPTS\\s*(?::[^=]*)?=\\s*\\{(.*?)\\n\\}", Pattern.DOTALL).matcher(source);
        assertThat(block.find()).as("CONCEPTS를 찾지 못했습니다").isTrue();
        List<String> collector = new ArrayList<>();
        // "us-gaap": ( … ), 묶음마다 — 주석(#)을 지운 뒤 따옴표 안의 이름만 꺼냅니다.
        Matcher taxonomy = Pattern.compile("\"([a-z-]+)\"\\s*:\\s*\\((.*?)\\)\\s*,", Pattern.DOTALL)
                .matcher(block.group(1).replaceAll("#[^\\n]*", ""));
        while (taxonomy.find()) {
            Matcher name = Pattern.compile("\"([A-Za-z]+)\"").matcher(taxonomy.group(2));
            while (name.find()) {
                collector.add(taxonomy.group(1) + ":" + name.group(1));
            }
        }

        Set<String> backend = new TreeSet<>();
        Fundamentals.CANDIDATES.values().forEach(backend::addAll);
        assertThat(collector).as("수집기 개념").isNotEmpty().doesNotHaveDuplicates();
        assertThat(new TreeSet<>(collector)).isEqualTo(backend);
    }
}
