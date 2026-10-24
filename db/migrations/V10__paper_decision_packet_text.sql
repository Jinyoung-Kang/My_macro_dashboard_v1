-- =============================================================================
-- V10__paper_decision_packet_text.sql
-- Claude 책 결정의 입력 패킷을 보낸 글자 그대로도 남깁니다(명세 C5 "패킷 전체·해시 저장").
--
-- - packet_hash는 Claude에 보낸 문자열(UTF-8)의 SHA-256인데, packet JSONB는 키를 길이·바이트 순으로 다시 정렬하고 공백·숫자
--   표기를 바꿔 저장합니다. 그래서 저장본을 다시 직렬화하면 해시가 맞지 않고, 결정 로그의 패킷도 Claude가 받은 섹션 순서
--   (0_instructions … 12_hardRules)가 아닙니다. packet_text는 보낸 문자열 그대로라 sha256(packet_text) = packet_hash입니다.
-- - packet(JSONB)은 그대로 둡니다(SQL로 섹션을 꺼내 볼 때). 이 열 전에 남긴 결정은 NULL(백엔드는 그때 JSONB를 읽음).
-- - 여러 번 실행해도 안전합니다(make db-test는 파일마다 매번 다시 실행).
-- - 쓰는 곳은 백엔드 store.PaperStore뿐입니다(ADR 0008).
-- =============================================================================

ALTER TABLE paper_decisions ADD COLUMN IF NOT EXISTS packet_text TEXT;
