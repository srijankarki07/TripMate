# Deploying TripMate

Everything in `runbook.md` assumes the machine you are sitting at. This is the
other document: putting the application on a server somebody else owns, at a
public address, with a certificate, so that it is reachable by people who are
not you.

It is written for the deployment this project actually has, which is a single
small virtual machine running GlassFish, PostgreSQL and the application
together. That is the cheapest thing that works and the easiest to reason
about, and for a coursework system with a handful of users it is also the right
size.

---

## 1. What the application needs

These are not guesses. They are what this stack actually occupies, measured on
the machine it was built on.

| | Occupies | Notes |
|---|---|---|
| Ubuntu Server, minimal | 180–250 MB | |
| PostgreSQL | 150–200 MB | ~100 MB after the tuning in section 4 |
| GlassFish 7, holding this application | 450–700 MB | Depends almost entirely on the heap cap |
| nginx | 10–20 MB | |
| **Total resident** | **~800 MB – 1.1 GB** | |

Disk is easy: about 6 GB for the OS, 400 MB for a JDK, 600 MB for GlassFish,
200 MB for the database, and 500 MB for a Maven repository if you build on the
server. **20 GB is comfortable; 25 GB means you never think about it again.**

Processor and bandwidth are not worth planning for. The application renders
HTML on the server and serves one 14 KB stylesheet, and the heaviest thing it
does is a reverse geocode to Nominatim when somebody places a trip.

The number that decides whether a host is big enough is **RAM**, and the
boundary is between 1 GB and 2 GB. See section 4.

### Architecture

The application is plain Java, so it runs on x86-64 and on ARM64 alike, given a
JDK built for the same. GlassFish's `.zip` distribution is bytecode and shell
scripts rather than a compiled binary, so an ARM host needs nothing special
beyond an ARM JDK.

Two things in this repository assume x86-64 Linux, and both are cosmetic:

- `scripts/env.sh` looks for `/usr/lib/jvm/java-17-openjdk-amd64` by name. On
  ARM that path is `-arm64`, so either `export JAVA_HOME` before running any
  script, or add the path to the candidate list.
- Nothing else is architecture-specific.

---

## 2. Choosing a host

### What to look for

You need a machine, not a platform. The container PaaS offerings (Render,
Railway, Fly.io) are built around a single process from a `Dockerfile` or a
buildpack, with an ephemeral filesystem and no separate database you can reach.
TripMate needs a servlet container holding a JMS broker, a database that
survives a restart, and a writable GlassFish domain directory. Fitting that
into a 512 MB container with no persistent disk is more work than running a
small VM, and the result is worse.

### Oracle Cloud's Always Free tier

This was the obvious answer for a long time and it is now a difficult one.
Two things changed:

- The Ampere A1 allowance was reduced from 4 cores and 24 GB to **2 cores and
  12 GB**. Still generous, still free with no expiry.
- **ARM capacity is frequently exhausted.** "Out of host capacity" in every
  availability domain of a popular region is the normal experience, not an
  error you have done something to cause. It can take days of retrying, or a
  different home region chosen at signup.

The AMD alternative, `VM.Standard.E2.1.Micro`, is **1 OCPU and 1 GB**, and it
is the shape this project's author ended up with. Note what that listing means:
the OCPU is burstable, so it is a fraction of a core most of the time; there is
no local disk, so everything is block storage; and 1 GB is the floor for this
stack rather than a comfortable size. Section 4 is about making it work.

The other always-free shapes are no better. Google's e2-micro is also 1 GB.
AWS's equivalent expires after twelve months, which makes it a trial rather
than a tier.

### If you have a card and no patience

A 2 GB instance from a mainstream provider costs a few pounds a month and turns
section 4 from "necessary" into "optional". There is no shame in that, and if
this deployment is being marked, spending the price of a coffee to remove a
swap file from the critical path is a reasonable trade.

---

## 3. Preparing the server

