# DisPinterest

Pinterest on Android, without the ads.

DisPinterest is a tiny app (about 0.2 MB) that opens the real Pinterest website and tidies it up: no more ads, and you can now download videos too.

It is not made by, endorsed by, or connected to Pinterest.

## Download

1. Go to the [Releases page](https://github.com/MarkCockerill12/DisPinterest/releases/latest) and download the `.apk` file.
2. Open it on your phone. Android will ask you to allow installing apps from your browser or file manager; say yes.
3. Open DisPinterest and sign in to Pinterest as you normally would. Signing in with Google works.

Needs Android 10 or newer.

## What you get over the normal Pinterest app

- **No ads.** Sponsored pins are taken out of every feed, and the pins around them close up so there are no blank spaces.
- **Much smaller.** About 0.2 MB to download, against roughly 90 MB for the official app.
- **Save videos.** Open a video pin and tap **Save video** in the top corner. It goes to your gallery under `Movies/DisPinterest` and shows up as your newest item.
- **Save pictures at full size.** Open a pin, tap **•••**, then **Download image**. You get the original upload when there is one, saved under `Pictures/DisPinterest`.
- **Fewer trackers.** Third-party advertising and analytics services are blocked.
- **No "open in the app" hand-offs.** Links stay inside DisPinterest.

Everything else is Pinterest's own website, so your home feed, search, boards, saving pins, messages, profile and creating pins all work the way they do there.

## Good to know

- **No notifications.** Pinterest's instant notifications are delivered only to its official app, and DisPinterest has no way to receive them. The only alternative would be for the app to keep checking Pinterest in the background, which would use battery while you aren't using it, so it deliberately doesn't. You'll see anything new when you open the app. If you want to be told sooner, Pinterest can email you instead: turn that on in Pinterest's own notification settings.
- **It is the website, not the official app.** Anything Pinterest only offers in its own app won't be here.
- **Swiping very quickly** can skip a swipe while the next pin is still loading. Give it a moment between swipes.
- **Pinterest can change its website at any time.** If it does, ads or other parts may come back until the app is updated.
- **Use it at your own risk.** DisPinterest shows you the real site and signs you in the normal way, but hiding ads isn't something Pinterest supports.

## How it works

DisPinterest is a browser window that only shows Pinterest. Pinterest's own website does all the work and talks to Pinterest's servers itself; the app never does that on your behalf. On top of the page it:

- hides tiles that Pinterest marks as sponsored,
- darkens the page,
- adds the swipe gesture and the **Save video** button,
- downloads the pictures and videos you choose to save from Pinterest's public media servers.

Your Pinterest login stays on your phone, inside the app, exactly as it would in a browser.

## Building it yourself

You need Android Studio (or the Android SDK with a JDK) and a phone with USB debugging turned on.

```powershell
.\build.ps1            # build, install and open the app on the connected phone
.\build.ps1 -Release   # the small, optimised build
```

Or with Gradle directly: `./gradlew assembleRelease`. The APK ends up in `app/build/outputs/apk/release/`.

The code is small:

| File | What it does |
|---|---|
| `app/src/main/java/app/tack/MainActivity.kt` | The browser window, downloads, sign-in pop-ups |
| `app/src/main/java/app/tack/Hosts.kt` | Which sites open in the app and which are blocked |
| `app/src/main/assets/page.js` | Ad removal, swiping and prompt dismissal inside the page |
