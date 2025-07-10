# Aeron Archive Replay Test Case

This repository contains a minimal test case demonstrating unexpected behavior when replaying recorded messages using Aeron Archive.

## Observed Behavior

When recording and replaying 1,000,000 messages:
- **Recording**: Completes with all messages (64MB total)
- **Replay**: Stops after:
  - ~1,700-1,900 messages using external Java archive
  - ~79 messages using embedded archive

## Test Setup

Two minimal examples are provided:

### 1. External Archive (`external_replay.rs`)
- Uses separate Java Aeron Archive process
- Result: ~1,700 messages replayed

### 2. Embedded Archive (`embedded_replay.rs`)
- Uses Rust's `EmbeddedArchiveMediaDriverProcess`
- Result: ~79 messages replayed

## Running the Tests

```bash
# External archive example
./scripts/run_external.sh

# Embedded archive example  
cargo run --release --bin embedded_replay
```

## Test Details

Each example:
1. Records 1,000,000 sequential integers (8 bytes each)
2. Verifies recording size (64MB with Aeron framing)
3. Attempts to replay all messages
4. Reports actual messages replayed

## Environment

- macOS ARM64
- Rust 1.83.0
- rusteron-archive 0.1.9 (Aeron 1.46.7)
- Java OpenJDK 21

## Results

- Consistent behavior across multiple runs
- No error messages or exceptions during replay
- Recording shows expected 64MB file size
- Same behavior with different buffer sizes and timeouts

## Key Findings

1. **Replay is being aborted**: With `extra-logging` enabled, logs reveal the replay session transitions to `INACTIVE` with `reason="replay aborted"`
2. **Position tracking**: Replay stops at position ~5KB out of 64MB (< 0.01% of the file)
3. **Features tested**: Using `static`, `precompile`, `extra-logging`, and `backtrace` features from rusteron does not resolve the issue

## Detailed Output

See [results.md](results.md) for complete test output.