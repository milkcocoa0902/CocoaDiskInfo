# CocoaDiskInfo

[日本語](README.md)

CocoaDiskInfo is a disk health viewer for monitoring S.M.A.R.T. information from local PCs, NAS systems, home servers, and lab machines through a consistent model and UI.

It keeps the at-a-glance clarity of tools such as CrystalDiskInfo while normalizing ATA/SATA and NVMe data returned by `smartctl` into shared snapshot and health models. The project supports one-time inspection, persisted history, and a desktop view for machines without a local display.

![CocoaDiskInfo client overview](docs/image/0001_client_overview.png)

## Concept and experience

For storage failures, the important question is often not only what is wrong now, but what changed over time. CocoaDiskInfo treats temperature, power-on hours, bytes written, reallocated sectors, and NVMe media errors as both current device state and time-series history.

- **Move quickly from overview to anomaly**: browse node/device groups, health, and warnings before opening details.
- **Use one experience for ATA and NVMe**: retain protocol-specific information while exposing a shared `DiskSnapshot` and health model.
- **Keep history, not only the latest value**: persist snapshots in SQLite or PostgreSQL and inspect per-device history.
- **Start small**: Standalone performs collection, persistence, and signed API serving in one process.
- **Distribute when needed**: run Node Agents on several machines and inspect their cache-first state through a central Hub.

## Current implementation

Phase 5 is implemented.

- Device scan and explicit-device collection through `smartctl --json`
- ATA/SATA and NVMe snapshot conversion
- Health classification as `GOOD`, `CAUTION`, `BAD`, or `UNKNOWN`
- Oneshot collection with optional persistence
- Standalone periodic collection, persistence, HTTP API, and scheduled cleanup
- SQLite and PostgreSQL storage backends with Flyway migrations
- Latest and bounded-history APIs scoped by node/device
- Retention dry-run, deletion, and SQLite/PostgreSQL vacuum operations
- Compose Desktop overview, details, `Current | History`, and automatic refresh
- Distributed Hub / Node Agent runtimes, idempotent snapshot ingest, and heartbeat
- Signed requests using Ed25519 JWS, body digests, and purpose-bound single-use nonces
- Node Agent join and Desktop Client pairing with owner-only credential files
- Bounded node/device aggregation, pagination, and freshness/partial metadata

API requests are signed, while TLS termination and certificate lifecycle remain deployment responsibilities. Plain HTTP requires explicit opt-in in each Node Agent or Client profile and does not provide server authentication, confidentiality, or response integrity. HTTPS may be terminated by an ALB or reverse proxy. mTLS and a Redis nonce backend are outside Phase 5.

## Execution modes

```text
oneshot      Collect once and print; connect to storage only with --persist
standalone   Run periodic collection, local DB, read API, and maintenance
hub          Receive Node Agent data and run the central DB, read API, and maintenance
node-agent   Collect periodically and deliver signed requests to a Hub
db migrate   Run Flyway migrations explicitly and nothing else
db cleanup   Inspect/delete retained data and optionally vacuum the database
```

## Repository layout

```text
CocoaDiskInfo
├── diskinfo-core      # Domain models, health evaluation, shared API payloads
├── diskinfo-agent     # smartctl, CLI/runtime, storage, HTTP API
├── diskinfo-client    # Compose Desktop UI
├── evolution_plan     # Strategies, phase plans, tasks, handoff baselines
├── sample             # smartctl JSON samples
└── docs/image         # Images used by documentation
```

`diskinfo-core` does not depend on collection or storage. `diskinfo-agent` assembles the Collector, Sinks, Repositories, and runtime. `diskinfo-client` reads current state and history from the API.

## Requirements

- JDK 21
- `smartmontools`
- Permission to run `smartctl`

Depending on the OS, transport, and device, `smartctl` may require administrator privileges.

## Quick start: SQLite Standalone

Runtime startup does not migrate the schema automatically. Run the migration explicitly first.

```bash
./gradlew :diskinfo-agent:run --args='db migrate'
./gradlew :diskinfo-agent:run --args='standalone --scan'
```

To monitor one device:

```bash
./gradlew :diskinfo-agent:run --args='standalone --device /dev/sda'
```

By default, snapshots are stored in `./sapphire.db` and the read API listens on `http://127.0.0.1:14631`.

### Desktop Client

```bash
./gradlew :diskinfo-client:run
```

Standalone read APIs are signed too. First create a pairing token, then enter the endpoint, token material, credential file, and explicit HTTP opt-in when applicable in Client Settings.

```bash
./gradlew :diskinfo-agent:run --args='standalone client-pairing-token create --public-endpoint http://127.0.0.1:14631 --allow-insecure-transport'
```

The token secret is shown once. Do not store it in shell history, configuration, or normal logs. The device detail view provides `Current | History` tabs.

## Hub / Node Agent

The Hub never migrates its schema automatically. Prepare it in this order:

