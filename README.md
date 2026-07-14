# Open Green Button

An open-source [Green Button](https://www.greenbuttondata.org/) (NAESB ESPI) third-party application that bridges utility energy data into [Home Assistant](https://www.home-assistant.io/).

The hosted server is a **stateless OAuth proxy**: utilities require a stable public callback URL, but your data never lives on our server. Refresh tokens are stored encrypted on your Home Assistant instance, and every request flows through the proxy carrying the token from your side.

## Status

* ✅ **Live!** Burlington Hydro (Ontario, Canada).
* ✅ **Live!** Consumers Energy (Michigan, USA), thank you Michael Phillippi ([@philliml74](https://github.com/philliml74)).
* ✅ **Live!** Kentucky Utilities (Kentucky, USA), thank you Tag Howard ([@jthoward64](https://github.com/jthoward64)).
* 🚧 **Registration in progress** Alectra (Ontario, Canada).
* 🚧 **Registration in progress** Elexicon Energy (Ontario, Canada), thank you Muhammad Aziz ([@rabbitholelabsinc](https://github.com/rabbitholelabsinc)).
* 🚧 **Registration in progress** Elk Energy (Ontario, Canada).
* 🚧 **Registration in progress** Enwin (Ontario, Canada).
* 🚧 **Registration in progress** Festival Hydro (Ontario, Canada).
* 🚧 **Registration in progress** London Hydro (Ontario, Canada).
* 🚧 **Registration in progress** Milton Hydro (Ontario, Canada), thank you Jeff Aycan ([@JAudi23](https://github.com/JAudi23)).
* 🚧 **Registration in progress** Newmarket-Tay (NT) Power (Ontario, Canada).
* 🚧 **Registration in progress** Niagara Peninsula Energy (Ontario, Canada).
* 🚧 **Registration in progress** Oakville Hydro (Ontario, Canada).
* 🚧 **Registration in progress** Oshawa Power (Ontario, Canada).
* 🚧 **Registration in progress** Pacific Gas & Electric (California).

### New Utility Support

Request a new utility here:

https://github.com/rocketraman/open-green-button/issues/new?template=new-utility-request.md

## Components

- `server/` — Kotlin/Ktor proxy server (deployed to Fly.io with scale-to-zero)
- `docs/` — Architecture, deployment, per-utility notes
- `branding/` — Logo and brand assets

The **Home Assistant custom integration** lives in its own repository so HACS validation finds the canonical `custom_components/` + `hacs.json` layout at the repo root: [rocketraman/open-green-button-homeassistant](https://github.com/rocketraman/open-green-button-homeassistant).

## Privacy

The hosted server holds **no per-user durable state**. Per-utility OAuth client credentials are configured globally; every other piece of state (your refresh token, your usage data) lives only on your Home Assistant instance.

## Community

Discuss the add-on, ask questions, and share feedback on the Home Assistant community forum:

https://community.home-assistant.io/t/utility-energy-data-integration-via-green-button-connect/1016031

## Support

Open Green Button is free to use. If it saves you time or you'd like to help keep it maintained and hosted (there's a small Fly.io bill and ongoing time spent adding new utilities and keeping up with Home Assistant changes), donations are welcome.

**Suggested: $5 / month** — roughly enough to cover hosting plus a contribution toward maintenance time. Anything above that funds new utility integrations.

- [Sponsor on GitHub](https://github.com/sponsors/rocketraman)
- [Buy Me a Coffee](https://www.buymeacoffee.com/rocketraman)

## License

[MIT](LICENSE)
