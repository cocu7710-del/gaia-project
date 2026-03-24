-- 연방 형성 횟수 카운터 (모든 종족 공통)
ALTER TABLE game_player_state ADD COLUMN IF NOT EXISTS federation_count INT NOT NULL DEFAULT 0;
