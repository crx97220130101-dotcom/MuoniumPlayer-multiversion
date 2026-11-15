package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.ambient.AmbientMusicController;
import com.muoniumplayer.core.ncm.music.ambient.AmbientSettings;
import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repackage.processing.sound.SoundFile;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real wrapper completion callbacks and handover commits, with no device, decoder or network. */
class AutomixPlaybackOwnershipTest {
    private final Map<Field, Object> saved = new LinkedHashMap<>();
    private final Map<Location, ITextureObject> savedTextures = new LinkedHashMap<>();
    private final List<Music> songs = new ArrayList<>();
    private Class<?> ownerType;
    private Class<?> trackType;
    private Object owner;
    private BarrierSession session;
    private ControlledDeck outgoing;
    private long oldGeneration;
    private boolean oldPlaying;
    private Map<String, Boolean> lyricFailures;
    private Set<String> coverAttempts;
    private final Map<String, Boolean> savedLyricFailures = new LinkedHashMap<>();
    private final Set<String> addedCoverAttempts = new HashSet<>();
    private final Set<Music> savedQueuedSongs = new HashSet<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        save(CloudMusic.class, "playList", "playThread", "currentlyPlaying", "player", "currentSession",
                "curIdx", "playMode", "queueRevision", "doBreak", "dontAdd", "awaitingPlaybackAction",
                "personalFmActive", "playedFrom", "fadingOutPlayer");
        save(AutomixSettings.class, "loaded", "enabled", "advancedMode", "transitionAnimation");
        save(AmbientMusicController.class, "suspendedByUser", "searching", "playingLabel", "requestRevision");
        save(AmbientSettings.class, "loaded", "enabled");
        oldGeneration = CloudMusic.generation.get();
        oldPlaying = CloudMusic.playing.get();
        savedQueuedSongs.addAll((Set<Music>) get(CloudMusic.playNextLedger, "queued"));
        CloudMusic.playNextLedger.clear();
        set(AutomixSettings.class, "loaded", true);
        set(AutomixSettings.class, "enabled", true);
        set(AutomixSettings.class, "advancedMode", false);
        set(AutomixSettings.class, "transitionAnimation", false);
        set(AmbientSettings.class, "loaded", true);
        set(AmbientSettings.class, "enabled", false);
        set(CloudMusic.class, "doBreak", false);
        set(CloudMusic.class, "dontAdd", false);
        set(CloudMusic.class, "awaitingPlaybackAction", false);
        set(CloudMusic.class, "personalFmActive", true);
        set(CloudMusic.class, "playedFrom", null);
        lyricFailures = (Map<String, Boolean>) get(CloudMusic.class, "FAILED_LYRIC_KEYS");
        coverAttempts = (Set<String>) get(MusicCoverService.class, "DYNAMIC_COVER_ATTEMPTS");
        for (long id = 99_201L; id <= 99_203L; id++) {
            Music song = new StubSong(id);
            songs.add(song);
            savedLyricFailures.put(song.getStableKey(), lyricFailures.put(song.getStableKey(), true));
            if (coverAttempts.add(song.getStableKey())) addedCoverAttempts.add(song.getStableKey());
            suppressCover(song.getCoverLocation());
            suppressCover(song.getSmallCoverLocation());
        }
        CloudMusic.playList = songs;
        CloudMusic.curIdx = 0;
        CloudMusic.playMode = CloudMusic.PlayMode.Sequential;
        CloudMusic.currentlyPlaying = songs.get(0);
        ownerType = Class.forName(CloudMusic.class.getName() + "$PlayThread");
        trackType = Class.forName(ownerType.getName() + "$ArmedTrack");
        Constructor<?> constructor = ownerType.getDeclaredConstructor(List.class, int.class);
        constructor.setAccessible(true);
        owner = constructor.newInstance(songs, 0);
        session = new BarrierSession(CloudMusic.generation.incrementAndGet(), songs.get(0));
        set(owner, "session", session);
        // The controlled decks have no engine; keep audio-task registration out of the fixture.
        set(owner, "automixAudioTaskRegistered", true);
        CloudMusic.currentSession = session;
        CloudMusic.playThread = (Thread) owner;
        outgoing = deck();
        set(owner, "ownedPlayer", outgoing);
        CloudMusic.player = outgoing;
        session.player = outgoing;
        Method start = ownerType.getDeclaredMethod("startPlayback", Music.class, Tuple.class,
                File.class, PlaybackSession.class);
        start.setAccessible(true);
        assertTrue((boolean) start.invoke(owner, songs.get(0), null, null, session));
        set(CloudMusic.class, "personalFmActive", false);
        outgoing.sample.positionSeconds = 179f;
    }

    @AfterEach
    void tearDown() throws Exception {
        session.release.countDown();
        CloudMusic.playNextLedger.clear();
        for (Music song : savedQueuedSongs) CloudMusic.playNextLedger.remember(song);
        for (Map.Entry<String, Boolean> entry : savedLyricFailures.entrySet()) {
            if (entry.getValue() == null) lyricFailures.remove(entry.getKey());
            else lyricFailures.put(entry.getKey(), entry.getValue());
        }
        coverAttempts.removeAll(addedCoverAttempts);
        for (Map.Entry<Location, ITextureObject> entry : savedTextures.entrySet()) {
            if (entry.getValue() == null) TextureManager.getInstance().mapTextureObjects.remove(entry.getKey());
            else TextureManager.getInstance().mapTextureObjects.put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<Field, Object> entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
        CloudMusic.generation.set(oldGeneration);
        CloudMusic.playing.set(oldPlaying);
    }

    @Test
    void completionAlreadyPastItsSessionCheckCannotFinishThePromotedDeck() throws Exception {
        ControlledDeck incoming = arm(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread completion = new Thread(() -> {
            try { outgoing.sample.complete(); }
            catch (Throwable thrown) { failure.set(thrown); }
        }, "controlled-old-completion");
        session.blockedThread = completion;
        completion.start();
        try {
            assertTrue(session.checked.await(2, TimeUnit.SECONDS));
            assertTrue(outgoing.isFinished(), "the real AudioPlayer callback publishes completion first");
            assertTrue(handover(outgoing));
            assertSame(incoming, CloudMusic.player);
            assertFalse(session.isActive());
        } finally {
            session.release.countDown();
            completion.join(2_000L);
        }
        assertFalse(completion.isAlive());
        assertNull(failure.get());
        assertTrue(CloudMusic.playing.get(), "an old deck cannot stop the newly committed deck");
        assertFalse(completed(incoming));
        assertFalse(handover(incoming), "there is no second armed song to skip to");
        assertSame(songs.get(1), CloudMusic.currentlyPlaying);
        assertEquals(1, CloudMusic.curIdx);
    }

    @Test
    void realCompletionOfAnAutomixDeckIsRecognizedWithoutStoppingTheFollowingDeck() throws Exception {
        ControlledDeck second = arm(1);
        assertTrue(handover(outgoing));
        second.sample.complete();
        assertTrue(second.isFinished());
        assertTrue(completed(second));
        ControlledDeck third = arm(2);
        assertTrue(handover(second));
        assertSame(third, CloudMusic.player);
        assertTrue(CloudMusic.playing.get());
        assertFalse(completed(third));
        assertSame(songs.get(2), CloudMusic.currentlyPlaying);
    }

    @Test
    void switchingToSingleLoopInvalidatesThePreparedNextSong() throws Exception {
        CloudMusic.playMode = CloudMusic.PlayMode.Random;
        ControlledDeck prepared = arm(1);
        long revision = CloudMusic.queueRevision;
        assertEquals(CloudMusic.PlayMode.LoopSingle, CloudMusic.cyclePlayMode());
        assertTrue(CloudMusic.queueRevision > revision);
        assertFalse(handover(outgoing));
        assertSame(outgoing, CloudMusic.player);
        assertEquals(0, prepared.starts);
        assertTrue(prepared.closed);
    }

    @Test
    void switchingToShuffleInvalidatesPreparationEvenWhenTheSongRemainsNext() throws Exception {
        songs.remove(2);
        ControlledDeck prepared = arm(1);
        long revision = CloudMusic.queueRevision;
        assertEquals(CloudMusic.PlayMode.Random, CloudMusic.cyclePlayMode());
        assertTrue(CloudMusic.queueRevision > revision);
        assertFalse(handover(outgoing));
        assertEquals(0, prepared.starts);
        assertSame(outgoing, CloudMusic.player);
    }

    @Test
    void anEqualButDifferentQueueEntryCannotReuseThePreparedIdentity() throws Exception {
        ControlledDeck prepared = arm(1);
        Music replacement = songs.get(1).queueIdentityCopy();
        assertEquals(songs.get(1), replacement);
        assertNotSame(songs.get(1), replacement);
        songs.set(1, replacement);
        assertFalse(handover(outgoing));
        assertEquals(0, prepared.starts);
        assertTrue(prepared.closed);
        assertSame(outgoing, CloudMusic.player);
    }

    @Test
    void modeChangeAfterTheEarlyHandoverCheckIsRecheckedAtCommit() throws Exception {
        CloudMusic.playMode = CloudMusic.PlayMode.Random;
        ControlledDeck prepared = arm(1);
        prepared.checked = new CountDownLatch(1);
        prepared.release = new CountDownLatch(1);
        AtomicReference<Boolean> accepted = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread handover = new Thread(() -> {
            try { accepted.set(handover(outgoing)); }
            catch (Throwable thrown) { failure.set(thrown); }
        }, "controlled-handover");
        prepared.blockedThread = handover;
        handover.start();
        try {
            assertTrue(prepared.checked.await(2, TimeUnit.SECONDS));
            assertEquals(CloudMusic.PlayMode.LoopSingle, CloudMusic.cyclePlayMode());
        } finally {
            prepared.release.countDown();
            handover.join(2_000L);
        }
        assertFalse(handover.isAlive());
        assertNull(failure.get());
        assertEquals(Boolean.FALSE, accepted.get());
        assertEquals(0, prepared.starts);
        assertSame(outgoing, CloudMusic.player);
        assertSame(songs.get(0), CloudMusic.currentlyPlaying);
    }

    @Test
    void aSongRemovedDuringSilentStartupCannotBeCommittedFromTheOldQueue() throws Exception {
        Music removed = songs.get(1);
        ControlledDeck prepared = arm(1);
        CloudMusic.playNextLedger.remember(removed);
        prepared.volumeChecked = new CountDownLatch(1);
        prepared.volumeRelease = new CountDownLatch(1);
        AtomicReference<Boolean> accepted = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread handover = new Thread(() -> {
            try { accepted.set(handover(outgoing)); }
            catch (Throwable thrown) { failure.set(thrown); }
        }, "controlled-queue-removal");
        prepared.volumeBlockedThread = handover;
        handover.start();
        try {
            assertTrue(prepared.volumeChecked.await(2, TimeUnit.SECONDS));
            assertTrue(CloudMusic.removeQueuedNext(0), "the edit completes before startup resumes");
            assertFalse(songs.contains(removed));
        } finally {
            prepared.volumeRelease.countDown();
            handover.join(2_000L);
        }
        assertFalse(handover.isAlive());
        assertNull(failure.get());
        assertEquals(Boolean.FALSE, accepted.get());
        assertFalse(prepared.isPlayRequested(), "the rejected silent deck must be stopped");
        assertSame(outgoing, CloudMusic.player);
        assertSame(songs.get(0), CloudMusic.currentlyPlaying);
        assertEquals(0, CloudMusic.curIdx);
        assertTrue(session.isActive());
    }

    @Test
    void aManualSelectionMovedDuringSilentStartupCommitsAtItsCurrentIndex() throws Exception {
        Music selected = songs.get(2);
        Music other = songs.get(1);
        ControlledDeck prepared = arm(2);
        Class<?> selectionType = Class.forName(ownerType.getName() + "$ManualSelection");
        Constructor<?> constructor = selectionType.getDeclaredConstructor(List.class, int.class,
                boolean.class, PlaybackSession.class);
        constructor.setAccessible(true);
        Object selection = constructor.newInstance(songs, 2, false, session);
        set(selection, "ready", get(owner, "armed"));
        set(owner, "armed", null);
        set(owner, "manualSelection", selection);
        CloudMusic.playNextLedger.remember(other);
        CloudMusic.playNextLedger.remember(selected);
        prepared.volumeChecked = new CountDownLatch(1);
        prepared.volumeRelease = new CountDownLatch(1);
        AtomicReference<Boolean> accepted = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread handover = new Thread(() -> {
            try { accepted.set((boolean) call("tryManualSelectionHandover", outgoing)); }
            catch (Throwable thrown) { failure.set(thrown); }
        }, "controlled-manual-queue-move");
        prepared.volumeBlockedThread = handover;
        handover.start();
        try {
            assertTrue(prepared.volumeChecked.await(2, TimeUnit.SECONDS));
            assertTrue(CloudMusic.moveQueuedNext(1, 0), "the edit completes before startup resumes");
            assertSame(selected, songs.get(1));
        } finally {
            prepared.volumeRelease.countDown();
            handover.join(2_000L);
        }
        assertFalse(handover.isAlive());
        assertNull(failure.get());
        assertEquals(Boolean.TRUE, accepted.get());
        assertSame(prepared, CloudMusic.player);
        assertSame(selected, CloudMusic.currentlyPlaying);
        assertEquals(1, CloudMusic.curIdx);
        assertSame(selected, songs.get(1));
        assertSame(other, songs.get(2), "committing the selection must not overwrite its former neighbour");
    }

    private ControlledDeck arm(int index) throws Exception {
        ControlledDeck deck = deck();
        Constructor<?> constructor = trackType.getDeclaredConstructor(Music.class, File.class,
                AudioPlayer.class, int.class, long.class, long.class);
        constructor.setAccessible(true);
        Object track = constructor.newInstance(songs.get(index), null, deck, index,
                CloudMusic.generation.get(), CloudMusic.queueRevision);
        set(track, "plan", new AutomixPlan(176_000L, 4_000L, 0L, 1f,
                false, false, 1f, false, 0L, 0f, "controlled tail"));
        set(owner, "armed", track);
        return deck;
    }

    private boolean handover(AudioPlayer deck) throws Exception {
        return (boolean) call("tryAutomixHandover", deck);
    }

    private boolean completed(AudioPlayer deck) throws Exception {
        return (boolean) call("playbackCompleted", deck);
    }

    private Object call(String name, AudioPlayer deck) throws Exception {
        Method method = ownerType.getDeclaredMethod(name, AudioPlayer.class);
        method.setAccessible(true);
        return method.invoke(owner, deck);
    }

    private void save(Class<?> type, String... names) throws Exception {
        for (String name : names) {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            saved.put(field, field.get(null));
        }
    }

    private void suppressCover(Location location) {
        savedTextures.put(location, TextureManager.getInstance().mapTextureObjects.put(location, DUMMY_TEXTURE));
    }

    private static ControlledDeck deck() throws Exception {
        ControlledDeck deck = allocate(ControlledDeck.class);
        deck.sample = allocate(ControlledSample.class);
        deck.player = deck.sample;
        deck.setListeners();
        return deck;
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = (target instanceof Class<?> ? (Class<?>) target : target.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target instanceof Class<?> ? null : target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target instanceof Class<?> ? null : target, value);
                return;
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("controlled operation was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class BarrierSession extends PlaybackSession {
        final CountDownLatch checked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Thread blockedThread;
        BarrierSession(long id, Music song) { super(id, song.getStableKey(), song.getId()); }
        @Override public boolean isActive() {
            boolean active = super.isActive();
            if (Thread.currentThread() == blockedThread) {
                checked.countDown();
                await(release);
            }
            return active;
        }
    }

    private static final class ControlledDeck extends AudioPlayer {
        ControlledSample sample;
        int starts;
        boolean closed;
        volatile Thread blockedThread;
        CountDownLatch checked;
        CountDownLatch release;
        volatile Thread volumeBlockedThread;
        CountDownLatch volumeChecked;
        CountDownLatch volumeRelease;
        private ControlledDeck() { super(null); }
        @Override public boolean isUsable() {
            if (Thread.currentThread() == blockedThread) {
                checked.countDown();
                await(release);
            }
            return super.isUsable();
        }
        @Override public void setVolume(float volume) {
            super.setVolume(volume);
            if (Thread.currentThread() == volumeBlockedThread) {
                volumeChecked.countDown();
                await(volumeRelease);
            }
        }
        @Override void enableManualTransitionProcessing() { }
        @Override public void resetAutomixProcessing() { }
        @Override public void prepareStart(float millis) { sample.positionSeconds = millis / 1_000f; }
        @Override public void play() {
            try {
                set(this, "playRequested", true);
                set(this, "finished", false);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            starts++;
            sample.running = true;
        }
        @Override public void playFrom(float millis) { sample.positionSeconds = millis / 1_000f; play(); }
        @Override public void close() { closed = true; sample.running = false; }
    }

    private static final class ControlledSample extends SoundFile {
        volatile boolean running;
        volatile Runnable completion;
        float positionSeconds;
        private ControlledSample() { super((String) null); }
        @Override public int frames() { return 180 * 44_100; }
        @Override public int sampleRate() { return 44_100; }
        @Override public float duration() { return 180f; }
        @Override public float position() { return positionSeconds; }
        @Override public boolean isPlaying() { return running; }
        @Override public boolean hasVocalMaterial() { return false; }
        @Override public void amp(float amplitude) { }
        @Override public void setOnFinished(Runnable callback) { completion = callback; }
        synchronized void complete() { running = false; completion.run(); }
    }

    private static final class StubSong extends Music {
        StubSong(long id) {
            super("ownership-" + id, "ownership-" + id, "", id, Collections.emptyList(),
                    Collections.emptyList(), new Album(id, "test", "", Collections.emptyList()),
                    180_000L, 0L, 0L, Collections.emptyList());
        }
        @Override public boolean isNetease() { return false; }
        @Override public Tuple<String, String> getPlayUrl() { throw new AssertionError("unexpected resolution"); }
    }

    private static final ITextureObject DUMMY_TEXTURE = new ITextureObject() {
        @Override public void setBlurMipmap(boolean blur, boolean mipmap) { }
        @Override public void restoreLastBlurMipmap() { }
        @Override public int getGlTextureId() { return 0; }
        @Override public void linearFilter() { }
        @Override public void nearestFilter() { }
    };
}
