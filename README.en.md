# CocoaDiskInfo

[日本語](README.md)

CocoaDiskInfo is a disk health viewer for making S.M.A.R.T. data visible across machines, not only on the machine currently in front of you.

It values the clear at-a-glance feel of CrystalDiskInfo, while using an agent-based design. The agent runs `smartctl` on each target machine, normalizes the collected data, stores snapshots when needed, and exposes them to the desktop client through an HTTP API. The goal is to inspect local PCs, NAS boxes, home servers, and lab machines through the same interface.

![CocoaDiskInfo client overview](docs/image/0001_client_overview.png)

## Concept

Disk degradation becomes harder to reason about when it is discovered late. CocoaDiskInfo treats low-level values such as temperature, power-on hours, written bytes, reallocated sectors, and NVMe media errors as device-level health signals.

The project focuses on three ideas:

- **Make disk health visible from more places**: run an agent on the target machine and read it from the client over HTTP.
- **Normalize protocol differences**: map ATA/SATA and NVMe data into shared `DiskSnapshot` and health models.
- **Keep room for history**: store snapshots in SQLite on the agent side so future UI can show trends, not only the latest value.

## Current Features

- Device scan and S.M.A.R.T. collection through `smartctl --json`
- ATA/SATA and NVMe snapshot conversion
- Health classification as `GOOD`, `CAUTION`, `BAD`, or `UNKNOWN`
- Oneshot output through structured or text-oriented output paths
- Agent mode with periodic collection, SQLite persistence, and HTTP API
- Compose Desktop client with node/device overview, warning count, last scan time, and device details

This repository is still under active development. Database migration behavior and UI details may change.

## Project Layout

```text
CocoaDiskInfo
├── diskinfo-core    # Data models, health rules, shared API responses
├── diskinfo-agent   # smartctl execution, SQLite storage, HTTP API
├── diskinfo-client  # Compose Desktop UI
├── sample           # smartctl JSON samples
└── docs/image       # Images used by README and docs
```

### diskinfo-core

Defines shared disk state models such as `DiskSnapshot`, `MetricsSnapshot`, and `UniversalMetrics`. It maps protocol-specific ATA/NVMe data into common structures that the API and UI can use.

### diskinfo-agent

Runs `smartctl` on the target machine, converts S.M.A.R.T. data into `diskinfo-core` models, and serves the latest state. In agent mode it collects data every 60 seconds, stores it in SQLite, and exposes an API at `http://localhost:14631`.

### diskinfo-client

A Compose Desktop viewer. It connects to an Agent URL and shows nodes, devices, warning count, last scan time, and per-device details.

## Requirements

- JDK 21
- `smartmontools`
- Permission to run `smartctl`

Depending on your OS and device setup, `smartctl` may require administrator privileges.

## Usage

### 1. Prepare the agent schema

```bash
./gradlew :diskinfo-agent:run --args='db migrate'
```

The current migration command is intended for development-time schema initialization. Back up `sapphire.db` first if you need to keep existing data.

### 2. Start the agent

```bash
./gradlew :diskinfo-agent:run --args='standalone --scan'
```

For a single device:

```bash
./gradlew :diskinfo-agent:run --args='standalone --device /dev/sda'
```

The agent serves its API at `http://localhost:14631`.

You can also use a TOML configuration file. CLI arguments override configuration file values.
An example is available at [diskinfo-agent/src/main/resources/agent.example.toml](diskinfo-agent/src/main/resources/agent.example.toml).

```toml
[smartctl]
scan = true
# device = "/dev/sda"

[runtime]
intervalSeconds = 60
persist = false

[storage]
jdbcUrl = "jdbc:sqlite:./sapphire.db"

[http]
port = 14631

[output]
mode = "text"
```

```bash
./gradlew :diskinfo-agent:run --args='standalone --config ./agent.toml'
```

### Run the agent with systemd

On Linux, use [deploy/systemd/cocoadiskinfo-agent.service](deploy/systemd/cocoadiskinfo-agent.service) as a template.

```bash
./gradlew :diskinfo-agent:shadowJar
sudo install -d /opt/cocoadiskinfo /var/lib/cocoadiskinfo
sudo install -m 0644 diskinfo-agent/build/libs/diskinfo-agent-1.0-SNAPSHOT-all.jar /opt/cocoadiskinfo/
(cd /var/lib/cocoadiskinfo && sudo /usr/bin/java -jar /opt/cocoadiskinfo/diskinfo-agent-1.0-SNAPSHOT-all.jar db migrate)
sudo install -m 0644 deploy/systemd/cocoadiskinfo-agent.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now cocoadiskinfo-agent
```

To monitor only one device, change `ExecStart` in the service file to use `standalone --device /dev/sda`.

### 3. Start the client

```bash
./gradlew :diskinfo-client:run
```

Set the Agent URL to `http://localhost:14631`, then use `Connect` or `Refresh`.

## Oneshot Mode

You can collect data once without running the HTTP API.

```bash
./gradlew :diskinfo-agent:run --args='oneshot --scan'
./gradlew :diskinfo-agent:run --args='oneshot --device /dev/sda --output text'
./gradlew :diskinfo-agent:run --args='oneshot --scan --output json'
```

Add `--persist` to store oneshot snapshots in SQLite.

```bash
./gradlew :diskinfo-agent:run --args='oneshot --scan --persist'
```

## API

Standalone mode provides:

```text
GET /api/v1/snapshots/latest
GET /api/v1/devices/{deviceKey}/snapshots/latest
```

The OpenAPI document is available at [diskinfo-agent/src/main/resources/openapi/documentation.yaml](diskinfo-agent/src/main/resources/openapi/documentation.yaml).

## Development

Common checks:

```bash
./gradlew :diskinfo-core:compileKotlin
./gradlew :diskinfo-agent:compileKotlin
./gradlew :diskinfo-client:compileKotlinDesktop
```

The `sample` directory contains `smartctl --json`-style input examples. When changing converters or health rules, check both ATA/SATA and NVMe behavior.

## Roadmap

- Device history view in the client
- Expanded health rules
- Better API response and OpenAPI coverage
- Improved client detail views and filtering
- Operational design for multiple agents
