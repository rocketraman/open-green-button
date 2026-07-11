#!/usr/bin/env bash
# Connect DIRECTLY to savagedata (bypassing the OGB proxy) over mTLS with our leaf cert, to see
# raw responses/headers and confirm the client certificate is accepted.
#
# It does a client_credentials grant (the registration flow) and then GETs a resource, printing
# the full status + headers + body. Reads the leaf-cert keystore + registration creds from mise
# (mise.local.toml [env]); no .env needed.
#
#   scripts/diag/direct_savagedata.sh                 # GET ApplicationInformation (default)
#   scripts/diag/direct_savagedata.sh '<resource_url>'  # GET any resource URL
#
# NOTE ON WHAT THIS CAN TEST:
#   - ApplicationInformation and other registration-scoped resources: work with this token.
#   - The customer's Batch/Subscription/{id} (the usage data) needs the USER access token from the
#     Connect-My-Data flow, NOT this registration token — so this script will get 401/403 there,
#     not the 400 we see through the proxy. It's for proving mTLS + inspecting raw responses.
#     (To hit the Subscription directly you'd need to decrypt a claim blob with the Fly crypto key
#     to recover the refresh token — ask and I'll provide that script.)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# Pull OPENGB_* from mise's [env] (mise.local.toml). Works whether or not the shell has mise
# activated, as long as `mise` is on PATH and the repo is trusted (`mise trust`).
set -a; eval "$(cd "$ROOT" && mise env)"; set +a

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

# The mTLS leaf cert is the shared runtime credential (same key the server reads), not an
# onboarding-specific copy.
printf '%s' "$OPENGB_UTILITY_MILTON_HYDRO_CLIENTAUTH_KEYSTOREBASE64" | base64 -d > "$TMP/leaf.p12"
PASS="$OPENGB_UTILITY_MILTON_HYDRO_CLIENTAUTH_KEYSTOREPASSWORD"
# p12 -> PEM (avoids curl's `cert:password` colon-splitting on odd passwords)
openssl pkcs12 -in "$TMP/leaf.p12" -clcerts -nokeys  -passin "pass:$PASS" -out "$TMP/cert.pem" 2>/dev/null
openssl pkcs12 -in "$TMP/leaf.p12" -nocerts -nodes    -passin "pass:$PASS" -out "$TMP/key.pem"  2>/dev/null
MTLS=(--cert "$TMP/cert.pem" --key "$TMP/key.pem")

echo "== 1) client_credentials (mTLS) → registration_access_token =="
TOKJSON="$(curl -sS "${MTLS[@]}" \
  -u "$OPENGB_ONBOARD_MILTON_HYDRO_REG_CLIENT_ID:$OPENGB_ONBOARD_MILTON_HYDRO_REG_CLIENT_SECRET" \
  -d grant_type=client_credentials \
  --data-urlencode "scope=${OPENGB_ONBOARD_MILTON_HYDRO_REG_SCOPE:-}" \
  "$OPENGB_ONBOARD_MILTON_HYDRO_TOKEN_URL")"
TOK="$(printf '%s' "$TOKJSON" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')"
echo "  got access_token (${#TOK} chars)"

URL="${1:-$OPENGB_ONBOARD_MILTON_HYDRO_APP_INFO_URL}"
echo
echo "== 2) GET $URL (mTLS + bearer) — full response =="
curl -sS -i "${MTLS[@]}" \
  -H "Authorization: Bearer $TOK" \
  -H "Accept: application/atom+xml, application/xml" \
  "$URL"
echo
