# CY Pharmacies – Privacy Policy and pharmacy feed

Two things for the CY Pharmacies Android app, published with GitHub Pages:

- **Privacy policy** (`index.html`), at https://foti-s.github.io/cy-pharmacies-privacy/
- **Pharmacy feed** (`feed/v1/pharmacies.json`), at
  https://foti-s.github.io/cy-pharmacies-privacy/feed/v1/pharmacies.json

## The feed

One small file with everything the app needs: every district's duty pharmacies from last night
to three nights ahead, the register of all private pharmacies, and a map position for each
address. The app downloads it in one go instead of a hundred-odd requests to the sources, so its
lists and map appear at once. If the feed is missing or more than 18 hours old, the app reads the
sources directly, as before.

It's rebuilt every three hours by the **Update feed** workflow (`.github/workflows/feed.yml`),
which runs `generator/` and commits the result. It can also be run by hand from the Actions tab.

**Sources:** Pharmaceutical Services, Ministry of Health, Cyprus, via
[data.gov.cy](https://www.data.gov.cy) (CC BY 4.0) and [moh.gov.cy](https://www.moh.gov.cy).
Positions: [MapTiler](https://www.maptiler.com) geocoding, © OpenStreetMap contributors.

### The generator

- `generator/src/main/kotlin/cy/pharmacies/app/data/` is copied unchanged from the app, so the
  feed reads the sources exactly as the app does. After changing those files in the app, run
  `generator/sync-from-app.sh <path to the app repo>`.
- `generator/geocode-cache.json`: positions already found, so each address is looked up only
  once. Addresses not found at their street are tried again after a month.
- `generator/overrides.json`: hand-checked positions that win over the geocoder, as
  `{"address|area": [lat, lng]}` with the address and area in lower case, exactly as in the feed's
  `positions`.
- Run locally: `cd generator && MAPTILER_KEY=… ./gradlew run`.

The workflow needs a repository secret **`MAPTILER_KEY`** (a MapTiler API key) to look up new
addresses; without it, only addresses already in the cache get positions.
