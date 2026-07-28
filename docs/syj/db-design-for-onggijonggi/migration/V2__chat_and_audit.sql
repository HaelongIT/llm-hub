-- docs/03-data-model.md
--
-- chat_session / chat_message: 사용자 소유. 사용자가 삭제하면 메시지도 함께 사라진다.
-- audit_log: 대화 이력과 완전히 별도. 세션·사용자 FK가 없다(S5).

create table chat_session (
    id         uuid primary key,
    user_id    uuid          not null references app_user (id) on delete cascade,
    title      varchar(255)  not null,
    created_at timestamptz   not null default now(),
    updated_at timestamptz   not null default now()
);

create table chat_message (
    id           uuid primary key,
    session_id   uuid        not null references chat_session (id) on delete cascade,
    role         varchar(16) not null check (role in ('USER', 'ASSISTANT')),
    content      text        not null,
    -- assistant 응답 시점의 근거 스냅샷. "그 응답 시점에 무엇을 봤나"의 박제다.
    sources_json jsonb,
    created_at   timestamptz not null default now()
);

create index idx_chat_message_session_created on chat_message (session_id, created_at);

-- 감사 로그. FK가 없는 것이 핵심이다(S5).
-- 사용자·세션 삭제와 무관하게 유지되어야 하므로 requester_id는 값 복사다.
create table audit_log (
    id           uuid primary key,
    trace_id     varchar(64)  not null,
    requester_id varchar(255) not null,
    question     text,
    answer       text,
    sources_json jsonb,
    created_at   timestamptz  not null default now()
);

create index idx_audit_log_trace on audit_log (trace_id);
create index idx_audit_log_created on audit_log (created_at);
