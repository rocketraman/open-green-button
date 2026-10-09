# Savage Data

Savage Data runs the Green Button service for several Ontario utilities.

## Utilities

| Utility | Proxy id | Notes |
| ------------- | ------------- | ------------- |
| Alectra | `alectra` | [alectra.md](../utilities/alectra.md) |
| Elexicon Energy | `elexicon_energy` | [elexicon-energy.md](../utilities/elexicon-energy.md) |
| Enova Power | `enova_hydro` | [enova-hydro.md](../utilities/enova-hydro.md) |
| Fortis Ontario (Algoma Power, Canadian Niagara Power, Cornwall Electric, Eastern Ontario Power) | `fortis_ontario` | [fortis-ontario.md](../utilities/fortis-ontario.md) — sandbox, registration in progress |
| Hydro Ottawa | `hydro_ottawa` | [hydro-ottawa.md](../utilities/hydro-ottawa.md) |
| Milton Hydro | `milton_hydro` | [milton-hydro.md](../utilities/milton-hydro.md) |
| Toronto Hydro | `toronto_hydro` | [toronto_hydro.md](../utilities/toronto_hydro.md) |

Issues for these utilities are labeled [`provider:savage-data`](https://github.com/rocketraman/open-green-button/issues?q=label%3Aprovider%3Asavage-data).

## Onboarding

Each utility is a separate application registration with its own client id, client secret, and scope, even though they all run on the same platform.

### 1. Register on the utility's onboarding portal

Each utility has its own Savage Data onboarding portal (for example `https://enovaonboarding.savagedata.com/`).
Register the application with these URIs, substituting the proxy id:

| Field | Value |
| ------------- | ------------- |
| Client URI | `https://opengreenbutton.org` |
| Redirect URI | `https://api.opengreenbutton.org/connect/<id>/callback` |
| Notify URI | `https://api.opengreenbutton.org/notify/<id>` |
| User Portal URI | `https://api.opengreenbutton.org/connect/<id>/scope` |
| Logo URI | `https://raw.githubusercontent.com/rocketraman/open-green-button/refs/heads/master/branding/logo-horizontal.svg` |

Once registered, the portal shows the client id, the client secret, the scope, and two test accounts.

### 2. Add the sandbox entry

All utilities share one certification sandbox Data Custodian at `https://sandboxdc.savagedata.com:4243`.
Add the utility to `utilities.conf` pointing at the sandbox:

- `authorizeUrl = "https://sandboxdc.savagedata.com:4243/connect/authorize"`
- `tokenUrl = "https://sandboxdc.savagedata.com:4243/connect/token"`
- `defaultScope` copied exactly from the portal, since the function block list differs between utilities.
- `displayName` suffixed with `(SANDBOX FOR TESTING ONLY)`.

Set the `OPENGB_UTILITY_<ID>_CLIENTID` and `OPENGB_UTILITY_<ID>_CLIENTSECRET` Fly secrets from the portal values, then deploy.

### 3. Connect Home Assistant to both test accounts

Connect Home Assistant to the sandbox utility once for each of the two test accounts listed on the portal.
This is the step that advances the registration: within about half an hour of both accounts being connected, Savage Data changes the application status to "Waiting for approval".

Running the `onboardFetchAppInfo` driver is not required for this.

### 4. Promote to production

After the utility approves the application, switch the entry to the utility's production Data Custodian at `https://<utility>dc.savagedata.com` (for example `https://miltondc.savagedata.com/connect/authorize`), and remove the sandbox suffix from `displayName`.

## Notes

Do not copy Alectra's `quirks` block onto other Savage Data utilities.
See the comment on the `alectra` entry in `utilities.conf` for why.
