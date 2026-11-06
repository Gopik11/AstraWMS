#!/usr/bin/env bash
# Read-only forensic audit collector for the whole VPS (all projects, not only AstraWMS).
#
#   sudo bash vps-audit.sh            # writes /root/vps-audit-<stamp>/ and /root/vps-audit-<stamp>.tar.gz
#   sudo bash vps-audit.sh --quick    # skips the slow parts (filesystem-wide du/find, 60 s sampling)
#   nohup bash vps-audit.sh > /root/vps-audit.log 2>&1 &   # survives an SSH disconnect; follow with tail -f
#
# It changes nothing: no prune, no restart, no writes outside its own output directory. Secrets are redacted:
# environment values of containers and .env files are never written, only variable NAMES. Review the output before
# sharing it anyway (hostnames, IPs, database and user names remain).
#
# Every section writes NN-name.txt; paste the files back one section at a time (or the whole tarball's contents).
set -uo pipefail

QUICK=0; [[ "${1:-}" == "--quick" ]] && QUICK=1
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${OUT_DIR:-/root}/vps-audit-$STAMP"
mkdir -p "$OUT"; chmod 700 "$OUT"
T="timeout 120"
have() { command -v "$1" >/dev/null 2>&1; }

# run "<section file>" "<title>" <command...>  — appends a titled block, never aborts the script
run() {
  local f="$OUT/$1"; shift; local title="$1"; shift
  printf '[%s] %s: %s\n' "$(date +%T)" "${f##*/}" "$title" >&2   # progress (also keeps SSH sessions from idling out)
  { echo; echo "===== $title"; echo "\$ $*"; $T bash -c "$*" 2>&1; echo "[exit $?]"; } >>"$f"
}
# KEY=value and YAML "KEY: value" are redacted; ${VAR} references are kept (they reveal no secret).
redact() {
  sed -E -e 's/((PASSWORD|PASSWD|SECRET|TOKEN|APIKEY|API_KEY|ACCESS_KEY|PRIVATE_KEY|PASSPHRASE|CREDENTIAL|DSN)[A-Z0-9_]*)=[^$ ",][^ ",]*/\1=<redacted>/Ig' \
         -e 's/((PASSWORD|PASSWD|SECRET|TOKEN|APIKEY|API_KEY|ACCESS_KEY|PRIVATE_KEY|PASSPHRASE|CREDENTIAL|DSN)[A-Z0-9_]*"?[[:space:]]*:[[:space:]]+)[^$ #][^ #]*/\1<redacted>/Ig' \
         -e 's#(://[^:/@ ]+:)[^@/ ]+@#\1<redacted>@#g'
}

echo "Writing to $OUT"

# ---------------------------------------------------------------- 01 system identity & OS health
S=01-system.txt
run $S "OS"                 "cat /etc/os-release; uname -a; hostnamectl 2>/dev/null"
run $S "Uptime / load"      "uptime; cat /proc/loadavg; nproc; lscpu | head -25"
run $S "Virtualisation"     "systemd-detect-virt 2>/dev/null; cat /sys/class/dmi/id/product_name 2>/dev/null"
run $S "Time sync"          "timedatectl 2>/dev/null"
run $S "Pending updates"    "(apt list --upgradable 2>/dev/null | head -80) || (dnf check-update 2>/dev/null | head -80)"
run $S "Reboot required"    "ls -l /var/run/reboot-required* 2>/dev/null || echo none"
run $S "Kernel tunables"    "sysctl vm.swappiness vm.overcommit_memory vm.vfs_cache_pressure vm.dirty_ratio vm.max_map_count fs.file-max net.core.somaxconn 2>/dev/null"
run $S "Failed units"       "systemctl --failed --no-pager"
run $S "Kernel errors/OOM"  "dmesg -T 2>/dev/null | grep -iE 'oom|killed process|out of memory|i/o error|segfault|ext4-fs error|blocked for more' | tail -60"
run $S "Journal OOM 7d"     "journalctl --since '7 days ago' --no-pager 2>/dev/null | grep -iE 'oom-kill|out of memory|Killed process' | tail -60"
run $S "Journal errors 24h" "journalctl -p err --since '24 hours ago' --no-pager 2>/dev/null | tail -100"
run $S "Last logins/reboots" "last -n 20 2>/dev/null; last -x reboot shutdown | head -10"

