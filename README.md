# Rusteron Archive Replay Issue Reproduction

This repository demonstrates a critical issue with Aeron Archive replay functionality where only a tiny fraction of recorded messages can be replayed.

## The Problem

When recording messages to Aeron Archive and attempting to replay them, the replay stops prematurely:
- **Expected**: Replay all 1,000,000 recorded messages
- **Actual**: Only ~0.01% to ~0.2% of messages are replayed
- Recording works correctly (full 64MB recorded)
- Replay stops at ~5KB to ~111KB regardless of configuration

## Quick Start

```bash
# Clone and build
cargo build --release

# Run external Java archive example
./scripts/run_external.sh

# Run embedded archive example  
cargo run --release --bin embedded_replay
```

## Repository Structure

```
├── Cargo.toml                  # Workspace configuration
├── README.md                   # This file
├── examples/
│   ├── external_replay.rs      # Uses external Java archive
│   └── embedded_replay.rs      # Uses embedded archive
├── scripts/
│   ├── run_external.sh         # Complete script to run external example
│   ├── start_archive.sh        # Start Java archive
│   └── kill_archive.sh         # Stop Java archive
└── results/
    └── FINDINGS.md             # Detailed analysis of results
```

## Two Reproduction Methods

### 1. External Java Archive (`external_replay`)
- Starts a separate Java Aeron Archive process
- Connects via UDP endpoints
- Results: ~1,700 messages replayed out of 1,000,000 (~0.17%)

### 2. Embedded Archive (`embedded_replay`)
- Uses Rust's `EmbeddedArchiveMediaDriverProcess`
- Archive runs in-process
- Results: ~79 messages replayed out of 1,000,000 (~0.008%)
- **Even worse than external!**

## What Each Example Does

1. **Setup**: Creates clean directories for Aeron/Archive
2. **Connect**: Establishes connection to archive
3. **Record**: Publishes 1,000,000 sequential integers (0..999,999) as 8-byte messages
4. **Verify**: Checks recording completed (64MB = 64 bytes per message with Aeron framing)
5. **Replay**: Attempts to replay all messages
6. **Report**: Shows how many messages were actually replayed

## Expected vs Actual Results

### Expected Output
```
=== RESULTS ===
Published: 1000000 messages
Replayed:  1000000 messages
First value: 0 (expected 0)
Last value:  999999 (expected 999999)
✓ SUCCESS: All messages replayed correctly!
```

### Actual Output (External Archive)
```
=== RESULTS ===
Published: 1000000 messages
Replayed:  1782 messages        ← Only 0.17%!
First value: 0 (expected 0)
Last value:  1781 (expected 999999)
FAILURE: Message count or values don't match!
```

### Actual Output (Embedded Archive)
```
=== RESULTS ===
Published: 1000000 messages
Replayed:  79 messages          ← Only 0.008%!
First value: 0 (expected 0)  
Last value:  78 (expected 999999)
FAILURE: Message count or values don't match!
```

## Technical Details

- Each message is 8 bytes (i64 value)
- With Aeron framing: 64 bytes per message (32-byte header + 8-byte payload + 24-byte padding)
- Total recording size: 64MB
- Replay configurations tested:
  - Various `file_io_max_length` values (1MB to 64MB)
  - Different fragment limits (1,000 to 100,000)
  - Multiple poll timeout strategies
  - Both handler and iterator approaches

## Dependencies

- Rust 1.70+
- Java 11+ (for external archive example)
- Aeron Archive JAR (downloaded automatically by scripts)

## Environment

Tested on:
- macOS (Darwin 24.5.0)
- rusteron-archive 0.3.5
- Aeron 1.46.7

## Key Finding

The issue appears fundamental to Aeron Archive's replay mechanism, not a configuration problem. Even with:
- Sufficient buffer sizes
- Correct replay parameters  
- Proper connection setup
- Following Java examples

The replay consistently stops after reading less than 0.2% of the recorded data.

## Related Issues

This may be related to:
- Internal replay position limits
- Buffer management in replay sessions
- File I/O constraints in archive replay

## How to Investigate Further

1. Check archive logs for errors during replay
2. Monitor replay session state changes
3. Inspect archive catalog files
4. Compare with Java client behavior
5. Test with different message sizes
6. Try different Aeron versions