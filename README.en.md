# OpsMonitor

[中文](README.md)

OpsMonitor is an automation-focused monitoring platform for servers and middleware. It combines a Spring Boot control plane, Prometheus metrics collection, Grafana visualization, AlertManager alerting, VictoriaMetrics long-term storage, and a Sentinel automation workflow. The goal is to provide monitoring, diagnosis, runbook execution, and incident management in one deployable system.

## What It Solves

Many monitoring setups split metrics, alerts, server inventory, diagnosis scripts, and incident records across different tools. When an outage happens, operators still need to log in to hosts manually, copy commands, check dashboards, and update incident states by hand. OpsMonitor brings those workflows into one platform:

- Register servers and exporters for Linux, Windows, and container-based targets.
- Maintain Prometheus targets, alert rules, and reload flow from the control plane.
- Receive AlertManager webhooks and manage alert acknowledgement, silence, and lifecycle.
- Trigger Sentinel diagnosis, create incidents, and execute runbook steps.
- Provide an operations console for overview, exporters, alerts, topology, notifications, config, tenants, audit, and users.

## Tech Stack, Real Pain Points, Quantified Results

| Tech Stack | Real Pain Point | Quantified Result |
| --- | --- | --- |
| Spring Boot 3.2 / Java 17 | Monitoring inventory, permissions, audit, config, and automation logic need one control plane | 20+ REST controllers covering servers, exporters, alerts, Sentinel, RBAC, tenants, and notifications |
| Prometheus / AlertManager / Grafana / VictoriaMetrics | Metrics, alert routing, dashboards, and long-term storage are usually configured separately | Docker Compose starts 5 monitoring components; default 15s scrape/evaluation; VictoriaMetrics keeps 365 days by default |
| docker-java 3.3.6 | Local containerized exporters are hard to manage manually | Supports exporter registration, start, stop, batch registration, health checks, and Prometheus target writing |
| Vue 3 CDN / static assets | Ops dashboards should be easy to ship with the backend without a frontend build pipeline | The Spring Boot app serves the admin console directly; `docs/` contains 7 product screenshots |
| Sentinel automation engine / JSch 0.2.17 | Alerts still require manual host login, diagnosis, and script execution | 14 Sentinel core classes; runbooks support LOG, HTTP, SCRIPT, and SSH steps |
| RBAC / audit / input validation | Operations platforms need permission boundaries and traceability for write actions | Built-in ADMIN / OPS / VIEWER role model and daily audit log persistence |

## Architecture

```mermaid
flowchart LR
    User["Operator"] --> UI["OpsMonitor Web Console"]
    UI --> API["Spring Boot Control Plane"]
    API --> Docker["docker-java Exporter Manager"]
    API --> Prom["Prometheus Config / Reload"]
    API --> RBAC["RBAC / Audit / Config Center"]
    Docker --> Exporters["Node / Process / Middleware Exporters"]
    Exporters --> Prometheus["Prometheus"]
    Prometheus --> Grafana["Grafana"]
    Prometheus --> Victoria["VictoriaMetrics"]
    Prometheus --> AlertManager["AlertManager"]
    AlertManager --> Webhook["Alert Webhook"]
    Webhook --> AlertCenter["Alert Center"]
    AlertCenter --> Sentinel["Sentinel Diagnosis"]
    Sentinel --> Incident["Incident"]
    Sentinel --> Runbook["Runbook Executor"]
    Runbook --> Actions["LOG / HTTP / SCRIPT / SSH"]
```

## Features

- Overview dashboard for agents, exporters, active alerts, and audit score.
- Exporter management with registration, batch registration, labels, health checks, and path diagnosis.
- Alert center with FIRING, ACKNOWLEDGED, and RESOLVED lifecycle states.
- Sentinel automation with manual diagnosis, alert-driven diagnosis, incident management, and runbook execution.
- Service topology organized by Global, Project, Service, and Instance.
- Notification channels for alert firing and recovery events.
- Config center with managed config versions and history.
- Multi-tenant model and RBAC for basic quota and permission management.
- System audit for configuration, health, alerts, managed resources, and platform status.

## Screenshots

Screenshots are stored in the `docs/` directory.

![Overview](docs/img.png)

![Exporter Management](docs/img_1.png)

![Alerts and Diagnosis](docs/img_2.png)

![Sentinel Automation](docs/img_3.png)

![Incident and Runbook](docs/img_4.png)

![System Management](docs/img_5.png)

![Config and Audit](docs/img_6.png)

## Quick Start

### Requirements

- JDK 17+
- Maven 3.8+
- Docker Engine
- Docker Compose v2

### Configure Environment Variables

Copy the example file and adjust it as needed:

```powershell
Copy-Item .env.example .env
```

Development can use defaults. Production or internet-facing deployments must use strong passwords and random secrets:

```powershell
$env:OPS_ADMIN_PASSWORD="ChangeMe_Admin_123!"
$env:OPS_GRAFANA_PASSWORD="ChangeMe_Grafana_123!"
$env:OPS_HMAC_SECRET="replace-with-at-least-32-random-characters"
$env:OPS_WEBHOOK_SECRET="replace-with-random-webhook-secret"
```

### Start Monitoring Components

Run commands from the `app/` directory. The effective runtime config directory is `app/docker/`.

```powershell
cd app
docker compose -f docker/docker-compose.yml up -d
```

### Start OpsMonitor

```powershell
mvn spring-boot:run
```

Endpoints:

- OpsMonitor Admin: http://127.0.0.1:8080/admin
- Classic Console: http://127.0.0.1:8080/
- Prometheus: http://127.0.0.1:9090
- AlertManager: http://127.0.0.1:9093
- Grafana: http://127.0.0.1:3000
- VictoriaMetrics: http://127.0.0.1:8428

## Build and Verify

```powershell
cd app
mvn -q -DskipTests compile
```

## Repository Layout

```text
ops-monitor/
  app/
    pom.xml
    docker/                      # Effective Prometheus / Grafana / AlertManager config
    src/main/java/com/opsmonitor # Spring Boot backend source
    src/main/resources/static    # Vue 3 CDN admin console static assets
    src/main/resources/templates # Classic page templates
  docs/                          # Public screenshots
  README.md
  README.en.md
```

## Public Release Scope

The public repository should contain only source code, public runtime config templates, public screenshots, and README files. The following are intentionally excluded:

- Internal implementation reports, delivery review records, validation records, and project construction notes.
- Agent instructions, personal workflow rules, roadmaps, product drafts, and internal test plans.
- Runtime data, audit logs, health reports, lock files, real targets, backup configs, and historical duplicate config directories.
- `.env`, private keys, certificates, real passwords, tokens, cookies, or production secrets.

If any of these files were already tracked by Git, adding them to `.gitignore` is not enough. Remove them from the Git index before the first public push.

## Security Notes

- Default passwords are for local development only. Production deployments must set `OPS_ADMIN_PASSWORD`, `OPS_GRAFANA_PASSWORD`, and `OPS_HMAC_SECRET`.
- Production deployments should set `OPS_WEBHOOK_SECRET` and align it with the AlertManager webhook config.
- SSH runbooks should use strict host key checking in production, with `OPS_SSH_KNOWN_HOSTS` configured.
- Do not commit `data/`, `app/data/`, `.env`, lock files, health reports, or audit logs.

## License

This repository does not include a license file yet. Before publishing, choose a license. Apache-2.0 or MIT are common choices for infrastructure projects.