# ---------------------------------------------------------------- 02 CPU / memory / swap / pressure
S=02-cpu-memory.txt
run $S "Memory"             "free -m; echo; cat /proc/meminfo | head -25"
run $S "Swap"               "swapon --show; cat /proc/swaps"
run $S "Pressure (PSI)"     "for r in cpu memory io; do echo \"-- \$r\"; cat /proc/pressure/\$r 2>/dev/null; done"
run $S "Top CPU processes"  "ps -eo pid,ppid,user,%cpu,%mem,rss,etime,stat,comm --sort=-%cpu | head -40"
run $S "Top RSS processes"  "ps -eo pid,ppid,user,%cpu,%mem,rss,etime,stat,comm --sort=-rss | head -40"
run $S "Per-process swap"   "for f in /proc/[0-9]*/status; do awk '/^Name/{n=\$2}/^Pid/{p=\$2}/^VmSwap/{if(\$2>10240)print \$2\" kB\",p,n}' \$f 2>/dev/null; done | sort -rn | head -30"
run $S "Zombie / D-state"   "ps -eo pid,ppid,stat,etime,comm | awk 'NR==1||\$3~/^[ZD]/'"
run $S "Thread count top"   "ps -eo pid,nlwp,comm --sort=-nlwp | head -20"
run $S "Java processes"     "ps -eo pid,rss,etime,args --sort=-rss | grep -E '[j]ava' | cut -c1-300 | head -40"
if have vmstat; then run $S "vmstat 1x10" "vmstat -w 1 10"; fi
if [[ $QUICK -eq 0 ]] && have mpstat; then run $S "mpstat 5x6" "mpstat -P ALL 5 6 | tail -40"; fi
if have sar; then run $S "sar history (CPU/mem today)" "sar -u | tail -30; sar -r | tail -30"; fi

# ---------------------------------------------------------------- 03 disk & I/O
S=03-disk-io.txt
run $S "Filesystems"        "df -hT -x tmpfs -x devtmpfs -x overlay -x squashfs"
run $S "Inodes"             "df -i -x tmpfs -x devtmpfs -x overlay -x squashfs"
run $S "Block devices"      "lsblk -o NAME,SIZE,TYPE,FSTYPE,MOUNTPOINT,ROTA"
run $S "Mount options"      "findmnt -t ext4,xfs,btrfs -o TARGET,SOURCE,FSTYPE,OPTIONS"
if have iostat; then run $S "iostat 5x4" "iostat -xz 5 4"; else run $S "diskstats" "cat /proc/diskstats"; fi
if have iotop; then run $S "iotop (accumulated 10s)" "iotop -boa -n 5 -d 2 | grep -vE ' 0.00 B +0.00 B' | tail -40"; fi
run $S "Deleted-but-open files" "lsof -nP +L1 2>/dev/null | awk 'NR==1||\$7>10485760' | head -40"

# ---------------------------------------------------------------- 04 storage breakdown
S=04-storage.txt
run $S "Top-level usage"    "du -xh --max-depth=1 / 2>/dev/null | sort -rh | head -25"
run $S "/var breakdown"     "du -xh --max-depth=2 /var 2>/dev/null | sort -rh | head -30"
run $S "/opt /srv /home /root" "du -xh --max-depth=2 /opt /srv /home /root 2>/dev/null | sort -rh | head -40"
run $S "Docker root"        "du -sh /var/lib/docker/* 2>/dev/null | sort -rh"
run $S "Journal size"       "journalctl --disk-usage; grep -E '^(SystemMaxUse|SystemKeepFree|MaxRetentionSec)' /etc/systemd/journald.conf 2>/dev/null"
run $S "/var/log largest"   "find /var/log -type f -printf '%s\t%TY-%Tm-%Td\t%p\n' 2>/dev/null | sort -rn | head -30 | numfmt --field=1 --to=iec"
run $S "Temp/caches"        "du -sh /tmp /var/tmp /var/cache/* /root/.cache /root/.m2 /root/.npm /home/*/.cache /home/*/.m2 /home/*/.npm 2>/dev/null | sort -rh | head -30"
run $S "Package cache"      "du -sh /var/cache/apt /var/cache/dnf /var/cache/yum 2>/dev/null; ls /var/crash 2>/dev/null"
run $S "Old kernels"        "dpkg -l 'linux-image-*' 2>/dev/null | awk '/^ii/{print \$2,\$3}'; uname -r"
run $S "Snap"               "snap list --all 2>/dev/null | awk '/disabled/'; du -sh /var/lib/snapd 2>/dev/null"
if [[ $QUICK -eq 0 ]]; then
  run $S "Largest files >200MB" "find / -xdev -type f -size +200M -printf '%s\t%TY-%Tm-%Td\t%p\n' 2>/dev/null | sort -rn | head -40 | numfmt --field=1 --to=iec"
  run $S "Archives/dumps/backups anywhere" "find / -xdev -type f \\( -name '*.sql' -o -name '*.sql.gz' -o -name '*.dump' -o -name '*.tar' -o -name '*.tar.gz' -o -name '*.tgz' -o -name '*.zip' -o -name '*.bak' -o -name '*.enc' \\) -size +10M -printf '%s\t%TY-%Tm-%Td\t%p\n' 2>/dev/null | sort -rn | head -60 | numfmt --field=1 --to=iec"
  run $S "Core dumps / heap dumps" "find / -xdev -type f \\( -name 'core' -o -name 'core.[0-9]*' -o -name '*.hprof' \\) -printf '%s\t%p\n' 2>/dev/null | head -20"
  run $S "node_modules / target dirs" "find / -xdev -type d \\( -name node_modules -o -name target -o -name .gradle \\) -prune -print 2>/dev/null | head -60 | xargs -r du -sh 2>/dev/null | sort -rh | head -30"
