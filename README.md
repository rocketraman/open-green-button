# Open Green Button

An open-source [Green Button](https://www.greenbuttondata.org/) (NAESB ESPI) third-party application that bridges utility energy data into [Home Assistant](https://www.home-assistant.io/).

The hosted server is a **stateless OAuth proxy**: utilities require a stable public callback URL, but your data never lives on our server. Refresh tokens are stored encrypted on your Home Assistant instance, and every request flows through the proxy carrying the token from your side.

## Status

### Live ✅

The following utilities are available to use now:

* Alectra (Ontario, Canada).
* Burlington Hydro (Ontario, Canada).
* Consumers Energy (Michigan, USA), thank you Michael Phillippi ([@philliml74](https://github.com/philliml74)).
* Elexicon Energy (Ontario, Canada), thank you Muhammad Aziz ([@rabbitholelabsinc](https://github.com/rabbitholelabsinc)).
* Elk Energy (Ontario, Canada).
* El Paso Electric (New Mexico / Texas, USA).
* Enbridge Gas (Ontario, Canada).
* Enova (Ontario, Canada), thank you Kate Kennedy ([@kennek6](https://github.com/kennek6)).
* Enwin (Ontario, Canada).
* Eversource (Massachusetts, USA), thank you Devin Kelly ([@dwwkelly](https://github.com/dwwkelly)).
* Festival Hydro (Ontario, Canada).
* Fortis Ontario (Algoma Power, Canadian Niagara Power, Cornwall Electric, Eastern Ontario Power) (Ontario, Canada), thank you William Seaton ([@william-seaton](https://github.com/william-seaton)).
* Grand Bridge Energy (Ontario, Canada), thank you Grant Emsley ([@grantemsley](https://github.com/grantemsley)).
* Hydro Ottawa (Ontario, Canada), thank you Mike Wrightly ([@mikewrightly](https://github.com/mikewrightly)).
* Kentucky Utilities (Kentucky, USA), thank you Tag Howard ([@jthoward64](https://github.com/jthoward64)).
* London Hydro (Ontario, Canada).
* Milton Hydro (Ontario, Canada), thank you Jeff Aycan ([@JAudi23](https://github.com/JAudi23)).
* Newmarket-Tay (NT) Power (Ontario, Canada).
* Niagara Peninsula Energy (Ontario, Canada).
* Oakville Hydro (Ontario, Canada).
* Oshawa Power (Ontario, Canada).
* Toronto Hydro (Ontario, Canada), thank you Mathew Lisk ([@Mlisk](https://github.com/Mlisk)).

### In Progress 🚧

None

### Not Supported or Blocked

* DTE Energy (Michigan, USA), [no Green Button Connect support](https://github.com/rocketraman/open-green-button/issues/12#issuecomment-5607485371).
* Pacific Gas & Electric (California, USA), [not approved, registration On Hold](https://github.com/rocketraman/open-green-button/issues/3#issuecomment-5275109009).
* Rhode Island Energy (Rhode Island, USA), [no longer supporting Green Button Connect](https://github.com/rocketraman/open-green-button/issues/37#issuecomment-5500880192).
* SDG&E San Diego Gas & Electric (California, USA), [implementation requires an sFTP server](https://github.com/rocketraman/open-green-button/issues/11#issuecomment-5321240543).
* Southern California Edison (SCE) (California, USA), [requires a 3rd party signed client certificate](https://github.com/rocketraman/open-green-button/issues/46), blocked on a certificate being donated to the project

### Water Data

Utilities whose Green Button service is run by London Hydro provide **electricity only**: Burlington Hydro, Elk Energy, Enwin, Festival Hydro, London Hydro, Newmarket-Tay (NT) Power, Niagara Peninsula Energy, Oakville Hydro, and Oshawa Power.
Water is not part of Ontario's Green Button mandate, and London Hydro does not offer it for any of these utilities, even where the utility bills you for water.
See the [London Hydro provider notes](docs/providers/london-hydro.md).

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

The server does write short-lived operational logs, which the hosting platform retains for a few days.
They record that a request happened, for which utility, and how the utility answered — never your usage data or tokens.
Identifiers that belong to you are not stored as-is: your utility subscription and meter ids are logged only as a keyed hash, your IP address only as its network (the last part zeroed), and the query string of a request not at all.

## Community

Discuss the add-on, ask questions, and share feedback on the Home Assistant community forum:

https://community.home-assistant.io/t/utility-energy-data-integration-via-green-button-connect/1016031

## Support

Open Green Button is free to use. If it saves you time or you'd like to help keep it maintained and hosted (there's a small Fly.io bill and ongoing time spent adding new utilities and keeping up with Home Assistant changes), donations are welcome.

**Suggested: $5 / month** — roughly enough to cover hosting plus a contribution toward maintenance time. Anything above that funds new utility integrations.

- [Sponsor on GitHub](https://github.com/sponsors/rocketraman)
- [Buy Me a Coffee](https://www.buymeacoffee.com/rocketraman)

## Legal

Open Green Button is an open community project.
It is not a legal entity, and it is not affiliated with or endorsed by any utility.
The software is free for anyone — individuals and organizations alike — to use as a tool for obtaining Green Button access to utility data, under the terms of the [MIT license](LICENSE):

> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
> IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### Registering with a utility

Registering as a third party with a utility is separate from using this software.
Whoever registers — whether an individual or a legal entity — is the party to any agreement with that utility, and is responsible for complying with its terms.
The Open Green Button project is not a party to that agreement and assumes no obligations under it, even where the registration is filed under the Open Green Button name and shared contact address as described in [ONBOARDING-UTILITIES.md](ONBOARDING-UTILITIES.md).

Registrants are encouraged, but not required, to use the Open Green Button name and [branding](branding/), and to make the resulting connection available to other users of this project.

### The hosted proxy

Registrants who make their connection available to the community through the hosted proxy at `https://api.opengreenbutton.org` share the utility's OAuth client credentials with the project maintainers, who configure them on that server.
That proxy is operated by volunteers on a best-effort basis, with no uptime or support commitment, under the same no-warranty terms as the software itself.
It holds no per-user durable state (see [Privacy](#privacy)), but a registrant remains responsible to their utility for the traffic that flows through it.

Using the hosted proxy is a choice, not a requirement: the server in this repository can be deployed by anyone (see [docs/deployment.md](docs/deployment.md)), so credentials never have to leave the registrant's hands.

### License and trademark

[MIT](LICENSE)

"Green Button" is a trademark of the Green Button Alliance; this project uses the name in reference to the open data standard.
