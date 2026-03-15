-- 연방 그룹 (각 연방 형성마다 1개)
CREATE TABLE game_federation_group (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id UUID NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    player_id UUID NOT NULL,
    federation_tile_code VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX ix_federation_group_game ON game_federation_group(game_id);
CREATE INDEX ix_federation_group_game_player ON game_federation_group(game_id, player_id);

-- 연방에 포함된 건물 좌표
CREATE TABLE game_federation_building (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    federation_group_id UUID NOT NULL REFERENCES game_federation_group(id) ON DELETE CASCADE,
    hex_q INTEGER NOT NULL,
    hex_r INTEGER NOT NULL
);

CREATE INDEX ix_federation_building_group ON game_federation_building(federation_group_id);

-- 연방 형성에 사용된 파워 토큰 (빈 헥스에 놓은 것)
CREATE TABLE game_federation_token_hex (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    federation_group_id UUID NOT NULL REFERENCES game_federation_group(id) ON DELETE CASCADE,
    hex_q INTEGER NOT NULL,
    hex_r INTEGER NOT NULL
);

CREATE INDEX ix_federation_token_hex_group ON game_federation_token_hex(federation_group_id);
