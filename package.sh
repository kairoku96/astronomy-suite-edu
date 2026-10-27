#!/usr/bin/env bash
# Package Orrerium for the OS it runs on, into build/package/dist/:
#   macOS    Orrerium.app, then Orrerium-<version>-MacOS-AppleSilicon.dmg (or -MacOS-Intel.dmg) holding it
#   Windows  Orrerium\ (Orrerium.exe + app\ + runtime\), then Orrerium-<version>-setup.exe
#            (a per-user installer, needs the WiX Toolset) and Orrerium-<version>-windows-x64.zip
# The app carries its own trimmed Java runtime (jlink), the JOGL/GlueGen jars with this
# platform's natives, FlatLaf and the assets/ folder, so nothing else needs installing.
#
# Usage:  ./package.sh               the app and its installer(s)    (macOS, or Windows in Git Bash)
#         ./package.sh --app-image   stop after the app (on Linux too)
#
# Environment (all optional):
#   JAVA_HOME            JDK 22+ with jlink/jpackage (default: /usr/libexec/java_home -v 24+, else PATH)
#   JOGL_DIR             folder with jogl-all.jar + jogl-all-natives-<platform>.jar
#   GLUEGEN_DIR          folder with gluegen-rt.jar + gluegen-rt-natives-<platform>.jar
#                        (default for both: build/jogamp, downloaded from jogamp.org (v2.6.0) if missing)
#   APP_VERSION          bundle version, numbers only, first one > 0 (default 1.0.1)
#   APP_ID               bundle identifier (default io.github.kairoku96.orrerium)
#   MAC_SIGN_IDENTITY    "Developer ID Application: Name (TEAMID)": sign the app and the .dmg with
#                        the hardened runtime. Unset: ad-hoc signature (Gatekeeper will warn, see README).
#   MAC_KEYCHAIN         keychain holding that identity (default: the search list)
#   NOTARY_PROFILE       notarytool keychain profile (xcrun notarytool store-credentials ...), or
#   APPLE_ID + APPLE_TEAM_ID + APPLE_APP_PASSWORD   notarize and staple the .dmg (needs signing)
set -eo pipefail

APP_NAME="Orrerium"
APP_VERSION="${APP_VERSION:-1.0.1}"
APP_ID="${APP_ID:-io.github.kairoku96.orrerium}"
VENDOR="Ethan Lin"
MAIN_CLASS="SolarSystemSimulation"
# What the app needs from the JDK (jdeps reports java.base, java.desktop, java.net.http; JOGL also
# reaches jdk.unsupported and java.logging reflectively, which jdeps can't see).
MODULES="java.base,java.desktop,java.net.http,java.logging,jdk.unsupported"
JOGAMP_URL="https://jogamp.org/deployment/v2.6.0/jar"
# Fixed for good: Windows recognises a newer installer with the same upgrade code as an update.
WIN_UPGRADE_UUID="2b42913f-6cea-4295-bcc3-db71b3fd75f9"

APP_IMAGE_ONLY=0
case "${1:-}" in
    --app-image) APP_IMAGE_ONLY=1 ;;
    "") ;;
    *) echo "usage: $0 [--app-image]" >&2; exit 2 ;;
esac

say()  { printf '\033[36m==> %s\033[0m\n' "$*"; }
die()  { printf '\033[31merror: %s\033[0m\n' "$*" >&2; exit 1; }

# ---- Platform ---------------------------------------------------------------------------------
SEP=":"
case "$(uname -s)" in
    Darwin) OS=mac; NATIVES=macosx-universal ;;
    MINGW*|MSYS*|CYGWIN*) OS=windows; NATIVES=windows-amd64; SEP=";" ;;
    Linux) OS=linux; case "$(uname -m)" in aarch64|arm64) NATIVES=linux-aarch64 ;; *) NATIVES=linux-amd64 ;; esac ;;
    *) die "unsupported OS $(uname -s)" ;;
esac
if [ "$OS" = linux ] && [ "$APP_IMAGE_ONLY" = 0 ]; then
    die "no Linux installer is set up; use --app-image"
