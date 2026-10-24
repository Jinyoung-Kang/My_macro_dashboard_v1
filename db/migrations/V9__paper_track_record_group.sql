-- =============================================================================
-- V9__paper_track_record_group.sql
-- 13F 트랙레코드에 기관 그룹(명세 D27, 설계 §2) — 그룹 A(후보 27곳, 7년 창)와 그룹 B(기존 '기관 13F 포트폴리오 분석'
-- 메뉴 12곳, 2년 창)를 같은 기준일에 따로 남깁니다. 두 그룹에 모두 있는 기관(2026-10-23 기준 Duquesne)은 창이 달라 수치가
-- 다르므로 행도 둘입니다 — 그래서 기본키에 group_code를 넣습니다.
--
-- - V8은 운영 DB에 이미 적용돼 있어 새 번호 파일로 바꿉니다. 이미 있는 행(단계 1b 계산)은 모두 그룹 A입니다(DEFAULT 'A').
-- - data_source는 계산에 쓴 13F 저장본 — 'q30'(후보 수집, CUSIP 매핑 티커) 또는 'q8'(기존 메뉴, 이름 매핑 티커). 그룹 B는
--   q30이 있으면 그것, 없으면 q8로 계산해 출처가 기관마다 다르고, 화면이 그 계산 때의 출처를 보여야 합니다(지금 저장본으로
--   다시 짐작하면 그 사이 생긴 q30을 계산 때 쓴 것처럼 보임). 단계 1b 행은 모름(NULL).
-- - 여러 번 실행해도 안전합니다(make db-test는 파일마다 매번 다시 실행): 열이 이미 있으면 ADD COLUMN IF NOT EXISTS가
--   CHECK까지 통째로 건너뛰고, 기본키는 지운 뒤 같은 모양으로 다시 만듭니다.
-- - 쓰는 곳은 백엔드 store.PaperStore뿐입니다(ADR 0008).
-- =============================================================================

ALTER TABLE paper_track_record ADD COLUMN IF NOT EXISTS group_code TEXT NOT NULL DEFAULT 'A'
    CHECK (group_code IN ('A', 'B'));
ALTER TABLE paper_track_record DROP CONSTRAINT IF EXISTS paper_track_record_pkey;
ALTER TABLE paper_track_record ADD PRIMARY KEY (as_of_date, group_code, cik);
ALTER TABLE paper_track_record ADD COLUMN IF NOT EXISTS data_source TEXT CHECK (data_source IN ('q30', 'q8'));