Ubuntu 24.04 assumes an `ubuntu` user with `sudo`. Substitute your own.

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y openjdk-17-jdk postgresql nginx certbot python3-certbot-nginx git unzip
```

On x86-64, `scripts/env.sh` finds the JDK by itself. On ARM, export it:

```bash
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64' | sudo tee /etc/profile.d/java.sh
```

### GlassFish

```bash
cd /opt
sudo curl -LO https://github.com/eclipse-ee4j/glassfish/releases/download/7.0.18/glassfish-7.0.18.zip
sudo unzip -q glassfish-7.0.18.zip
sudo mv glassfish7 glassfish
sudo chown -R ubuntu:ubuntu /opt/glassfish
export GLASSFISH_HOME=/opt/glassfish
export PATH="$GLASSFISH_HOME/bin:$PATH"
```

`GLASSFISH_HOME` must be set for the deployment scripts to find `asadmin`.
`scripts/env.sh` also checks `/opt/glassfish7` and `${HOME}/glassfish7`, so
`/opt/glassfish` needs the variable rather than a guess.

### PostgreSQL

The schema uses plain tables, identity columns and foreign keys, with **nothing
version-specific** in it. Ubuntu 24.04 ships PostgreSQL 16, which is fine. If
you specifically want 18, add the PGDG repository first; nothing in this
project requires it.

---

## 4. Making it fit on 1 GB

This is the section that matters if you are on `VM.Standard.E2.1.Micro`. The
default configuration does not fit: GlassFish alone is configured for a 512 MB
heap, and the JVM needs roughly 250 MB on top of its heap for metaspace, code
cache, thread stacks and GC structures.

In practice, GlassFish at defaults plus PostgreSQL plus the OS lands somewhere
around 1.1 GB resident, which on a 1 GB machine means the kernel starts killing
things.

Most of the levers below are now applied for you by `scripts/glassfish-tune.sh`,
which is idempotent and which `deploy.sh` runs on every deployment. This section
explains what it does and why, so that the values can be argued with rather than
taken on faith, and covers the two things it cannot do: swap, and PostgreSQL's
own settings.

The script reads its values from `scripts/env.sh`, so every number here can be
changed by exporting it or by putting it in `.env` and re-running the script.

```bash
./scripts/glassfish-tune.sh            # apply, and say what still needs a restart
./scripts/glassfish-tune.sh --restart  # apply, and restart if the JVM changed
```

### 4.1 Swap, first and always

This buys more than any tuning below, and it is the difference between slow and
dead.

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# Only under real pressure. The default of 60 makes an idle machine swap for
# no reason, which on block storage is far slower than the memory it saves.
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-tripmate.conf
echo 'vm.vfs_cache_pressure=50' | sudo tee -a /etc/sysctl.d/99-tripmate.conf
sudo sysctl --system
```

### 4.2 The JVM

The default is `-Xmx512m`, set in `domain.xml`, and nothing else is bounded at
all. The script sets every one of these, and a restart is needed before any of
them takes effect.

Why each one:

- **`-Xmx384m`**, with **`-Xms384m`** on top of it. Smaller than the default,
  but the application is not memory-hungry; it holds small entities and page
  state. If you see `OutOfMemoryError` in the server log, raise it in 64 MB
  steps and take the memory back from somewhere else. `Xms` is pinned equal to
  `Xmx` so the heap is never resized at runtime, because growing and shrinking
  it costs a full collection each way.
- **`-XX:MaxMetaspaceSize=192m`**. Metaspace is unbounded by default and is
  where a JSF application leaks if it leaks anywhere. PrimeFaces, Mojarra and
  EclipseLink together want about 120–150 MB, so this is a ceiling rather than
  a constraint, and it turns a slow leak into a loud failure.
- **`-XX:MaxDirectMemorySize=64m`**. Grizzly allocates its network buffers here,
  and it is outside the heap, so `-Xmx` does not bound it. Left unbounded it is
  the reason a process can be killed for memory while the heap still looks
  comfortable.
- **`-XX:+UseSerialGC`**. The most useful of the four. G1, the default, keeps
  remembered sets and concurrent marking threads that cost memory and CPU
  regardless of load. Below about a gigabyte of heap, SerialGC is both smaller
  and faster, and on a burstable fraction of a core the CPU saving matters as
  much as the memory.
