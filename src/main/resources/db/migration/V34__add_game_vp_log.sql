CREATE TABLE game_vp_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id UUID NOT NULL,
    player_id UUID NOT NULL,
    category VARCHAR(50) NOT NULL,
    amount INT NOT NULL,
    round_number INT,
    description VARCHAR(200),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vp_log_game_player ON game_vp_log(game_id, player_id);
CREATE INDEX idx_vp_log_game_category ON game_vp_log(game_id, category);