fi

# ---------------------------------------------------------------- 05 network
S=05-network.txt
run $S "Listening sockets"  "ss -tulpn"
run $S "Public-bound ports" "ss -tulpnH | awk '\$5 !~ /^(127\\.|\\[::1\\]|localhost)/'"
run $S "Connection summary" "ss -s; ss -tanH | awk '{print \$1}' | sort | uniq -c"
run $S "Top remote peers"   "ss -tnH state established | awk '{print \$4}' | sed -E 's/:[0-9]+\$//' | sort | uniq -c | sort -rn | head -20"
run $S "Interface counters" "ip -s link"
if have vnstat; then run $S "vnstat" "vnstat; vnstat -d | tail -15"; fi
run $S "Firewall"           "ufw status verbose 2>/dev/null; nft list ruleset 2>/dev/null | head -150 || iptables -S | head -150"
run $S "Docker iptables"    "iptables -t nat -S DOCKER 2>/dev/null | head -60"
run $S "fail2ban"           "fail2ban-client status 2>/dev/null"
run $S "SSH config"         "sshd -T 2>/dev/null | grep -iE '^(port|permitrootlogin|passwordauthentication|pubkeyauthentication|maxauthtries|x11forwarding)'"
run $S "SSH auth failures 24h" "journalctl -u ssh -u sshd --since '24 hours ago' --no-pager 2>/dev/null | grep -ciE 'failed|invalid'"

# ---------------------------------------------------------------- 06 services, cron, timers
S=06-services-cron.txt
run $S "Running services"   "systemctl list-units --type=service --state=running --no-pager"
run $S "Enabled services"   "systemctl list-unit-files --state=enabled --no-pager"
run $S "Timers"             "systemctl list-timers --all --no-pager"
run $S "System crontabs"    "cat /etc/crontab; for f in /etc/cron.d/*; do echo \"--- \$f\"; cat \"\$f\"; done"
run $S "Periodic cron dirs" "ls -la /etc/cron.hourly /etc/cron.daily /etc/cron.weekly /etc/cron.monthly"
run $S "User crontabs"      "for u in \$(cut -d: -f1 /etc/passwd); do c=\$(crontab -l -u \$u 2>/dev/null | grep -v '^#' | grep .); [ -n \"\$c\" ] && echo \"--- \$u\" && echo \"\$c\"; done"
run $S "Cron runs 48h"      "journalctl -u cron -u crond --since '48 hours ago' --no-pager 2>/dev/null | grep -E 'CMD|error|fail' | tail -80"
run $S "Web server"         "nginx -v 2>&1; nginx -T 2>/dev/null | grep -E '^\\s*(server_name|listen|proxy_pass|root|# configuration file)' | head -150"
run $S "Certificates"       "certbot certificates 2>/dev/null"
run $S "Non-docker daemons" "ps -eo pid,user,rss,etime,args --sort=-rss | grep -vE 'containerd-shim|\\[' | awk '\$3>51200' | cut -c1-200 | head -40"

