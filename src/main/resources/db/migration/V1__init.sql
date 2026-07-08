create table users (
    id            uuid primary key,
    username      varchar(50)  not null unique,
    email         varchar(255),
    password_hash varchar(100) not null,
    provider      varchar(20)  not null,
    created_at    timestamp with time zone not null
);

create table decks (
    id            uuid primary key,
    name          varchar(100) not null,
    card_values   text         not null,
    owner_user_id uuid,
    built_in      boolean      not null default false
);

create table sessions (
    id                         uuid primary key,
    code                       varchar(16) not null unique,
    name                       varchar(100) not null,
    deck_id                    uuid not null references decks (id),
    facilitator_participant_id uuid,
    status                     varchar(20) not null,
    auto_reveal                boolean not null default false,
    allow_observers            boolean not null default true,
    allow_vote_change          boolean not null default true,
    created_at                 timestamp with time zone not null,
    last_activity_at           timestamp with time zone not null
);

create table participants (
    id                uuid primary key,
    session_id        uuid not null references sessions (id),
    user_id           uuid,
    display_name      varchar(50) not null,
    role              varchar(20) not null,
    connection_status varchar(20) not null,
    active            boolean not null default true,
    joined_at         timestamp with time zone not null
);

create table stories (
    id             uuid primary key,
    session_id     uuid not null references sessions (id),
    title          varchar(255) not null,
    description    text,
    reference_url  varchar(500),
    status         varchar(20) not null,
    final_estimate varchar(50),
    order_index    int not null
);

create table rounds (
    id          uuid primary key,
    story_id    uuid not null references stories (id),
    state       varchar(20) not null,
    created_at  timestamp with time zone not null,
    revealed_at timestamp with time zone
);

create table votes (
    id             uuid primary key,
    round_id       uuid not null references rounds (id),
    participant_id uuid not null references participants (id),
    card_value     varchar(50) not null,
    submitted_at   timestamp with time zone not null,
    constraint uq_vote_round_participant unique (round_id, participant_id)
);

create index idx_participants_session on participants (session_id);
create index idx_stories_session on stories (session_id);
create index idx_rounds_story on rounds (story_id);
create index idx_votes_round on votes (round_id);
