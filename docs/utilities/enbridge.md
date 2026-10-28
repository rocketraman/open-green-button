# Enbridge Gas (Ontario, Canada)

## Provider

Enbridge Gas runs its own Green Button Connect My Data service (Okta-based authorization server).

* Token endpoint: `https://api.enbridgegas.com/oauth2/default/v1/token`
* Customer authorization (consent) page: `https://myaccount.enbridgegas.com/green-button/sign-in`
* Resource server: `https://api.enbridgegas.com/espi/1_1/resource/`

There is **no test environment and no test customers**. Connectivity testing runs against production,
and the first end-to-end authorization has to come from a real Enbridge Gas customer.

## Onboarding

Enbridge's third-party onboarding document describes three checks, all client_credentials grants
against the token endpoint (HTTP Basic, `client_id:client_secret`):

1. **Connectivity**: scope `FB=34_35`, then `GET .../resource/ReadServiceStatus` with the bearer
   token — expect 200.
2. **Registration details**: scope `FB=36_40`. The token response carries a `resourceURI` pointing at
   our ApplicationInformation. Set it as `OPENGB_ONBOARD_ENBRIDGE_APP_INFO_URL` (with
   `OPENGB_ONBOARD_ENBRIDGE_TOKEN_URL` and `OPENGB_ONBOARD_ENBRIDGE_REG_SCOPE=FB=36_40`) and run
   `./gradlew :app:onboardFetchAppInfo --args="enbridge"` to dump `client_id`, `scope` and
   `redirect_uri`. The `defaultScope` in `utilities.conf` must match the registered `scope`.
3. **Consent URL**: built by the server — customers start at
   `https://api.opengreenbutton.org/connect/enbridge/scope`.

ApplicationInformation lists seven registered scopes: every combination of usage (FB 4, 10),
billing (FB 15) and account (FB 51–69) over a common base, matching the categories the customer can
tick on Enbridge's consent form. `utilities.conf` requests the all-three combination.

The utility slug is `enbridge`. The `redirect_uri` was originally submitted as the literal template
`https://api.opengreenbutton.org/connect/<utility_slug>/callback`; Enbridge corrected it to
`https://api.opengreenbutton.org/connect/enbridge/callback` on 2026-10-28.

Okta only issues a refresh token when the scope includes `offline_access`, so it is appended
(space-separated) to the ESPI scope string.

Live as of 2026-10-28: Enbridge approved the registration (`dataCustodianApplicationStatus = 2`),
and a real customer authorization completed end to end.

## Feed Quirks

Every `powerOfTenMultiplier` in the feed has the wrong sign. ESPI's real quantity is
value × 10^multiplier; Enbridge scales the raw value *up* instead, so a 538 m³ month arrives as
`538000` at multiplier `3`, and a $35.03 bill as `35030` at `3`. Read per spec, usage and cost come
out a million times too large.

`utilities.conf` sets two client quirks for Enbridge, forwarded to the Home Assistant integration
in the `OpenGB-Quirks` header:

* `invertUsageIfPositiveMultiplier`: ReadingType multipliers (interval usage).
* `invertCostIfPositiveMultiplier`: UsageSummary cost-line multipliers (billing).

Each flips only a *positive* multiplier, so both stay correct if Enbridge fixes its feed before
they are removed. Reported to Enbridge 2026-10-28. Once their feed sends `-3`, remove the quirks;
the integration sees the quirk set change and rebuilds each entry's statistics automatically.

The UsageSummary's itemized cost lines don't add up to the bill on their own: they include $/m³
rates and the supply charge under two names. The integration takes the bill total from
`billLastPeriod`, at the scale of the matching "Amount Due" line.

Readings are gas volume in m³ (`uom` 42), one per billing period (roughly monthly), despite the
`IntervalDuration=86400` in the registered scope.

## Information on File

Registration created by Nadim Khimani, updated by Raman Gupta (@rocketraman) to correct utility
slugs, using opengreenbutton@googlegroups.com.

## mTLS Status

Not checked by utility.
