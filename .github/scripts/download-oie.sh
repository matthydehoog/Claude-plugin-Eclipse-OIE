#!/usr/bin/env bash
# Downloads the Open Integration Engine release tarball (oie_unix_<version>.tar.gz) from GitHub
# into a folder, checked against the SHA-256 the engine publishes, and prints its path. A tarball
# that is already there (for example restored from the CI cache) is checked and reused.
#
# Usage: download-oie.sh <engine version, e.g. 4.7.0> <folder>
set -euo pipefail

version="${1:?engine version, e.g. 4.7.0}"
dir="${2:?folder for the download}"
file="oie_unix_${version//./_}.tar.gz"
base="https://github.com/OpenIntegrationEngine/engine/releases/download/v${version}"
mkdir -p "${dir}"

curl -fsSL -o "${dir}/sha256sums-${version}" "${base}/sha256sums"
expected="$(grep " \*\?${file}\$" "${dir}/sha256sums-${version}" | awk '{print $1}')"
if [ -z "${expected}" ]; then
  echo "No checksum for ${file} in the engine's sha256sums" >&2
  exit 1
fi

if [ ! -f "${dir}/${file}" ] || [ "$(sha256sum "${dir}/${file}" | awk '{print $1}')" != "${expected}" ]; then
  echo "Downloading ${file} (engine ${version})" >&2
  curl -fsSL -o "${dir}/${file}" "${base}/${file}"
  actual="$(sha256sum "${dir}/${file}" | awk '{print $1}')"
  if [ "${expected}" != "${actual}" ]; then
    echo "Checksum mismatch for ${file}: expected '${expected}', got '${actual}'" >&2
    exit 1
  fi
fi
echo "${dir}/${file}"
