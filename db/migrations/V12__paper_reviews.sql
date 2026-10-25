-- =============================================================================
-- V12__paper_reviews.sql
-- 주간 복기(2단계 명세 docs/specs/2026-10-25-paper-trading-phase2-spec.md §3 D1~D6) — Claude 계좌의 지난 결정(기본 4주, 입력이
-- 크면 2주)을 그 뒤 가격과 대조한 복기 메모를 주마다 한 행 남깁니다.
--
-- - week_ending은 그 ISO 주의 마지막 미국 거래일(금요일, 휴장이면 목요일)이고 기본키입니다 — 정기 작업이 30분마다 돌거나 수동 실행과
--   겹쳐도 한 주에 한 행입니다(INSERT … ON CONFLICT DO NOTHING, 명세 §3-3 "두 번 돌아도 1건").
-- - status: OK(형식 통과) · FORMAT_ERROR(형식 위반)·REFUSED(모델 거절) — 그 주는 이것으로 끝 · API_ERROR(호출 실패) — 같은 행에서
--   한 번만 다시 부름(결정의 C7과 같은 규칙, retry_count 0 → 1) · SKIPPED(창 안에 Claude 결정이 없어 부르지 않음).
-- - input은 코드가 만든 복기 입력(SQL로 꺼내 볼 때), input_text는 Claude에 보낸 그 입력 JSON 문자열 그대로입니다(구분자·안내 문장은
--   코드의 고정 틀이라 빼고). JSONB는 키를 길이·바이트 순으로 다시 정렬하므로(V10의 packet_text와 같은 이유) 화면은 input_text로
--   보낸 순서를 봅니다. 부르지 않은 행(SKIPPED)은 NULL.
-- - response는 형식 검사를 거친(실패면 읽은 그대로의) JSON, response_raw는 모델이 보낸 원문, usage는 토큰·추정 비용·지연입니다.
-- - proposal_id는 복기가 낸 프레임 갱신 제안(paper_framework_proposals에 PENDING 또는 FAILED로 넣은 행). 자동 반영은 없고 승인·거절은
--   기존 제안 흐름 그대로입니다(D3). 제안 행이 지워지면 연결만 비웁니다(ON DELETE SET NULL — 복기 메모는 남김).
-- - framework_version은 복기 때 시스템 프롬프트에 붙인 프레임 판(제안의 base_version과 같음).
-- - 여러 번 실행해도 안전합니다(make db-test는 파일마다 매번 다시 실행).
-- - 쓰는 곳은 백엔드 store.PaperStore뿐입니다(ADR 0008, collector/tests/test_table_ownership.py). 백엔드는 이 테이블로 "스키마
--   준비됨"을 봅니다(PaperStore.ready).
-- =============================================================================

CREATE TABLE IF NOT EXISTS paper_reviews (
    week_ending       DATE        PRIMARY KEY,
    window_weeks      INTEGER     CHECK (window_weeks > 0),      -- 입력에 넣은 주 수(4, 어림이 크면 2)
    input             JSONB       NOT NULL,
    input_text        TEXT,
    response          JSONB,
    response_raw      TEXT,
    model             TEXT,
    usage             JSONB,
    status            TEXT        NOT NULL
        CHECK (status IN ('OK', 'FORMAT_ERROR', 'API_ERROR', 'REFUSED', 'SKIPPED')),
    error             TEXT,
    proposal_id       BIGINT      REFERENCES paper_framework_proposals (id) ON DELETE SET NULL,
    framework_version INTEGER,
    retry_count       INTEGER     NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()       -- 지금 남은 내용을 만든 시각(재시도면 재시도 시각)
);