# ---------------------------------------------------------------- 07 docker engine & inventory
S=07-docker-inventory.txt
if have docker; then
  run $S "Engine"           "docker version --format '{{.Server.Version}}'; docker info 2>/dev/null | grep -vE '^\\s*(Registry|Insecure|127\\.)'"
  run $S "daemon.json"      "cat /etc/docker/daemon.json 2>/dev/null || echo 'no daemon.json (no default log rotation!)'"
  run $S "System df"        "docker system df -v"
  run $S "All containers"   "docker ps -a --size --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Size}}\t{{.Ports}}\t{{.Label \"com.docker.compose.project\"}}'"
  run $S "Stopped/exited"   "docker ps -a --filter status=exited --filter status=created --filter status=dead --format '{{.Names}}\t{{.Image}}\t{{.Status}}'"
  run $S "Restarting/unhealthy" "docker ps -a --filter status=restarting --format '{{.Names}} {{.Status}}'; docker ps --filter health=unhealthy --format '{{.Names}} {{.Status}}'"
  run $S "Compose projects" "docker compose ls -a 2>/dev/null"
  run $S "Images"           "docker images --format 'table {{.Repository}}:{{.Tag}}\t{{.ID}}\t{{.Size}}\t{{.CreatedSince}}' | sort -k1"
  run $S "Dangling images"  "docker images -f dangling=true -q | wc -l; docker images -f dangling=true --format '{{.Size}}'"
  run $S "Images not used by any container" "comm -23 <(docker images -q --no-trunc | sort -u) <(docker ps -aq | xargs -r docker inspect --format '{{.Image}}' | sort -u) | xargs -r docker inspect --format '{{index .RepoTags 0}} {{.Size}} {{.Created}}' 2>/dev/null | awk '{printf \"%s %.0fMB %s\\n\",\$1,\$2/1048576,\$3}'"
  run $S "Volumes"          "docker volume ls --format '{{.Name}}\t{{.Driver}}\t{{.Label \"com.docker.compose.project\"}}'"
  run $S "Dangling volumes" "docker volume ls -f dangling=true -q | while read v; do echo \"\$v \$(du -sh \$(docker volume inspect -f '{{.Mountpoint}}' \$v) 2>/dev/null | cut -f1)\"; done"
  run $S "Volume sizes"     "for v in \$(docker volume ls -q); do echo \"\$(du -sh \$(docker volume inspect -f '{{.Mountpoint}}' \$v) 2>/dev/null | cut -f1) \$v\"; done | sort -rh | head -50"
  run $S "Networks"         "docker network ls; for n in \$(docker network ls -q); do echo \"\$(docker network inspect -f '{{.Name}} containers={{len .Containers}} subnet={{range .IPAM.Config}}{{.Subnet}}{{end}}' \$n)\"; done"
  run $S "Build cache"      "docker buildx du 2>/dev/null | tail -5 || docker builder du 2>/dev/null | tail -5"
  run $S "Container logs on disk" "find /var/lib/docker/containers -name '*-json.log*' -printf '%s\t%p\n' 2>/dev/null | sort -rn | head -30 | while IFS=\$'\\t' read s p; do id=\$(basename \$(dirname \$p)); echo \"\$(numfmt --to=iec \$s) \$(docker inspect -f '{{.Name}}' \$id 2>/dev/null) \$p\"; done"
fi

# ---------------------------------------------------------------- 08 docker runtime usage
S=08-docker-usage.txt
if have docker; then
  run $S "stats snapshot"   "docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}\t{{.BlockIO}}\t{{.PIDs}}'"
  if [[ $QUICK -eq 0 ]]; then
    # 6 samples over ~60 s to separate idle baseline from spikes
    run $S "stats 6 samples / 10 s" "for i in 1 2 3 4 5 6; do date +%T; docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}'; sleep 10; done"
  fi
  run $S "Restart counts, OOM, uptime" "docker ps -aq | xargs -r docker inspect --format '{{.Name}} restarts={{.RestartCount}} oom={{.State.OOMKilled}} status={{.State.Status}} started={{.State.StartedAt}} finished={{.State.FinishedAt}} exit={{.State.ExitCode}}'"
  run $S "Top processes per container" "for c in \$(docker ps --format '{{.Names}}'); do echo \"--- \$c\"; docker top \$c -eo pid,rss,pcpu,etime,args 2>/dev/null | cut -c1-180 | head -6; done"
  run $S "Zombies inside containers"  "for c in \$(docker ps -q); do n=\$(docker top \$c -eo stat 2>/dev/null | grep -c '^Z'); [ \"\$n\" -gt 0 ] && echo \"\$(docker inspect -f '{{.Name}}' \$c) zombies=\$n\"; done; echo done"
  run $S "Last log activity (age of newest line, lines/24h)" "for c in \$(docker ps --format '{{.Names}}'); do l=\$(docker logs --since 24h \$c 2>&1 | wc -l); last=\$(docker logs -t --tail 1 \$c 2>&1 | cut -c1-30); echo \"\$c lines24h=\$l last=\$last\"; done"
  run $S "Error rate in logs 24h" "for c in \$(docker ps --format '{{.Names}}'); do e=\$(docker logs --since 24h \$c 2>&1 | grep -ciE 'error|exception|fatal|panic'); [ \"\$e\" -gt 0 ] && echo \"\$c errors24h=\$e\"; done; echo done"
  run $S "Inbound traffic per container (ESTABLISHED conns)" "for c in \$(docker ps -q); do pid=\$(docker inspect -f '{{.State.Pid}}' \$c); n=\$(nsenter -t \$pid -n ss -tnH state established 2>/dev/null | wc -l); echo \"\$(docker inspect -f '{{.Name}}' \$c) established=\$n\"; done"
