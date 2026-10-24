package com.macrodash.feature.paper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 재무 섹션이 빼는 ETF·ETP({@link FundamentalsSection#TRACK_RECORD_ETPS})와 수집기가 재무 수집에서 빼는 집합
 * ({@code collector/app/equities_cusip.py}의 {@code TRACK_RECORD_ETPS})을 대조합니다(D28).
 *
 * <p>한쪽에만 더하면 조용히 틀립니다 — 수집기에만 있으면 그 ETF가 패킷 대상에 '재무 사실 없음' 빈 줄로 남아 상한 25의 자리를
 * 차지하고, 백엔드에만 있으면 수집기가 그 종목을 받아 예산만 씁니다.
 */
class FundamentalsEtpParityTest {

    private static final Path SOURCE = Path.of("../collector/app/equities_cusip.py");

    @Test
    @DisplayName("백엔드 TRACK_RECORD_ETPS가 수집기 equities_cusip.TRACK_RECORD_ETPS와 같은 집합이다")
    void etpsMatchCollector() throws IOException {
        assumeTrue(Files.exists(SOURCE), "collector/app/equities_cusip.py를 찾을 수 없습니다");
        String source = Files.readString(SOURCE);

        Matcher block = Pattern.compile("\\nTRACK_RECORD_ETPS\\s*(?::[^=]*)?=\\s*frozenset\\(\\{(.*?)\\}\\)", Pattern.DOTALL)
                .matcher(source);
        assertThat(block.find()).as("TRACK_RECORD_ETPS를 찾지 못했습니다").isTrue();
        List<String> collector = new ArrayList<>();
        Matcher name = Pattern.compile("\"([A-Z.\\-]+)\"").matcher(block.group(1).replaceAll("#[^\\n]*", ""));
        while (name.find()) {
            collector.add(name.group(1));
        }
        assertThat(collector).as("수집기 ETP").isNotEmpty().doesNotHaveDuplicates();
        assertThat(new TreeSet<>(collector)).isEqualTo(new TreeSet<>(FundamentalsSection.TRACK_RECORD_ETPS));
    }
}
