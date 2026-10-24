package com.macrodash.feature.paper;

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
 * 기본 설정의 후보 기관({@link PaperSettingsService#CANDIDATE_CIKS}, 설정 {@code trackRecord.candidateCiks})과 수집기의
 * 수집 목록({@code collector/app/indicators.py}의 {@code TRACK_RECORD_FILERS})을 대조합니다({@code DatasetsParityTest}와 같은
 * 목적 — 그 테스트는 store 패키지라 이 목록을 볼 수 없어 여기 둡니다).
 *
 * <p>수집 목록에 기관을 더하거나 CIK를 바꾸고 여기를 잊으면, 수집과 진행률에는 보이는데 계산은 설정 필터
 * ({@code PaperSelectionService})가 조용히 빼서 결과 행이 저장되지 않고 후보 표에서도 사라집니다.
 */
class TrackRecordCandidatesParityTest {

    private static final Path INDICATORS = Path.of("../collector/app/indicators.py");

    @Test
    @DisplayName("기본 후보 CIK가 수집기 TRACK_RECORD_FILERS의 지금 CIK와 같다(선행 CIK는 수집기만)")
    void candidateCiksMatchCollectorFilers() throws IOException {
        assumeTrue(Files.exists(INDICATORS), "collector/app/indicators.py를 찾을 수 없습니다");
        String source = Files.readString(INDICATORS);

        Matcher block = Pattern.compile("TRACK_RECORD_FILERS\\s*(?::[^=]*)?=\\s*\\((.*?)\\n\\)", Pattern.DOTALL)
                .matcher(source);
        assertThat(block.find()).as("TRACK_RECORD_FILERS를 찾지 못했습니다").isTrue();
        List<String> collector = new ArrayList<>();
        for (String line : block.group(1).split("\\n")) {
            // 선행 CIK는 "predecessors": ("…",)라 "cik" 키로만 꺼내면 지금 CIK만 남습니다. 주석은 지웁니다.
            Matcher cik = Pattern.compile("\"cik\"\\s*:\\s*\"(\\d+)\"").matcher(line.replaceAll("#.*$", ""));
            while (cik.find()) {
                collector.add(cik.group(1));
            }
        }

        assertThat(collector).as("수집 목록").isNotEmpty().doesNotHaveDuplicates();
        assertThat(PaperSettingsService.CANDIDATE_CIKS).containsExactlyInAnyOrderElementsOf(collector);
    }
}