```bash
./gradlew :diskinfo-agent:run --args='db migrate --config ./hub.toml'
./gradlew :diskinfo-agent:run --args='hub --config ./hub.toml'
```

In another terminal, issue a short-lived join token and pass its `hubId`, `tokenId`, and `tokenSecret` to the Node Agent join command.

```bash
./gradlew :diskinfo-agent:run --args='hub join-token create --config ./hub.toml --node-name nas-01'
./gradlew :diskinfo-agent:run --args='node-agent join --config ./node.toml --hub-id <hubId> --token-id <tokenId> --token-secret <tokenSecret> --node-name nas-01'
./gradlew :diskinfo-agent:run --args='node-agent --config ./node.toml --scan'
```

Use `hub client-pairing-token create` for Desktop Client pairing. For credential recovery, `hub join-token create --recovery-node-id <existing-node-id>` preserves the existing node identity and history. Disable an obsolete key with `hub principal disable --kid <old-kid>`.

## Oneshot

By default, collect once without connecting to storage or starting an HTTP server:

```bash
./gradlew :diskinfo-agent:run --args='oneshot --scan'
./gradlew :diskinfo-agent:run --args='oneshot --device /dev/sda --output text'
./gradlew :diskinfo-agent:run --args='oneshot --scan --output json'
```

Use `--persist` only when the result should also be stored. Migrate the schema first.

```bash
./gradlew :diskinfo-agent:run --args='db migrate'
./gradlew :diskinfo-agent:run --args='oneshot --scan --persist'
```

Available output mode names are `default`, `json`, `text`, and `cbor`. The current `cbor` path uses the text formatter; it is not binary CBOR yet.

## Configuration

Configuration priority is:

```text
defaults < TOML config < environment variables < CLI arguments
```

When `--config` is omitted, `/etc/cocoadiskinfo/agent.toml` is loaded if it exists. Unknown sections/keys, invalid types, and out-of-range values are rejected. See [agent.example.toml](diskinfo-agent/src/main/resources/agent.example.toml) for the complete example.

```toml
[smartctl]
scan = true
# device = "/dev/sda"

[deviceIdentity]
namespaceSalt = "default"

[runtime]
intervalSeconds = 60
persist = false

[storage]
type = "sqlite"
jdbcUrl = "jdbc:sqlite:./sapphire.db"

[http]
host = "127.0.0.1"
port = 14631

[publicEndpoint]
baseUrl = "https://hub.example"
allowInsecureTransport = false

[hub]
endpoint = "https://hub.example"
allowInsecureTransport = false
credentialFile = "/etc/cocoadiskinfo/node-credential.json"
# pemCaFile = "/etc/cocoadiskinfo/private-ca.pem"
heartbeatIntervalSeconds = 60
requestTimeoutSeconds = 30
maxRetries = 2

[auth]
nonceTtlSeconds = 60
maxRequestBodyBytes = 2097152

[retention]
rawSnapshotDays = 30

[maintenance]
cleanupOnStartup = true
cleanupIntervalHours = 24
vacuumAfterCleanup = false

[output]
mode = "text"
```

```bash
./gradlew :diskinfo-agent:run --args='db migrate --config ./agent.toml'
./gradlew :diskinfo-agent:run --args='standalone --config ./agent.toml'
```

Supported environment variables:

```text
COCOADISKINFO_AGENT_SMARTCTL_SCAN
COCOADISKINFO_AGENT_SMARTCTL_DEVICE
COCOADISKINFO_AGENT_DEVICE_IDENTITY_NAMESPACE_SALT
COCOADISKINFO_AGENT_OUTPUT_MODE
COCOADISKINFO_AGENT_RUNTIME_PERSIST
COCOADISKINFO_AGENT_RUNTIME_INTERVAL_SECONDS
COCOADISKINFO_AGENT_STORAGE_TYPE
COCOADISKINFO_AGENT_STORAGE_JDBC_URL
COCOADISKINFO_AGENT_STORAGE_USERNAME
COCOADISKINFO_AGENT_STORAGE_PASSWORD
COCOADISKINFO_AGENT_HTTP_HOST
COCOADISKINFO_AGENT_HTTP_PORT
COCOADISKINFO_AGENT_PUBLIC_ENDPOINT_BASE_URL
COCOADISKINFO_AGENT_PUBLIC_ENDPOINT_ALLOW_INSECURE_TRANSPORT
COCOADISKINFO_AGENT_HUB_ENDPOINT
COCOADISKINFO_AGENT_HUB_ALLOW_INSECURE_TRANSPORT
COCOADISKINFO_AGENT_HUB_CREDENTIAL_FILE
COCOADISKINFO_AGENT_HUB_PEM_CA_FILE
COCOADISKINFO_AGENT_HUB_HEARTBEAT_INTERVAL_SECONDS
COCOADISKINFO_AGENT_HUB_REQUEST_TIMEOUT_SECONDS
COCOADISKINFO_AGENT_HUB_MAX_RETRIES
COCOADISKINFO_AGENT_AUTH_NONCE_TTL_SECONDS
COCOADISKINFO_AGENT_AUTH_MAX_REQUEST_BODY_BYTES
COCOADISKINFO_AGENT_RETENTION_RAW_SNAPSHOT_DAYS
COCOADISKINFO_AGENT_MAINTENANCE_CLEANUP_ON_STARTUP
COCOADISKINFO_AGENT_MAINTENANCE_CLEANUP_INTERVAL_HOURS
COCOADISKINFO_AGENT_MAINTENANCE_VACUUM_AFTER_CLEANUP
```

