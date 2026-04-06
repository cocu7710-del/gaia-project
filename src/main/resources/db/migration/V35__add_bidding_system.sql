-- 비딩 테이블
CREATE TABLE game_bid (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id UUID NOT NULL REFERENCES game(id),
    player_id UUID NOT NULL,
    bid_round INT NOT NULL DEFAULT 1,
    bid_amount INT NOT NULL DEFAULT 0,
    is_passed BOOLEAN NOT NULL DEFAULT FALSE,
    pick_order INT,
    seat_no INT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(game_id, player_id)
);

-- 게임 비딩 상태
ALTER TABLE game ADD COLUMN bidding_round INT NOT NULL DEFAULT 0;
ALTER TABLE game ADD COLUMN bidding_current_bid INT NOT NULL DEFAULT 0;
ALTER TABLE game ADD COLUMN bidding_turn_player_id UUID;

-- 플레이어 비딩 패널티 (게임 종료 시 VP 차감)
ALTER TABLE game_player_state ADD COLUMN bid_penalty INT NOT NULL DEFAULT 0;