fi
# Git Bash: C:/... paths, which the Windows JDK tools understand and MSYS won't rewrite.
if [ "$OS" = windows ]; then ROOT="$(cd "$(dirname "$0")" && pwd -W)"; else ROOT="$(cd "$(dirname "$0")" && pwd)"; fi
cd "$ROOT"

# ---- JDK ---------------------------------------------------------------------------------------
if [ -z "$JAVA_HOME" ] && [ "$OS" = mac ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 24+ 2>/dev/null || true)"
fi
if [ -z "$JAVA_HOME" ] && command -v javac >/dev/null; then
    JAVA_HOME="$(cd "$(dirname "$(command -v javac)")/.." && pwd)"
fi
[ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/jpackage" -o -x "$JAVA_HOME/bin/jpackage.exe" ] \
    || die "need a JDK 22+ with jpackage: set JAVA_HOME"
JBIN="$JAVA_HOME/bin"
JAVA_MAJOR="$("$JBIN/java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = //p' | tr -d '\r')"
[ "${JAVA_MAJOR:-0}" -ge 22 ] 2>/dev/null || die "JDK $JAVA_MAJOR at $JAVA_HOME is too old: the sim needs 22+ (unnamed variables)"
say "JDK $JAVA_MAJOR at $JAVA_HOME; natives $NATIVES"

# ---- JOGL / GlueGen ----------------------------------------------------------------------------
# Not on Maven Central, so fetch the four jars this platform needs from jogamp.org, checked
# against the SHA-256 of the v2.6.0 release where we know it.
sha_of() { if command -v shasum >/dev/null; then shasum -a 256 "$1"; else sha256sum "$1"; fi | cut -d' ' -f1; }
expected_sha() {
    case "$1" in
        jogl-all.jar) echo 34c919bc6073c2d9e73cbe7558c4e9de6b5c58146f3658e2bcc5f23ec3fccc9f ;;
        gluegen-rt.jar) echo 465bbc8d410b872a76b5b901cdb9c2c07905edd5e61a7120dc6a4d007880ec2f ;;
        jogl-all-natives-macosx-universal.jar) echo 81db6a2b50f3803d4307a8bd7570a4f78c41ae0d44361208d0885a5cdf289dfa ;;
        gluegen-rt-natives-macosx-universal.jar) echo e543d5e81bc8b8f63e5dc365ac47da009f82175b2c6eba16a24ce3b2eb031773 ;;
        jogl-all-natives-windows-amd64.jar) echo 33081ebfac520d7d283830a614f458f728104bef4eff56b52f604ee8c5af90c8 ;;
        gluegen-rt-natives-windows-amd64.jar) echo c7c9138606858fed0b1650a2444cc1bc96599cc635a8d879979a37646e8594b1 ;;
    esac
}
fetch_jogamp() {   # fetch_jogamp <dir> <jar>
    local f="$1/$2"
    if [ ! -f "$f" ]; then
        say "downloading $2 from jogamp.org"
        mkdir -p "$1"
        curl -fsSL --retry 3 -o "$f.part" "$JOGAMP_URL/$2" || die "download failed: $JOGAMP_URL/$2"
        mv "$f.part" "$f"
    fi
    local want; want="$(expected_sha "$2")"
    if [ -n "$want" ]; then
        local got; got="$(sha_of "$f")"
        [ "$got" = "$want" ] || die "$f: SHA-256 $got, expected $want (delete it to re-download)"
    fi
}
use_jogamp() {     # use_jogamp <dir or empty for build/jogamp> <jar>...
    local dir="$1"; shift
    for j in "$@"; do
        if [ -z "$dir" ]; then fetch_jogamp "$ROOT/build/jogamp" "$j"
        elif [ ! -f "$dir/$j" ]; then die "missing $dir/$j"; fi
    done
}
use_jogamp "$JOGL_DIR" jogl-all.jar "jogl-all-natives-$NATIVES.jar"
use_jogamp "$GLUEGEN_DIR" gluegen-rt.jar "gluegen-rt-natives-$NATIVES.jar"
JOGL_DIR="${JOGL_DIR:-$ROOT/build/jogamp}"
GLUEGEN_DIR="${GLUEGEN_DIR:-$ROOT/build/jogamp}"
FLATLAF="$(ls "$ROOT"/libs/flatlaf-*.jar 2>/dev/null | head -n 1)"
[ -n "$FLATLAF" ] || die "libs/flatlaf-*.jar not found"

