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
# sdk-repo/ IS NOT IN THIS REPOSITORY. It is proprietary RealWear software,
# supplied separately (see NOTICE), and .gitignore keeps git from taking a copy.
# So this script needs the folder on disk to run at all — whoever cuts a release
# has it — and reads it straight off disk rather than out of git.
#
# Everything else comes from git, so anything ignored (build outputs,
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
# marker pom too, which is what `id("com.ari_os.ari-tools")` resolves. Checked
# on disk, not in git, because the folder is deliberately untracked: without
# this the script would happily pack a sample with no SDK in it.
for f in \
    "sdk-repo/com/ari_os/ari-tool-sdk/$sdkver/ari-tool-sdk-$sdkver.aar" \
    "sdk-repo/com/ari_os/ari-tool-gradle-plugin/$sdkver/ari-tool-gradle-plugin-$sdkver.jar" \
    "sdk-repo/com/ari_os/ari-tools/com.ari_os.ari-tools.gradle.plugin/$sdkver/com.ari_os.ari-tools.gradle.plugin-$sdkver.pom"
do
    [ -f "$f" ] || {
        echo "$f is missing." >&2
        echo "sdk-repo/ is not in this repository — it comes from the RealWear" >&2
        echo "Developer Program. Put it in the project root and run this again." >&2
        exit 1
    }
done

# The sources jar is the SDK source under another name and must never ship.
if find sdk-repo -name '*-sources.jar' -print -quit | grep -q .; then
    echo "a sources jar is present under sdk-repo/ — it must not ship" >&2
    exit 1
fi

# -X drops the extra timestamp fields, so the same tree packs to the same bytes.
# zip stores unix modes, which is what keeps gradlew executable on unpack.
# sdk-repo/ is appended from disk because git does not track it; sorted, so the
# archive still packs deterministically whatever order the filesystem hands back.
# .DS_Store is excluded because nothing filters this half of the list the way
# .gitignore filters the other half, and the folder gets opened in Finder.
{
    git ls-files -z
    find sdk-repo -type f ! -name .DS_Store -print0 | LC_ALL=C sort -z
} | xargs -0 zip -q -X "$sample_zip"

echo "$sample_zip   $(du -h "$sample_zip" | cut -f1)"
echo
echo "Unpack it into an empty directory and run ./gradlew :app:assembleDebug."
