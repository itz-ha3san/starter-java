#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# LabBank S3 — تست دستی consumerها با شبیه‌سازی پاسخ S4 و S5
#
# این اسکریپت پیام‌های AssessRisk/ExecuteExternalTransfer را که S3 تولید
# می‌کند مشاهده می‌کند، و سپس پاسخ‌های شبیه‌سازی‌شده‌ی S4/S5 را در
# topicهای نتیجه می‌فرستد تا state-machine سرویس S3 را تست کند.
#
# پیش‌فرض: docker compose up -d اجرا شده باشد.
#
# استفاده:
#   ./scripts/test-messaging.sh            # حالت تعاملی
#   ./scripts/test-messaging.sh watch-s4   # فقط watch topic S4
#   ./scripts/test-messaging.sh watch-s5   # فقط watch topic S5
#   ./scripts/test-messaging.sh watch-out  # فقط watch topicهای خروجی S3
#   ./scripts/test-messaging.sh replay-s4-approved <txn-id>
#   ./scripts/test-messaging.sh replay-s4-rejected  <txn-id>
#   ./scripts/test-messaging.sh replay-s5-settled   <txn-id>
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

CONTAINER_KAFKA="s3-kafka"
CONTAINER_DB="s3-db"
DB_NAME="${DB_NAME:-paymentdb}"
DB_USER="${DB_USER:-postgres}"

# ─── Colors ───────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'

log()  { echo -e "${BLUE}[$(date +%H:%M:%S)]${NC} $*"; }
ok()   { echo -e "${GREEN}[$(date +%H:%M:%S)] ✓${NC} $*"; }
warn() { echo -e "${YELLOW}[$(date +%H:%M:%S)] ⚠${NC} $*"; }
err()  { echo -e "${RED}[$(date +%H:%M:%S)] ✗${NC} $*" >&2; }

# ─── Helpers ──────────────────────────────────────────────────────────
kexec() {
  docker exec -i "$CONTAINER_KAFKA" "$@"
}

# Get a fresh transaction_id from the DB (the most recent PENDING/RISK_CHECKING transfer)
get_txn_id() {
  docker exec -i "$CONTAINER_DB" psql -U "$DB_USER" -d "$DB_NAME" -t -A \
    -c "SELECT id FROM transfers ORDER BY created_at DESC LIMIT 1;" 2>/dev/null \
    | tr -d '[:space:]'
}

get_random_uuid() { kexec kafka-run-class kafka.utils.MockProducer -1 2>/dev/null \
  | head -1 || python3 -c 'import uuid; print(uuid.uuid4())'; }

# ─── Verify docker is up ──────────────────────────────────────────────
check_docker() {
  log "Checking docker containers..."
  for c in "$CONTAINER_KAFKA" "$CONTAINER_DB" "s3-app"; do
    if ! docker ps --format '{{.Names}}' | grep -q "^${c}$"; then
      err "Container '$c' is not running. Run: docker compose up -d"
      exit 1
    fi
  done
  ok "All required containers are up."
}

# ─── 1. Watch topics (consumer side) ──────────────────────────────────
watch_s4_out() {
  # Topic that S3 produces to (AssessRisk command) — S4 is supposed to consume
  log "Watching topic: lab.s3.s4.command.risk.assess.v1 (S3 → S4 commands)..."
  log "Press Ctrl+C to stop."
  kexec kafka-console-consumer \
    --bootstrap-server localhost:29092 \
    --topic lab.s3.s4.command.risk.assess.v1 \
    --from-beginning \
    --property print.key=true \
    --property print.headers=true
}

watch_s5_out() {
  log "Watching topic: lab.s3.s5.command.transfer.external.v3 (S3 → S5 commands)..."
  log "Press Ctrl+C to stop."
  kexec kafka-console-consumer \
    --bootstrap-server localhost:29092 \
    --topic lab.s3.s5.command.transfer.external.v3 \
    --from-beginning \
    --property print.key=true
}

