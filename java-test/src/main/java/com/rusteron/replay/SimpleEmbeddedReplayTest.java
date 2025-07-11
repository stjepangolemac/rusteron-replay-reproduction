package com.rusteron.replay;

import io.aeron.*;
import io.aeron.archive.*;
import io.aeron.archive.client.*;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.driver.*;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.File;
import java.nio.ByteBuffer;

public class SimpleEmbeddedReplayTest {
    private static final String RECORDING_CHANNEL = "aeron:ipc";
    private static final String REPLAY_CHANNEL = "aeron:ipc";
    private static final int RECORDING_STREAM_ID = 16;
    private static final int REPLAY_STREAM_ID = 17;
    private static final int MESSAGE_COUNT = 1_000_000;
    private static final int MESSAGE_SIZE = 8;
    private static final int MAX_EMPTY_POLLS = 10_000;

    public static void main(String[] args) {
        System.out.println("\n=== Aeron Archive Replay Test - Java Embedded (Simple) ===\n");

        MediaDriver.Context driverContext = new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true);

        Archive.Context archiveContext = new Archive.Context()
            .deleteArchiveOnStart(true);

        try (ArchivingMediaDriver driver = ArchivingMediaDriver.launch(driverContext, archiveContext);
             AeronArchive archive = AeronArchive.connect()) {

            System.out.println("Embedded archive started\n");

            // STEP 1: Start recording
            System.out.println("STEP 1: Starting recording on stream " + RECORDING_STREAM_ID);
            long subscriptionId = archive.startRecording(
                RECORDING_CHANNEL, RECORDING_STREAM_ID, SourceLocation.LOCAL);

            // STEP 2: Create publication and publish messages
            System.out.println("\nSTEP 2: Publishing " + MESSAGE_COUNT + " messages...");
            try (Publication publication = archive.context().aeron().addPublication(
                    RECORDING_CHANNEL, RECORDING_STREAM_ID)) {
                
                while (!publication.isConnected()) {
                    Thread.sleep(10);
                }

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
            }

            // Wait for recording to complete
            Thread.sleep(1000);

            // Find recording
            long recordingId = archive.findLastMatchingRecording(
                0, RECORDING_CHANNEL, RECORDING_STREAM_ID, 0);
            
            System.out.println("\nRecording ID: " + recordingId);

            // STEP 3: Start replay
            System.out.println("\nSTEP 3: Starting replay on stream " + REPLAY_STREAM_ID);
            long replaySessionId = archive.startReplay(
                recordingId, 0, AeronArchive.NULL_LENGTH, REPLAY_CHANNEL, REPLAY_STREAM_ID);
            System.out.println("Replay session started: " + replaySessionId);

            // STEP 4: Subscribe and poll for replayed messages
            System.out.println("\nSTEP 4: Polling for replayed messages...");
            try (Subscription subscription = archive.context().aeron().addSubscription(
                    REPLAY_CHANNEL, REPLAY_STREAM_ID)) {
                
                while (!subscription.isConnected()) {
                    Thread.sleep(10);
                }

                MessageCounter counter = new MessageCounter();
                long startTime = System.nanoTime();
                
                int emptyPolls = 0;
                while (emptyPolls < MAX_EMPTY_POLLS && counter.messageCount < MESSAGE_COUNT) {
                    int fragments = subscription.poll(counter, 256);
                    if (fragments == 0) {
                        emptyPolls++;
                    } else {
                        emptyPolls = 0;
                    }
                }

                long replayTime = System.nanoTime() - startTime;
                
                // Stop replay
                archive.stopReplay(replaySessionId);

                // Print results
                System.out.println("\n=== RESULTS ===");
                System.out.println("Published: " + MESSAGE_COUNT + " messages");
                System.out.println("Replayed:  " + counter.messageCount + " messages");
                if (counter.messageCount > 0) {
                    System.out.println("First value: " + counter.firstValue);
                    System.out.println("Last value:  " + counter.lastValue);
                }
                System.out.println("Replay time: " + (replayTime / 1_000_000.0) + "ms");
                System.out.println("\nREPLAY EFFICIENCY: " + 
                    String.format("%.2f%%", (counter.messageCount * 100.0) / MESSAGE_COUNT));

                if (counter.messageCount != MESSAGE_COUNT) {
                    System.err.println("\nERROR: Expected " + MESSAGE_COUNT + 
                        " messages but replayed " + counter.messageCount);
                }
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