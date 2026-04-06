-- 등록된 플레이어 테이블 (닉네임 화이트리스트)
-- 직접 DB에 INSERT하여 등록: INSERT INTO registered_player (id, nickname) VALUES (gen_random_uuid(), '닉네임');
CREATE TABLE registered_player (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    nickname   VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT uq_registered_player_nickname UNIQUE (nickname)
);
