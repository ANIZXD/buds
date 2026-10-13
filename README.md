# Buds — hands-free Android voice assistant

Earbuds in, tap once, talk, and it answers out loud. Built to replace the
browser-extension approach (Hey Nova / NOVA), which could never work hands-free
because a Chrome tab loses the microphone the moment you switch tabs.

> Naming: **Buds** is this Android app. It is a separate project from the
> **hey-nova** browser assistant and from **NOVA**; only the offline command
> vocabulary was borrowed from NOVA so the words you already use keep working.

## What it does

- **Earbud tap starts listening.** Your earbud's media key is caught by an active
  `MediaSession` inside a foreground service, so it works with the screen off and
  the phone in your pocket.
- **It talks back** through the earbuds, and shuts up when you talk over it.
- **A 20-second follow-up window** after each command, so you can chain
  "next" / "louder" / "pause" without tapping again.
- **Offline fast path first.** Media transport, volume, time/date/battery, opening
  apps, search, flashlight are all answered on-device with *zero* network calls.
- **Cloud brain for everything else** — Groq (`llama-3.3-70b-versatile` by default)
  returns one small JSON object: something to say, and optionally something to do.
  Actions: open app, open URL, search, set alarm, set timer, call, SMS, media
  transport, volume, flashlight.

## Alternate ways in (when a music app owns the earbud button)

- Quick Settings tile — pull down the shade, tap **Buds**.
- The Buds notification's **Listen** button.

## Build

```powershell
.\build.ps1        # -> app\build\outputs\apk\debug\app-debug.apk
.\install.ps1      # installs on the attached phone and opens it
```

The toolchain is not on PATH; the scripts point at `C:\Users\ay660\dev`
(JDK 17, Gradle 8.10.2, Android SDK 35). Re-run
`C:\Users\ay660\dev\setup-toolchain.ps1` to recreate it from scratch.

## First run

1. **Turn on hands-free** — starts the foreground service.
2. **Grant permissions.** Microphone is required. Contacts + Phone enable
   "call mum". Camera enables the flashlight. Notifications lets it talk to you
   in the shade.
3. **Allow opening apps in the background** — Android blocks a background app from
   launching other apps unless it has "Display over other apps". Without it,
   "open Instagram" silently does nothing.
4. **Paste a Groq API key** (free at console.groq.com) and hit **Test brain**.
   With no key, the offline fast path still works.

## Known limits of v0.1

- **No wake word yet.** The trigger is the earbud tap, the tile, or the
  notification — saying "hey buds" only works because nothing filters it out, it
  does not start a listen by itself. A wake-word engine is the next obvious
  addition.
- If a music player is playing, Android may send the earbud key to the music app
  instead of Buds. That's the phone's routing, not a bug here — use the tile.
- Media transport control is best-effort: Buds briefly deactivates its own media
  session so the key lands on the music app.