# ---- Compile + jar -----------------------------------------------------------------------------
OUT="$ROOT/build/package"
CLASSES="$OUT/classes"
INPUT="$OUT/input"        # becomes the app's own folder (Contents/app on macOS, app\ on Windows)
DIST="$OUT/dist"
rm -rf "$CLASSES" "$INPUT" "$OUT/runtime" "$DIST"
mkdir -p "$CLASSES" "$INPUT/natives" "$DIST"

say "compiling src/"
find src -name '*.java' > "$OUT/sources.txt"
"$JBIN/javac" -encoding UTF-8 -nowarn -d "$CLASSES" \
    -cp "$FLATLAF$SEP$JOGL_DIR/jogl-all.jar$SEP$GLUEGEN_DIR/gluegen-rt.jar" @"$OUT/sources.txt"

cp "$FLATLAF" "$JOGL_DIR/jogl-all.jar" "$GLUEGEN_DIR/gluegen-rt.jar" "$INPUT/"
printf 'Class-Path: %s jogl-all.jar gluegen-rt.jar\n' "$(basename "$FLATLAF")" > "$OUT/manifest.txt"
"$JBIN/jar" --create --file "$INPUT/orrerium.jar" --main-class "$MAIN_CLASS" \
    --manifest "$OUT/manifest.txt" -C "$CLASSES" .

# The natives go in as plain libraries (loaded from java.library.path, see JAVA_OPTIONS below)
# rather than inside their jars: GlueGen would otherwise unpack them to /tmp on every launch,
# and notarization rejects unsigned dylibs hidden inside jars.
for j in "$JOGL_DIR/jogl-all-natives-$NATIVES.jar" "$GLUEGEN_DIR/gluegen-rt-natives-$NATIVES.jar"; do
    unzip -q -j -o "$j" "natives/$NATIVES/*" -d "$INPUT/natives"
done
cp -R assets "$INPUT/assets"
cp LICENSE "$INPUT/"

# ---- Icon --------------------------------------------------------------------------------------
# packaging/macos/Orrerium.icns if present; else build one from the ray-traced Saturn in
# assets/icon/orrerium.png (SaturnIcon), rendering it first if that file is missing.
ICON_ARGS=()
if [ "$OS" = mac ]; then
    ICNS="$ROOT/packaging/macos/Orrerium.icns"
    if [ ! -f "$ICNS" ]; then
        PNG="$ROOT/assets/icon/orrerium.png"
        if [ ! -f "$PNG" ]; then
            PNG="$OUT/icon.png"
            "$JBIN/java" -Djava.awt.headless=true -Dsss.icon="$PNG" -cp "$CLASSES$SEP$FLATLAF" "$MAIN_CLASS" >/dev/null
        fi
        say "building .icns from $PNG"
        SET="$OUT/Orrerium.iconset"
        rm -rf "$SET"; mkdir -p "$SET"
        for s in 16 32 128 256 512; do
            sips -z "$s" "$s" "$PNG" --out "$SET/icon_${s}x${s}.png" >/dev/null
            sips -z $((s * 2)) $((s * 2)) "$PNG" --out "$SET/icon_${s}x${s}@2x.png" >/dev/null
        done
        ICNS="$OUT/Orrerium.icns"
        iconutil -c icns "$SET" -o "$ICNS"
    fi
    ICON_ARGS=(--icon "$ICNS")
