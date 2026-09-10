#!/usr/bin/env bash
#
# Builds the archive the sample ships as:
#
#   build/dist/ari-tool-sample-<version>.zip
#
# One archive. A partner unzips it into an empty directory and builds — the
# SDK is in there, as an AAR under sdk-repo/, so there is nothing else to
# fetch and nothing else to unpack. It used to be two archives because the SDK
# was vendored as source and was most of the repo by line count; the binary is
# a few hundred kilobytes and splitting it would only give a partner a way to
# end up with half a project.
#
# The file list comes from git, so anything ignored (build outputs,
# local.properties, IDE folders) cannot leak into the archive, and a new source
# file is in the next release the moment it is tracked.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

command -v zip >/dev/null || { echo "zip is not installed" >&2; exit 1; }
git rev-parse --is-inside-work-tree >/dev/null 2>&1 ||
    { echo "not a git checkout, so there is no file list to package" >&2; exit 1; }

# The project keeps exactly one version, and this is it. Do not add a second
# one here: two version numbers drift, and the archive would then disagree
# with the APK a partner installs.
version="$(sed -n 's/^ *versionName *= *"\(.*\)"/\1/p' app/build.gradle.kts)"
[ -n "$version" ] || { echo "no versionName in app/build.gradle.kts" >&2; exit 1; }

dist="build/dist"
sample_zip="$dist/ari-tool-sample-$version.zip"

rm -rf "$dist"
mkdir -p "$dist"

# Read, not repeated: the same reason `version` above is read rather than typed.
# A version bump touching this script would fail it on a path nobody changed.
sdkver="$(sed -n 's/^ *id("com.ari_os.ari-tools") version "\(.*\)"/\1/p' app/build.gradle.kts)"
[ -n "$sdkver" ] || { echo "no com.ari_os.ari-tools version in app/build.gradle.kts" >&2; exit 1; }

# The AAR and the Gradle plugin are what the sample builds against, so an
# archive missing either is not a buildable project — and the plugin needs its
# marker pom too, which is what `id("com.ari_os.ari-tools")` resolves. Cheap to
# assert, and it catches a sdk-repo/ that was emptied or never committed.
git ls-files --error-unmatch \
    "sdk-repo/com/ari_os/ari-tool-sdk/$sdkver/ari-tool-sdk-$sdkver.aar" \
    "sdk-repo/com/ari_os/ari-tool-gradle-plugin/$sdkver/ari-tool-gradle-plugin-$sdkver.jar" \
    "sdk-repo/com/ari_os/ari-tools/com.ari_os.ari-tools.gradle.plugin/$sdkver/com.ari_os.ari-tools.gradle.plugin-$sdkver.pom" \
    >/dev/null

# The sources jar is the SDK source under another name and must never ship.
if git ls-files -- 'sdk-repo/**-sources.jar' | grep -q .; then
    echo "a sources jar is tracked under sdk-repo/ — it must not ship" >&2
    exit 1
fi

# -X drops the extra timestamp fields, so the same tree packs to the same bytes.
# zip stores unix modes, which is what keeps gradlew executable on unpack.
git ls-files -z | xargs -0 zip -q -X "$sample_zip"

echo "$sample_zip   $(du -h "$sample_zip" | cut -f1)"
echo
echo "Unpack it into an empty directory and run ./gradlew :app:assembleDebug."
