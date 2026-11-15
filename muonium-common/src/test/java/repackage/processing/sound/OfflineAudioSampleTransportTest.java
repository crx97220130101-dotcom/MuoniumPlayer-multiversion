package repackage.processing.sound;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repackage.com.jsyn.devices.AudioDeviceManager;
import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.com.jsyn.unitgen.ChannelOut;
import repackage.com.jsyn.unitgen.Multiply;
import repackage.com.softsynth.shared.time.ScheduledCommand;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Renders real JSyn readers and queued commands without opening an audio device. */
class OfflineAudioSampleTransportTest {
    private Engine previousEngine;
    private DeferredSegmentEngine synth;
    private double peak;

    @BeforeEach
    void setUp() throws Exception {
        AudioDeviceManager devices = (AudioDeviceManager) Proxy.newProxyInstance(
                AudioDeviceManager.class.getClassLoader(),
                new Class<?>[]{AudioDeviceManager.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getMaxOutputChannels")) return 2;
                    throw new AssertionError("Offline rendering must not access an audio device: " + method);
                });
        synth = new DeferredSegmentEngine(devices);
        synth.setRealTime(false);
        synth.start(44_100);
        synth.setOutputObserver((samples, channels, sampleRate) -> {
            for (double value : samples) peak = Math.max(peak, Math.abs(value));
        });

        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Engine offlineEngine = (Engine) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(Engine.class);
        offlineEngine.synth = synth;
        field(Engine.class, "addedUnits").set(offlineEngine, new HashSet<>());
        Multiply[] volume = new Multiply[2];
        for (int channel = 0; channel < 2; channel++) {
            volume[channel] = new Multiply();
            synth.add(volume[channel]);
            volume[channel].inputB.set(1.0);
            ChannelOut output = new ChannelOut();
            output.setChannelIndex(channel);
            synth.add(output);
            volume[channel].output.connect(output.input);
            output.start();
        }
        field(Engine.class, "volume").set(offlineEngine, volume);
        Field singleton = field(Engine.class, "singleton");
        previousEngine = (Engine) singleton.get(null);
        singleton.set(null, offlineEngine);
    }

    @AfterEach
    void tearDown() throws Exception {
        field(Engine.class, "singleton").set(null, previousEngine);
        if (synth != null) synth.stop();
    }

    @Test
    void delayedPlayRemainsActiveUntilItsCommandActuallyRuns() throws Exception {
        AudioSample sample = sample(44_100);
        sample.play();
        assertFalse(sample.player.dataQueue.hasMore(), "play must still be queued");
        Thread.sleep(80L);
        assertTrue(sample.isPlaying(), "queue delay is not a playback completion");
        render(1);
        assertTrue(peak > 0.1, "the queued segment must eventually render");
        assertTrue(sample.isPlaying());
    }

    @Test
    void pauseCancelsPlayEvenWhenTheEngineHasBeenDelayedBeyondFiftyMillis() throws Exception {
        AudioSample sample = sample(44_100);
        AtomicInteger completions = new AtomicInteger();
        sample.setOnFinished(completions::incrementAndGet);
        sample.play();
        Thread.sleep(80L);
        sample.isPlaying();
        sample.pause();
        render(3);
        assertFalse(sample.isPlaying());
        assertFalse(sample.player.dataQueue.hasMore());
        assertEquals(0, sample.player.dataQueue.getFrameCount());
        assertEquals(0.0, peak);
        assertEquals(0, completions.get(), "pause must not advance the song queue");
    }

    @Test
    void aPlayCommandDeliveredAfterPauseCannotRepopulateTheDataQueue() {
        AudioSample sample = sample(44_100);
        synth.deferNextSegment = true;
        sample.play();
        sample.pause();
        render(1);
        synth.releaseSegment();
        render(1);
        assertFalse(sample.isPlaying());
        assertFalse(sample.player.dataQueue.hasMore(), "the cancelled segment must not be queued again");
        assertEquals(0, sample.player.dataQueue.getFrameCount());
        assertEquals(0.0, peak);
    }

    @Test
    void aPlayCommandDeliveredAfterSeekCannotCompleteTheReplacementSegmentEarly() {
        AudioSample sample = sample(1_024);
        AtomicInteger completions = new AtomicInteger();
        sample.setOnFinished(completions::incrementAndGet);
        synth.deferNextSegment = true;
        sample.play();
        sample.jumpFrame(256);
        synth.releaseSegment();
        render(16);
        assertEquals(1, completions.get());
        assertEquals(768, sample.player.dataQueue.getFrameCount(),
                "only the replacement segment may consume frames");
        assertFalse(sample.isPlaying());
        assertFalse(sample.player.dataQueue.hasMore());
    }

    @Test
    void pauseAndResumeKeepPositionAndCompleteExactlyOnce() {
        AudioSample sample = sample(2_048);
        AtomicInteger completions = new AtomicInteger();
        sample.setOnFinished(completions::incrementAndGet);
        sample.play();
        render(2);
        sample.pause();
        int pausedFrame = sample.positionFrame();
        assertTrue(pausedFrame > 0);
        peak = 0.0;
        render(2);
        assertEquals(pausedFrame, sample.positionFrame());
        assertEquals(0.0, peak);
        assertEquals(0, completions.get());
        sample.play();
        render(40);
        assertEquals(1, completions.get());
        assertEquals(2_048, sample.player.dataQueue.getFrameCount());
        assertFalse(sample.isPlaying());
    }

    @Test
    void aPendingLoopCanBePausedWithoutACompletion() throws Exception {
        AudioSample sample = sample(1_024);
        AtomicInteger completions = new AtomicInteger();
        sample.setOnFinished(completions::incrementAndGet);
        sample.loop();
        Thread.sleep(80L);
        sample.isPlaying();
        sample.pause();
        render(6);
        assertFalse(sample.isPlaying());
        assertFalse(sample.player.dataQueue.hasMore());
        assertEquals(0.0, peak);
        assertEquals(0, completions.get());
    }

    private static AudioSample sample(int frames) {
        float[] samples = new float[frames];
        Arrays.fill(samples, 0.5f);
        return new AudioSample(samples);
    }

    private void render(int buffers) {
        for (int buffer = 0; buffer < buffers; buffer++) synth.generateNextBuffer();
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class DeferredSegmentEngine extends SynthesisEngine {
        boolean deferNextSegment;
        ScheduledCommand deferredSegment;

        private DeferredSegmentEngine(AudioDeviceManager devices) {
            super(devices);
        }

        @Override
        public void queueCommand(ScheduledCommand command) {
            if (deferNextSegment) {
                deferNextSegment = false;
                deferredSegment = command;
            } else {
                super.queueCommand(command);
            }
        }

        void releaseSegment() {
            assertNotNull(deferredSegment);
            super.queueCommand(deferredSegment);
            deferredSegment = null;
        }
    }
}
