# Supporting PiscesPlayer

PiscesPlayer is free software under **GPL-3.0-or-later**. Every feature is available to
everyone; nothing is unlocked, limited, or advertised against by donating.

The GitHub account ([@pisces312](https://github.com/pisces312)), the Afdian page
([afdian.com/a/pisces312](https://afdian.com/a/pisces312)) and the app's package name
(`com.pisces312.piscesplayer`) share the same handle — same person, same project.

## Where donations go

| Channel | Notes |
|---|---|
| [Afdian / 爱发电](https://afdian.com/a/pisces312) | Accepts WeChat Pay and Alipay; payout to either. One-off or monthly. |

Donations pay for the time spent on the app and for test hardware. They do not buy features,
priority, or a different licence — the GPL text is the licence, and it does not change.

## What we do not do

- No in-app payment SDK, no checkout inside the app, no purchase history.
- No personal payment QR codes in the repository or in the APK: a QR image carries the
  payee's real name, and both the repo and the APK are public.
- No "donate to unlock" — that would also conflict with F-Droid's inclusion policy.

## F-Droid

F-Droid shows a donation link on the app page, taken from the fdroiddata recipe's `Donate:`
field and from [`FUNDING.yml`](FUNDING.yml). It is a plain `https://` link; clicking it
opens a browser. The F-Droid build is signed with F-Droid's key, so it cannot be updated
over the project-key builds from GitHub releases — see `docs/RELEASE.md`.
