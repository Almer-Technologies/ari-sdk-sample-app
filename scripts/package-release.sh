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

# The AAR is what the sample builds against, so an archive without it is not a
# buildable project. Cheap to assert, and it catches a sdk-repo/ that was
# emptied or never committed.
git ls-files --error-unmatch \
    sdk-repo/com/ari_os/ari-tool-sdk/0.1.0/ari-tool-sdk-0.1.0.aar >/dev/null

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
