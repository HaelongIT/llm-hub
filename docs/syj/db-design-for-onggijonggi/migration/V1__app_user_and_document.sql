-- docs/03-data-model.md
--
-- app_user: 로컬 사용자 식별. 인증은 Keycloak이 하고 여기엔 참조용 최소 정보만 둔다.
--           역할은 저장하지 않는다. Keycloak이 부여하고 요청 시점에 태그로 변환한다(S3).
--           저장하면 이중 관리가 된다.
create table app_user (
    id               uuid primary key,
    keycloak_subject varchar(255) not null unique,
    created_at       timestamptz  not null default now()
);

-- document: 원본 문서 레코드. 조각(ES)의 상위이며 접근 태그의 유일한 원천이다(S18).
--           같은 doc_key 재업로드는 이 행을 갱신한다. id가 바뀌면 조각들이 고아가 된다(S17).
create table document (
    id               uuid primary key,
    doc_key          varchar(255)  not null unique,
    filename         varchar(1024) not null,
    original_path    varchar(1024) not null,
    access_tags      text[]        not null,
    embedding_model  varchar(255)  not null,
    chunking_version varchar(64)   not null,
    uploaded_by      uuid references app_user (id),
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now()
);

-- 접근 태그가 없는 문서는 어떤 사용자에게도 검색되지 않는다. 색인 서비스가 거부하지만
-- 데이터베이스에서도 막는다.
alter table document
    add constraint document_access_tags_not_empty check (cardinality(access_tags) > 0);