fi

# ---------------------------------------------------------------- 09 docker configuration (limits, health, restart, security)
S=09-docker-config.txt
if have docker; then
  run $S "Limits / restart / health / logging per container" "docker ps -aq | xargs -r docker inspect --format '{{.Name}} | mem={{.HostConfig.Memory}} memswap={{.HostConfig.MemorySwap}} reservation={{.HostConfig.MemoryReservation}} nanocpus={{.HostConfig.NanoCpus}} cpushares={{.HostConfig.CpuShares}} pids={{.HostConfig.PidsLimit}} | restart={{.HostConfig.RestartPolicy.Name}}:{{.HostConfig.RestartPolicy.MaximumRetryCount}} | health={{if .Config.Healthcheck}}{{.Config.Healthcheck.Test}}{{else}}NONE{{end}} {{if .State.Health}}{{.State.Health.Status}}{{end}} | log={{.HostConfig.LogConfig.Type}} {{.HostConfig.LogConfig.Config}}'"
  run $S "Security posture per container" "docker ps -aq | xargs -r docker inspect --format '{{.Name}} | user={{if .Config.User}}{{.Config.User}}{{else}}ROOT{{end}} privileged={{.HostConfig.Privileged}} netmode={{.HostConfig.NetworkMode}} pidmode={{.HostConfig.PidMode}} capadd={{.HostConfig.CapAdd}} ro_rootfs={{.HostConfig.ReadonlyRootfs}} secopt={{.HostConfig.SecurityOpt}}'"
  run $S "Published ports (0.0.0.0 = internet facing)" "docker ps --format '{{.Names}}\t{{.Ports}}' | grep -E '0\\.0\\.0\\.0|:::' || echo none"
  run $S "Sensitive mounts" "docker ps -aq | xargs -r docker inspect --format '{{.Name}} {{range .Mounts}}{{.Source}}->{{.Destination}}({{.Mode}}) {{end}}' | grep -E 'docker.sock|-> ?/host|/etc|/root|/proc|/sys|^/ ' || echo none"
  run $S "Mounts per container" "docker ps -aq | xargs -r docker inspect --format '{{.Name}} {{range .Mounts}}[{{.Type}} {{.Name}}{{.Source}} -> {{.Destination}}] {{end}}'"
  run $S "Image ages & tags (latest = unpinned)" "docker ps -a --format '{{.Names}} {{.Image}}' | while read n i; do echo \"\$n \$i created=\$(docker image inspect -f '{{.Created}}' \$i 2>/dev/null | cut -c1-10)\"; done"
  run $S "Env variable NAMES only (values redacted)" "docker ps -q | while read c; do echo \"--- \$(docker inspect -f '{{.Name}}' \$c)\"; docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' \$c | cut -d= -f1 | tr '\\n' ' '; echo; done"
  run $S "JVM options per container" "docker ps -q | while read c; do echo \"\$(docker inspect -f '{{.Name}}' \$c): \$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' \$c | grep -E '^(JAVA_TOOL_OPTIONS|JAVA_OPTS|JAVA_OPTS_KC_HEAP|_JAVA_OPTIONS)=' | tr '\\n' ' ')\"; done"
  run $S "Compose files on host" "find / -xdev \\( -name 'docker-compose*.y*ml' -o -name 'compose*.y*ml' \\) -not -path '*/node_modules/*' -not -path '/var/lib/docker/*' -printf '%TY-%Tm-%Td %p\n' 2>/dev/null"
  run $S "Compose working dirs of running projects" "docker ps -aq | xargs -r docker inspect --format '{{index .Config.Labels \"com.docker.compose.project\"}} {{index .Config.Labels \"com.docker.compose.project.working_dir\"}} {{index .Config.Labels \"com.docker.compose.project.config_files\"}}' | sort -u"
