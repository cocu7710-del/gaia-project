-- turn_order 유니크 제약조건 제거 (라운드마다 갱신 시 중복 충돌 방지)
ALTER TABLE game_seat DROP CONSTRAINT IF EXISTS uq_game_turn_order;
