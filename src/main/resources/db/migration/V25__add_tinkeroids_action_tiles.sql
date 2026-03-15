-- 팅커로이드 PI: 사용한 액션 타일 추적 (게임 전체에서 한번 선택하면 재선택 불가)
ALTER TABLE game_player_state ADD COLUMN tinkeroids_used_actions VARCHAR(200) DEFAULT '';
-- 팅커로이드 현재 라운드 액션 타일 코드 (라운드마다 1개 선택, 사용 후 null)
ALTER TABLE game_player_state ADD COLUMN tinkeroids_current_action VARCHAR(50) DEFAULT NULL;