- **`-Xss512k`**. Halves each thread's stack. GlassFish starts a lot of
  threads, so this is 20–40 MB across the process. Safe because this
  application does not recurse.
- **`-Djava.security.egd=file:/dev/./urandom`**. Session ids come from
  `SecureRandom`, and the blocking `/dev/random` can hold a request until enough
  entropy has accumulated, which shows up as the first request after a restart
  taking seconds for no visible reason.

If you set any of these by hand rather than through the script, note that
`asadmin` splits its command line on `:`, so a colon inside an option has to be
escaped or the command fails with a message that names half the option and not
the colon that caused it:

```bash
asadmin create-jvm-options '-XX\:MaxMetaspaceSize=192m'   # note the backslash
```

### 4.3 The request thread pool

This is the setting that decides how many people the application can serve at
once, and its default is the main reason it feels slow with more than one
reader.

**GlassFish 7 ships `http-thread-pool` at min 5, max 5.** Five threads handle
every request in the server, static resources included. Any request path that
blocks holds one of them for its whole duration, and several paths here block
for a long time: verifying a password is 210,000 rounds of PBKDF2, sending a
trip summary waits on SMTP once per recipient, placing a trip waits on a reverse
geocode, and a calendar sync waits on Google once per member per itinerary item.
With five threads, five people doing any of those at once is a stalled server
for everybody else, which is the reported symptom exactly.

The script raises the pool to 8 minimum and 24 maximum, and cuts the accept
queue from 4096 to 256 so that overload becomes a fast failure rather than a
slow one:

```
TM_HTTP_THREADS_MIN=8
TM_HTTP_THREADS_MAX=24
TM_HTTP_QUEUE_SIZE=256
```

24 is chosen against the machine rather than against a benchmark. On one
burstable core more threads cannot add throughput, but they do stop one slow
request from denying service to the rest. Raise it on a larger host, or if you
find requests queuing while the threads sit idle.

### 4.4 PostgreSQL

PostgreSQL's defaults assume a machine several times this size.
`shared_buffers` alone is 128 MB out of the box.

```bash
sudo -u postgres psql <<'SQL'
ALTER SYSTEM SET shared_buffers = '64MB';
ALTER SYSTEM SET effective_cache_size = '256MB';
ALTER SYSTEM SET work_mem = '2MB';
ALTER SYSTEM SET maintenance_work_mem = '32MB';
ALTER SYSTEM SET max_connections = 20;
SQL
sudo systemctl restart postgresql
```

`ALTER SYSTEM` rather than editing `postgresql.conf` because the file's path
contains the version number and this does not care. `max_connections` is the
one that surprises people: it is not just a limit, it is a per-slot allocation,
and a hundred slots cost memory whether or not they are used.

Twenty is deliberately a little above the application's own JDBC pool, which
`glassfish-tune.sh` sizes at 3 steady and 16 maximum. The two have to be read
together: a pool allowed to grow past `max_connections` does not degrade
gracefully, it starts failing to connect, and that surfaces as an application
error rather than as the database being full. If you raise `TM_DB_POOL_MAX`, raise
this too.

**Do not turn `fsync` off.** It is the usual advice for a slow small server and
it trades your data for speed.

### 4.5 The last 100 MB, if you need it

If the four levers above are not enough, move the database off the machine.
Neon and Supabase both offer a free PostgreSQL that is reachable over JDBC, and
pointing TripMate at one is configuration rather than code:

```
# .env
TM_DB_HOST=<the host they give you>
TM_DB_PORT=5432
TM_DB_NAME=<the database>
TM_DB_USER=<the user>
TM_DB_PASSWORD=<the password>
```

Then re-run `scripts/glassfish-setup.sh` so the connection pool picks up the new
URL, and skip section 4.4 entirely. That frees 100–150 MB, which on this machine
is the difference between swapping occasionally and not at all.

