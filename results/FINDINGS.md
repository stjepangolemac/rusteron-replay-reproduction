# Detailed Findings: Aeron Archive Replay Limitation

## Executive Summary

Aeron Archive has a critical bug where replay stops after reading only 0.008% to 0.17% of recorded data, regardless of configuration or implementation approach.

## Test Results

### External Java Archive
- **Messages Published**: 1,000,000
- **Messages Replayed**: ~1,700-1,900 (varies between runs)
- **Replay Efficiency**: ~0.17%
- **Bytes Replayed**: ~111KB out of 64MB

### Embedded Archive (Rust)
- **Messages Published**: 1,000,000
- **Messages Replayed**: 79
- **Replay Efficiency**: 0.008%
- **Bytes Replayed**: 5,056 bytes out of 64MB
- **Replay Stop Reason**: "replay aborted"

## Technical Analysis

### Message Structure
Each message in the recording consists of:
- 32-byte Aeron header
- 8-byte payload (i64 value)
- 24-byte padding (alignment to 64 bytes)
- **Total**: 64 bytes per message

### Recording Phase (Works Correctly)
1. All 1,000,000 messages successfully written
2. Recording size matches expected: 64MB
3. Recording marked as complete with correct stop position
4. No errors during recording phase

### Replay Phase (Fails)
1. Replay starts successfully
2. Initial messages are delivered correctly (sequential from 0)
3. Replay stops abruptly:
   - External: After ~1,700 messages
   - Embedded: After 79 messages
4. No error messages in client
5. Archive logs show replay marked as "INACTIVE" or "aborted"

## Configurations Tested

### Replay Parameters
- `file_io_max_length`: 1MB, 16MB, 64MB (no difference)
- `replay_length`: Exact recording length
- `fragment_limit`: 1,000 to 100,000 (minimal impact)

### Polling Strategies
- Continuous polling with yield
- Polling with sleep delays
- Handler-based approach
- Iterator-based approach

### Buffer Sizes
- Default term buffers
- Increased term buffers (64MB)
- Various MTU settings

## Potential Root Causes

1. **Internal Replay Position Limit**: Archive may have an undocumented limit on replay position
2. **Buffer Management Issue**: Replay buffers may not be properly managed for large recordings
3. **File I/O Constraint**: Despite `file_io_max_length` setting, actual file reads may be limited
4. **State Machine Bug**: Replay session transitions to INACTIVE prematurely

## Impact

This bug makes Aeron Archive unsuitable for:
- Historical data replay
- Large message stream recording/replay
- Any use case requiring complete data recovery

## Workarounds Attempted

1. **Chunked Replay**: Tried replaying in smaller chunks - same issue
2. **Multiple Sessions**: Created new replay sessions - same limit hit
3. **Different Channels**: Used various channel configurations - no improvement
4. **Archive Restart**: Restarted archive between record/replay - no effect

## Recommendations

1. **File a Bug Report**: This appears to be a fundamental issue in Aeron Archive
2. **Alternative Storage**: Consider alternative storage for large data sets
3. **Live Processing Only**: Use Aeron for live streaming, not historical replay
4. **Investigate Java Client**: Test if Java clients exhibit same behavior

## Environment Details

- **OS**: macOS (Darwin 24.5.0)
- **Rust**: 1.70+
- **rusteron-archive**: 0.3.5
- **Aeron**: 1.46.7
- **Java**: 11+ (for external archive)

## Reproduction Steps

See README.md for complete reproduction instructions. The issue is 100% reproducible across multiple runs and configurations.