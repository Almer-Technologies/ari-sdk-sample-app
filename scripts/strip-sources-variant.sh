#!/usr/bin/env bash
#
# Deletes the sources variant from a Gradle module metadata file.
#
# publishToMavenLocal writes a sources jar and records it in the .module as a
# variant. The jar is SDK source and must not ship, so it is not copied into
# sdk-repo/ — but leaving the variant behind makes the metadata advertise a file
# that is not there, and a request for it (an IDE sync with source download on)
# then fails on a missing artifact rather than reporting no sources. Stripping
# the variant makes the metadata say what is true.
#
# Idempotent: running it on an already-stripped file changes nothing.
set -euo pipefail

[ $# -eq 1 ] || { echo "usage: $0 <path to *.module>" >&2; exit 1; }

python3 - "$1" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path) as f:
    module = json.load(f)

module["variants"] = [
    v for v in module["variants"]
    if v.get("attributes", {}).get("org.gradle.docstype") != "sources"
]

with open(path, "w") as f:
    json.dump(module, f, indent=2)
    f.write("\n")

print(f"{path}: {len(module['variants'])} variants, none of them sources")
PY