The trade is latency: every query becomes a network round trip, and the free
tiers suspend after a period of inactivity, so the first request after a quiet
spell waits for the database to wake. For a coursework deployment that is
usually a good trade. For anything else, it is not.

---

## 5. The database

```bash
cd ~/tripmate
sudo -u postgres psql -v ON_ERROR_STOP=1 \
     -v tripmate_password='<a real password, not the development default>' \
     -f database/bootstrap.sql
```

Then put that same password in `.env` as `TM_DB_PASSWORD`, and load the schema:

```bash
cp .env.example .env
chmod 600 .env
nano .env                     # set TM_DB_PASSWORD at minimum
./database/load.sh
```

`bootstrap.sql` creates a role that owns one database and holds no superuser
rights, which is deliberate: a leaked application credential costs one database
rather than the cluster. Keep it that way, and use a real password:
the development default is published in this repository.

`load.sh` loads `schema.sql` and `seed.sql`, so the sample trips and the
demonstration account come with it. On a deployment you intend to keep, decide
whether you want the sample data at all; it is harmless, but it is somebody
else's trip.

> **Never run `scripts/test-matrix.sh` on a deployed server.** It reloads the
> database from `schema.sql` and `seed.sql` and destroys everything in it. It
> is a development tool and it is not guarded.

---

## 6. Building and deploying

### Build where there is memory

Do not run Maven on a 1 GB machine. It wants 500 MB to a gigabyte on its own,
and it will either swap for ten minutes or be killed. Build on the machine you
develop on and copy the artifact:

```bash
# on your own machine
./scripts/build.sh
scp tripmate-ear/target/tripmate-ear-1.0.0-SNAPSHOT.ear \
    ubuntu@tripmate.example.com:~/tripmate/tripmate-ear/target/
```

**Build without `-Pdev`.** The artifact you deploy has to be the production
build; the profile is only for working on the application locally. It is not a
cosmetic difference. `-Pdev` sets the Faces project stage to Development, which
stops Mojarra caching Facelets templates so that every request re-parses the
views it renders, and turns EclipseLink's SQL logging on at FINE, which writes
every statement and its bound parameters to the server log. On this machine that
is not a preference, it is the difference between a responsive server and a
95 MB log.

```bash
mvn clean package         # what you deploy
mvn -Pdev clean package   # what you develop against
```

There is nothing in the archive that says which one it is, so it is worth
checking after a build if you are unsure:

```bash
unzip -p tripmate-web/target/tripmate-web-*.war WEB-INF/web.xml \
    | grep -A1 PROJECT_STAGE
```

That should print `Production`.

Then on the server, run the setup scripts and deploy the EAR you copied:

```bash
cd ~/tripmate
./scripts/glassfish-setup.sh     # the JDBC pool and resource, from .env
./scripts/glassfish-tune.sh      # thread pool, pool sizes, JVM, log rotation
./scripts/glassfish-jms.sh       # the expense queue; deployment fails without it
./scripts/glassfish-mail.sh      # the mail session; same
asadmin deploy --force=true --name tripmate \
    tripmate-ear/target/tripmate-ear-1.0.0-SNAPSHOT.ear
```

`glassfish-tune.sh` takes effect for the JVM options only after a restart, and
says so when it needs one. On a 2 GB machine or larger, `./scripts/deploy.sh`
runs all of the above including the build, and is the shorter path.

### Two things about the deployment name

The application serves at **`/`**, because `tripmate-ear/pom.xml` sets the
context root to `/`. It is deployed under the **name `tripmate`**, so
GlassFish's exploded directory is `glassfish/domains/domain1/applications/tripmate/`
even though the URL has no path in it. `scripts/live.sh` knows this and
`runbook.md` repeats it; it is the kind of thing that wastes an hour otherwise.

### The trust store, which is not optional

```bash
./scripts/glassfish-trust.sh
asadmin stop-domain domain1 && asadmin start-domain domain1
```

GlassFish has its own trust store and does not use the system one. Its domain
starts with

```
-Djavax.net.ssl.trustStore=${com.sun.aas.instanceRoot}/config/cacerts.jks
```

