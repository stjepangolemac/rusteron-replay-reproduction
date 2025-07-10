use anyhow::Result;
use rusteron_archive::*;
use std::ffi::CString;
use std::sync::atomic::{AtomicI64, AtomicUsize, Ordering};
use std::thread::sleep;
use std::time::Duration;

const CHANNEL: &str = "aeron:ipc";
const STREAM_ID: i32 = 16;
const REPLAY_STREAM_ID: i32 = 17;
const MESSAGE_COUNT: i64 = 1_000_000;

fn main() -> Result<()> {
    println!("=== Aeron Archive Replay Test - Embedded Archive ===\n");
    
    // Use temp directories for embedded archive
    let aeron_dir = "/tmp/embedded_aeron";
    let archive_dir = "/tmp/embedded_archive";
    
    // Clean up any existing data
    if std::path::Path::new(aeron_dir).exists() {
        std::fs::remove_dir_all(aeron_dir)?;
    }
    if std::path::Path::new(archive_dir).exists() {
        std::fs::remove_dir_all(archive_dir)?;
    }
    
    println!("Starting embedded archive media driver...");
    println!("  Aeron dir: {}", aeron_dir);
    println!("  Archive dir: {}\n", archive_dir);
    
    // Start embedded archive media driver
    let archive_process = rusteron_archive::testing::EmbeddedArchiveMediaDriverProcess::start(
        aeron_dir,
        archive_dir,
        "aeron:udp?endpoint=localhost:18010",  // Different ports to avoid conflicts
        "aeron:udp?endpoint=localhost:18020",
        "aeron:udp?control-mode=dynamic|control=localhost:18030",
    )?;
    
    println!("Embedded archive started, connecting...");
    
    // Connect to the embedded archive
    let (archive, aeron) = archive_process.archive_connect()?;
    let archive_id = archive.get_archive_id();
    println!("Connected to embedded archive ID: {}\n", archive_id);
    
    // Start recording
    let channel_cstr = CString::new(CHANNEL)?;
    println!("STEP 1: Starting recording on {}:{}", CHANNEL, STREAM_ID);
    
    let recording_subscription_id = archive.start_recording(
        &channel_cstr,
        STREAM_ID,
        SOURCE_LOCATION_LOCAL,
        true, // auto stop
    )?;
    
    println!("Recording started (subscription ID: {})\n", recording_subscription_id);
    
    // Create publication
    println!("STEP 2: Publishing {} messages...", MESSAGE_COUNT);
    let publication = aeron.add_exclusive_publication(
        &channel_cstr,
        STREAM_ID,
        Duration::from_secs(5),
    )?;
    
    while !publication.is_connected() {
        print!(".");
        std::io::Write::flush(&mut std::io::stdout())?;
        sleep(Duration::from_millis(100));
    }
    println!(" connected!");
    
    // Publish messages
    let start = std::time::Instant::now();
    let mut published = 0i64;
    
    for i in 0..MESSAGE_COUNT {
        let bytes = i.to_le_bytes();
        
        loop {
            let result = publication.offer(
                &bytes,
                Handlers::no_reserved_value_supplier_handler(),
            );
            
            if result > 0 {
                published += 1;
                if published % 100_000 == 0 {
                    print!(".");
                    std::io::Write::flush(&mut std::io::stdout())?;
                }
                break;
            } else if result == -2 {
                // Back pressure
                sleep(Duration::from_micros(1));
            } else {
                break;
            }
        }
    }
    
    println!(" done!");
    println!("Published {} messages in {:?}\n", published, start.elapsed());
    
    // Finalize recording
    drop(publication);
    sleep(Duration::from_millis(100));
    
    // Find the recording
    println!("STEP 3: Finding recording...");
    let mut recording_id = -1i64;
    let mut recording_start = 0i64;
    let mut recording_stop = 0i64;
    let mut count = 0i32;
    
    archive.list_recordings_for_uri_once(
        &mut count,
        0,
        100,
        &channel_cstr,
        STREAM_ID,
        |desc| {
            let id = desc.recording_id();
            let start = desc.start_position();
            let stop = desc.stop_position();
            
            println!("  Recording {}: {} bytes", id, stop - start);
            
            if id > recording_id {
                recording_id = id;
                recording_start = start;
                recording_stop = stop;
            }
        },
    )?;
    
    if recording_id < 0 {
        anyhow::bail!("No recording found!");
    }
    
    let recording_length = recording_stop - recording_start;
    let bytes_per_message = 64; // 32-byte header + 8 payload + 24 padding
    let expected_bytes = bytes_per_message * MESSAGE_COUNT;
    
    println!("\nRecording details:");
    println!("  ID: {}", recording_id);
    println!("  Size: {} bytes", recording_length);
    println!("  Expected: {} bytes", expected_bytes);
    println!("  Bytes per message: {}", recording_length / MESSAGE_COUNT);
    
    if recording_length != expected_bytes {
        println!("  Note: Size mismatch");
    }
    
    // Create replay subscription
    println!("\nSTEP 4: Setting up replay on stream {}", REPLAY_STREAM_ID);
    let replay_subscription = aeron.add_subscription::<AeronAvailableImageLogger, AeronUnavailableImageLogger>(
        &channel_cstr,
        REPLAY_STREAM_ID,
        None,
        None, 
        Duration::from_secs(5),
    )?;
    
    sleep(Duration::from_millis(100));
    
    // Verify subscription is ready
    let mut wait_count = 0;
    while !replay_subscription.is_connected() && wait_count < 50 {
        sleep(Duration::from_millis(100));
        wait_count += 1;
    }
    
    println!("Subscription connected: {}", replay_subscription.is_connected());
    
    // Start replay
    println!("\nSTEP 5: Starting replay...");
    let replay_params = AeronArchiveReplayParams::new(
        0,
        64 * 1024 * 1024,  // 64MB file I/O buffer
        recording_start,
        recording_length,
        0,
        0,
    )?;
    
    let replay_session_id = archive.start_replay(
        recording_id,
        &channel_cstr,
        REPLAY_STREAM_ID,
        &replay_params,
    )?;
    
    println!("Replay session started: {}", replay_session_id);
    
    // Verify connection after replay starts
    sleep(Duration::from_millis(100));
    println!("After replay start - Subscription connected: {}", replay_subscription.is_connected());
    
    // Poll for messages
    println!("\nSTEP 6: Polling for replayed messages...");
    let received_count = AtomicUsize::new(0);
    let last_value = AtomicI64::new(-1);
    let first_value = AtomicI64::new(-1);
    
    struct ReplayHandler<'a> {
        received_count: &'a AtomicUsize,
        last_value: &'a AtomicI64,
        first_value: &'a AtomicI64,
    }
    
    impl<'a> AeronFragmentHandlerCallback for ReplayHandler<'a> {
        fn handle_aeron_fragment_handler(
            &mut self,
            buffer: &[u8],
            _header: AeronHeader,
        ) {
            if buffer.len() >= 8 {
                let value = i64::from_le_bytes([
                    buffer[0], buffer[1], buffer[2], buffer[3],
                    buffer[4], buffer[5], buffer[6], buffer[7],
                ]);
                
                let count = self.received_count.fetch_add(1, Ordering::Relaxed);
                
                if count == 0 {
                    self.first_value.store(value, Ordering::Relaxed);
                    println!("First replayed value: {}", value);
                }
                
                self.last_value.store(value, Ordering::Relaxed);
                
                if (count + 1) % 100_000 == 0 {
                    print!(".");
                    std::io::Write::flush(&mut std::io::stdout()).ok();
                }
            }
        }
    }
    
    let handler = ReplayHandler {
        received_count: &received_count,
        last_value: &last_value,
        first_value: &first_value,
    };
    
    let handler = Handler::leak(handler);
    
    let start_replay = std::time::Instant::now();
    let mut empty_polls = 0;
    let mut last_progress = 0;
    
    while received_count.load(Ordering::Relaxed) < MESSAGE_COUNT as usize {
        let fragments = replay_subscription.poll(Some(&handler), 100_000)?;
        
        if fragments == 0 {
            empty_polls += 1;
            
            let current = received_count.load(Ordering::Relaxed);
            if current > last_progress {
                last_progress = current;
                empty_polls = 0;
            } else if empty_polls > 10_000 {
                println!("\n\nReplay stopped after {} empty polls", empty_polls);
                println!("Received {} messages so far", current);
                break;
            }
            
            std::thread::yield_now();
        } else {
            empty_polls = 0;
            if fragments > 0 {
                println!("Got {} fragments", fragments);
            }
        }
    }
    
    archive.stop_replay(replay_session_id)?;
    
    // Results
    let final_count = received_count.load(Ordering::Relaxed);
    let first = first_value.load(Ordering::Relaxed);
    let last = last_value.load(Ordering::Relaxed);
    
    println!("\n\n=== RESULTS ===");
    println!("Published: {} messages", published);
    println!("Replayed:  {} messages", final_count);
    println!("First value: {} (expected 0)", first);
    println!("Last value:  {} (expected {})", last, MESSAGE_COUNT - 1);
    println!("Publish time: {:?}", start.elapsed());
    println!("Replay time:  {:?}", start_replay.elapsed());
    
    let percentage = (final_count as f64 / published as f64) * 100.0;
    println!("\nREPLAY EFFICIENCY: {:.2}%", percentage);
    
    println!("\nNote: Embedded archive replayed fewer messages than external");
    println!("External replay: ~0.17% of messages");
    println!("Embedded replay: ~0.008% of messages");
    
    if final_count as i64 == published && first == 0 && last == MESSAGE_COUNT - 1 {
        println!("\n✓ SUCCESS: All messages replayed correctly!");
        Ok(())
    } else {
        println!("\nExpected {} messages but replayed {} ({:.2}%)", published, final_count, percentage);
        anyhow::bail!("Unexpected replay count")
    }
}