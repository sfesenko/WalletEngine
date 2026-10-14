#!/usr/bin/env bash
#
# WalletEngine gRPC API smoke test.
#
# Requires grpcurl and a running app (`mvn spring-boot:run` starts the infra too).
# Mutating checks use fresh, randomly generated wallets, so the script is
# re-runnable; the seeded wallets are only read.
#
# Every check prints the equivalent grpcurl command line, so this script doubles
# as a catalog of example calls.

set -u -o pipefail

ADDR="localhost:9090"
STRESS=0

usage() {
  cat <<'EOF'
Usage: docs/api-test.sh [--stress] [host:port]

  --stress   additionally fire 50 concurrent debits at a fresh wallet and verify
             that exactly 25 succeed, the rest are rejected and the final
             balance is 0 (no negative balance)

Default target: localhost:9090
Env: HTTP_URL for the actuator health check (default http://localhost:8080)
EOF
}

for arg in "$@"; do
  case "$arg" in
    --stress) STRESS=1 ;;
    -h|--help) usage; exit 0 ;;
    *) ADDR="$arg" ;;
  esac
done

HTTP_URL="${HTTP_URL:-http://localhost:8080}"
SERVICE="wallet.v1.WalletService"

command -v grpcurl >/dev/null 2>&1 || {
  echo "ERROR: grpcurl not found (https://github.com/fullstorydev/grpcurl)" >&2
  exit 1
}

PASSED=0
FAILED=0
TMP_DIR=""
cleanup() { [ -n "$TMP_DIR" ] && rm -rf "$TMP_DIR"; }
trap cleanup EXIT

pass() { PASSED=$((PASSED + 1)); echo "  PASS: $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  FAIL: $1"; }
section() { printf '\n=== %s ===\n' "$1"; }

# Generate a fresh, lower-case UUID for wallet_id / transaction_id. Transactions
# are single-use, so every run must use ids that were never consumed before.
uuid() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen
  elif [ -r /proc/sys/kernel/random/uuid ]; then
    cat /proc/sys/kernel/random/uuid
  else
    python3 -c 'import uuid; print(uuid.uuid4())'
  fi | tr 'A-Z' 'a-z'
}

[ -n "$(uuid)" ] || {
  echo "ERROR: cannot generate UUIDs (need uuidgen, /proc/sys/kernel/random/uuid or python3)" >&2
  exit 1
}

show() { # show <Method> <json>
  printf "  $ grpcurl -plaintext -d '%s' %s %s/%s\n" "$2" "$ADDR" "$SERVICE" "$1"
}

request() { # request <Method> <json> -> stdout, grpcurl errors included
  grpcurl -plaintext -d "$2" "$ADDR" "$SERVICE/$1" 2>&1
}

expect_ok() { # expect_ok <Method> <json> [grep -E pattern ...]
  local method="$1" payload="$2" out ok=1 pattern
  shift 2
  show "$method" "$payload"
  out=$(request "$method" "$payload")
  echo "$out" | sed 's/^/    /'
  case "$out" in ERROR:*) ok=0 ;; esac
  for pattern in "$@"; do
    grep -Eq -- "$pattern" <<<"$out" || { echo "    (missing /$pattern/)"; ok=0; }
  done
  if [ "$ok" -eq 1 ]; then pass "$method"; else fail "$method"; fi
}

expect_error() { # expect_error <Method> <json> <ExpectedCode>
  local method="$1" payload="$2" code="$3" out
  show "$method" "$payload"
  out=$(request "$method" "$payload")
  echo "$out" | sed 's/^/    /'
  if grep -q "Code: $code" <<<"$out"; then
    pass "$method -> $code"
  else
    fail "$method: expected Code: $code"
  fi
}

