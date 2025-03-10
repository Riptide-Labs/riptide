
![riptide-logo](./artwork/riptide-logo.png)


# 🌊 Welcome to Riptide 

Give people who love working with IT networks the tools they deserve to optimize and troubleshoot network traffic.

# Build from source

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

# Run the application with Docker Compose

Check out the repository
```
git clone https://github.com/Riptide-Labs/riptide.git
```

Run with a local build OCI image riptide:local
```
cd deployment/riptide-stack
docker compose up -d
```

Access Grafana http://localhost:3000 with login admin/admin.

Option run a Clickhouse UI

```
cd deployment/clickhoust-ui
export CLICKHOUSE_FQDN=<YOUR_DOCKERHOST_IP_OR_FQDN>
docker compose up -d
```

Acces the Clickhouse UI with http://localhost:5521.

# 👋 Say hello

You are very welcome to join us to make this project a better place.
You can find us at:

* [Riptide Chat](https://matrix.to/#/#riptide:gitter.im)
* [Riptide Meetup Calendar](https://calendar.google.com/calendar/embed?src=7353b4b1be84c2378387cc5380c27aa48aee6cc4f8cc025cf2083b2bf34cbc67%40group.calendar.google.com&ctz=Europe%2FBerlin)

# 👮 Contribution Guidelines

* [Conventional commits](https://www.conventionalcommits.org/en/v1.0.0/)

