# Build the OIE AI Assistant

This plugin targets OIE 4.7 and requires an OIE 4.7 distribution for its compile-time libraries.

The workspace has Apache Maven 3.10.0 under `../toolchain/apache-maven-3.10.0`. To build once you
have an OIE 4.6 installation, set `OIE_HOME` to its root (the directory containing `server-lib` and
`client-lib`) and run:

```sh
OIE_HOME="/path/to/OpenIntegrationEngine" ./build-local.sh
```

The script checks the required OIE jars, installs the five OIE artifacts into the local Maven
repository, and creates `target/claude-assistant-1.1.1.zip`. It skips running tests. If
Maven is installed elsewhere, set `MAVEN_BIN` to its `mvn` executable.

The OIE 4.7 archive used here is unpacked at `../toolchain/oie-4.7.0/oie`; the build command is:

```sh
OIE_HOME="$(cd ../toolchain/oie-4.7.0/oie && pwd)" ./build-local.sh
```

The project targets Java 17 bytecode. The workspace's Java 20 can build that target.