section "Discovery (server reflection)"
printf '  $ grpcurl -plaintext %s list\n' "$ADDR"
out=$(grpcurl -plaintext "$ADDR" list 2>&1)
echo "$out" | sed 's/^/    /'
if ! grep -q "$SERVICE" <<<"$out"; then
  echo "  FAIL: $SERVICE not reachable at $ADDR - start the app first: mvn spring-boot:run" >&2
  exit 1
fi
pass "reflection lists $SERVICE"

printf '  $ grpcurl -plaintext %s list %s\n' "$ADDR" "$SERVICE"
grpcurl -plaintext "$ADDR" list "$SERVICE" 2>&1 | sed 's/^/    /'

section "Health (HTTP)"
if command -v curl >/dev/null 2>&1; then
  printf '  $ curl -s %s/actuator/health\n' "$HTTP_URL"
  out=$(curl -s -m 5 "$HTTP_URL/actuator/health" 2>&1)
  echo "    $out"
  if grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"$out"; then
    pass "actuator health is UP"
  else
    fail "actuator health"
  fi
else
  echo "  SKIP: curl not found"
fi

section "Seeded wallets (informational, read-only)"
for entry in \
  "00000000-0000-0000-0000-000000000001 100000" \
  "00000000-0000-0000-0000-000000000002 5000" \
  "00000000-0000-0000-0000-000000000003 25000"; do
  read -r wallet expected <<<"$entry"
  payload='{"wallet_id": "'"$wallet"'"}'
  show GetBalance "$payload"
  out=$(request GetBalance "$payload")
  echo "$out" | sed 's/^/    /'
  if grep -Eq '"amount": *"'"$expected"'"' <<<"$out"; then
    echo "    NOTE: matches the seed value ($expected)"
  else
    echo "    NOTE: differs from the seed value ($expected) - the stack has been used"
  fi
done

section "CreateWallet / Credit / Debit (fresh wallet)"
W=$(uuid)
TX_CREDIT=$(uuid)
TX_DEBIT=$(uuid)

expect_ok CreateWallet '{"wallet_id": "'"$W"'"}' \
  '"wallet_id": *"'"$W"'"' '"amount": *"0"'
expect_ok GetBalance '{"wallet_id": "'"$W"'"}' \
  '"amount": *"0"'
expect_ok Credit '{"transaction_id": "'"$TX_CREDIT"'", "wallet_id": "'"$W"'", "amount": 100}' \
  '"amount": *"100"' '"transaction_amount": *"100"'
expect_ok Debit '{"transaction_id": "'"$TX_DEBIT"'", "wallet_id": "'"$W"'", "amount": 100}' \
  '"amount": *"0"' '"transaction_amount": *"-100"'
expect_ok GetBalance '{"wallet_id": "'"$W"'"}' \
  '"amount": *"0"'

section "Idempotency (same transaction_id)"
A=$(uuid)
B=$(uuid)
TX=$(uuid)

expect_ok CreateWallet '{"wallet_id": "'"$A"'"}'
expect_ok CreateWallet '{"wallet_id": "'"$B"'"}'
expect_ok Credit '{"transaction_id": "'"$TX"'", "wallet_id": "'"$A"'", "amount": 100}' \
  '"amount": *"100"'
expect_ok Credit '{"transaction_id": "'"$TX"'", "wallet_id": "'"$A"'", "amount": 100}' \
  '"amount": *"100"'
expect_ok GetBalance '{"wallet_id": "'"$A"'"}' \
  '"amount": *"100"'
# move the balance on, then replay the original transaction:
# the original snapshot (100), not the live balance (150), must come back
expect_ok Credit '{"transaction_id": "'"$(uuid)"'", "wallet_id": "'"$A"'", "amount": 50}' \
  '"amount": *"150"'
expect_ok Credit '{"transaction_id": "'"$TX"'", "wallet_id": "'"$A"'", "amount": 100}' \
  '"amount": *"100"' '"transaction_amount": *"100"'
