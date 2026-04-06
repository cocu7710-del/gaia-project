-- FED_TILE_3(즉시 VP 12, 비활성 연방토큰)은 획득 즉시 used 처리되어야 함
-- 기존 발급된 레코드 일괄 업데이트
UPDATE game_federation_group
SET used = TRUE
WHERE federation_tile_code = 'FED_TILE_3';