fi
# Compose file contents with secrets redacted (one file each so they can be shared individually)
mkdir -p "$OUT/compose"
if have docker; then
  docker ps -aq | xargs -r docker inspect --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}' 2>/dev/null \
    | tr ',' '\n' | sort -u | grep . | while read -r cf; do
      [[ -f "$cf" ]] || continue
      safe="$(echo "$cf" | tr '/' '_')"
      redact <"$cf" >"$OUT/compose/$safe"
      envf="$(dirname "$cf")/.env"
      [[ -f "$envf" ]] && cut -d= -f1 "$envf" | grep -vE '^\s*(#|$)' >"$OUT/compose/${safe}.env-names"
    done
fi

# ---------------------------------------------------------------- 10 data services (PostgreSQL / MySQL / Redis / Mongo / Kafka / ES)
S=10-databases.txt
if have docker; then
  for c in $(docker ps --format '{{.Names}} {{.Image}}' | awk 'tolower($2) ~ /postgres|postgis|timescale/ {print $1}'); do
    U=$(docker exec "$c" sh -c 'echo ${POSTGRES_USER:-postgres}' 2>/dev/null); P="docker exec $c psql -U ${U:-postgres} -d postgres -X -P pager=off"
    run $S "[$c] version & settings" "$P -c 'select version();' -c \"select name,setting,unit,source from pg_settings where name in ('shared_buffers','work_mem','maintenance_work_mem','effective_cache_size','max_connections','max_wal_size','wal_level','checkpoint_timeout','autovacuum','random_page_cost','max_parallel_workers','log_min_duration_statement','idle_in_transaction_session_timeout','shared_preload_libraries') order by 1;\""
    run $S "[$c] databases size & activity" "$P -c \"select d.datname, pg_size_pretty(pg_database_size(d.datname)) size, s.numbackends conns, s.xact_commit commits, s.xact_rollback rollbacks, s.tup_inserted+s.tup_updated+s.tup_deleted writes, s.blks_hit*100/nullif(s.blks_hit+s.blks_read,0) hit_pct, s.temp_bytes, s.deadlocks, s.stats_reset from pg_database d join pg_stat_database s using(datname) where not d.datistemplate order by pg_database_size(d.datname) desc;\""
    run $S "[$c] connections" "$P -c \"select datname,usename,application_name,state,count(*),max(now()-state_change) oldest from pg_stat_activity where backend_type='client backend' group by 1,2,3,4 order by 5 desc;\""
    run $S "[$c] long-running / idle-in-tx" "$P -c \"select pid,datname,usename,state,now()-xact_start xact_age,now()-query_start q_age,left(query,120) from pg_stat_activity where state<>'idle' and backend_type='client backend' and (now()-coalesce(xact_start,query_start))>interval '1 minute' order by 5 desc nulls last;\""
    run $S "[$c] locks waiting" "$P -c \"select count(*) waiting from pg_locks where not granted;\""
    run $S "[$c] replication slots / WAL" "$P -c \"select slot_name,active,pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(),restart_lsn)) retained from pg_replication_slots;\" -c \"select pg_size_pretty(sum(size)) wal_dir from pg_ls_waldir();\""
    run $S "[$c] extensions" "$P -c \"select extname,extversion from pg_extension;\""
    for db in $(docker exec "$c" psql -U "${U:-postgres}" -d postgres -XAtc "select datname from pg_database where not datistemplate and datallowconn"); do
      D="docker exec $c psql -U ${U:-postgres} -d $db -X -P pager=off"
      run $S "[$c/$db] largest tables + bloat signals" "$D -c \"select schemaname||'.'||relname tbl, pg_size_pretty(pg_total_relation_size(relid)) total, n_live_tup live, n_dead_tup dead, round(n_dead_tup*100.0/nullif(n_live_tup+n_dead_tup,0),1) dead_pct, seq_scan, idx_scan, last_autovacuum, last_autoanalyze from pg_stat_user_tables order by pg_total_relation_size(relid) desc limit 15;\""
      run $S "[$c/$db] unused indexes" "$D -c \"select schemaname||'.'||indexrelname idx, relname tbl, pg_size_pretty(pg_relation_size(indexrelid)) size, idx_scan from pg_stat_user_indexes join pg_index using(indexrelid) where idx_scan=0 and not indisunique and not indisprimary order by pg_relation_size(indexrelid) desc limit 15;\""
      run $S "[$c/$db] seq-scan heavy tables" "$D -c \"select relname, seq_scan, seq_tup_read, idx_scan, n_live_tup from pg_stat_user_tables where seq_scan>100 and n_live_tup>10000 order by seq_tup_read desc limit 10;\""
      run $S "[$c/$db] xid age" "$D -c \"select relname, age(relfrozenxid) from pg_class where relkind='r' order by 2 desc limit 5;\""
      run $S "[$c/$db] top queries (pg_stat_statements)" "$D -c \"select calls, round(total_exec_time::numeric) total_ms, round(mean_exec_time::numeric,1) mean_ms, rows, left(regexp_replace(query,'\\s+',' ','g'),140) q from pg_stat_statements order by total_exec_time desc limit 10;\" 2>&1 | head -20"
    done
    run $S "[$c] data dir size" "docker exec $c sh -c 'du -sh \$PGDATA \$PGDATA/base \$PGDATA/pg_wal \$PGDATA/log 2>/dev/null'"
  done
  for c in $(docker ps --format '{{.Names}} {{.Image}}' | awk 'tolower($2) ~ /mysql|mariadb|percona/ {print $1}'); do
    M="docker exec $c sh -c 'mysql -uroot -p\"\${MYSQL_ROOT_PASSWORD:-\$MARIADB_ROOT_PASSWORD}\" -e \"\$0\"'"
    run $S "[$c] mysql schemas size" "$M \"select table_schema, round(sum(data_length+index_length)/1048576,1) mb, sum(table_rows) rows_est from information_schema.tables group by 1 order by 2 desc;\""
    run $S "[$c] mysql status" "$M \"show global status where Variable_name in ('Uptime','Threads_connected','Max_used_connections','Questions','Slow_queries','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests','Created_tmp_disk_tables');\""
    run $S "[$c] mysql config" "$M \"show variables where Variable_name in ('innodb_buffer_pool_size','max_connections','performance_schema','binlog_expire_logs_seconds','expire_logs_days','log_bin','innodb_log_file_size','tmp_table_size');\""
    run $S "[$c] mysql processlist" "$M \"select id,user,db,command,time,state,left(info,100) from information_schema.processlist where command<>'Sleep' or time>600 order by time desc limit 20;\""
    run $S "[$c] mysql binlogs" "$M \"show binary logs;\""
  done
  for c in $(docker ps --format '{{.Names}} {{.Image}}' | awk 'tolower($2) ~ /redis|valkey|keydb|dragonfly/ {print $1}'); do
    R="docker exec $c sh -c 'redis-cli \${REDIS_PASSWORD:+-a \$REDIS_PASSWORD} --no-auth-warning \"\$@\"' --"
    run $S "[$c] redis memory/stats/keyspace" "$R info memory | grep -E 'used_memory_human|used_memory_peak_human|maxmemory_human|maxmemory_policy|mem_fragmentation_ratio'; $R info stats | grep -E 'total_commands_processed|instantaneous_ops_per_sec|keyspace_(hits|misses)|evicted_keys|expired_keys'; $R info clients | grep connected_clients; $R info persistence | grep -E 'rdb_last_save_time|aof_enabled|rdb_last_bgsave_status'; $R info keyspace; $R config get save"
  done
  for c in $(docker ps --format '{{.Names}} {{.Image}}' | awk 'tolower($2) ~ /mongo/ {print $1}'); do
    run $S "[$c] mongo dbs" "docker exec $c sh -c 'mongosh --quiet --eval \"db.adminCommand({listDatabases:1}).databases.forEach(d=>print(d.name, (d.sizeOnDisk/1048576).toFixed(1)+\\\"MB\\\"))\"' 2>&1 | head -30"
  done
  for c in $(docker ps --format '{{.Names}} {{.Image}}' | awk 'tolower($2) ~ /elasticsearch|opensearch/ {print $1}'); do
    run $S "[$c] search cluster" "docker exec $c sh -c 'curl -s localhost:9200/_cat/indices?v\\&s=store.size:desc | head -20; curl -s localhost:9200/_cat/nodes?v\\&h=heap.percent,ram.percent,cpu'"
  done
  for c in $(docker ps --format '{{.Names}} {{.Image}}' | awk 'tolower($2) ~ /kafka|redpanda|rabbitmq/ {print $1}'); do
    run $S "[$c] broker data size" "docker exec $c sh -c 'du -sh /var/lib/kafka/data /var/lib/rabbitmq /var/lib/redpanda 2>/dev/null; ls /var/lib/kafka/data 2>/dev/null | wc -l'"
  done
