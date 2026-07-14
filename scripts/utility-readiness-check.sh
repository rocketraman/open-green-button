#!/usr/bin/env bash
#
# Readiness check for a hosted Open Green Button instance, exercising the endpoints a utility's
# review team would touch before approving the app for their test lab:
#
#   - TLS cert is valid and chains to a trusted root
#   - /health responds 200
#   - Landing page renders
#   - Security headers are set
#   - OAuth callback handles missing / invalid / hostile inputs gracefully (no 5xx)
#   - NotificationURI accepts POST
#   - Unknown utility / unknown claim code paths return appropriate errors
#   - OAuth start endpoint redirects with the expected query params
#
# Usage:  scripts/utility-readiness-check.sh [BASE_URL] [UTILITY_ID]
# Default: api.opengreenbutton.org / burlington_hydro
#
# Exits non-zero if any FAIL is recorded. WARN does not fail the run — it flags things to
# eyeball before going live, but does not block submission.

set -euo pipefail

BASE_URL="${1:-https://api.opengreenbutton.org}"
UTILITY="${2:-burlington_hydro}"

# Strip a trailing slash from BASE_URL so we don't end up with // in concatenated paths.
BASE_URL="${BASE_URL%/}"
HOST="${BASE_URL#https://}"
HOST="${HOST#http://}"
HOST="${HOST%%/*}"

# Resolve the NotificationURI path the utility will POST to. The path is per-utility config
# (UtilityProfile.notificationPath), defaulting server-side to /notify/<id>. Almost every profile
# inherits that default and so sets nothing; a custodian that mandates a non-default callback path
# overrides it in utilities.conf. Read the override from the conf when present so this check probes
# the *registered* path rather than assuming the convention; otherwise fall back to the same
# /notify/<id> default the server derives. If the conf isn't reachable (e.g. run standalone against
# a hosted URL), the default still applies.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONF="$SCRIPT_DIR/../server/app/src/main/resources/utilities.conf"
NOTIFY_PATH=""
if [ -f "$CONF" ]; then
    # Track the most recent uncommented `id = "..."` and, within that same block, capture an
    # explicit `notificationPath = "..."`. POSIX awk (no gawk match-capture extension).
    NOTIFY_PATH=$(awk -v util="$UTILITY" '
        /^[ \t]*#/ { next }
        /^[ \t]*id[ \t]*=[ \t]*"/ {
            s=$0; sub(/^[ \t]*id[ \t]*=[ \t]*"/, "", s); sub(/".*/, "", s); cur=s; next
        }
        /^[ \t]*notificationPath[ \t]*=[ \t]*"/ {
            if (cur==util) {
                s=$0; sub(/^[ \t]*notificationPath[ \t]*=[ \t]*"/, "", s); sub(/".*/, "", s)
                print s; exit
            }
        }
    ' "$CONF")
