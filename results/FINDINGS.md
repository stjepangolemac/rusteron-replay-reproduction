# Aeron Archive Replay Issue - Detailed Findings

## Executive Summary

Aeron Archive has a critical bug where replay sessions stop prematurely, returning less than 0.2% of recorded messages. This issue is reproducible in both external Java archives and embedded Rust archives.

## Test Results

### External Java Archive
- **Messages published**: 1,000,000
- **Messages replayed**: ~1,700-1,900 (varies per run)
- **Success rate**: ~0.17%
- **Data recorded**: 64MB (verified)
- **Data replayed**: ~111KB

### Embedded Rust Archive
- **Messages published**: 1,000,000
- **Messages replayed**: ~79
- **Success rate**: ~0.008%
- **Data recorded**: 64MB (verified)
- **Data replayed**: ~5KB
- **Note**: Embedded performs 20x worse than external!

## Technical Analysis

### Message Structure
Each message in the recording consists of:
- 32 bytes: Aeron header
- 8 bytes: Payload (i64 value)
- 24 bytes: Padding (to align to 64-byte boundary)
- **Total**: 64 bytes per message

### Recording Phase (Works Correctly)
1. Archive successfully records all 1,000,000 messages
2. Recording size matches expectation: 64MB
3. Recording metadata shows correct start/stop positions
4. No errors during recording phase

### Replay Phase (Fails)
1. Replay session starts successfully
2. Initial messages are received correctly (sequential from 0)
3. Replay suddenly stops with no more fragments available
4. Archive logs show replay marked as "INACTIVE" with reason "replay aborted"
5. No error messages or exceptions thrown

## Configurations Tested

### Replay Parameters Varied
- `file_io_max_length`: 1MB, 16MB, 64MB, 128MB
- `fragment_limit` in poll(): 1,000 to 100,000
- Different poll strategies: busy wait, sleep, yield
- Both handler and iterator approaches

### Archive Configurations
- Default term buffer sizes
- Increased term buffers (up to 64MB)
- Various MTU sizes (1408, 8192)
- Different channel endpoints (IPC, UDP)

**Result**: No configuration changes fixed the issue

## Key Observations

1. **Consistent Cutoff**: External archive always stops around 1,700-1,900 messages
2. **Even Worse Embedded**: Embedded archive stops at just 79 messages
3. **No Errors**: No exceptions or error logs - replay just stops
4. **Recording Valid**: Full 64MB recording exists and is valid
5. **Initial Success**: First messages replay correctly in sequence

## Possible Root Causes

1. **Internal Buffer Limit**: Replay may have hardcoded buffer size limit
2. **Position Tracking Bug**: Replay position calculation may overflow/wrap
3. **Resource Cleanup**: Replay resources may be freed prematurely
4. **State Machine Issue**: Replay session transitions to INACTIVE too early

## Impact

This bug makes Aeron Archive effectively unusable for replaying any substantial amount of data. Applications cannot reliably replay recorded streams, defeating the primary purpose of the archive functionality.

## Reproduction Rate

100% - The issue occurs on every single run with the provided examples.

## Environment

- **OS**: macOS (Darwin 24.5.0)
- **Rust**: 1.70+
- **rusteron-archive**: 0.3.5
- **Aeron**: 1.46.7
- **Java**: 11+

## Recommendations

1. Report this as a critical bug to Aeron project
2. Test with different Aeron versions to find working version
3. Examine Aeron source code for replay buffer limits
4. Consider alternative archiving solutions for production use

## Related Code Locations

The issue likely exists in one of these areas:
- Aeron Archive replay session management
- File I/O buffer handling during replay
- Replay position tracking logic
- Resource lifecycle management

## Workaround Attempts (All Failed)

1. Increasing all buffer sizes
2. Slowing down replay consumption
3. Using different replay strategies
4. Modifying polling patterns
5. Changing term buffer configurations

None of these attempts resolved the issue.