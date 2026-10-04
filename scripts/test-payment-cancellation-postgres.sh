#!/usr/bin/env bash
set -euo pipefail

# Disposable, local-only PostgreSQL. No application configuration or existing database is used.
task_repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
task_pg_bin=${KFE_TEST_PG_BIN:-/usr/lib/postgresql/17/bin}
for task_binary in initdb pg_ctl createdb psql; do
    if [[ ! -x "$task_pg_bin/$task_binary" ]]; then
        printf 'PostgreSQL binary unavailable: %s\nSet KFE_TEST_PG_BIN to its bin directory.\n' \
            "$task_pg_bin/$task_binary" >&2
        exit 1
    fi
done

task_root=$(mktemp -d /tmp/kfe-cancellation-postgres.XXXXXXXX)
task_data_dir="$task_root/data"
task_socket_dir="$task_root/socket"
mkdir "$task_socket_dir"

cleanup() {
    "$task_pg_bin/pg_ctl" -D "$task_data_dir" -m immediate -w stop >/dev/null 2>&1 || true
    # Keep diagnostics available; all paths belong to this invocation's newly created cluster.
    printf '\nDisposable PostgreSQL stopped. Test cluster and log: %s\n' "$task_root"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"$task_pg_bin/initdb" -D "$task_data_dir" -U kfe_cancellation_test \
    --auth-local=trust --auth-host=trust --no-locale --encoding=UTF8 >"$task_root/initdb.log"

# Try a high port with no alteration of any existing listener. Failed binds are harmless.
task_port=0
for task_attempt in {1..20}; do
    task_candidate=$((40000 + RANDOM % 20000))
    if "$task_pg_bin/pg_ctl" -D "$task_data_dir" -l "$task_root/postgres.log" \
        -o "-h 127.0.0.1 -p $task_candidate -k $task_socket_dir -c fsync=off" \
        -w -t 5 start >/dev/null 2>&1; then
        task_port=$task_candidate
        break
    fi
done
if [[ "$task_port" == 0 ]]; then
    printf 'Could not start isolated PostgreSQL; inspect %s\n' "$task_root/postgres.log" >&2
    exit 1
fi

task_database="kfe_cancellation_test_${task_port}"
"$task_pg_bin/createdb" -h 127.0.0.1 -p "$task_port" -U kfe_cancellation_test "$task_database"
"$task_pg_bin/psql" -X -h 127.0.0.1 -p "$task_port" -U kfe_cancellation_test \
    -d "$task_database" -v ON_ERROR_STOP=1 -q \
    -c "create table public.kfe_test_database_marker (id integer primary key, purpose text not null);" \
    -c "insert into public.kfe_test_database_marker values (1, 'ephemeral-payment-cancellation-test');" \
    -c "create schema financial;"

export KFE_TEST_POSTGRES_URL="jdbc:postgresql://127.0.0.1:$task_port/$task_database"
printf 'Running cancellation tests on isolated PostgreSQL at 127.0.0.1:%s\n' "$task_port"
cd "$task_repo_dir"
./gradlew paymentCancellationPostgresTest "$@"
