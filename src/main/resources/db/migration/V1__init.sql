create table if not exists player (
                                      id uuid primary key,
                                      nickname varchar(100) not null,
    created_at timestamp not null default now()
    );
