**# Onboarding a New Utility**

## Understanding

It will help while following this process to understand the overall flow and role of each piece in the system.

"Open Green Button" is a third-party data provider from the perspective of the utility, and the utilities customer.
The utility obtains the customer's consent for the third-party data provider to access the customer's data on the customer's behalf.
Most third-party data providers ingest the data and provide access to it via their own apps or systems, along with analysis or other tooling.
In this project, we simply allow you, the customer, to use "Open Green Button" as a proxy to obtain your own data, via client-side add-ons like [Open Green Button Home Assistant](https://github.com/rocketraman/open-green-button-homeassistant/).

To set this up, utilities require each third party to register with them.
That is the purpose this document.

## Resources

Utility setup notes: https://github.com/rocketraman/open-green-button/wiki/Utility-Notes.

This is a good example to follow: https://github.com/rocketraman/open-green-button/wiki/Milton-Hydro

Make sure the side-bar is also updated.

## Steps

1. Request access to Google Group **opengreenbutton@googlegroups.com** via email to [rocketraman@gmail.com](mailto:rocketraman@gmail.com).

    This is a Google Group I've set up so that ongoing correspondence from the utility will use a common email address available to the community, rather than just a single personal address.

1. Find the Green Button third-party registration form on the utility site.

   Use **Open Green Button** as the **business name**.

   Use **opengreenbutton@googlegroups.com** as the **email address**.

   If you are asked for a **description / summary**, use:

   > An open source integration of Green Button data into your local Home Assistant instance. Creates the necessary statistics in Home Assistant that allow you to track energy usage and cost on the Home Assistant Energy Dashboard and to define home automations and alerts based on energy usage.

   If you are asked for **title / subtitle**, use "Open Green Button Home Assistant Integration".

   If you are asked for **logos**, you can use the logos from here: https://github.com/rocketraman/open-green-button/tree/master/branding.

   If you are asked for an SSL certificate, first try `ogb-ca.crt` from the `certs/` directory.
   If that doesn't work, try `ogb-client.crt`.
   If that doesn't work, try `ogb-client-bundle.crt`.
   If that doesn't work, contact me.

1. Create a utility wiki page at https://github.com/rocketraman/open-green-button/wiki/Utility-Notes (example https://github.com/rocketraman/open-green-button/wiki/Milton-Hydro).
Note yourself as the "Information on File" for the utility.
This is so the community knows who's information each utility has on record.
Also note in here if a client certificate was required, and if so, which one.

1. Please share any secrets, such as client id and client password by sending them to [rocketraman@gmail.com](mailto:rocketraman@gmail.com).
I will upload the credentials into the running proxy on Fly.io as secrets.

If you are asked for anything else I haven't anticipated, email me or post on the Google Group.

1. After that we follow the utility registration process.
This will generally require setting up a test account against a sandbox system, having the utility validate it, and then the utility will move us to production, providing us with the necessary URLs and credentials.
Most of this can be configured via Pull Requests to the [open-green-button](https://github.com/rocketraman/open-green-button) repository, specifically updating `utilities.conf`, which a tool like Claude Code can help with.
The only thing that cannot be configured there are the client id and password credentials (see the previous step).

Good luck!