set as a JVM option, so every TLS connection the server makes is validated
against that file rather than against the JDK's. The JDK's ships with the whole
Mozilla root programme; GlassFish's ships with two entries, its own instance
certificate and `s1as`.

Without this, the reverse geocode to Nominatim fails with `PKIX path building
failed`, which the application presents as the map quietly refusing to place a
trip rather than as an error.

The domain **must be restarted afterwards**, and this is not a formality: the
trust store is read when the JVM's SSL context is first built, and once built
it is cached for the life of the process. Changing the file underneath a
running server does nothing until it restarts.

```bash
asadmin stop-domain domain1 && asadmin start-domain domain1
```

Run this on **every** deployment. It is in the short version at the end for
that reason: it is the step most likely to be skipped and the one whose
symptom least resembles its cause.

---

## 7. The reverse proxy and a certificate

GlassFish stays on 8080 behind nginx. nginx terminates TLS, which means the
certificate is renewed by `certbot` without GlassFish ever knowing.

```nginx
# /etc/nginx/sites-available/tripmate

# A keepalive pool to GlassFish, rather than a fresh TCP connection per request.
# Without this every proxied request pays a handshake to a server on the same
# machine, which is pure waste on a small host.
upstream tripmate {
    server 127.0.0.1:8080;
    keepalive 16;
}

server {
    listen 80;
    server_name tripmate.example.com;

    # Compression. This is the cheapest large win on the page weight: the
    # stylesheet is 266 KB of mostly comments before it is minified, the JSF
    # responses are HTML full of repeated markup, and the map page inlines
    # stored GeoJSON. None of it is compressed anywhere else in the stack.
    #
    # `text/html` is always compressed by nginx and does not belong in the list.
    # `application/json` matters because PrimeFaces talks in it.
    gzip on;
    gzip_vary on;
    gzip_min_length 1024;
    gzip_proxied any;
    gzip_comp_level 5;
    gzip_types
        text/plain text/css text/xml
        application/javascript application/json
        application/xml image/svg+xml;

    location / {
        proxy_pass http://tripmate;
        proxy_http_version 1.1;
        proxy_set_header Connection        "";
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host  $host;

        # The request thread pool behind this is deliberately small, so a
        # request that waits a little is normal and should not be turned into a
        # gateway error while the server is still working. The JVM also pauses
        # for a full collection after a redeploy, and a proxy timeout that is
        # tight enough to catch that reports it as the site being down.
        proxy_connect_timeout 5s;
        proxy_read_timeout    60s;
    }

    # Versioned Faces resources, which PrimeFaces and Mojarra serve from
    # /jakarta.faces.resource/. The URL carries a build version, so a cached
    # copy is never stale, and there is no reason for a returning reader to
    # re-fetch any of it. Without this every page load revalidates the theme
    # CSS, PrimeIcons and the component scripts.
    #
    # The max-age is repeated inside the header rather than left to `expires`,
    # because add_header replaces the Cache-Control that `expires` generates
    # instead of adding to it. Writing only "public, immutable" here would drop
    # the max-age entirely and, with no max-age and no Expires to fall back on,
    # leave the browser revalidating on every request.
    location /jakarta.faces.resource/ {
        proxy_pass http://tripmate;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        expires 30d;
        add_header Cache-Control "public, max-age=2592000, immutable";
    }

    # Leaflet, served from the application but not versioned in its URL, so it
    # gets a real expiry rather than a promise of immutability.
    location ~* /resources/vendor/ {
        proxy_pass http://tripmate;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        expires 7d;
        add_header Cache-Control "public, max-age=604800";
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/tripmate /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d tripmate.example.com
```

Then tell the application a proxy is really there:

```
# .env
TM_TRUST_PROXY=true
TM_APP_BASE_URL=https://tripmate.example.com
```

**Both of these matter and neither is cosmetic.** The application writes
absolute addresses of its own. The calendar subscribe URL that goes in an
email, and the sign-in link in a password reset. It refuses to believe
`X-Forwarded-*` until you say a proxy is in front, which is deliberate: any
caller can send those headers, so believing them by default would let a request
decide what hostname the application names. And the base URL cannot be derived
from a request at all in the places that need it most, because mail is composed
on another thread after the request that caused it has been answered.

