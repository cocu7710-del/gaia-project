-- 글린 전용: QIC 아카데미 건설 여부 (true면 QIC 정상 획득, false면 QIC→ORE 변환)
ALTER TABLE game_player_state ADD COLUMN gleens_has_qic_academy BOOLEAN NOT NULL DEFAULT FALSE;
