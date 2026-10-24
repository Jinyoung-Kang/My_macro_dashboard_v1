package com.macrodash.feature.paper;

import com.macrodash.analytics.UsMarketCalendar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 🧪 모의 매매의 배포 설정({@code dashboard.paper.*}, application.yml) — 재기동해야 바뀌는 값만.
 *
 * <p>슬리피지·스탑 비율처럼 화면에서 바꾸는 값은 여기가 아니라 DB 설정 한 행({@link PaperSettingsService})입니다.
 * 여기에는 "언제·얼마로 시작하나"와 "정기 작업을 돌리나"만 둡니다 — 이 둘을 화면에서 바꾸면 이미 쌓인 장부와 맞지 않습니다.
 *
 * <p>값이 틀리면 기동하지 않습니다(RegimeLogService의 기록 시각과 같은 방식). 출발일이 휴장일이면 그날 종가가 없어 벤치마크가
 * 영영 시작하지 않고, 초기 현금이 0 이하면 모든 책이 빈 책이 됩니다 — 조용히 지나가면 몇 주 뒤에야 알게 됩니다.
 *
 * @param enabled     false면 정기 작업(리스크 점검·결정·출발일 종가 얼리기)이 일하지 않습니다. 수동 실행(POST run/risk·
 *                    run/decision)은 상관없이 돕니다
 * @param startDate   네 책의 출발(사후 체결) 거래일 — 명세 D2(2026-10-23). 미국 동부 세션 날짜
 * @param initialCash 책마다 시작 현금(달러) — 명세 D1
 */
@Component
public record PaperConfig(boolean enabled, LocalDate startDate, double initialCash) {

    public PaperConfig {
        if (startDate == null || !UsMarketCalendar.isTradingDay(startDate)) {
            throw new IllegalStateException("dashboard.paper.start-date는 미국 증시 거래일이어야 합니다: " + startDate);
        }
        if (!(initialCash > 0) || Double.isInfinite(initialCash)) {
            throw new IllegalStateException("dashboard.paper.initial-cash는 0보다 커야 합니다: " + initialCash);
        }
    }

    /**
     * 스프링이 쓰는 생성자. 날짜는 문자열로 받아 직접 읽습니다 — 형식이 틀렸을 때 변환기 오류 대신 무엇을 고칠지 알려 주려고.
     */
    @Autowired
    public PaperConfig(@Value("${dashboard.paper.enabled:true}") boolean enabled,
                       @Value("${dashboard.paper.start-date:2026-10-23}") String startDate,
                       @Value("${dashboard.paper.initial-cash:100000}") double initialCash) {
        this(enabled, parse(startDate), initialCash);
    }

    private static LocalDate parse(String text) {
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalStateException("dashboard.paper.start-date 형식이 올바르지 않습니다(예: 2026-10-23): " + text);
        }
    }
}
