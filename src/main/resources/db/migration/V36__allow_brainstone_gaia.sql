-- 브레인스톤 가이아 구역(0) 허용
ALTER TABLE game_player_state DROP CONSTRAINT chk_brainstone_bowl;
ALTER TABLE game_player_state ADD CONSTRAINT chk_brainstone_bowl CHECK (brainstone_bowl IS NULL OR brainstone_bowl BETWEEN 0 AND 3);
