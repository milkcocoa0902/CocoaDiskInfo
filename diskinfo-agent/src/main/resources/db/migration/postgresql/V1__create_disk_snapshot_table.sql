-- Raw SMART/NVMe snapshot history for the plain PostgreSQL backend.
-- Partitioning and pg_partman are intentionally not required by Phase 4.
create table disk_snapshot
(
    -- PostgreSQL owns the native UUID representation while the application
    -- continues to generate snapshot identities.
    snapshot_id                      UUID           not null
        primary key,

    node_id                          UUID           not null,
    node_name                        VARCHAR(255)   not null,

    -- TIMESTAMPTZ stores an instant independently from the session timezone.
    collect_time                     TIMESTAMPTZ    not null,

    device_key                       VARCHAR(255)   not null,
    device_serial_name               VARCHAR(255),
    connection_protocol              VARCHAR(255)   not null,
    device_model                     VARCHAR(255),
    device_path                      VARCHAR(255)   not null,
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

    -- The serialized payload remains the API-compatible source while JSONB
    -- provides PostgreSQL-native validation and future query options.
    snapshot_json                    JSONB          not null
);

create index disk_snapshot_node_id_device_key_collect_time
    on disk_snapshot (node_id, device_key, collect_time);

-- Retention cleanup filters the whole table by collect_time only. The
-- node/device/time index cannot efficiently serve this access path.
create index disk_snapshot_collect_time
    on disk_snapshot (collect_time);
