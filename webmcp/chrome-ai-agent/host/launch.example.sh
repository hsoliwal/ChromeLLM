#!/usr/bin/env sh
# Install a copy in an approved path, replace placeholders and chmod +x there.
# This process emits only native-messaging binary frames to stdout.
exec /absolute/path/to/java -cp /absolute/path/to/host/target/classes io.synexia.chromellm.webmcp.AgentHost /absolute/path/to/approved-config.properties "$@"