watch_s4_in() {
  # Topic that S3 consumes (AssessmentResult replies) — S4 produces
  log "Watching topic: lab.s4.s3.event.operation-result.v1 (S4 → S3 replies)..."
  log "Press Ctrl+C to stop."
  kexec kafka-console-consumer \
    --bootstrap-server localhost:29092 \
    --topic lab.s4.s3.event.operation-result.v1 \
    --from-beginning \
    --property print.key=true
}

watch_s5_in() {
  log "Watching topic: lab.s5.s3.event.operation-result.v3 (S5 → S3 replies)..."
  kexec kafka-console-consumer \
    --bootstrap-server localhost:29092 \
    --topic lab.s5.s3.event.operation-result.v3 \
    --from-beginning \
    --property print.key=true
}

# ─── 2. Simulate S4 reply (producer side) ─────────────────────────────
send_s4_result() {
  local txn_id="$1"
  local decision="$2"   # APPROVED | REJECTED | MANUAL_REVIEW
  local risk_score="${3:-50}"

  if [ -z "$txn_id" ]; then
    err "Usage: $0 replay-s4-<decision> <txn-id>"
    exit 1
  fi

  local msg_id corr_id op_id
  msg_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
  corr_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
  op_id=$(python3 -c 'import uuid; print(uuid.uuid4())')

  local payload
  payload=$(cat <<EOF
{
  "message_id": "${msg_id}",
  "schema_version": "1.0",
  "transaction_id": "${txn_id}",
  "correlation_id": "${corr_id}",
  "causation_id": "${msg_id}",
  "occurred_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "producer": "S4-RSK",
  "message_type": "AssessmentResult.v1",
  "operation_id": "${op_id}",
  "outcome": "APPLIED",
  "data": {
    "decision": "${decision}",
    "risk_score": ${risk_score},
    "rules_evaluated": ["DAILY_LIMIT_OK", "VELOCITY_OK"],
    "assessed_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  }
}
EOF
)

  log "Sending S4 result to lab.s4.s3.event.operation-result.v1"
  log "  txn_id=${txn_id} decision=${decision} risk_score=${risk_score}"
  echo "$payload" | kexec kafka-console-producer \
    --bootstrap-server localhost:29092 \
    --topic lab.s4.s3.event.operation-result.v1 \
    --property "key=${txn_id}" \
    --property "parse.key=true"

  ok "Sent. Check s3-app logs: docker logs -f s3-app"
  log "Expected: status should change to:"
  case "$decision" in
    APPROVED)        echo -e "  ${GREEN}HOLDING${NC}" ;;
    REJECTED)        echo -e "  ${GREEN}RISK_REJECTED${NC}" ;;
    MANUAL_REVIEW)   echo -e "  ${GREEN}RISK_MANUAL_REVIEW${NC}" ;;
  esac
}

send_s4_validation_error() {
  local txn_id="$1"
  local msg_id corr_id op_id
  msg_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
  corr_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
  op_id=$(python3 -c 'import uuid; print(uuid.uuid4())')

  local payload
  payload=$(cat <<EOF
{
  "message_id": "${msg_id}",
  "schema_version": "1.0",
  "transaction_id": "${txn_id}",
  "correlation_id": "${corr_id}",
  "causation_id": "${msg_id}",
  "occurred_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "producer": "S4-RSK",
  "message_type": "AssessmentResult.v1",
  "operation_id": "${op_id}",
  "outcome": "REJECTED",
  "error": {
    "code": "VALIDATION_ERROR",
    "retryable": false,
    "public_message": "amount must be a positive integer.",
    "problem": {
      "type": "https://errors.labbank.example/validation-error",
      "title": "Invalid request",
      "status": 400,
      "code": "VALIDATION_ERROR",
      "detail": "amount must be a positive integer.",
      "correlation_id": "${corr_id}"
    }
  }
}
EOF
)
  log "Sending S4 REJECTED (validation error) for txn=${txn_id}"
  echo "$payload" | kexec kafka-console-producer \
    --bootstrap-server localhost:29092 \
    --topic lab.s4.s3.event.operation-result.v1 \
    --property "key=${txn_id}" --property "parse.key=true"
  ok "Sent. Expected: status → FAILED"
}

