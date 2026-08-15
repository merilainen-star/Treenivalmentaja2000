# Strava setup

What you do once, on Strava's side, before the app can read your runs. Everything here happens in
a browser and takes a few minutes; the phone half is one screen in Settings.

This mirrors how Oura is set up, and for the same reason
([ADR-009](DECISIONS.md#adr-009-the-oura-client-credentials-are-entered-in-the-app-not-compiled-into-it)):
the credentials are typed into the app rather than compiled into the build, so the whole thing can
be done from a phone that only ever receives an APK.

## 1. Create a Strava API application

1. Sign in to Strava and open <https://www.strava.com/settings/api>. It works in a phone browser.
2. Fill in the form:

   | Field | What to put |
   | --- | --- |
   | Application Name | Anything — `Treenivalmentaja` is fine. |
   | Category | `Training` |
   | Club | Leave empty. |
   | Website | Anything valid; `https://github.com/merilainen-star/Treenivalmentaja2000` works. |
   | Application Description | Optional. |
   | **Authorization Callback Domain** | **`localhost`** — exactly this, no scheme, no slash, no port. |

3. Upload an icon if Strava insists on one, then create the application.

**The callback domain is the field that matters.** Strava validates the *host* of the redirect it
is asked to send you back to, and rejects the login outright if it does not match. The app's
redirect is `treenivalmentaja://localhost/strava`, whose host is `localhost` — see
`data/strava/StravaOAuth.kt`. If you typed something else here, the browser will show a Strava
error page instead of a consent screen.

## 2. Copy the credentials into the app

The API page now shows **Client ID** and **Client Secret** (the secret is behind a "Show" link).

On the phone: **Asetukset → Strava** → paste both → *Tallenna tunnukset* → *Yhdistä Strava*.

A browser opens on Strava's consent screen. **Leave every permission ticked** — the app asks only
for `activity:read_all`, and unticking it produces a connection that can authenticate but never
return a run. The app refuses such a connection rather than looking empty forever.

After you approve, the browser returns to the app and the card says *Strava on yhdistetty*.

## 3. What happens then

Opening the Tänään or Viikko screen fetches the last two weeks of activities. A run lands under the
planned running session nearest it in time on the same day — the same rule Oura's workouts follow —
and the session shows a Strava line with pace, moving time, distance and heart rate.

Both lines can appear at once when the ring and the watch both recorded the session. That is
deliberate: they are two devices' measurements of the same run, and merging them would hide which
one said what.

## Notes

- **Rate limits.** Strava allows 200 requests per 15 minutes and 2 000 per day for a new
  application. A sync spends one request per 100 activities, so this is not a limit normal use can
  reach.
- **Disconnecting** (*Katkaise Strava-yhteys*) deletes the tokens and the cached activities from
  the phone and keeps your training plan. It deliberately does **not** call Strava's deauthorize
  endpoint — that would revoke the whole application, and a failed network call would leave the app
  unsure whether it is still authorized. To revoke it on Strava's side, use *My Apps* in your
  Strava settings.
- **Private activities** are readable because the app asks for `activity:read_all`. Nothing is ever
  written back to Strava; the app has no write scope at all.
- See [PRIVACY.md](PRIVACY.md) for what leaves the device, and
  [API_INTEGRATIONS.md](API_INTEGRATIONS.md) § Strava for the endpoint itself.
