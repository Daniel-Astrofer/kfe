#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_dir"

fail() {
    printf 'KFE verification failed: %s\n' "$1" >&2
    exit 1
}

forbidden_paths=(
    src/main/java/com/kerosene/kfe/application/settlement/BinarySettlementGate.java
    src/main/java/com/kerosene/kfe/application/settlement/SettlementGateCommand.java
    src/main/java/com/kerosene/kfe/application/settlement/SettlementFlag.java
    src/main/java/com/kerosene/kfe/application/transaction/KfeTransactionCancellationService.java
    src/main/java/com/kerosene/kfe/application/transaction/KfeTransactionStateMachine.java
    src/main/java/com/kerosene/kfe/service/KfeTransactionCancellationService.java
    src/main/java/com/kerosene/kfe/paymentexecution/application/port/out/PaymentCancellationPort.java
    src/main/java/com/kerosene/kfe/paymentexecution/adapters/out/legacy/LegacyPaymentCancellationAdapter.java
)
for path in "${forbidden_paths[@]}"; do
    [[ ! -e "$path" ]] || fail "removed path returned: $path"
done

for root in src/main/java/com/kerosene/kfe src/test/java/com/kerosene/kfe; do
    for forbidden_root in legacy application domain exception runtime time webhook; do
        [[ ! -e "$root/$forbidden_root" ]] || fail "generic root package returned: $root/$forbidden_root"
    done
done
if rg -n --glob '*.java' 'com\.kerosene\.kfe\.legacy\.' src/main; then
    fail 'consumer of removed generic legacy package found'
fi

if find src -type f -name 'package-info.java' -print -quit | grep -q .; then
    fail 'package-info.java is not allowed under src'
fi

if rg -n --glob '*.java' --glob '*.properties' \
    'beta-pass|allow-simulated-balances|kfe\.security\.enabled|kfe\.legacy-financial\.enabled|source\.kfe|HashiCorp Raft|mpc-sidecar' \
    src/main; then
    fail 'permissive, legacy, or non-KFE production marker found'
fi

if rg -n --glob '*.java' 'return true;[[:space:]]*//[[:space:]]*fail open|fail-open' src/main; then
    fail 'fail-open production path found'
fi

if rg -n 'KfeTransactionCancellationService|LegacyPaymentCancellationAdapter|PaymentCancellationPort|BinarySettlementGate|(^|[^A-Za-z])SettlementGateCommand([^A-Za-z]|$)' \
    src/main/java; then
    fail 'removed financial bridge or legacy gate is still referenced'
fi

for context in pricing paymentexecution paymentrequest ledger wallet liquidity messaging audit bootstrap; do
    for layer in domain application; do
        root="src/main/java/com/kerosene/kfe/${context}/${layer}"
        [[ -d "$root" ]] || continue
        if rg -n --glob '*.java' '^import (org\.springframework|jakarta\.persistence|com\.fasterxml\.jackson|com\.kerosene\.common\.|com\.kerosene\.kfe\.(adapters|config|controller|dto|integration|model|rail|repository|runtime|service)\.)' "$root"; then
            fail "framework, shared or outer adapter dependency found in pure context: $root"
        fi
        if rg -n --glob '*.java' '^import (static )?com\.kerosene\.kfe\.([[:alnum:]_]+\.)*(adapters|config|bootstrap)\.' "$root"; then
            fail "context adapter dependency found in pure context: $root"
        fi
    done
done

rg -F -q 'workload-operation allow-listing' docs/operations/STATUS.md \
    || fail 'status does not record workload-operation authorization evidence'
rg -F -q 'operationAuthorizer.requireAllowed(workload, message.type())' \
    src/main/java/com/kerosene/kfe/adapters/out/integration/messaging/KfeAuthenticatedMessageTransport.java \
    || fail 'authenticated message transport has no operation authorization gate'

required_routes=(
    '/kfe/transactions/quote'
    '/kfe/transactions'
    '/kfe/transactions/{transactionId}'
    '/kfe/transactions/{transactionId}/cancel'
)
for route in "${required_routes[@]}"; do
    rg -F -q "$route" docs/operations/api/KFE.md || fail "API catalog is missing route: $route"
done

if [[ "${STRICT_DOCS:-0}" == 1 ]]; then
    rg -F -q 'não homologado integralmente.' docs/agents/KFE_COMPLETION_CHECKLIST.md \
        || fail 'completion checklist status is not explicit'
    rg -F -q 'Local test suites are evidence only' docs/operations/STATUS.md \
        || fail 'validation status does not state local evidence limits'
fi

printf 'KFE-only verification passed.\n'
