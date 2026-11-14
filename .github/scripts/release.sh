#!/usr/bin/env bash
# Creates the GitHub release for a version tag, with the zip and its SHA-256 attached. A release
# that already exists (for example written by hand with release notes) gets the zip and checksum
# only when it has no zip yet; a release with a zip is left alone, because a new build of the same
# code is a different file (timestamps) and its checksum would not match the attached zip.
#
# Usage: release.sh <tag, e.g. v1.0.6>. Needs GH_TOKEN. DRY_RUN=1 prints instead of doing.
set -euo pipefail

tag="${1:?tag, e.g. v1.0.6}"
version="${tag#v}"
zip="target/claude-assistant-${version}.zip"
sum="${zip}.sha256"
run() { if [ "${DRY_RUN:-}" = "1" ]; then echo "DRY RUN: $*"; else "$@"; fi; }

[ -f "${zip}" ] || { echo "${zip} not found; build first" >&2; exit 1; }
(cd "$(dirname "${zip}")" && sha256sum "$(basename "${zip}")") > "${sum}"
checksum="$(awk '{print $1}' "${sum}")"
echo "SHA-256 of $(basename "${zip}"): ${checksum}"

if gh release view "${tag}" >/dev/null 2>&1; then
  assets="$(gh release view "${tag}" --json assets -q '.assets[].name')"
  if grep -qx "$(basename "${zip}")" <<< "${assets}"; then
    echo "Release ${tag} already has $(basename "${zip}"); left as it is"
  else
    run gh release upload "${tag}" "${zip}" "${sum}"
  fi
else
  notes="Install: *Settings > Extensions > Install Extension* with \`$(basename "${zip}")\` below, then restart the OIE service.

SHA-256 of \`$(basename "${zip}")\`: \`${checksum}\`"
  run gh release create "${tag}" "${zip}" "${sum}" --verify-tag --title "${tag}" --notes "${notes}" --generate-notes
  # A release created here with GITHUB_TOKEN fires no `release` event: build.yml then files the catalog PR itself.
  if [ -n "${GITHUB_OUTPUT:-}" ] && [ "${DRY_RUN:-}" != "1" ]; then
    echo "created=true" >> "${GITHUB_OUTPUT}"
  fi
fi
