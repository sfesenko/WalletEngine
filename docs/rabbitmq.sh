#!/usr/bin/env bash
#
# Inspect WalletEngine wallet events in RabbitMQ.
#
# Uses the management API; messages are peeked with ack_requeue_true, so nothing
# is consumed. Events are published asynchronously by the outbox publisher
# (poll interval 5s) - give it a moment after a movement before peeking.
#
# Usage: docs/rabbitmq.sh [count]
#
# Env overrides:
#   RABBIT_URL   default http://localhost:15672
#   RABBIT_USER  default myuser
#   RABBIT_PASS  default secret
#   QUEUE        default wallet.updated
#   EXCHANGE     default wallet.events

set -u -o pipefail

RABBIT_URL="${RABBIT_URL:-http://localhost:15672}"
RABBIT_USER="${RABBIT_USER:-myuser}"
RABBIT_PASS="${RABBIT_PASS:-secret}"
QUEUE="${QUEUE:-wallet.updated}"
EXCHANGE="${EXCHANGE:-wallet.events}"
COUNT="${1:-${COUNT:-5}}"

command -v curl >/dev/null 2>&1 || {
  echo "ERROR: curl not found" >&2
  exit 1
}

HAVE_JQ=0
command -v jq >/dev/null 2>&1 && HAVE_JQ=1

pretty() {
  if [ "$HAVE_JQ" -eq 1 ]; then jq .; else cat; fi
}

mgmt() { # mgmt <method> <path> [json-body]
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -sS -u "$RABBIT_USER:$RABBIT_PASS" -X "$method" \
      -H 'content-type: application/json' -d "$body" "$RABBIT_URL$path"
  else
    curl -sS -u "$RABBIT_USER:$RABBIT_PASS" -X "$method" "$RABBIT_URL$path"
  fi
}

echo "RabbitMQ management API: $RABBIT_URL (user: $RABBIT_USER)"

overview="$(mgmt GET /api/overview || true)"
if ! grep -q 'rabbitmq_version' <<<"$overview"; then
  echo "ERROR: management API not reachable at $RABBIT_URL" >&2
  echo "Hint: docker compose up -d rabbitmq" >&2
  exit 1
fi
if [ "$HAVE_JQ" -eq 1 ]; then
  echo "Broker version: $(jq -r '.rabbitmq_version // "unknown"' <<<"$overview")"
fi

echo
echo "Exchange bindings ($EXCHANGE):"
bindings="$(mgmt GET "/api/exchanges/%2F/$EXCHANGE/bindings/source")"
if grep -q 'Object Not Found' <<<"$bindings"; then
  echo "  (exchange does not exist yet - the app declares it on startup)"
elif [ "$HAVE_JQ" -eq 1 ]; then
  jq -r '.[] | "  \(.routing_key) -> \(.destination)"' <<<"$bindings"
else
  echo "$bindings" | pretty | sed 's/^/  /'
fi

echo
echo "Queue stats ($QUEUE):"
queue="$(mgmt GET "/api/queues/%2F/$QUEUE")"
if grep -q 'Object Not Found' <<<"$queue"; then
  echo "  (queue does not exist yet - start the app; it declares $QUEUE on startup)"
  exit 0
fi
if [ "$HAVE_JQ" -eq 1 ]; then
  jq '{queue: .name, state, durable, messages, messages_ready, messages_unacknowledged, consumers}' <<<"$queue"
else
  echo "$queue" | pretty
fi

echo
echo "Peeking up to $COUNT message(s) (ack_requeue_true - nothing is consumed):"
messages="$(mgmt POST "/api/queues/%2F/$QUEUE/get" '{"count": '"$COUNT"', "ackmode": "ack_requeue_true", "encoding": "auto"}')"
if [ "$HAVE_JQ" -eq 1 ]; then
  n="$(jq 'length' <<<"$messages" 2>/dev/null || echo 0)"
  echo "  $n message(s) returned."
  if [ "$n" -gt 0 ]; then
    jq -r '.[] | .payload' <<<"$messages" | while IFS= read -r payload; do
      jq . <<<"$payload" 2>/dev/null || echo "$payload"
    done | sed 's/^/  /'
  fi
else
  echo "$messages" | pretty | sed 's/^/  /'
fi

echo
echo "Done. Movements recorded while the app was running appear here ~5s later"
echo "(outbox poll interval), or in the UI at $RABBIT_URL"
