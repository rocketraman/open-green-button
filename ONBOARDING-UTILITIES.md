# **Onboarding a New Utility**

## Understanding

It will help while following this process to understand the overall flow and role of each piece in the system.

"Open Green Button" is a third-party data provider from the perspective of the utility, and the utilities customer.
The utility obtains the customer's consent for the third-party data provider to access the customer's data on the customer's behalf.
Most third-party data providers ingest the data and provide access to it via their own apps or systems, along with analysis or other tooling.
In this project, we simply allow you, the customer, to use "Open Green Button" as a proxy to obtain your own data, via client-side add-ons like [Open Green Button Home Assistant](https://github.com/rocketraman/open-green-button-homeassistant/).

To set this up, utilities require each third party to register with them.
That is the purpose of this document.

Registering makes **you** — not the project, which is not a legal entity — the party to the utility's agreement, and sharing credentials with the hosted proxy carries its own implications.
Both are covered in [Legal](README.md#legal); please read that section before you start.

## Steps

1. Request access to Google Group **opengreenbutton@googlegroups.com** via email to [rocketraman@gmail.com](mailto:rocketraman@gmail.com).

    This is a Google Group I've set up so that ongoing correspondence from the utility will use a common email address available to the community, rather than just a single personal address.

1. Find the Green Button third-party registration form on the utility site.

   Use **Open Green Button** as the **business name**.
   Some forms split this into a **client name** (the OAuth client) and a **third-party name** (what the customer sees when authorizing) — use **Open Green Button** for both.

   Use **opengreenbutton@googlegroups.com** as the **email address**.

   If you are asked for a **phone number**, use your own.
   Note it on the utility documentation page (see below) alongside the rest of the "Information on File".

   If you are asked for a **description / summary**, use:

   > An open source integration of Green Button data into your local Home Assistant instance. Creates the necessary statistics in Home Assistant that allow you to track energy usage and cost on the Home Assistant Energy Dashboard and to define home automations and alerts based on energy usage.

   If you are asked for a **scope of use**, use:

   > Customer energy usage and cost / billing information is made available to the customer for use within their own on-premises analysis software. We also obtain customer account information for display within the same software, so that customer can visually associate their account with the integration.

   If you are asked for a **security statement** or **security certifications**, use:

   > Data is never stored by us -- end consumers store their own data on their own systems. Please see the description of the product and "Privacy is built-in" section at https://opengreenbutton.org/.

   If you are asked for **title / subtitle**, use "Open Green Button Home Assistant Integration".

   If you are asked for **logos**, you can use the logos from here: https://github.com/rocketraman/open-green-button/tree/master/branding (see the `web` directory for PNGs).

   If you are asked for **URIs**, create a lower-case path slug for the utility e.g. `burlington_hydro` for Burlington Hydro.
   See the following table, replace `<utility_slug>` with the slug you created:

   | Form Field | URI                                                                                                           |
   | ------------- |------------------------------------------------------------------------------------------------------------|
   | Application URI (a.k.a. Client URI) | `https://opengreenbutton.org`                                                          |
   | Redirect URI | `https://api.opengreenbutton.org/connect/<utility_slug>/callback`                                           |
   | Notification URI (a.k.a. Third-Party Notify URI) | `https://api.opengreenbutton.org/notify/<utility_slug>`                 |
   | User Portal Screen URI | `https://api.opengreenbutton.org/connect/<utility_slug>/scope`                                    |
   | Policy URI | `https://opengreenbutton.org/#privacy`                                                                        |
   | Logo URI | `https://raw.githubusercontent.com/rocketraman/open-green-button/refs/heads/master/branding/logo-horizontal.svg`|

   Use the same slug in every one of those URIs.
   If the form mentions pixel dimensions for the logo, give it the PNG instead: `https://raw.githubusercontent.com/rocketraman/open-green-button/refs/heads/master/branding/web/logo-horizontal.png`.

   Some forms — generally the ESPI 3.x ones — ask for the OAuth client registration details as well:

   | Form Field | Value |
   | ------------- | ------------- |
   | Software ID | `4c47b008-1e67-4f01-9731-e4e7008092d6` |
   | Software Version | `1.0` |
   | Third-Party Application Status | `Production` |
   | Third-Party Application Type | `Web` |
   | Third-Party Application Use | `Energy management` |
   | Token Endpoint Authentication Method | `client_secret_basic` |
   | Grant Types | `authorization_code, refresh_token, client_credentials` |
   | Response Types | `code` |

   The Software ID identifies the software rather than any one registration, so use that same UUID for every utility — do not generate a new one.

   If you are asked for an **SSL certificate**, first try `ogb-ca.crt` from the `certs/` directory.
   If that doesn't work, try `ogb-client.crt`.
   If that doesn't work, try `ogb-client-bundle.crt`.
   If that doesn't work, contact me.

   If you are asked for **Scope Information**, choose:
   * Usage information (4)
     * Interval electricity (5)
     * Demand electricity (6)
     * Forward or reverse metering (8)
     * Cost (12)
   * Billing information (15, 16, 17, 27, 28) -- might not be needed but can't hurt
   * Customer information (51, 54, 55, 56, 57, 58, 59, 60, 61) -- for displaying account info on the HA add-on page

   Some utilities also require Common (1) and Connect My Data (3), so include those if they are offered.

   Instead of checkboxes, some forms ask for the **scope** as a raw ESPI scope string.
   Note that an ESPI scope string contains no spaces — everything is packed into `;`-separated `Key=Value` pairs, with `_` separating values within a pair — so it is a single value even where the form says "space-separated list".
   Build it from the function blocks above plus whatever format parameters the utility says it supports, e.g.:

   > `FB=1_3_4_5_12_15_16_17_27_28_51_54_55_56_57_58_59_60_61;IntervalDuration=900_3600;BlockDuration=Monthly;HistoryLength=94608000;SubscriptionFrequency=Daily`

   `HistoryLength` is in seconds (94608000 is 36 months).
   If the utility rejects the string, drop the format parameters and retry with just the `FB=` list — some sandboxes accept only that.
   See the comments at the top of [`utilities.conf`](https://github.com/rocketraman/open-green-button/blob/master/server/app/src/main/resources/utilities.conf) for what each format parameter does.

   If you are asked for the **information sharing period**, choose "Daily" (this is the same thing as `SubscriptionFrequency=Daily` above).

1. Create a utility documentation page at https://github.com/rocketraman/open-green-button/tree/master/docs/utilities (example https://github.com/rocketraman/open-green-button/blob/master/docs/utilities/milton-hydro.md).
Note yourself as the "Information on File" for the utility.
This is so the community knows whose information each utility has on record.
Also note in here if a client certificate was required for mTLS, and if so, which one.
If no client certificate was required, note "Not checked by utility."

1. Please share any secrets, such as client id and client password by sending them to [rocketraman@gmail.com](mailto:rocketraman@gmail.com).
I will upload the credentials into the running proxy on Fly.io as secrets.
In addition, if there are any credentials for portal or dashboard access, please share those as well.

This step is what puts your utility connection on the community's hosted proxy, and it is optional — see [the hosted proxy](README.md#the-hosted-proxy) for what it means for you as the registrant, and [docs/deployment.md](docs/deployment.md) if you would rather run your own deployment and keep the credentials yourself.

1. After that we follow the utility registration process.
This will generally require setting up a test account against a sandbox system, having the utility validate it, and then the utility will move us to production, providing us with the necessary URLs and credentials.
Most of this can be configured via Pull Requests to the [open-green-button](https://github.com/rocketraman/open-green-button) repository, specifically updating `utilities.conf`, which a tool like Claude Code can help with.
Point it to https://github.com/rocketraman/open-green-button/blob/master/server/app/src/main/resources/utilities.conf and this document.
The only thing that cannot be configured there directly are the client id and password credentials (see the previous step).

If you are asked for anything else I haven't anticipated or don't understand a step, email me, post on the Google Group, or [create a discussion in GitHub](https://github.com/rocketraman/open-green-button/discussions).

Good luck!
