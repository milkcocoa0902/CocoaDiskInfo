-- Hub identity and application-level signing credentials. TLS certificates and
-- private signing keys intentionally do not belong in the Hub database.
create table hub_identity
(
    singleton_key INTEGER    not null primary key,
    hub_id        BINARY(16) not null unique,
    created_at    TEXT       not null,

    constraint chk_hub_identity_singleton check (singleton_key = 1)
);

create table security_principal
(
    principal_id   BINARY(16)   not null primary key,
    principal_type VARCHAR(32)  not null,
    display_name   VARCHAR(255) not null,
    status         VARCHAR(32)  not null,
    kid            VARCHAR(43)  not null unique,
    public_key_jwk BLOB         not null,
    key_algorithm  VARCHAR(32)  not null,
    node_id        BINARY(16),
    created_at     TEXT         not null,
    last_seen_at   TEXT,

    constraint chk_security_principal_type
        check (principal_type in ('NODE_AGENT', 'CLIENT')),
    constraint chk_security_principal_status
        check (status in ('ACTIVE', 'DISABLED')),
    constraint chk_security_principal_algorithm
        check (key_algorithm = 'Ed25519'),
    constraint chk_security_principal_node_scope
        check ((principal_type = 'NODE_AGENT' and node_id is not null) or
               (principal_type = 'CLIENT' and node_id is null))
);

create index security_principal_status_type
    on security_principal (status, principal_type);

create table bootstrap_token
(
    token_id              BINARY(16)   not null primary key,
    token_type            VARCHAR(32)  not null,
    join_key              BLOB         not null,
    created_at            TEXT         not null,
    expires_at            TEXT         not null,
    used_at               TEXT,
    bound_kid             VARCHAR(43),
    recovery_node_id      BINARY(16),
    expected_display_name VARCHAR(255),

    constraint chk_bootstrap_token_type
        check (token_type in ('JOIN_TOKEN', 'PAIRING_TOKEN')),
    constraint chk_bootstrap_token_join_key
        check (length(join_key) = 32),
    constraint chk_bootstrap_token_binding
        check ((used_at is null and bound_kid is null) or
               (used_at is not null and bound_kid is not null)),
    constraint chk_bootstrap_token_recovery_scope
        check (token_type = 'JOIN_TOKEN' or recovery_node_id is null)
);

create index bootstrap_token_expiry
    on bootstrap_token (expires_at);
