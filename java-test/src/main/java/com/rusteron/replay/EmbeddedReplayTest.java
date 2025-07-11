package com.rusteron.replay;

import io.aeron.*;
import io.aeron.archive.*;
import io.aeron.archive.client.*;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.client.ArchiveException;
import io.aeron.driver.*;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class EmbeddedReplayTest {
    private static final String RECORDING_CHANNEL = "aeron:ipc";
    private static final String REPLAY_CHANNEL = "aeron:ipc";
    private static final int RECORDING_STREAM_ID = 16;
    private static final int REPLAY_STREAM_ID = 17;
    private static final int MESSAGE_COUNT = 1_000_000;
    private static final int MESSAGE_SIZE = 8; // 8 bytes per message
    private static final int MAX_EMPTY_POLLS = 10_000;

    public static void main(String[] args) {
        System.out.println("\n=== Aeron Archive Replay Test - Java Embedded ===\n");

        // Configure embedded archive
        String aeronDir = "/tmp/java_embedded_aeron";
        String archiveDir = "/tmp/java_embedded_archive";

        System.out.println("Starting embedded archive media driver...");
        System.out.println("  Aeron dir: " + aeronDir);
        System.out.println("  Archive dir: " + archiveDir);

        MediaDriver.Context driverContext = new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED);

        Archive.Context archiveContext = new Archive.Context()
            .deleteArchiveOnStart(true)
            .archiveDir(new File(archiveDir))
            .aeronDirectoryName(aeronDir)
            .controlChannel("aeron:udp?endpoint=localhost:18010")
            .localControlChannel("aeron:ipc")
            .recordingEventsChannel("aeron:udp?control-mode=dynamic|control=localhost:18030")
            .replicationChannel("aeron:udp?endpoint=localhost:0");

        try (ArchivingMediaDriver driver = ArchivingMediaDriver.launch(driverContext, archiveContext);
             Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
             AeronArchive archive = AeronArchive.connect(new AeronArchive.Context()
                 .aeron(aeron)
                 .controlRequestChannel("aeron:udp?endpoint=localhost:18010")
                 .controlResponseChannel("aeron:udp?endpoint=localhost:0"))) {

            System.out.println("\nEmbedded archive started, connected");
            System.out.println("Archive control session ID: " + archive.controlSessionId());

            // STEP 1: Create publication first
            System.out.println("STEP 1: Setting up publication on stream " + RECORDING_STREAM_ID);
            Publication publication = archive.addRecordedPublication(RECORDING_CHANNEL, RECORDING_STREAM_ID);
            final int sessionId = publication.sessionId();
            final String actualChannel = publication.channel();
            System.out.println("Publication session ID: " + sessionId);
            System.out.println("Publication channel: " + actualChannel);
            System.out.println("Waiting for publication to connect...");
            int waitCount = 0;
            while (!publication.isConnected()) {
                Thread.sleep(10);
                waitCount++;
                if (waitCount % 100 == 0) {
                    System.out.println("Still waiting for publication... " + waitCount);
                }
                if (waitCount > 1000) {
                    throw new RuntimeException("Publication failed to connect after 10 seconds");
                }
            }
            System.out.println("Publication connected (recorded publication)");

            // STEP 3: Publish messages
            System.out.println("\nSTEP 3: Publishing " + MESSAGE_COUNT + " messages...");
            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(MESSAGE_SIZE));
            long startTime = System.nanoTime();
            
            for (long i = 0; i < MESSAGE_COUNT; i++) {
                buffer.putLong(0, i);
                while (publication.offer(buffer) < 0) {
                    Thread.yield();
                }
            }
            
            long publishTime = System.nanoTime() - startTime;
            System.out.println("Published " + MESSAGE_COUNT + " messages in " + 
                (publishTime / 1_000_000) + " ms");

            // Close publication to ensure recording is finalized
            publication.close();
            System.out.println("Closed publication");
            
            // Wait for recording to complete
            Thread.sleep(2000);

            // Get recording details first
            long recordingId = -1;
            System.out.println("\nSearching for recording with channel: " + RECORDING_CHANNEL + 
                ", streamId: " + RECORDING_STREAM_ID);
            
            for (int i = 0; i < 10; i++) {
                // Try different approaches to find the recording
                // First try with the publication's actual channel (includes session ID)
                recordingId = archive.findLastMatchingRecording(0, actualChannel, RECORDING_STREAM_ID, sessionId);
                
                if (recordingId == -1) {
                    // Try with just base channel
                    recordingId = archive.findLastMatchingRecording(0, RECORDING_CHANNEL, RECORDING_STREAM_ID, 0);
                }
                
                if (recordingId != -1) {
                    System.out.println("Found recording ID: " + recordingId);
                    break;
                }
                System.out.println("Waiting for recording to be indexed... attempt " + (i + 1));
                
                // List all recordings to see what's available
                if (i == 5) {
                    System.out.println("Debug: Listing all available recordings:");
                    archive.listRecordings(0, 100,
                        (controlSessionId, correlationId, recId, startTimestamp, stopTimestamp,
                         startPosition, stopPosition, initialTermId, segmentFileLength, termBufferLength,
                         mtuLength, sid, sid2, strippedChannel, originalChannel, sourceIdentity) -> {
                            System.out.println("  Recording " + recId + ": streamId=" + sid2 + 
                                ", channel=" + strippedChannel + ", sessionId=" + sid + 
                                ", size=" + stopPosition);
                        });
                }
                
                Thread.sleep(500);
            }
            
            if (recordingId == -1) {
                throw new RuntimeException("Recording not found after multiple attempts");
            }

            // List all recordings to debug
            System.out.println("\nListing all recordings:");
            archive.listRecordings(0, 100,
                (controlSessionId, correlationId, recId, startTimestamp, stopTimestamp,
                 startPosition, stopPosition, initialTermId, segmentFileLength, termBufferLength,
                 mtuLength, sid, sid2, strippedChannel, originalChannel, sourceIdentity) -> {
                    System.out.println("  Found recording: ID=" + recId + ", streamId=" + sid2 + 
                        ", channel=" + strippedChannel + ", size=" + stopPosition);
                });

            // Now list recordings to get size
            final long finalRecordingId = recordingId;
            AtomicLong recordingSize = new AtomicLong(0);
            int foundCount = archive.listRecordingsForUri(0, 100, RECORDING_CHANNEL, RECORDING_STREAM_ID,
                (controlSessionId, correlationId, recId, startTimestamp, stopTimestamp,
                 startPosition, stopPosition, initialTermId, segmentFileLength, termBufferLength,
                 mtuLength, sid, sid2, strippedChannel, originalChannel, sourceIdentity) -> {
                    if (recId == finalRecordingId) {
                        System.out.println("  Recording " + recId + ": " + stopPosition + " bytes");
                        recordingSize.set(stopPosition);
                    }
                });
            
            if (foundCount == 0) {
                throw new RuntimeException("No recordings found");
            }
            
            System.out.println("\nRecording details:");
            System.out.println("  ID: " + recordingId);
            System.out.println("  Size: " + recordingSize.get() + " bytes");
            System.out.println("  Expected: " + (MESSAGE_COUNT * MESSAGE_SIZE) + " bytes");
            System.out.println("  Bytes per message: " + MESSAGE_SIZE);

            // STEP 4: Setup subscription for replay
            System.out.println("\nSTEP 4: Setting up replay on stream " + REPLAY_STREAM_ID);
            Subscription subscription = aeron.addSubscription(REPLAY_CHANNEL, REPLAY_STREAM_ID);
            
            // STEP 5: Start replay
            System.out.println("\nSTEP 5: Starting replay...");
            long replaySessionId = archive.startReplay(
                recordingId, 0, AeronArchive.NULL_LENGTH, REPLAY_CHANNEL, REPLAY_STREAM_ID);
            System.out.println("Replay session started: " + replaySessionId);

            // Wait for subscription to be connected
            while (!subscription.isConnected()) {
                Thread.sleep(10);
            }
            System.out.println("Subscription connected: true");

            // STEP 6: Poll for replayed messages
            System.out.println("\nSTEP 6: Polling for replayed messages...");
            MessageCounter counter = new MessageCounter();
            startTime = System.nanoTime();
            
            int emptyPolls = 0;
            while (emptyPolls < MAX_EMPTY_POLLS) {
                int fragments = subscription.poll(counter, 256);
                if (fragments == 0) {
                    emptyPolls++;
                } else {
                    emptyPolls = 0;
                    if (counter.messageCount == 1) {
                        System.out.println("First replayed value: " + counter.firstValue);
                    }
                    if (counter.messageCount == 10) {
                        System.out.println("Got " + counter.messageCount + " fragments");
                    }
                }
            }

            long replayTime = System.nanoTime() - startTime;
            
            if (counter.messageCount < MESSAGE_COUNT) {
                System.out.println("\n\nReplay stopped after " + MAX_EMPTY_POLLS + " empty polls");
            }
            System.out.println("Received " + counter.messageCount + " messages so far");

            // Stop replay if still active
            try {
                archive.stopReplay(replaySessionId);
            } catch (ArchiveException e) {
                // Replay may have already completed
            }

            // Print results
            System.out.println("\n\n=== RESULTS ===");
            System.out.println("Published: " + MESSAGE_COUNT + " messages");
            System.out.println("Replayed:  " + counter.messageCount + " messages");
            System.out.println("First value: " + counter.firstValue + " (expected 0)");
            System.out.println("Last value:  " + counter.lastValue + " (expected " + (MESSAGE_COUNT - 1) + ")");
            System.out.println("Publish time: " + (publishTime / 1_000_000_000.0) + "s");
            System.out.println("Replay time:  " + (replayTime / 1_000_000.0) + "ms");
            System.out.println("\nREPLAY EFFICIENCY: " + 
                String.format("%.2f%%", (counter.messageCount * 100.0) / MESSAGE_COUNT));

            if (counter.messageCount != MESSAGE_COUNT) {
                System.err.println("\nExpected " + MESSAGE_COUNT + " messages but replayed " + 
                    counter.messageCount + " (" + 
                    String.format("%.2f%%", (counter.messageCount * 100.0) / MESSAGE_COUNT) + ")");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static class MessageCounter implements FragmentHandler {
        long messageCount = 0;
        long firstValue = -1;
        long lastValue = -1;

        @Override
        public void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
            long value = buffer.getLong(offset);
            if (firstValue == -1) {
                firstValue = value;
            }
            lastValue = value;
            messageCount++;
        }
    }
}