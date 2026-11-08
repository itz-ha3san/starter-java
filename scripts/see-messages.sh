#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# LabBank S3 — ساده‌ترین راه برای دیدن پیام‌ها در Kafka
#
# فقط دو کار می‌کند:
#   1. consumer روی یک topic باز می‌کند (پیش‌فرض: AssessRisk که S3 تولید می‌کند)
#   2. یک درخواست POST می‌فرستد تا سرویس یک پیام به topic بفرستد
#
# استفاده:
#   ./scripts/see-messages.sh watch            # گوش دادن به topic پیش‌فرض
#   ./scripts/see-messages.sh watch <topic>    # گوش دادن به topic دلخواه
#   ./scripts/see-messages.sh send             # ارسال یک انتقال داخلی
#   ./scripts/see-messages.sh send-external    # ارسال یک انتقال خارجی
#   ./scripts/see-messages.sh topics           # لیست همه topicها
#   ./scripts/see-messages.sh all              # watch + send در دو ترمینال
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

CONTAINER_KAFKA="s3-kafka"
BOOTSTRAP="localhost:29092"
DEFAULT_TOPIC="lab.s3.s4.command.risk.assess.v1"

GREEN='\033[0;32m'; BLUE='\033[0;34m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'

log()  { echo -e "${BLUE}▶${NC} $*"; }
ok()   { echo -e "${GREEN}✓${NC} $*"; }
warn() { echo -e "${YELLOW}⚠${NC} $*"; }
err()  { echo -e "${RED}✗${NC} $*" >&2; }

# Verify kafka is up
check() {
  if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_KAFTA}$"; then
    if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_KAFKA}$"; then
      err "Container '${CONTAINER_KAFKA}' is not running."
      err "Run: docker compose up -d"
      exit 1
    fi
  fi
}

# ─── Watch a topic ────────────────────────────────────────────────────
watch_topic() {
  local topic="${1:-$DEFAULT_TOPIC}"
  log "Listening on topic: ${topic}"
  log "Press Ctrl+C to stop."
  echo "────────────────────────────────────────────────────────────────"
  docker exec -it "$CONTAINER_KAFKA" kafka-console-consumer \
    --bootstrap-server "$BOOTSTRAP" \
    --topic "$topic" \
    --from-beginning \
    --property print.key=true \
    --property print.headers=true
}

# ─── Send a transfer via API ──────────────────────────────────────────
send_internal() {
  local idem_key="cli-$(date +%s)"
  log "Sending POST /api/v1/transfers (internal)..."
  log "  Idempotency-Key: ${idem_key}"
  echo "────────────────────────────────────────────────────────────────"
  curl -sS -X POST http://localhost:8083/api/v1/transfers \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: ${idem_key}" \
    -H "X-Correlation-ID: $(python3 -c 'import uuid;print(uuid.uuid4())' 2>/dev/null || echo 'no-uuid')" \
    -d '{
      "source_account_number": "001200456789",
      "dest_account_number": "001200987654",
      "amount": 2500000,
      "currency": "IRR",
      "description": "test message from CLI"
    }' | python3 -m json.tool 2>/dev/null || true
  echo "────────────────────────────────────────────────────────────────"
  ok "Request sent. Check the consumer terminal — a message should have appeared."
}

send_external() {
  local idem_key="cli-ext-$(date +%s)"
  log "Sending POST /api/v1/transfers (external)..."
  curl -sS -X POST http://localhost:8083/api/v1/transfers \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: ${idem_key}" \
    -H "X-Correlation-ID: $(python3 -c 'import uuid;print(uuid.uuid4())' 2>/dev/null || echo 'no-uuid')" \
    -d '{
      "source_account_number": "001200456789",
      "dest_iban": "IR430120000000000000000001",
      "amount": 5000000,
      "currency": "IRR",
      "description": "external test"
    }' | python3 -m json.tool 2>/dev/null || true
  ok "Request sent."
}

# ─── List topics ──────────────────────────────────────────────────────
list_topics() {
  log "All LabBank topics:"
  docker exec "$CONTAINER_KAFKA" kafka-topics \
    --bootstrap-server "$BOOTSTRAP" --list | grep '^lab\.' | sort
}

# ─── All-in-one: open consumer in background, send message ────────────
all_in_one() {
  log "Step 1: List topics"
  list_topics
  echo
  log "Step 2: Open a consumer on '${DEFAULT_TOPIC}' in a new terminal window"
  warn "  → Open another terminal and run:"
  echo
  echo "  ./scripts/see-messages.sh watch"
  echo
  log "Step 3: Then come back here and send a message."
  read -p "Press ENTER when the consumer is ready..." < /dev/tty
  log "Sending message..."
  send_internal
  echo
  ok "Done! Check the consumer terminal — the message should be there."
}

# ─── Help ─────────────────────────────────────────────────────────────
show_help() {
  cat <<EOF
${BLUE}LabBank S3 — See Kafka Messages${NC}

${GREEN}Usage:${NC}
  $0 watch [topic]       Listen to a topic (default: ${DEFAULT_TOPIC})
  $0 send                Send an internal transfer (creates a message)
  $0 send-external       Send an external transfer
  $0 topics              List all topics
  $0 all                 Step-by-step guided flow

${GREEN}Examples:${NC}

  ${YELLOW}# Terminal 1: listen${NC}
  $0 watch

  ${YELLOW}# Terminal 2: send a message (S3 will produce one)${NC}
  $0 send

  ${YELLOW}# Listen to a different topic${NC}
  $0 watch lab.s5.s3.event.operation-result.v3
  $0 watch lab.s3.s5.command.transfer.external.v3

${GREEN}Topics you can listen to:${NC}
  lab.s3.s4.command.risk.assess.v1        ← S3 produces (recommended)
  lab.s3.s5.command.transfer.external.v3  ← S3 produces (for external)
  lab.s4.s3.event.operation-result.v1     ← S4 would produce (empty now)
  lab.s5.s3.event.operation-result.v3     ← S5 would produce (empty now)

${GREEN}Prerequisite:${NC}
  docker compose up -d
EOF
}

# ─── Main ─────────────────────────────────────────────────────────────
case "${1:-help}" in
  watch)          check; shift; watch_topic "${1:-}" ;;
  send)           check; send_internal ;;
  send-external)  check; send_external ;;
  topics)         check; list_topics ;;
  all)            check; all_in_one ;;
  help|-h|--help) show_help ;;
  *)
    err "Unknown command: $1"
    show_help
    exit 1
    ;;
esac
