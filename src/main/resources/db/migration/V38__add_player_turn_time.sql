-- 플레이어 턴 사용 시간 추적
ALTER TABLE game_player_state ADD COLUMN used_time_seconds INTEGER NOT NULL DEFAULT 0;
ALTER TABLE game_player_state ADD COLUMN turn_started_at TIMESTAMP;