# ─── 3. Simulate S5 reply (producer side) ─────────────────────────────
send_s5_result() {
  local txn_id="$1"
  local status="$2"   # SETTLED | ACCEPTED | PROCESSING | FAILED | UNKNOWN
  local ext_ref="S5-DEMO-$(date +%s)"

  if [ -z "$txn_id" ]; then
    err "Usage: $0 replay-s5-<status> <txn-id>"
    exit 1
  fi

  local msg_id corr_id op_id
  msg_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
  corr_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
  op_id=$(python3 -c 'import uuid; print(uuid.uuid4())')

  local payload
  payload=$(cat <<EOF
{
  "message_id": "${msg_id}",
  "schema_version": "3.0",
  "transaction_id": "${txn_id}",
  "correlation_id": "${corr_id}",
  "causation_id": "${msg_id}",
  "occurred_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "producer": "S5-STL",
  "message_type": "ExecuteExternalTransferResult.v3",
  "operation_id": "${op_id}",
  "outcome": "APPLIED",
  "data": {
    "dest_iban": "IR430120000000000000000001",
    "amount": 2500000,
    "currency": "IRR",
    "external_reference": "${ext_ref}",
    "status": "${status}",
    "updated_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  }
}
EOF
)

  log "Sending S5 result to lab.s5.s3.event.operation-result.v3"
  log "  txn_id=${txn_id} status=${status} ext_ref=${ext_ref}"
  echo "$payload" | kexec kafka-console-producer \
    --bootstrap-server localhost:29092 \
    --topic lab.s5.s3.event.operation-result.v3 \
    --property "key=${txn_id}" --property "parse.key=true"

  ok "Sent. Expected transfer status →"
  case "$status" in
    SETTLED)                       echo -e "  ${GREEN}SETTLED${NC}" ;;
    ACCEPTED|PROCESSING)           echo -e "  ${GREEN}EXECUTING${NC}" ;;
    FAILED|UNKNOWN)                echo -e "  ${GREEN}FAILED${NC}" ;;
  esac
}

# ─── 4. List all topics ───────────────────────────────────────────────
list_topics() {
  log "All Kafka topics:"
  kexec kafka-topics --bootstrap-server localhost:29092 --list | grep '^lab\.'
}

# ─── 5. Show DB state ─────────────────────────────────────────────────
show_db() {
  log "Current transfers in DB:"
  docker exec -i "$CONTAINER_DB" psql -U "$DB_USER" -d "$DB_NAME" \
    -c "SELECT id, status, risk_decision, risk_score, external_reference, created_at FROM transfers ORDER BY created_at DESC LIMIT 10;"

  log "Recent events:"
  docker exec -i "$CONTAINER_DB" psql -U "$DB_USER" -d "$DB_NAME" \
    -c "SELECT transfer_id, event_type, occurred_at FROM transfer_events ORDER BY occurred_at DESC LIMIT 10;"
}

# ─── 6. End-to-end happy path demo ────────────────────────────────────
e2e_demo() {
  log "=== End-to-End Demo ==="
  log "Step 1: Create transfer via API..."
  local resp txn_id
  resp=$(curl -sS -X POST http://localhost:8083/api/v1/transfers \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: e2e-demo-$(date +%s)" \
    -H "X-Correlation-ID: $(python3 -c 'import uuid; print(uuid.uuid4())')" \
    -d '{"source_account_number":"001200456789","dest_account_number":"001200987654","amount":2500000,"currency":"IRR","description":"E2E test"}')
  echo "  Response: $resp"
  txn_id=$(echo "$resp" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null || echo "")
  if [ -z "$txn_id" ]; then
    err "Could not parse transfer id. Is the app running on :8083?"
    exit 1
  fi
  ok "Transfer created: ${txn_id}"

  log "Step 2: Verify S3 produced AssessRisk command (watch topic for ~3s)..."
  timeout 3 kexec kafka-console-consumer \
    --bootstrap-server localhost:29092 \
    --topic lab.s3.s4.command.risk.assess.v1 \
    --from-beginning --max-messages 1 \
    --property print.key=true 2>/dev/null || true

  log "Step 3: Simulate S4 reply (APPROVED)..."
  send_s4_result "$txn_id" "APPROVED" "12"
  sleep 2

  log "Step 4: Check transfer state..."
  curl -sS http://localhost:8083/api/v1/transfers/"$txn_id" | python3 -m json.tool

  log "Step 5: Check events..."
  curl -sS http://localhost:8083/api/v1/transfers/"$txn_id"/events | python3 -m json.tool

  ok "Demo complete. Transfer should now be in HOLDING state."
}

