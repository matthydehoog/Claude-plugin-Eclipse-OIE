#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
MAVEN="${MAVEN_BIN:-$ROOT/../toolchain/apache-maven-3.10.0/bin/mvn}"
OIE_VERSION="4.7.0"

if [[ -z "${OIE_HOME:-}" ]]; then
  echo "Set OIE_HOME to the root of an OIE 4.7 installation (the folder containing server-lib and client-lib)." >&2
  exit 2
fi
if [[ ! -x "$MAVEN" ]]; then
  echo "Maven not found at $MAVEN. Set MAVEN_BIN to an installed Maven executable." >&2
  exit 2
fi

required=(
  "$OIE_HOME/server-lib/mirth-server.jar"
  "$OIE_HOME/server-lib/donkey/donkey-server.jar"
  "$OIE_HOME/server-lib/donkey/donkey-model.jar"
  "$OIE_HOME/client-lib/mirth-client.jar"
  "$OIE_HOME/client-lib/mirth-client-core.jar"
  "$OIE_HOME/server-lib/mirth-crypto.jar"
  "$OIE_HOME/server-lib/javax/javax.ws.rs-api-2.0.1.jar"
  "$OIE_HOME/server-lib/javax/javax.servlet-api-3.1.0.jar"
  "$OIE_HOME/server-lib/swagger/swagger-annotations-2.0.10.jar"
  "$OIE_HOME/client-lib/swingx-core-1.6.2.jar"
  "$OIE_HOME/client-lib/miglayout-swing-4.2.jar"
  "$OIE_HOME/client-lib/miglayout-core-4.2.jar"
)
for jar in "${required[@]}"; do
  if [[ ! -f "$jar" ]]; then
    echo "Required OIE library is missing: $jar" >&2
    exit 2
  fi
done

install_jar() {
  "$MAVEN" -f "$ROOT/pom.xml" "-Doie.home=$OIE_HOME" install:install-file \
    -Dfile="$1" -DgroupId=com.mirth.connect -DartifactId="$2" \
    -Dversion="$OIE_VERSION" -Dpackaging=jar -DgeneratePom=true
}
install_jar "$OIE_HOME/server-lib/mirth-server.jar" server-api
install_jar "$OIE_HOME/server-lib/donkey/donkey-server.jar" donkey-server
install_jar "$OIE_HOME/server-lib/donkey/donkey-model.jar" donkey-model
install_jar "$OIE_HOME/client-lib/mirth-client.jar" client
install_jar "$OIE_HOME/client-lib/mirth-client-core.jar" client-core

"$MAVEN" -f "$ROOT/pom.xml" -Dmaven.test.skip=true package "-Doie.home=$OIE_HOME"
echo "Built extension: $ROOT/target/claude-assistant-1.1.0.zip"