fi
[ -n "$NOTIFY_PATH" ] || NOTIFY_PATH="/notify/$UTILITY"
# Guard against a config value written without a leading slash.
case "$NOTIFY_PATH" in /*) ;; *) NOTIFY_PATH="/$NOTIFY_PATH" ;; esac

if [ -t 1 ]; then
    GREEN=$'\033[0;32m'; RED=$'\033[0;31m'; YELLOW=$'\033[1;33m'; BOLD=$'\033[1m'; NC=$'\033[0m'
else
    GREEN=''; RED=''; YELLOW=''; BOLD=''; NC=''
fi

PASS=0; FAIL=0; WARN=0
pass() { printf '%sPASS%s  %s\n' "$GREEN" "$NC" "$1"; PASS=$((PASS+1)); }
fail() { printf '%sFAIL%s  %s\n' "$RED" "$NC" "$1"; FAIL=$((FAIL+1)); }
warn() { printf '%sWARN%s  %s\n' "$YELLOW" "$NC" "$1"; WARN=$((WARN+1)); }
note() { printf '      %s\n' "$1"; }
section() { printf '\n%s%s%s\n' "$BOLD" "$1" "$NC"; }

# curl wrapper with a generous connect timeout (first hit may wake a stopped Fly machine).
xcurl() {
    curl --connect-timeout 30 --max-time 60 -s "$@"
}

# Returns just the status code from a request.
status() {
    xcurl -o /dev/null -w '%{http_code}' "$@" || echo "000"
}

printf '%sOpen Green Button — utility readiness check%s\n' "$BOLD" "$NC"
printf '  Base URL: %s\n' "$BASE_URL"
printf '  Utility:  %s\n' "$UTILITY"
printf '  Notify:   %s\n' "$NOTIFY_PATH"
printf '  (first request may take 5-15s if Fly machine is stopped)\n'

# DNS + TLS only matter against a real public URL; skip when caller is exercising a local
# loopback for development.
IS_PUBLIC=true
case "$BASE_URL" in
    http://*|*localhost*|*127.0.0.1*|*::1*) IS_PUBLIC=false ;;
esac

if [ "$IS_PUBLIC" = "true" ]; then
# ------------------------------------------------------------------
section "DNS"
# ------------------------------------------------------------------
if getent hosts "$HOST" >/dev/null 2>&1; then
    pass "$HOST resolves"
    note "$(getent hosts "$HOST" | head -3 | awk '{print $1}' | paste -sd' ' -)"
else
    fail "$HOST does not resolve"
fi

# ------------------------------------------------------------------
section "TLS"
# ------------------------------------------------------------------
if cert=$(echo | openssl s_client -servername "$HOST" -connect "$HOST:443" 2>/dev/null \
            | openssl x509 -noout -dates -issuer -subject 2>/dev/null); then
    if [ -n "$cert" ]; then
        pass "Certificate present and parseable"
        echo "$cert" | sed 's/^/      /'
        # Reject self-signed or very-soon-to-expire certs
        not_after=$(echo "$cert" | awk -F= '/notAfter/ {print $2}')
        if [ -n "$not_after" ]; then
            expiry_epoch=$(date -d "$not_after" +%s 2>/dev/null || echo 0)
            now_epoch=$(date +%s)
            days_left=$(( (expiry_epoch - now_epoch) / 86400 ))
            if [ "$days_left" -lt 14 ]; then
                warn "Cert expires in $days_left days"
            else
                pass "Cert valid for at least 14 more days ($days_left days remaining)"
            fi
        fi
        if echo "$cert" | grep -qi 'issuer=.*self'; then
            fail "Certificate is self-signed"
        fi
    else
        fail "TLS handshake succeeded but certificate could not be parsed"
    fi
else
    fail "TLS handshake failed"
fi
fi  # IS_PUBLIC

# ------------------------------------------------------------------
section "Liveness"
# ------------------------------------------------------------------
s=$(status "$BASE_URL/health")
[ "$s" = "200" ] && pass "/health returns 200" || fail "/health returned $s (expected 200)"
s=$(status "$BASE_URL/ready")
[ "$s" = "200" ] && pass "/ready returns 200" || fail "/ready returned $s (expected 200)"

# ------------------------------------------------------------------
section "Landing page"
# ------------------------------------------------------------------
body=$(xcurl "$BASE_URL/" || true)
if echo "$body" | grep -qi 'Open Green Button'; then
    pass "/ contains project branding"
else
    fail "/ does not contain expected 'Open Green Button' branding"
fi

# Verify favicons + manifest are reachable (Burlington reviewers may load the page in a browser
# and notice 404s in devtools; better to catch missing assets here).
for asset in favicon.ico favicon.svg site.webmanifest logo-horizontal.svg; do
    code=$(status "$BASE_URL/$asset")
    if [ "$code" = "200" ]; then
        pass "/$asset returns 200"
    else
        warn "/$asset returns $code (expected 200)"
    fi
done

# ------------------------------------------------------------------
section "Security headers"
# ------------------------------------------------------------------
headers=$(xcurl -D - -o /dev/null "$BASE_URL/" || true)
for entry in 'x-frame-options:DENY' 'x-content-type-options:nosniff' 'referrer-policy:no-referrer'; do
    name="${entry%%:*}"
    expected="${entry##*:}"
    if echo "$headers" | grep -qi "^$name:"; then
        actual=$(echo "$headers" | grep -i "^$name:" | head -1 | sed -E 's/^[^:]+:[[:space:]]*//; s/\r$//')
        if echo "$actual" | grep -qi "$expected"; then
            pass "$name: $actual"
        else
            warn "$name set to '$actual' (expected to contain '$expected')"
        fi
    else
        warn "Missing $name header"
    fi
done
# HSTS isn't set in code — Fly's edge typically adds it for HTTPS responses. Flag if absent.
if echo "$headers" | grep -qi '^strict-transport-security:'; then
    pass "strict-transport-security present"
else
    warn "strict-transport-security not present (Fly normally adds it on HTTPS — worth checking)"
fi

# ------------------------------------------------------------------
section "OAuth callback robustness"
# ------------------------------------------------------------------
# Each of these should NOT 5xx — the worst is 400/404. Burlington's reviewer will likely poke
# at the callback URL with crafted inputs to see if anything crashes.
s=$(status "$BASE_URL/connect/$UTILITY/callback")
[ "$s" = "400" ] && pass "Callback with no params returns 400" \
                 || fail "Callback with no params returned $s (expected 400)"

s=$(status "$BASE_URL/connect/$UTILITY/callback?error=access_denied")
[ "$s" = "400" ] && pass "Callback with error=access_denied returns 400" \
                 || fail "Callback with error param returned $s (expected 400)"

s=$(status "$BASE_URL/connect/$UTILITY/callback?code=fake&state=unknown-replay-attempt")
[ "$s" = "400" ] && pass "Callback with unknown state returns 400 (replay/CSRF protection)" \
                 || fail "Callback with bogus state returned $s (expected 400)"

s=$(status "$BASE_URL/connect/no_such_utility/callback?code=x&state=y")
[ "$s" = "404" ] && pass "Callback for unknown utility returns 404" \
                 || fail "Unknown-utility callback returned $s (expected 404)"

