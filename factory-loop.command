#!/usr/bin/env bash
# Dubbelklik dit bestand in Finder → Terminal opent en draait de factory-loop.
# (.command-bestanden worden door macOS standaard met Terminal geopend.)
exec "$(dirname "$0")/factory-loop.sh"
