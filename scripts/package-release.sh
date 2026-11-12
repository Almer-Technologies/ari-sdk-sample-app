#!/usr/bin/env bash
#
# Builds the archive the sample ships as:
#
#   build/dist/ari-tool-sample-<version>.zip
#
# The archive is the tracked source and nothing else. The Ari App Tools SDK and
# its Gradle plugin are not in it: the build downloads them from Maven Central
# (com.ari-os), so a partner unzips into an empty directory and builds.
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

# -X drops the extra timestamp fields, so the same tree packs to the same bytes.
# zip stores unix modes, which is what keeps gradlew executable on unpack.
git ls-files -z | xargs -0 zip -q -X "$sample_zip"

echo "$sample_zip   $(du -h "$sample_zip" | cut -f1)"
echo
echo "Unpack it into an empty directory and run ./gradlew :app:assembleDebug."
