#!/bin/bash
set -e

# Complete script to run the external replay example

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Running External Archive Replay Example ==="
echo ""

# Kill any existing archive
echo "Stopping any existing archive..."
"$SCRIPT_DIR/kill_archive.sh"
echo ""

# Build the project
echo "Building project..."
cd "$ROOT_DIR"
cargo build --release --bin external_replay
echo ""

# Start archive in background
echo "Starting Aeron Archive..."
"$SCRIPT_DIR/start_archive.sh" &
ARCHIVE_PID=$!

# Wait for archive to start
sleep 3

# Run the example
echo ""
echo "Running external replay example..."
echo "=================================="
echo ""
cargo run --release --bin external_replay

# Stop archive
echo ""
echo "Stopping archive..."
kill $ARCHIVE_PID 2>/dev/null || true
"$SCRIPT_DIR/kill_archive.sh"

echo ""
echo "Example complete. Check the results above."