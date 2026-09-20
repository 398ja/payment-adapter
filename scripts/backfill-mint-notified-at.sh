#!/usr/bin/env bash
#
# Back-fills quote.mint_notified_at from what the mint actually received (cashu-mint#462).
#
# V11 adds the column NULL for every existing row, because the adapter has no record of which
# historical payments it successfully forwarded — that is the defect the column exists to fix,
# and it cannot retroactively know. Left alone, the gauge counts the whole PAID backlog rather
# than the stranded part of it: 218 rows on staging, of which the mint demonstrably received 130.
#
# The only honest source of truth is the mint's own webhook_event table. A payment the mint holds
# an `accepted` event for was delivered, whatever the adapter failed to write down. This script
# reads that, and stamps exactly those rows.
#
# It does NOT deliver anything, change any state machine, or touch a row the mint has not
# confirmed. The payments left NULL afterwards are the genuinely stranded ones, and deciding what
# to do about them is a separate step -- see the issue.
#
# Usage:
#   scripts/backfill-mint-notified-at.sh            # report only, changes nothing
#   scripts/backfill-mint-notified-at.sh --apply    # write the stamps
#
set -euo pipefail

REMOTE="${REMOTE:-nostr@398ja.xyz}"
PAYMENT_DB="${PAYMENT_DB:-imani-payment-db}"
MINT_DB="${MINT_DB:-imani-mint-db}"
APPLY=0
[[ "${1:-}" == "--apply" ]] && APPLY=1

payment_sql() { ssh -o BatchMode=yes "$REMOTE" "docker exec $PAYMENT_DB psql -U postgres -d payment_gateway -t -A $*"; }
mint_sql()    { ssh -o BatchMode=yes "$REMOTE" "docker exec $MINT_DB    psql -U postgres -d cashu_mint     -t -A $*"; }

echo "==> Reading what each side believes"

# Every PAID quote the adapter holds with no forward recorded.
payment_sql -c "\"SELECT quote_id FROM quote WHERE state='PAID' AND mint_notified_at IS NULL\"" \
    | sort -u > /tmp/bf-adapter-unstamped.txt

# Every quote the mint has actually accepted a payment for.
mint_sql -c "\"SELECT DISTINCT quote_id FROM webhook_event WHERE outcome='accepted'\"" \
    | sort -u > /tmp/bf-mint-accepted.txt

comm -12 /tmp/bf-adapter-unstamped.txt /tmp/bf-mint-accepted.txt > /tmp/bf-delivered.txt
comm -23 /tmp/bf-adapter-unstamped.txt /tmp/bf-mint-accepted.txt > /tmp/bf-stranded.txt

echo "    adapter PAID, unstamped : $(wc -l < /tmp/bf-adapter-unstamped.txt)"
echo "    mint confirmed received : $(wc -l < /tmp/bf-delivered.txt)   <- will be stamped"
echo "    still stranded          : $(wc -l < /tmp/bf-stranded.txt)   <- left NULL, deliberately"
echo
echo "    stranded quote ids:"
sed 's/^/      /' /tmp/bf-stranded.txt

if [[ "$APPLY" -ne 1 ]]; then
    echo
    echo "==> Report only. Re-run with --apply to write the stamps."
    exit 0
fi

echo
echo "==> Stamping the confirmed-delivered rows"

# now() is deliberately not the original delivery time, which nothing recorded. The column's
# meaning is "the mint has this", and a back-filled timestamp says when we established that --
# not when it happened. Anyone reading a stamp at the back-fill instant should read it as
# "confirmed during the #462 reconciliation".
while read -r quote_id; do
    [[ -z "$quote_id" ]] && continue
    payment_sql -c "\"UPDATE quote SET mint_notified_at = now() WHERE quote_id = '$quote_id' AND state = 'PAID' AND mint_notified_at IS NULL\"" >/dev/null
done < /tmp/bf-delivered.txt

echo "==> Verifying"
remaining=$(payment_sql -c "\"SELECT count(*) FROM quote WHERE state='PAID' AND mint_notified_at IS NULL\"")
expected=$(wc -l < /tmp/bf-stranded.txt)
echo "    unstamped after back-fill : $remaining"
echo "    expected (the stranded)   : $expected"

if [[ "$remaining" == "$expected" ]]; then
    echo "    OK — the gauge now reads the stranded payments only."
else
    echo "    MISMATCH — investigate before enabling mint.webhook.reconcile.enabled."
    exit 1
fi
