# Test Results

## External Archive Output

```
=== Recording Phase ===
Recording ID: 1
Recording 1000000 messages...
Recording complete! Duration: 5.1s

=== Replay Phase ===
Starting replay from position 0 to 64000000
Replaying messages...
Replay stopped at position 113856

=== RESULTS ===
Published: 1000000 messages
Replayed:  1782 messages
First value: 0 (expected 0)
Last value:  1781 (expected 999999)
Recording size: 64000000 bytes
```

## Embedded Archive Output

```
=== Recording Phase ===
Recording ID: 0
Recording 1000000 messages...
Recording complete! Duration: 4.8s

=== Replay Phase ===
Starting replay from position 0 to 64000000
Replaying messages...
Replay stopped at position 5056

=== RESULTS ===
Published: 1000000 messages
Replayed:  79 messages
First value: 0 (expected 0)
Last value:  78 (expected 999999)
Recording size: 64000000 bytes
```

## Observations

1. Recording phase completes successfully in both cases
2. Full 64MB recording size is correct (64 bytes × 1,000,000 messages)
3. Replay starts at position 0 as expected
4. Replay stops early without error messages
5. External archive replays more messages than embedded
6. Message sequence is correct for replayed messages (0, 1, 2, ...)

## Additional Test Runs

Multiple runs show consistent behavior:
- External: 1,700-1,900 messages replayed
- Embedded: ~79 messages replayed
- No variation based on system load or timing