#!/usr/bin/env bash
#
# Builds the two archives the sample ships as:
#
#   build/dist/ari-tool-sample-<version>.zip   the sample project, WITHOUT the SDK
#   build/dist/ari-tool-sdk-<version>.zip      the ari-tool-sdk/ folder, alone
#
# They are split because the vendored SDK is most of the repo by line count, and
# a partner opening the sample should see their own half first. Unpacked into
# the same directory — sample first, then the SDK inside it — they are the
# project this repo is. settings.gradle.kts fails with a message naming the SDK
# archive if only one of them was unpacked.
#
# The file list comes from git, so anything ignored (build outputs,
# local.properties, IDE folders) cannot leak into an archive, and a new source
# file is in the next release the moment it is tracked.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

command -v zip >/dev/null || { echo "zip is not installed" >&2; exit 1; }
git rev-parse --is-inside-work-tree >/dev/null 2>&1 ||
    { echo "not a git checkout, so there is no file list to package" >&2; exit 1; }

# The project keeps exactly one version, and this is it. Do not add a second
# one here: two version numbers drift, and the archives would then disagree
# with the APK a partner installs.
version="$(sed -n 's/^ *versionName *= *"\(.*\)"/\1/p' app/build.gradle.kts)"
[ -n "$version" ] || { echo "no versionName in app/build.gradle.kts" >&2; exit 1; }

sdk_dir="ari-tool-sdk"
dist="build/dist"
sdk_zip="$dist/ari-tool-sdk-$version.zip"
sample_zip="$dist/ari-tool-sample-$version.zip"

rm -rf "$dist"
mkdir -p "$dist"

# -X drops the extra timestamp fields, so the same tree packs to the same bytes.
# zip stores unix modes, which is what keeps gradlew executable on unpack.
git ls-files -z -- "$sdk_dir" | xargs -0 zip -q -X "$sdk_zip"
git ls-files -z | grep -zv "^$sdk_dir/" | xargs -0 zip -q -X "$sample_zip"

echo "$sample_zip   $(du -h "$sample_zip" | cut -f1)"
echo "$sdk_zip   $(du -h "$sdk_zip" | cut -f1)"
echo
echo "Unpack the sample into an empty directory, then the SDK inside it."
