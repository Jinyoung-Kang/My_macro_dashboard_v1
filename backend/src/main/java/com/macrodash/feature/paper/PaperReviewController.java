package com.macrodash.feature.paper;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 🧪 주간 복기(운영자 전용, 2단계 명세 §3 D1~D6) — 복기 목록·한 건과 수동 실행. 결정은 {@link PaperDecisionController},
 * 프레임 제안(복기가 낸 제안의 승인·거절 포함)은 {@link PaperFrameworkController}입니다.
 *
 * <pre>
 *  GET  /api/paper/reviews                 복기 목록(주 최근 순, 판정 수·연결된 제안 상태)
 *  GET  /api/paper/reviews/{weekEnding}    복기 한 건(입력·응답·사용량·판정 표·연결된 제안)
 *  POST /api/paper/reviews/run             가장 최근 주를 지금 복기(토요일 시각 조건만 빼고 정기 실행과 같은 조건, 있으면 그 행)
 * </pre>
 *
 * <p>수동 실행은 Claude를 한 번 부릅니다(설정 {@code model}, 하루 호출 상한 {@code apiDailyCallCap}을 결정·제안과 함께 셈). 테이블이
 * 없으면 500이 아니라 {@code available:false}(+ POST는 {@code ok:false})입니다.
 */
@RestController
@RequestMapping("/api/paper")
public class PaperReviewController {

    private final ReviewService reviews;
    private final PaperReviewJob job;

    public PaperReviewController(ReviewService reviews, PaperReviewJob job) {
        this.reviews = reviews;
        this.job = job;
    }

    @GetMapping("/reviews")
    public Map<String, Object> reviews() {
        return reviews.listView();
    }

    @GetMapping("/reviews/{weekEnding}")
    public Map<String, Object> review(@PathVariable String weekEnding) {
        return reviews.detailView(weekEnding);
    }

    @PostMapping("/reviews/run")
    public Map<String, Object> run() {
        return job.runNow();
    }
}
