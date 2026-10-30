# LibreLogin 0.24.0-pillage.1

A minimal security patch of LibreLogin 0.24.0 (upstream tag `0.24.0`, commit `5cae5bd`) for the Pillage
network's Velocity proxy (new-uuid-creator=MOJANG, auto-register=true, profile-conflict-resolution BLOCK).
Every source change is marked with `// Pillage:`. LibreLogin is MPL-2.0; the licence and headers are unchanged.

## Changes

1. **A fallback "name does not exist" is not trusted** (`common/premium/AuthenticPremiumProvider.java`).
   Only Mojang's own API (the first fetcher) may say a name is free. When Mojang failed and playerdb or
   minetools answers "not found", the next fallback is tried; if none answers positively, a
   `PremiumException(SERVER_EXCEPTION)` is thrown. Positive fallback answers are still used. A failure is never
   cached (the Caffeine loader returns null, which Caffeine does not store).
   *Why:* during a Mojang outage a fallback's "not found" turned premium names into cracked registrations.

2. **A premium UUID is never handed to an offline/password row**
   (`common/listener/AuthenticListeners.checkAndValidateByName`).
   - Premium data present but not reliable (a fallback returned another name) -> the login is refused with
     `kick-premium-error-undefined` instead of an offline registration.
   - A row created without a premium UUID always gets `generateNewUUID(username, null)` (the offline UUID), also
     with auto-register=false. The occupied-UUID check uses the UUID that is actually inserted.
   *Why:* upstream inserted offline rows under the premium UUID (MOJANG creator), so the real owner was later
   locked out or merged into a password account.

3. **Mojang outage is decided from our own DB** (`AuthenticListeners.onPreLogin`). When the premium lookup
   throws, the row is looked up by name. Same capitals: premium row -> FORCE_ONLINE, offline row ->
   FORCE_OFFLINE (/login or /register). No row or other capitals -> denied exactly as before. One warn line is
   logged per allowed decision. Known players no longer need Mojang to join; unknown names still do.

4. **The online UUID must match the stored premium UUID** (`velocity/VelocityListeners`,
   new `velocity/PremiumProfileGuard`). In online mode, if the row found by name has a premium UUID that differs
   from the Mojang-authenticated id (`GameProfileRequestEvent.getOriginalProfile().getId()`), the stored UUID is
   not assigned and the connection (remote address + name, kept 1 minute) is denied in `LoginEvent`
   (PostOrder.LAST) with `kick-name-mismatch`. *Why:* upstream gave the row of name N to whichever Mojang
   account owns N now (stale-name takeover). This also makes change 3 safe for premium rows.
   Velocity only; the Paper and BungeeCord listeners are unchanged.

5. **Version** `0.24.0-pillage.1` (root `build.gradle`). `SemanticVersion.parse` cannot read the suffix, so
   `AuthenticLibreLogin.enable()` parses the version with `-pillage.N` stripped (0.24.0, not dev: the update check,
   metrics and the dev-build warning behave as in 0.24.0). The raw string keeps the suffix.

   Detecting the patch from another plugin:
   - `LibreLoginProvider#getLibreLogin().getVersion()` (API `LibreLoginPlugin.getVersion()`) returns
     `"0.24.0-pillage.1"`; check `contains("-pillage")`. `getParsedVersion()` returns 0.24.0.
   - Velocity: `proxy.getPluginManager().getPlugin("librelogin").flatMap(c -> c.getDescription().getVersion())`.
   - Class marker: `xyz.kyngs.librelogin.velocity.PremiumProfileGuard` exists only in patched builds.

## Build changes (upstream 0.24.0 no longer builds as published)

- `settings.gradle`: `mavenLocal()` for the libby Gradle plugin. repo.kyngs.xyz no longer serves
  `libby-gradle-plugin:plugin:1.2.1`. Build it once:
  `git clone https://github.com/kyngs/libby-gradle-plugin && cd libby-gradle-plugin && git checkout 071a725 && ./gradlew :plugin:publishToMavenLocal`
- `Plugin/build.gradle.kts`:
  - repo `https://repo.kyngs.xyz/releases/`, because libby 1.6.0 moved there from `/public/`.
  - `bungeecord-api:1.21-R0.1` instead of the unpublished `-SNAPSHOT` (compile-only, so nothing is shaded).
  - libby excludes `org.jetbrains:annotations`, so `libby.json` lists the same 49 libraries as the upstream jar.
  - Reproducible jars (`isPreserveFileTimestamps = false`, `isReproducibleFileOrder = true`).
  - JUnit 5 + Mockito in test scope only.

## Build and verify

```
export JAVA_HOME=<JDK 21>
./gradlew --no-build-cache clean :Plugin:shadowJar      # -> Plugin/build/libs/LibreLogin.jar
./gradlew --no-build-cache :Plugin:test --tests 'xyz.kyngs.librelogin.common.premium.*' \
    --tests 'xyz.kyngs.librelogin.common.listener.*' --tests 'xyz.kyngs.librelogin.velocity.*'
```

Two clean builds give the same sha256. Compared with the upstream `LibreLogin-0.24.0.jar`, the jar has the
same entries plus `PremiumProfileGuard.class`, the same relocations and the same `libby.json` library set.
Its `libby.json` also lists one extra repository (`repo.kyngs.xyz/releases/`). `velocity-plugin.json` keeps
the id `librelogin`. The jar is a drop-in replacement: same config, messages and database.