fi
run $S "Host-installed databases" "systemctl list-units --type=service --no-pager | grep -iE 'postgres|mysql|mariadb|redis|mongo|elastic' || echo none"

# ---------------------------------------------------------------- 11 backups
S=11-backups.txt
run $S "Backup-related cron/timers" "grep -rilE 'backup|dump|rsync|rclone|restic|borg|duplicity' /etc/cron* /var/spool/cron 2>/dev/null | while read f; do echo \"--- \$f\"; grep -vE '^\\s*#' \"\$f\" | grep .; done; systemctl list-timers --all --no-pager | grep -iE 'backup|restic|borg|dump'"
run $S "Backup tools installed" "for t in restic borg rclone duplicity rsnapshot pgbackrest wal-g autorestic; do command -v \$t && \$t version 2>/dev/null | head -1; done; echo done"
run $S "Backup dirs (size, count, oldest, newest)" "for d in /opt/*/backups /opt/*/backup /srv/backup* /var/backups /root/backup* /home/*/backup* /backup*; do [ -d \"\$d\" ] || continue; echo \"--- \$d size=\$(du -sh \$d | cut -f1) files=\$(find \$d -type f | wc -l)\"; ls -lt --time-style=+%F_%T \$d | sed -n '2,4p'; echo ...; ls -lt --time-style=+%F_%T \$d | tail -2; done"
run $S "AstraWMS backup log" "tail -30 /opt/astrawms/backups/backups.log 2>/dev/null; echo; tail -40 /opt/astrawms/backups/cron.log 2>/dev/null"
run $S "AstraWMS off-host target set?" "grep -E '^BACKUP_REMOTE(_KEEP_DAYS)?=' /opt/astrawms/.env 2>/dev/null | sed -E 's/=.+/=<set>/' || echo 'BACKUP_REMOTE not set'"
run $S "rclone remotes (names only)" "rclone listremotes 2>/dev/null || echo none"
run $S "Backup job failures in journal 14d" "journalctl --since '14 days ago' --no-pager 2>/dev/null | grep -iE '(backup|dump|restic|borg|rclone).*(fail|error|denied)' | tail -40"
run $S "Provider snapshots/agents" "ls /etc/hostinger* /etc/digitalocean* /etc/hetzner* 2>/dev/null; systemctl list-units --no-pager | grep -iE 'droplet|hetzner|qemu-guest|cloud-init|monarx'"

