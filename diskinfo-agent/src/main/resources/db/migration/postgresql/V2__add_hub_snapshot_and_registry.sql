-- Phase 4 rows become local ingest records while retaining their original identities
-- and timestamps.
alter table disk_snapshot
    add column ingest_id UUID,
    add column received_at TIMESTAMPTZ;

update disk_snapshot
set ingest_id = snapshot_id,
    received_at = collect_time;

alter table disk_snapshot
    alter column ingest_id set not null,
    alter column received_at set not null,
    add constraint uq_disk_snapshot_node_ingest unique (node_id, ingest_id);

create index disk_snapshot_latest_lookup
    on disk_snapshot (node_id, device_key, collect_time desc, snapshot_id desc);

create table node_agent_registry
(
    node_id                              UUID          not null primary key,
    node_name                            VARCHAR(255)  not null,
    status                               VARCHAR(32)   not null,
    expected_collection_interval_seconds BIGINT        not null,
    joined_at                            TIMESTAMPTZ   not null,
    last_seen_at                         TIMESTAMPTZ,
    last_snapshot_received_at            TIMESTAMPTZ,
    last_error_code                      VARCHAR(128),
    last_error_message                   VARCHAR(1024),
    last_failure_at                      TIMESTAMPTZ,

    constraint chk_node_agent_registry_status
        check (status in ('ACTIVE', 'DISABLED')),
    constraint chk_node_agent_registry_expected_interval
        check (expected_collection_interval_seconds > 0)
);

create index node_agent_registry_status_node_id
    on node_agent_registry (status, node_id);
