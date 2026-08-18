# BasePlayer2 Packaging Guide

This document explains how to create distributable packages for BasePlayer2.

## Prerequisites

- JDK 23 (with `jpackage` available)
- Gradle wrapper (`./gradlew`)
- Project builds cleanly (`./gradlew classes`)

Check `jpackage` is available:

```bash
jpackage --version
```

## Build Outputs

Packaging outputs are created under:

- `build/jpackage/`

## Linux Packaging

Create an app-image:

```bash
./gradlew jpackageImage
```

On Linux, this creates:

- `build/jpackage/BasePlayer2/`
- `build/jpackage/BasePlayer2/run.sh`

Run locally:

```bash
build/jpackage/BasePlayer2/run.sh
```

## macOS Packaging

Important:

- `jpackage` must run on macOS to produce macOS installers.

### 1) Create macOS app bundle

```bash
./gradlew jpackageImage
```

This creates:

- `build/jpackage/BasePlayer2.app`

Run locally:

```bash
open build/jpackage/BasePlayer2.app
```

### 2) Create DMG installer

```bash
./gradlew jpackageMacDmg
```

### 3) Create PKG installer

```bash
./gradlew jpackageMacPkg
```

## Optional Packaging Parameters

You can customize packaging with Gradle properties:

- `appVersion` (default: `1.0.0`)
- `packageIcon` (path to icon file)
- `macPackageName` (default: `BasePlayer2`)
- `macPackageIdentifier` (default: `org.baseplayer.baseplayer2`)
- `macSign` (`true`/`false`, default: `false`)
- `macSigningIdentity` (Developer ID identity)
- `macKeychain` (path to keychain)

Example:

```bash
./gradlew jpackageMacDmg \
  -PappVersion=1.0.1 \
  -PmacPackageName=BasePlayer2 \
  -PmacPackageIdentifier=org.baseplayer.baseplayer2 \
  -PpackageIcon=src/main/resources/org/baseplayer/BasePlayer_icon.png
```

Signing example:

```bash
./gradlew jpackageMacDmg \
  -PmacSign=true \
  -PmacSigningIdentity="Developer ID Application: Your Name (TEAMID)" \
  -PmacKeychain=/Users/you/Library/Keychains/login.keychain-db
```

You can also provide signing values via environment variables:

- `MAC_SIGN`
- `MAC_SIGNING_IDENTITY`
- `MAC_KEYCHAIN`

## Notes

- The packaging task copies `genomes/` and `additions/` into the packaged app content.
- For best macOS icon results, use an `.icns` icon file with `-PpackageIcon=...`.
- Notarization is not included in Gradle tasks yet; if distributing publicly, notarize the signed macOS artifact.

## Quick Command Summary

```bash
# Verify compile
./gradlew classes

# Linux/macOS app-image (run on target OS)
./gradlew jpackageImage

# macOS only
./gradlew jpackageMacDmg
./gradlew jpackageMacPkg
```