# ─── Main ─────────────────────────────────────────────────────────────
case "${1:-interactive}" in
  watch-s4)        check_docker; watch_s4_out ;;
  watch-s5)        check_docker; watch_s5_out ;;
  watch-s4-in)     check_docker; watch_s4_in ;;
  watch-s5-in)     check_docker; watch_s5_in ;;
  watch-out)       check_docker; watch_s4_out ;;
  replay-s4-approved)     check_docker; send_s4_result "${2:-}" "APPROVED" ;;
  replay-s4-rejected)     check_docker; send_s4_result "${2:-}" "REJECTED" "87" ;;
  replay-s4-manual)       check_docker; send_s4_result "${2:-}" "MANUAL_REVIEW" "55" ;;
  replay-s4-error)        check_docker; send_s4_validation_error "${2:-}" ;;
  replay-s5-settled)      check_docker; send_s5_result "${2:-}" "SETTLED" ;;
  replay-s5-accepted)     check_docker; send_s5_result "${2:-}" "ACCEPTED" ;;
  replay-s5-failed)       check_docker; send_s5_result "${2:-}" "FAILED" ;;
  topics)          check_docker; list_topics ;;
  db)              check_docker; show_db ;;
  e2e)             check_docker; e2e_demo ;;
  interactive|"")
    cat <<EOF
${CYAN}LabBank S3 — Messaging Test Helper${NC}

${GREEN}پیش‌فرض:${NC} docker compose up -d اجرا شده باشد.

${YELLOW}۱. مشاهده topicها (Consumer side)${NC}
  $0 watch-s4          # پیام‌های AssessRisk که S3 تولید می‌کند (S4 باید مصرف کند)
  $0 watch-s5          # پیام‌های ExecuteExternalTransfer که S3 تولید می‌کند
  $0 watch-s4-in       # پاسخ‌های S4 که S3 مصرف می‌کند
  $0 watch-s5-in       # پاسخ‌های S5 که S3 مصرف می‌کند

${YELLOW}۲. شبیه‌سازی پاسخ S4 (S4 واقعی لازم نیست)${NC}
  $0 replay-s4-approved <txn-id>     # decision=APPROVED → status=HOLDING
  $0 replay-s4-rejected <txn-id>     # decision=REJECTED → status=RISK_REJECTED
  $0 replay-s4-manual   <txn-id>     # decision=MANUAL_REVIEW → status=RISK_MANUAL_REVIEW
  $0 replay-s4-error    <txn-id>     # outcome=REJECTED → status=FAILED

${YELLOW}۳. شبیه‌سازی پاسخ S5${NC}
  $0 replay-s5-settled  <txn-id>     # status=SETTLED → transfer SETTLED
  $0 replay-s5-accepted <txn-id>     # status=ACCEPTED → transfer EXECUTING
  $0 replay-s5-failed   <txn-id>     # status=FAILED → transfer FAILED

${YELLOW}۴. ابزارهای کمکی${NC}
  $0 topics            # لیست همه topicها
  $0 db                # نمایش transfers و events در PostgreSQL
  $0 e2e               # دموی کامل end-to-end (ساخت + شبیه‌سازی + بررسی)

${YELLOW}نکته:${NC} برای گرفتن txn-id از دیتابیس:
  $0 db
EOF
    ;;
  *)
    err "Unknown command: $1"
    echo "Run: $0  (without args) for help"
    exit 1
    ;;
esac