# /utilities — the discovery endpoint the HA config flow calls
utilities_body=$(xcurl "$BASE_URL/utilities" || true)
utilities_code=$(status "$BASE_URL/utilities")
if [ "$utilities_code" = "200" ]; then
    pass "GET /utilities returns 200"
    if echo "$utilities_body" | grep -q "\"id\":\"$UTILITY\""; then
        pass "/utilities lists '$UTILITY'"
    else
        fail "/utilities does not list '$UTILITY' — got: $utilities_body"
    fi
else
    fail "GET /utilities returned $utilities_code (expected 200)"
fi

# Verify no stack traces / internal details leak in the error page body
error_body=$(xcurl "$BASE_URL/connect/$UTILITY/callback?code=fake&state=junk" || true)
if echo "$error_body" | grep -qiE 'stack|exception|caused by|kotlin\.|java\.lang'; then
    fail "Error page leaks stack trace / internal class names"
    echo "$error_body" | head -10 | sed 's/^/      /'
else
    pass "Error page does not leak stack traces or internal types"
fi

# ------------------------------------------------------------------
section "NotificationURI"
# ------------------------------------------------------------------
s=$(status -X POST -H 'Content-Type: application/atom+xml' \
       --data-binary '<?xml version="1.0"?><feed/>' \
       "$BASE_URL$NOTIFY_PATH")
if [[ "$s" =~ ^2 ]]; then
    pass "POST $NOTIFY_PATH returns $s"
else
    fail "POST $NOTIFY_PATH returned $s (expected 2xx)"
fi
# GET should not be 200 — notify is POST-only
s=$(status "$BASE_URL$NOTIFY_PATH")
case "$s" in
    404|405|400) pass "GET $NOTIFY_PATH returns $s (POST-only as designed)" ;;
    200) fail "GET $NOTIFY_PATH returns 200 — endpoint should reject GET" ;;
    *) warn "GET $NOTIFY_PATH returns $s (would expect 404 or 405)" ;;
esac

# ------------------------------------------------------------------
section "OAuth start (redirect)"
# ------------------------------------------------------------------
s=$(status "$BASE_URL/connect/$UTILITY/start")
if [ "$s" = "302" ]; then
    pass "/connect/$UTILITY/start returns 302"
    loc=$(xcurl -D - -o /dev/null "$BASE_URL/connect/$UTILITY/start" \
            | awk -F': ' 'tolower($1)=="location" {print $2}' | tr -d '\r')
    note "Location: $loc"
    # Inspect query params we sent — these are what Burlington's authorize endpoint will receive
    for p in response_type client_id redirect_uri scope state; do
        if echo "$loc" | grep -q "[?&]$p="; then
            pass "Redirect carries '$p' parameter"
        else
            fail "Redirect missing '$p' parameter"
        fi
    done
    expected_redirect="$BASE_URL/connect/$UTILITY/callback"
    if echo "$loc" | grep -q "redirect_uri=$(printf %s "$expected_redirect" | sed 's|/|%2F|g; s|:|%3A|g')"; then
        pass "redirect_uri matches expected $expected_redirect"
    else
        warn "redirect_uri in redirect doesn't match expected. Found:"
        echo "      $(echo "$loc" | grep -oE 'redirect_uri=[^&]*')"
    fi
else
    fail "/connect/$UTILITY/start returned $s (expected 302)"
fi

# ------------------------------------------------------------------
section "Claim redemption"
# ------------------------------------------------------------------
s=$(status -X POST "$BASE_URL/claim/gb_live_nonexistent_definitely_not_real")
[ "$s" = "410" ] && pass "Unknown claim code returns 410 Gone" \
                 || fail "Unknown claim code returned $s (expected 410)"

# ------------------------------------------------------------------
section "Request tracing"
# ------------------------------------------------------------------
# We should reflect X-Request-Id (or generate one) — useful for support tickets later.
echo_id="readiness-$(date +%s)-$$"
got=$(xcurl -H "X-Request-Id: $echo_id" -D - -o /dev/null "$BASE_URL/health" \
       | awk -F': ' 'tolower($1)=="x-request-id" {print $2}' | tr -d '\r')
if [ "$got" = "$echo_id" ]; then
    pass "X-Request-Id is reflected back in the response ($got)"
else
    warn "X-Request-Id not reflected — sent '$echo_id', got '$got'"
fi

# ------------------------------------------------------------------
section "Summary"
# ------------------------------------------------------------------
printf '%s%d passed%s, %s%d failed%s, %s%d warnings%s\n' \
    "$GREEN" "$PASS" "$NC" "$RED" "$FAIL" "$NC" "$YELLOW" "$WARN" "$NC"

if [ "$FAIL" -gt 0 ]; then
    printf '\n%sNOT READY%s — fix the FAIL items before sending the URLs to the utility.\n' "$RED" "$NC"
    exit 1
fi

if [ "$WARN" -gt 0 ]; then
    printf '\n%sReady with caveats%s — review the WARN items but you can submit.\n' "$YELLOW" "$NC"
fi

printf '\n%sReady to submit to the utility.%s\n' "$GREEN" "$NC"
