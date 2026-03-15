-- 란티다 기생 광산: 같은 좌표에 2개 건물 허용
ALTER TABLE game_building DROP CONSTRAINT IF EXISTS uq_game_building_hex;
