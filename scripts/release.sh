#!/bin/sh
# Stamp one release everywhere: the app's VERSION, each driver's
# DRIVER_VERSION, packageManifest.json (version, date, release notes) and,
# once OWNER is set, every file's importUrl and the manifest's GitHub links.
# The app warns when a driver's version differs, so run this for every release.
#   scripts/release.sh 0.3.0 "What changed, in one line"
set -eu

# The GitHub user or organization that will host the repository. While it is
# GITHUB_OWNER, importUrl stays empty and the manifest keeps the placeholder.
OWNER=digital-homesteaders
REPO=hubitat-dreo
BRANCH=main

cd "$(dirname "$0")/.."
version=${1:?usage: scripts/release.sh X.Y.Z "release notes"}
notes=${2:?usage: scripts/release.sh X.Y.Z "release notes"}
echo "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "version must look like 1.2.3" >&2; exit 1; }
echo "$OWNER" | grep -Eq '^[A-Za-z0-9-]+$' || { echo "OWNER must be a GitHub user or organization name" >&2; exit 1; }

sed -i '' -E "s/(static final String (DRIVER_)?VERSION = )\"[^\"]+\"/\1\"$version\"/" apps/*.groovy drivers/*.groovy

raw="https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH"
for f in apps/*.groovy drivers/*.groovy; do
    url="$raw/$f"
    [ "$OWNER" = GITHUB_OWNER ] && url=""
    sed -i '' -E "s#importUrl: \"[^\"]*\"#importUrl: \"$url\"#" "$f"
done

python3 - "$version" "$notes" "$OWNER" "$REPO" "$BRANCH" <<'PY'
import json, sys, datetime, re
version, notes, owner, repo, branch = sys.argv[1:]
p = "packageManifest.json"
m = json.load(open(p))
m["version"], m["releaseNotes"] = version, notes
m["dateReleased"] = datetime.date.today().isoformat()
raw = "https://raw.githubusercontent.com/%s/%s/%s/" % (owner, repo, branch)
m["documentationLink"] = "https://github.com/%s/%s#readme" % (owner, repo)
m["licenseFile"] = raw + "LICENSE"
for kind, folder in (("apps", "apps"), ("drivers", "drivers")):
    for e in m[kind]:
        e["location"] = raw + folder + "/" + re.sub(r".*/", "", e["location"])
open(p, "w").write(json.dumps(m, indent=2) + "\n")
PY
grep -n 'VERSION = "' apps/*.groovy drivers/*.groovy
[ "$OWNER" = GITHUB_OWNER ] && echo "OWNER is still the placeholder: importUrl left empty."
echo "Now run tests/run.sh, then commit."
