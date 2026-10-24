-- =============================================================================
-- V11__paper_api_usage_cost.sql
-- Claude 호출 사용량에 추정 비용(명세 C8 "월 누적 토큰·추정 비용 표시")을 더합니다.
--
-- - est_cost_usd는 그날 추정한 호출들의 비용 합(달러, 설정의 pricing.* 단가 × 토큰 ÷ 100만), est_cost_calls는 그 추정에 든
--   호출 수입니다. 단가를 모르는 모델·토큰을 모르는 호출·이 열 전에 남긴 호출은 추정에서 빠지므로 est_cost_calls ≤ calls이고,
--   추정이 하나도 없는 날은 est_cost_usd가 NULL입니다(0달러로 채우지 않음 — 화면은 "추정 n/m회"로 함께 보임).
-- - 단가는 코드가 아니라 설정 행에 둡니다(가격이 바뀌면 화면에서 고침). 기록은 그 호출 때의 단가로 낸 값 그대로입니다.
-- - 여러 번 실행해도 안전합니다(make db-test는 파일마다 매번 다시 실행).
-- - 쓰는 곳은 백엔드 store.PaperStore뿐입니다(ADR 0008). 백엔드는 est_cost_calls 열로 "스키마 준비됨"을 봅니다(PaperStore.ready).
-- =============================================================================

ALTER TABLE paper_api_usage ADD COLUMN IF NOT EXISTS est_cost_usd NUMERIC(12,6);
ALTER TABLE paper_api_usage ADD COLUMN IF NOT EXISTS est_cost_calls INTEGER NOT NULL DEFAULT 0;
