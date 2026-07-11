# Kentucky Utilities

Part of **LG&E and KU Energy** (PPL Corp), on the **My Meter** platform (`mymeter.lge-ku.com`).

## Information on File

Registration created by Joshua Tag Howard (@jaudi23) using his own contact information.
See https://github.com/rocketraman/open-green-button/issues/15.

## mTLS Status

Not checked by utility.

## Operational notes

- **Client secret never expires** (`client_secret_expires_at = 0`) — no rotation to track.
- Refresh token request requires a scope -- an omitted scope causes an `invalid_scope` failure.

## Customer onboarding (non-obvious)

The customer needs a My Meter **local** account, **separate from their normal "My Account" login**:

1. Request a My Meter registration code — email `mymeter@lge-ku.com` (or the site's "Feedback" link).
2. "Create an Account" on the My Meter site with that code.
   **The local-account email must differ from the My Account primary email.**
   (Shortcut found in practice: log into My Meter from KU's own site and invite yourself under a different email, skipping the code request.)
3. Authorize from our connect flow — the customer picks period/bulk/subscription; we receive the token only after they finish.
   All usage + billing data (VEE + raw) becomes available.
4. Revocable anytime from either side.
