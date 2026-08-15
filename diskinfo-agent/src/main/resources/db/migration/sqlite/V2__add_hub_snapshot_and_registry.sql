-- Add Hub ingest identity and receive time without changing the Phase 4 V1 baseline.
-- SQLite cannot backfill a NOT NULL column from another column through ALTER TABLE,
-- so rebuild the table and preserve every V1 constraint explicitly.
create table disk_snapshot_v2
(
    snapshot_id                      BINARY(16)   not null primary key,
    ingest_id                        BINARY(16)   not null,
    node_id                          BINARY(16)   not null,
    node_name                        VARCHAR(255) not null,
    collect_time                     TEXT         not null,
    received_at                      TEXT         not null,
    device_key                       VARCHAR(255) not null,
    device_serial_name               VARCHAR(255),
    connection_protocol              VARCHAR(255) not null,
    device_model                     VARCHAR(255),
    device_path                      VARCHAR(255) not null,
    temperature_celsius              DECIMAL(5, 2),
    power_on_hours                   BIGINT,
    power_on_cycles                  BIGINT,
    ata_reallocated_sector_count     INT,
    ata_current_pending_sector_count INT,
    ata_offline_uncorrectable_count  INT,
    ata_udma_crc_error_count         INT,
    nvme_percentage_used             INT,
    nvme_available_spare             INT,
    nvme_media_error_count           BIGINT,
    nvme_data_units_written          BIGINT,
    nvme_data_units_read             BIGINT,
    snapshot_json                    BLOB         not null,

    constraint chk_disk_snapshot_signed_integer_ata_current_pending_sector_count
        check (ata_current_pending_sector_count BETWEEN -2147483648 AND 2147483647),
    constraint chk_disk_snapshot_signed_integer_ata_offline_uncorrectable_count
        check (ata_offline_uncorrectable_count BETWEEN -2147483648 AND 2147483647),
    constraint chk_disk_snapshot_signed_integer_ata_reallocated_sector_count
        check (ata_reallocated_sector_count BETWEEN -2147483648 AND 2147483647),
    constraint chk_disk_snapshot_signed_integer_ata_udma_crc_error_count
        check (ata_udma_crc_error_count BETWEEN -2147483648 AND 2147483647),
    constraint chk_disk_snapshot_signed_integer_nvme_available_spare
        check (nvme_available_spare BETWEEN -2147483648 AND 2147483647),
    constraint chk_disk_snapshot_signed_integer_nvme_percentage_used
        check (nvme_percentage_used BETWEEN -2147483648 AND 2147483647),
    constraint uq_disk_snapshot_node_ingest unique (node_id, ingest_id)
);

insert into disk_snapshot_v2
select snapshot_id,
       snapshot_id,
       node_id,
       node_name,
       collect_time,
       collect_time,
       device_key,
       device_serial_name,
       connection_protocol,
       device_model,
       device_path,
       temperature_celsius,
       power_on_hours,
       power_on_cycles,
       ata_reallocated_sector_count,
       ata_current_pending_sector_count,
       ata_offline_uncorrectable_count,
       ata_udma_crc_error_count,
       nvme_percentage_used,
       nvme_available_spare,
       nvme_media_error_count,
       nvme_data_units_written,
       nvme_data_units_read,
       snapshot_json
from disk_snapshot;

drop table disk_snapshot;
alter table disk_snapshot_v2 rename to disk_snapshot;

create index disk_snapshot_node_id_device_key_collect_time
    on disk_snapshot (node_id, device_key, collect_time);

create index disk_snapshot_collect_time
    on disk_snapshot (collect_time);

create index disk_snapshot_latest_lookup
    on disk_snapshot (node_id, device_key, collect_time desc, snapshot_id desc);

create table node_agent_registry
(
    node_id                              BINARY(16)   not null primary key,
    node_name                            VARCHAR(255) not null,
    status                               VARCHAR(32)  not null,
    expected_collection_interval_seconds BIGINT       not null,
    joined_at                            TEXT          not null,
    last_seen_at                         TEXT,
    last_snapshot_received_at            TEXT,
    last_error_code                      VARCHAR(128),
    last_error_message                   VARCHAR(1024),
    last_failure_at                      TEXT,

    constraint chk_node_agent_registry_status
        check (status in ('ACTIVE', 'DISABLED')),
    constraint chk_node_agent_registry_expected_interval
        check (expected_collection_interval_seconds > 0)
);

create index node_agent_registry_status_node_id
    on node_agent_registry (status, node_id);