elif [ "$OS" = windows ]; then
    # packaging/windows/Orrerium.ico if present; else the app writes one (16-256 px, PNG entries)
    # from the same render, cropped to the planet as for its window icons.
    ICO="$ROOT/packaging/windows/Orrerium.ico"
    if [ ! -f "$ICO" ]; then
        ICO="$OUT/Orrerium.ico"
        "$JBIN/java" -Djava.awt.headless=true -Dsss.icon="$ICO" -cp "$CLASSES$SEP$FLATLAF" "$MAIN_CLASS" >/dev/null
    fi
    ICON_ARGS=(--icon "$ICO")
fi

# ---- Runtime -----------------------------------------------------------------------------------
say "linking a trimmed runtime ($MODULES)"
"$JBIN/jlink" --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages \
    --compress zip-6 --output "$OUT/runtime"

# ---- Signing -----------------------------------------------------------------------------------
SIGN_ARGS=()
if [ "$OS" = mac ] && [ -n "$MAC_SIGN_IDENTITY" ]; then
    say "signing as $MAC_SIGN_IDENTITY"
    SIGN_ARGS=(--mac-sign --mac-app-image-sign-identity "$MAC_SIGN_IDENTITY"
               --mac-entitlements "$ROOT/packaging/macos/entitlements.plist")
    if [ -n "$MAC_KEYCHAIN" ]; then SIGN_ARGS+=(--mac-signing-keychain "$MAC_KEYCHAIN"); fi
