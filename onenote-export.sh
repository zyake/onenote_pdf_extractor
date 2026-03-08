#!/usr/bin/env bash
# Launcher script for OneNote PDF Extractor
# Passes --enable-preview since StructuredTaskScope is a JDK 25 preview API
exec java --enable-preview -jar "$(dirname "$0")/target/onenote-pdf-extractor-1.0-SNAPSHOT.jar" "$@"