## PostgreSQL

Configure the backend and credentials, then run the same explicit migration command.

```toml
[storage]
type = "postgresql"
jdbcUrl = "jdbc:postgresql://localhost:5432/cocoadiskinfo"
username = "cocoadiskinfo"
password = "change-me"
```

```bash
./gradlew :diskinfo-agent:run --args='db migrate --config ./agent-postgresql.toml'
./gradlew :diskinfo-agent:run --args='standalone --config ./agent-postgresql.toml'
```

Passwords are redacted from normal logging, but production deployments must still protect configuration permissions and secret delivery.

## Retention and database maintenance

By default, Standalone cleanup runs at startup and every 24 hours, deleting raw snapshots older than 30 days. The boundary is retained: only `collect_time < cutoff` is deleted.

```bash
./gradlew :diskinfo-agent:run --args='db cleanup --raw-snapshot-days 30 --dry-run'
./gradlew :diskinfo-agent:run --args='db cleanup --raw-snapshot-days 30'
./gradlew :diskinfo-agent:run --args='db cleanup --raw-snapshot-days 30 --vacuum'
```

SQLite uses `VACUUM`; PostgreSQL runs `VACUUM (ANALYZE) disk_snapshot` outside a transaction. Verify the target database and dry-run result before deletion.

## HTTP API

Hub and Standalone expose signed cache/history-first read endpoints. Only Standalone provides the device-only compatibility route.

```text
GET /api/v1/snapshots/latest
GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots/latest
GET /api/v1/devices/{deviceKey}/snapshots/latest
GET /api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots
    ?limit=100&from=<ISO-8601>&to=<ISO-8601>&order=desc
```

Aggregate `limit` is 1..500 with a default of 100 and uses an opaque keyset `cursor`. History `limit` is 1..1000 with a default of 100. `from` and `to` are inclusive; `order` is `asc` or `desc`. Treat `deviceKey` as an opaque stable identity, not as an OS path or raw serial number. See OpenAPI for the authentication bootstrap, ingest, heartbeat, and read wire contracts.

The OpenAPI document is [documentation.yaml](diskinfo-agent/src/main/resources/openapi/documentation.yaml).

## systemd template

[deploy/systemd/cocoadiskinfo-agent.service](deploy/systemd/cocoadiskinfo-agent.service) is available as a Linux template. It is not a finalized OS package yet.

```bash
./gradlew :diskinfo-agent:shadowJar
sudo install -d /opt/cocoadiskinfo /var/lib/cocoadiskinfo /etc/cocoadiskinfo
sudo install -m 0644 diskinfo-agent/build/libs/diskinfo-agent-1.0-SNAPSHOT-all.jar /opt/cocoadiskinfo/
sudo install -m 0640 diskinfo-agent/src/main/resources/agent.example.toml /etc/cocoadiskinfo/agent.toml
(cd /var/lib/cocoadiskinfo && sudo /usr/bin/java -jar /opt/cocoadiskinfo/diskinfo-agent-1.0-SNAPSHOT-all.jar db migrate --config /etc/cocoadiskinfo/agent.toml)
sudo install -m 0644 deploy/systemd/cocoadiskinfo-agent.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now cocoadiskinfo-agent
```

## Development and verification

```bash
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:test
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-client:compileKotlinDesktop
```

The PostgreSQL integration test runs only when an explicit connection and an empty dedicated database are provided. Do not treat a skip in the default suite as real-database success.

`sample/` contains `smartctl --json`-style inputs. Check both ATA/SATA and NVMe behavior when changing converters or health evaluation.

## Roadmap

- **Phase 5 (implemented)**: Hub / Node Agent, JWS + body digest, nonce/join, transport opt-in, aggregate freshness
- **Phase 6**: versioned and explainable health policies
- **Phase 7**: finalized packaging, systemd, permissions, and logging operations
- **Phase 8+**: Prometheus, charts, and long-term aggregation

See the [evolution plan](evolution_plan/master.md), [Phase 5 plan](evolution_plan/phase_0005/phase_0005_plan.md), and [Phase 5 handoff](evolution_plan/phase_0005/phase_0005_handoff.md) for details.