elif [ "$OS" = mac ]; then
    codesign --force --sign - "$INPUT"/natives/*.dylib   # arm64 refuses to load unsigned code
fi

# ---- App image ---------------------------------------------------------------------------------
# $APPDIR is expanded by the launcher to the bundle's Contents/app, so assets and natives resolve
# however the app is started (Finder runs it with the working directory set to /).
JAVA_OPTIONS=(--java-options "--enable-native-access=ALL-UNNAMED"
              --java-options '-Dsss.assets=$APPDIR/assets'
              --java-options '-Djava.library.path=$APPDIR/natives'
              --java-options "-Djogamp.gluegen.UseTempJarCache=false")
PLATFORM_ARGS=()
case "$OS" in
    mac) PLATFORM_ARGS=(--mac-package-identifier "$APP_ID" --mac-package-name "$APP_NAME"
                        --mac-app-category public.app-category.education
                        --java-options "-Dapple.awt.application.appearance=system") ;;
esac

say "jpackage --type app-image"
"$JBIN/jpackage" --type app-image --dest "$DIST" \
    --name "$APP_NAME" --app-version "$APP_VERSION" --vendor "$VENDOR" \
    --description "Solar-system simulator with built-in astronomy lessons" \
    --copyright "Copyright (c) 2025 $VENDOR, MIT License" \
    --input "$INPUT" --main-jar orrerium.jar --main-class "$MAIN_CLASS" \
    --runtime-image "$OUT/runtime" \
    "${JAVA_OPTIONS[@]}" ${PLATFORM_ARGS[@]+"${PLATFORM_ARGS[@]}"} \
    ${ICON_ARGS[@]+"${ICON_ARGS[@]}"} ${SIGN_ARGS[@]+"${SIGN_ARGS[@]}"}

case "$OS" in
    mac) APP="$DIST/$APP_NAME.app"; LAUNCHER="$APP/Contents/MacOS/$APP_NAME" ;;
    windows) APP="$DIST/$APP_NAME"; LAUNCHER="$APP/$APP_NAME.exe" ;;
    linux) APP="$DIST/$APP_NAME"; LAUNCHER="$APP/bin/$APP_NAME" ;;
esac
if [ "$OS" = mac ]; then
    if [ -z "$MAC_SIGN_IDENTITY" ]; then
        codesign --force --deep --sign - "$APP"   # one consistent ad-hoc seal over the whole bundle
    fi
    codesign --verify --deep --strict "$APP"
fi

# Launch the packaged app the way Finder or a shortcut does (working directory elsewhere) in its
# self-test mode: assets found through $APPDIR, JOGL natives loaded from the app, bundled runtime
# used. Piped through cat so the Windows launcher (a GUI program, no console) still reports here.
say "self-test of the packaged launcher"
(cd / && JAVA_TOOL_OPTIONS="-Dsss.selftest=1" "$LAUNCHER" | cat) || die "packaged app failed its self-test"
say "app image: $APP"
if [ "$APP_IMAGE_ONLY" = 1 ]; then exit 0; fi

# ---- Windows: installer + portable zip ---------------------------------------------------------
if [ "$OS" = windows ]; then
    ZIP="$DIST/$APP_NAME-$APP_VERSION-windows-x64.zip"
    say "zipping the app folder"
    "$JBIN/jar" --create --no-manifest --file "$ZIP" -C "$DIST" "$APP_NAME"

    # A per-user install (no administrator prompt) into %LOCALAPPDATA%\Programs, with Start-menu
    # and desktop shortcuts; a later version with the same upgrade code replaces it.
    say "jpackage --type exe"
    "$JBIN/jpackage" --type exe --dest "$DIST" --app-image "$APP" \
        --name "$APP_NAME" --app-version "$APP_VERSION" --vendor "$VENDOR" \
        --description "Solar-system simulator with built-in astronomy lessons" \
        --copyright "Copyright (c) 2025 $VENDOR, MIT License" \
        --win-per-user-install --win-dir-chooser --win-menu --win-menu-group "$APP_NAME" \
        --win-shortcut --win-upgrade-uuid "$WIN_UPGRADE_UUID" \
        || die "jpackage could not build the installer: it needs the WiX Toolset (v3.14, or v4+ with JDK 24+)"
    EXE="$DIST/$APP_NAME-$APP_VERSION-setup.exe"
    mv "$DIST/$APP_NAME-$APP_VERSION.exe" "$EXE"
    say "done: $EXE"
    say "      $ZIP"
    exit 0
fi

# ---- DMG ---------------------------------------------------------------------------------------
say "jpackage --type dmg"
"$JBIN/jpackage" --type dmg --dest "$DIST" --app-image "$APP" \
    --name "$APP_NAME" --app-version "$APP_VERSION" --vendor "$VENDOR" \
    --copyright "Copyright (c) 2025 $VENDOR, MIT License" \
    ${SIGN_ARGS[@]+"${SIGN_ARGS[@]}"}
case "$(uname -m)" in arm64) MAC_ARCH=AppleSilicon ;; *) MAC_ARCH=Intel ;; esac
DMG="$DIST/$APP_NAME-$APP_VERSION-MacOS-$MAC_ARCH.dmg"
mv "$DIST/$APP_NAME-$APP_VERSION.dmg" "$DMG"

if [ -n "$MAC_SIGN_IDENTITY" ]; then
    codesign --force --timestamp --sign "$MAC_SIGN_IDENTITY" \
        ${MAC_KEYCHAIN:+--keychain "$MAC_KEYCHAIN"} "$DMG"
fi

# ---- Notarization ------------------------------------------------------------------------------
NOTARY_ARGS=()
if [ -n "$NOTARY_PROFILE" ]; then
    NOTARY_ARGS=(--keychain-profile "$NOTARY_PROFILE")
elif [ -n "$APPLE_ID" ] && [ -n "$APPLE_TEAM_ID" ] && [ -n "$APPLE_APP_PASSWORD" ]; then
    NOTARY_ARGS=(--apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" --password "$APPLE_APP_PASSWORD")
fi
if [ ${#NOTARY_ARGS[@]} -gt 0 ]; then
    [ -n "$MAC_SIGN_IDENTITY" ] || die "notarization needs a signed build: set MAC_SIGN_IDENTITY"
    say "notarizing (this can take a few minutes)"
    xcrun notarytool submit "$DMG" "${NOTARY_ARGS[@]}" --wait | tee "$OUT/notary.log"
    grep -q "status: Accepted" "$OUT/notary.log" \
        || die "notarization was not accepted; see: xcrun notarytool log <submission id> ${NOTARY_ARGS[*]:0:2}"
    xcrun stapler staple "$DMG"
fi

say "done: $DMG"