# ---------------------------------------------------------------- 12 security quick scan
S=12-security.txt
run $S "Users with shell"      "awk -F: '\$7 !~ /(nologin|false)\$/ {print \$1,\$3,\$6,\$7}' /etc/passwd"
run $S "sudoers"               "grep -rvE '^\\s*(#|\$)' /etc/sudoers /etc/sudoers.d 2>/dev/null"
run $S "docker group members"  "getent group docker"
run $S "Unattended upgrades"   "systemctl is-enabled unattended-upgrades 2>/dev/null; cat /etc/apt/apt.conf.d/20auto-upgrades 2>/dev/null"
run $S "World-readable .env"   "find / -xdev -name '.env*' -type f -perm -o=r -not -path '*/node_modules/*' 2>/dev/null | head -30"
run $S "Docker API exposed"    "ss -tlnp | grep -E ':(2375|2376)\\b' || echo 'not exposed'"

# ---------------------------------------------------------------- summary
{
  echo "Collected $STAMP on $(hostname) (quick=$QUICK)"
  ls -la "$OUT" "$OUT/compose"
} > "$OUT/00-index.txt"
tar -czf "$OUT.tar.gz" -C "$(dirname "$OUT")" "$(basename "$OUT")" && chmod 600 "$OUT.tar.gz"
echo "Done: $OUT  ($OUT.tar.gz)"