expect_error Credit '{"transaction_id": "'"$TX"'", "wallet_id": "'"$B"'", "amount": 100}' InvalidArgument
expect_error Credit '{"transaction_id": "'"$TX"'", "wallet_id": "'"$A"'", "amount": 999}' InvalidArgument

section "Strict idempotency (stored failure is replayed)"
C=$(uuid)
FAILED_TX=$(uuid)

expect_ok CreateWallet '{"wallet_id": "'"$C"'"}'
expect_error Debit '{"transaction_id": "'"$FAILED_TX"'", "wallet_id": "'"$C"'", "amount": 999999}' FailedPrecondition
expect_ok Credit '{"transaction_id": "'"$(uuid)"'", "wallet_id": "'"$C"'", "amount": 999999}' \
  '"amount": *"999999"'
expect_error Debit '{"transaction_id": "'"$FAILED_TX"'", "wallet_id": "'"$C"'", "amount": 999999}' FailedPrecondition

section "Error mapping"
expect_error GetBalance '{"wallet_id": "'"$(uuid)"'"}' NotFound
expect_error Credit '{"transaction_id": "'"$(uuid)"'", "wallet_id": "'"$(uuid)"'", "amount": 100}' NotFound
expect_error CreateWallet '{"wallet_id": "'"$A"'"}' AlreadyExists
expect_error Credit '{"transaction_id": "'"$(uuid)"'", "wallet_id": "'"$A"'", "amount": 0}' InvalidArgument
expect_error Debit '{"transaction_id": "'"$(uuid)"'", "wallet_id": "'"$A"'", "amount": -5}' InvalidArgument
expect_error GetBalance '{"wallet_id": "not-a-uuid"}' InvalidArgument

if [ "$STRESS" -eq 1 ]; then
  section "Stress: 50 concurrent debits of 1000 from a fresh wallet funded with 25000"
  S=$(uuid)
  TMP_DIR=$(mktemp -d)
  expect_ok CreateWallet '{"wallet_id": "'"$S"'"}' \
    '"amount": *"0"'
  expect_ok Credit '{"transaction_id": "'"$(uuid)"'", "wallet_id": "'"$S"'", "amount": 25000}' \
    '"amount": *"25000"'
  echo "  firing 50 concurrent Debit calls..."
  for i in $(seq 1 50); do
    (
      out=$(grpcurl -plaintext -d '{"transaction_id": "'"$(uuid)"'", "wallet_id": "'"$S"'", "amount": 1000}' "$ADDR" "$SERVICE/Debit" 2>&1)
      if grep -q 'Code: FailedPrecondition' <<<"$out"; then
        : > "$TMP_DIR/$i.rejected"
      elif grep -q '^ERROR' <<<"$out"; then
        echo "$out" > "$TMP_DIR/$i.error"
      else
        : > "$TMP_DIR/$i.ok"
      fi
    ) &
  done
  wait
  ok=$(find "$TMP_DIR" -name '*.ok' | wc -l)
  rejected=$(find "$TMP_DIR" -name '*.rejected' | wc -l)
  errors=$(find "$TMP_DIR" -name '*.error' | wc -l)
  if [ "$ok" -eq 25 ]; then pass "25 debits accepted"; else fail "expected 25 accepted, got $ok"; fi
  if [ "$rejected" -eq 25 ]; then pass "25 debits rejected (insufficient funds)"; else fail "expected 25 rejected, got $rejected"; fi
  if [ "$errors" -eq 0 ]; then
    pass "no unexpected errors"
  else
    fail "$errors unexpected error(s):"
    for f in "$TMP_DIR"/*.error; do sed 's/^/    /' "$f"; done
  fi
  expect_ok GetBalance '{"wallet_id": "'"$S"'"}' \
    '"amount": *"0"'
fi

printf '\n=== Summary ===\n'
echo "PASSED: $PASSED  FAILED: $FAILED"
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
  echo "RabbitMQ side: docs/rabbitmq.sh (queue wallet.updated)"
  exit 0
fi
exit 1
