#!/usr/bin/env bash
# Unpacks the server-lib and client-lib folders of the Open Integration Engine release into a
# folder and prints the engine folder, for use as -Doie.home=... Some jars this plugin compiles
# against (mirth-crypto, javax.ws.rs-api, javax.servlet-api, swagger-annotations, swingx-core,
# miglayout) are `system` dependencies referenced from an engine install (see pom.xml), not
# installed into the Maven repository. The tarball comes from download-oie.sh.
#
# Usage: oie-home.sh <engine version, e.g. 4.6.0> <folder> [download folder]
set -euo pipefail

version="${1:?engine version, e.g. 4.6.0}"
dir="${2:?folder to unpack into}"
downloads="${3:-${HOME}/.cache/oie}"
tarball="$(bash "$(dirname "$0")/download-oie.sh" "${version}" "${downloads}")"

mkdir -p "${dir}"
tar -xzf "${tarball}" -C "${dir}" oie/server-lib oie/client-lib
echo "${dir}/oie"
