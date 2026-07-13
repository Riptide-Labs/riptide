
<!-- ALL-CONTRIBUTORS-BADGE:START - Do not remove or modify this section -->
[![Riptide Build](https://github.com/Riptide-Labs/riptide/actions/workflows/build.yml/badge.svg)](https://github.com/Riptide-Labs/riptide/actions/workflows/build.yml)
[![All Contributors](https://img.shields.io/badge/all_contributors-1-orange.svg?style=flat-square)](#contributors-)
[![Latest Release](https://img.shields.io/github/v/release/Riptide-Labs/riptide?sort=semver)](https://github.com/Riptide-Labs/riptide/releases)
[![License](https://img.shields.io/github/license/Riptide-Labs/riptide)](LICENSE)
[![Container Image](https://img.shields.io/badge/ghcr.io-riptide-blue?logo=docker)](https://github.com/Riptide-Labs/riptide/pkgs/container/riptide)
[![Java](https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Fraw.githubusercontent.com%2FRiptide-Labs%2Friptide%2Fmain%2Fpom.xml&query=%2F%2F*%5Blocal-name()%3D%27java.version%27%5D&label=Java&logo=openjdk)](pom.xml)
<!-- ALL-CONTRIBUTORS-BADGE:END -->

![riptide-logo](./artwork/riptide-logo.png)

# 🌊 Welcome to Riptide

Give people who love working with networks the tools they deserve to optimize and troubleshoot network traffic.

📖 **Documentation:** https://riptide.space/docs/

# 👩‍🏭 Build from source

**Requirements:**
* git
* Java 25
* Docker with Docker Compose

```
git clone https://github.com/Riptide-Labs/riptide.git && cd riptide
```

Compile from source with tests
```
make
```

Build a container image in your local registry

```
make oci
```

Render a test coverage report (JaCoCo)

```
make coverage
```

Run the integration and e2e test tier (requires Docker; drives real NetFlow v5/v9 and IPFIX
traffic from the [nl6](https://github.com/labmonkeys-space/nl6) simulator through riptide
into ClickHouse). The nl6 image tag is pinned in `src/test/java/org/riptide/e2e/Nl6Container.java`
and is bumped deliberately — it is a wire-format contract, not a Dependabot-managed dependency.

```
make e2e
```

The e2e tier includes an optional **full mode** (Linux only): nl6 devices export flows
from per-device source IPs and riptide's SNMP enrichment walks back to each device's
simulated agent. It is gated on `RIPTIDE_E2E_FULL_MODE=1` and skipped otherwise
(e.g. on macOS). To run it on a Linux host:

```
docker network create --subnet 172.30.42.0/24 nl6-fullmode
sudo ip route add 10.42.0.0/16 via 172.30.42.10
sudo sysctl -w net.ipv4.conf.all.rp_filter=2
RIPTIDE_E2E_FULL_MODE=1 make e2e
```

# 🕹️ Run on your local system

```
cd target
java -jar riptide-flows-*.jar
```

# 🕹️ Run with Docker Compose

```
git clone https://github.com/Riptide-Labs/riptide.git
```

Run with a local build OCI image riptide:local
```
cd deployment/riptide
docker compose up -d
```

* Grafana: http://localhost:3000 with login admin/admin.
* Clickhouse UI with http://localhost:5521
* Send flows to your Riptide server on 9999/udp

> [!TIP]
> If you want to run the latest stable version, create a `compose.override.yml` and set the image tag to `ghcr.io/riptide:latest`.

Run just a ClickHouse stack
```
cd deployment/clickhouse
docker compose up -d
```

# 👩‍🔧 Configuration

The default configuration is shipped in [application.properties](src/main/resources/application.properties).
You have two options to customize configuration parameters.
1. Providing an application.properties next to the jar file, in Docker /app/application.properties
2. Set environment variables

If you want to use environment variables, you need to convert the configuration key to upper case and underscores.
Here is an example:
```
riptide.clickhouse.endpoint -> RIPTIDE_CLICKHOUSE_ENDPOINT
```
You can also bind mount your configuration to `/app/application.properties`.

# 📦 Make a release

Here is an example if you want to release a new version 1.0.0.
> [!NOTE]
> The `main` branch is protected and requires a pull request to merge changes. Create a branch named `release` to make release related changes.
> Send a PR to merge the release branch into `main`.

```
git checkout -b release
make release RELEASE_VERSION=1.0.0
```

The following key functions are provided:

1. Set the release version in the Maven pom.xml
2. Commit new version in the pom.xml and create a git tag
3. Set the Maven pom.xml to a new snapshot version
4. Commit new snapshot version in the pom.xml
5. Optional: Push commits and tags to the release branch to publish the release, add `PUSH_RELEASE=true`

The CI/CD pipeline to build and publish container images for releases is triggered by pushed git version tags.
The version number from the pom.xml is driving the container image version tag.

# 👋 Say hello

You are very welcome to join us to make this project a better place.
You can find us at:

* [Riptide Chat](https://matrix.to/#/#riptide:gitter.im)
* [Riptide Meetup Calendar](https://calendar.google.com/calendar/embed?src=7353b4b1be84c2378387cc5380c27aa48aee6cc4f8cc025cf2083b2bf34cbc67%40group.calendar.google.com&ctz=Europe%2FBerlin)

# 👮 Contribution Guidelines

* [Conventional commits](https://www.conventionalcommits.org/en/v1.0.0/)


## Contributors ✨

Thanks goes to these wonderful people ([emoji key](https://allcontributors.org/docs/en/emoji-key)):

<!-- ALL-CONTRIBUTORS-LIST:START - Do not remove or modify this section -->
<!-- prettier-ignore-start -->
<!-- markdownlint-disable -->
<table>
  <tbody>
    <tr>
      <td align="center" valign="top" width="14.28%"><a href="http://open-desk.org"><img src="https://avatars.githubusercontent.com/u/405105?v=4?s=100" width="100px;" alt="Dustin Frisch"/><br /><sub><b>Dustin Frisch</b></sub></a><br /><a href="https://github.com/Riptide-Labs/riptide/commits?author=fooker" title="Code">💻</a> <a href="#research-fooker" title="Research">🔬</a> <a href="https://github.com/Riptide-Labs/riptide/pulls?q=is%3Apr+reviewed-by%3Afooker" title="Reviewed Pull Requests">👀</a> <a href="#ideas-fooker" title="Ideas, Planning, & Feedback">🤔</a></td>
      <td align="center" valign="top" width="14.28%"><a href="https://blog.no42.org"><img src="https://avatars.githubusercontent.com/u/1095181?v=4?s=100" width="100px;" alt="Ronny Trommer"/><br /><sub><b>Ronny Trommer</b></sub></a><br /><a href="#infra-indigo423" title="Infrastructure (Hosting, Build-Tools, etc)">🚇</a></td>
    </tr>
  </tbody>
</table>

<!-- markdownlint-restore -->
<!-- prettier-ignore-end -->

<!-- ALL-CONTRIBUTORS-LIST:END -->

This project follows the [all-contributors](https://github.com/all-contributors/all-contributors) specification. Contributions of any kind welcome!