They are written into the domain by **two different scripts**, which is worth
knowing because changing the wrong one looks like the setting being ignored:

| Setting | Written by | Read by |
|---|---|---|
| `TM_TRUST_PROXY` | `scripts/glassfish-setup.sh` | `SiteBean`, per request |
| `TM_APP_BASE_URL` | `scripts/glassfish-mail.sh` | `MailService`, as `tripmate.baseUrl` |

So after editing `.env`, re-run whichever one you changed, and restart the
domain. `TM_APP_BASE_URL` reaching the application through the mail resource
rather than as a property of its own is not obvious, and a password reset email
with a `localhost` link in it is how you find out.

---

## 8. The Google Calendar connection

Three things change from a local deployment, and the third one is the one that
catches people.

**The redirect URI.** Add this to the OAuth client in Google Cloud, and remove
the `localhost` one:

```
https://tripmate.example.com/calendar/google/callback
```

**The consent screen's Authorized domains** takes the **registrable domain**,
not the subdomain:

```
example.com
```

A subdomain is not accepted there, and it does not need to be: once the parent
domain is listed and verified, any subdomain of it may appear in a redirect
URI. Verification is done in Google Search Console, and if you already own the
domain this is a DNS TXT record and a few minutes.

**Publishing the consent screen.** While it is in Testing, Google expires
refresh tokens after seven days, so every connected calendar stops syncing
weekly. For a deployment that is meant to keep working, submit it for
verification.

The rest is unchanged: `TM_GOOGLE_CLIENT_ID`, `TM_GOOGLE_CLIENT_SECRET` and
`TM_CREDENTIALS_KEY` in `.env`. **Keep `.env` at mode 600**, because `TM_CREDENTIALS_KEY`
is what encrypts the stored refresh tokens, and while losing it breaks nothing,
it makes every existing connection unreadable and everybody has to reconnect.

Generate a fresh key for the deployment rather than reusing a development one:

```bash
openssl rand -base64 32
```

---

## 9. Starting on boot

GlassFish does not come back by itself after a reboot.

The application's own tool for this is `asadmin create-service`, but on Linux
it writes a **System-V init script** to `/etc/init.d/GlassFish_domain1` rather
than a systemd unit, and runs as root. Ubuntu 24.04 still runs those through
`systemd-sysv-generator`, so it works, with deprecation warnings and a
behaviour that is not quite the same as a native unit.

A native unit is short enough that it is usually the better choice on a modern
Ubuntu:

```ini
# /etc/systemd/system/glassfish.service
[Unit]
Description=GlassFish 7 domain1
After=network.target postgresql.service
Wants=postgresql.service

[Service]
# `asadmin start-domain` starts a daemon and exits, so this is a oneshot with
# nothing left in the foreground rather than a forking service with a pid file.
Type=oneshot
RemainAfterExit=yes
User=ubuntu
Environment=JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
Environment=GLASSFISH_HOME=/opt/glassfish
ExecStart=/opt/glassfish/bin/asadmin start-domain domain1
ExecStop=/opt/glassfish/bin/asadmin stop-domain domain1
# Generous, because on a small machine the JVM takes a while to come up.
TimeoutStartSec=180

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now glassfish
systemctl status glassfish
```

`User=ubuntu` matters: the domain directory and the trust store script both
write as the owning user, and a service running as root leaves files the
deployment scripts can no longer touch.

PostgreSQL and nginx are enabled by their own packages and need nothing here.

If you would rather use the built-in one:

```bash
sudo /opt/glassfish/bin/asadmin create-service --serviceuser ubuntu domain1
sudo /etc/init.d/GlassFish_domain1 start
```

---

## 10. Living with it

### Is it healthy

```bash
free -m                                  # watch the available column, not free
sudo systemctl status glassfish postgresql nginx
tail -f /opt/glassfish/glassfish/domains/domain1/logs/server.log
```

On a 1 GB machine, `available` sitting under about 100 MB with swap in use is
normal and survivable; the machine being unresponsive is not, and means
something else needs to give.

The other thing worth looking at when it feels slow is the request thread pool,
because that is the ceiling and a saturated one looks like slowness rather than
like an error:

```bash
asadmin get 'configs.config.server-config.thread-pools.thread-pool.http-thread-pool.*'
```

Threads named `http-listener-1(N)` in a thread dump are the workers. If they are
all busy while the machine is idle, the requests are blocking on something
outside the server (SMTP, a reverse geocode, Google) and the answer is a bigger
pool, not a faster one. If they are busy and the CPU is pinned, the answer is
one of the other levers. `asadmin generate-jvm-report --type=thread` prints the
dump without needing `jstack` on the classpath.

### Updating the deployment

```bash
# locally
./scripts/build.sh
scp tripmate-ear/target/tripmate-ear-1.0.0-SNAPSHOT.ear ubuntu@host:~/tripmate/tripmate-ear/target/

# on the server
asadmin redeploy --name tripmate ~/tripmate/tripmate-ear/target/tripmate-ear-1.0.0-SNAPSHOT.ear
```

A redeploy invalidates every session, so everybody is signed out. On this
machine it also takes longer than you would like, because the JVM is fighting
for a fraction of a core.

Because building elsewhere and copying the EAR is the normal path, note that
`scripts/live.sh` copies individual web files into the exploded directory and
is a development shortcut. It works on a server, but the next redeploy undoes
it silently.

### Backing up

The whole database, which is small:

```bash
pg_dump -U tripmate -h localhost tripmate | gzip > tripmate-$(date +%F).sql.gz
```

Worth copying off the machine. `.env` is worth backing up too, for
`TM_CREDENTIALS_KEY` alone.

---

## 11. The alternative, if this is only for a demonstration

None of the above is necessary to show somebody the application working. A
tunnel from the machine you develop on gives a public HTTPS address in about a
minute:

```bash
./scripts/deploy.sh
ngrok http 8080
```

The address is temporary and dies with the tunnel, and it is not a deployment
in any sense that survives you closing the laptop. But for a viva, a
walkthrough, or a supervisor checking that it runs, it does the same job as a
server without any of section 4.

The one thing it cannot do is satisfy Google's OAuth verification, which needs
a stable verified domain. If the calendar connection is part of what is being
assessed, use the server.

---

## 12. The short version

For a 1 GB `VM.Standard.E2.1.Micro` and a subdomain you already own:

1. `apt install` OpenJDK 17, PostgreSQL, nginx, certbot.
2. GlassFish 7 unzipped to `/opt/glassfish`, `GLASSFISH_HOME` exported.
3. **2 GB of swap**, and swappiness down to 10.
4. **`./scripts/glassfish-tune.sh`**, which sets the JVM bounds, the log
   rotation, the JDBC pool and, most importantly, raises the request thread
   pool off its default of five. Restart the domain afterwards.
5. PostgreSQL: `shared_buffers` 64 MB, `max_connections` 20.
6. `bootstrap.sql`, then `load.sh`.
7. Build locally **without `-Pdev`**, copy the EAR, run `glassfish-setup.sh`,
   `glassfish-tune.sh`, `glassfish-jms.sh` and `glassfish-mail.sh`, then deploy
   it.
8. **`glassfish-trust.sh`, then restart the domain.**
9. nginx in front, with the gzip and caching block above, and `certbot`. Set
   `TM_TRUST_PROXY=true` in `.env` and re-run `glassfish-setup.sh`; set
   `TM_APP_BASE_URL` and re-run `glassfish-mail.sh`.
10. Redirect URI and Authorized domain updated in Google Cloud.

Steps 3, 4 and 5 are the ones that decide whether it runs at all. Step 4 is the
one that decides how many people it can serve at once, and its default is wrong
enough that skipping it is the single worst thing you can do to a deployment.
Steps 7 and 9 are the ones with a script for each half, and running the wrong
half looks exactly like the setting being ignored. Everything else is ordinary.
