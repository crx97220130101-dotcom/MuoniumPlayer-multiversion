package com.muoniumplayer.core.ncm.music.ambient;

import com.muoniumplayer.core.ncm.music.AudioPlayer;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AmbientPlaybackOwnershipTest {
    private final Map<Field, Object> saved = new LinkedHashMap<>();
    private boolean oldPlaying;
    private AtomicBoolean playing;
    private List<Music> queue;
    private PausableDeck deck;

    @BeforeEach
    void setUp() throws Exception {
        saveMutableStatics(AmbientMusicController.class);
        saveMutableStatics(AmbientSettings.class);
        for (String name : new String[]{"playList", "playThread", "currentlyPlaying", "player",
                "curIdx", "personalFmActive", "pendingResumeOffsetMillis"}) {
            Field field = field(CloudMusic.class, name);
            saved.put(field, field.get(null));
        }
        playing = (AtomicBoolean) field(CloudMusic.class, "playing").get(null);
        oldPlaying = playing.get();
        set(AmbientSettings.class, "loaded", true);
        set(AmbientSettings.class, "enabled", true);
        set(AmbientSettings.class, "stableMs", 5_000);
        AmbientMusicController.reset();

        Music song = new Music("manual", "manual", "", 921L,
                Collections.emptyList(), Collections.emptyList(),
                new Album(921L, "test", "", Collections.emptyList()),
                180_000L, 0L, 0L, Collections.emptyList());
        queue = new ArrayList<>(Collections.singletonList(song));
        deck = allocate(PausableDeck.class);
        CloudMusic.playList = queue;
        CloudMusic.curIdx = 0;
        CloudMusic.currentlyPlaying = song;
        CloudMusic.player = deck;
        CloudMusic.playThread = null;
        playing.set(true);
        // The transport's persistence hook must not write a real listening queue.
        set(CloudMusic.class, "personalFmActive", true);
        set(CloudMusic.class, "pendingResumeOffsetMillis", -1L);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Map.Entry<Field, Object> entry : saved.entrySet()) {
            entry.getKey().set(null, entry.getValue());
        }
        playing.set(oldPlaying);
    }

    @Test
    void manualPauseCancelsAResultThatWouldReplaceTheUsersPlaylist() throws Exception {
        long ticket = pendingSearch();

        assertEquals(CloudMusic.PlayPauseResult.PAUSED, CloudMusic.togglePlayPause());
        assertTrue(deck.paused);
        assertTrue(AmbientMusicController.isSuspendedByUser());
        assertNotEquals(ticket, get("requestRevision"));
        assertEquals(false, get("searching"));

        deliver(ticket, "宁静");
        assertSame(queue, CloudMusic.playList);
        assertSame(deck, CloudMusic.player);
        assertTrue(deck.paused);
    }

    @Test
    void resumingManuallyDoesNotGiveTheQueueBackToAutomaticSelection() throws Exception {
        pendingSearch();
        assertEquals(CloudMusic.PlayPauseResult.PAUSED, CloudMusic.togglePlayPause());
        assertEquals(CloudMusic.PlayPauseResult.RESUMED, CloudMusic.togglePlayPause());
        assertFalse(deck.paused);
        assertTrue(AmbientMusicController.isSuspendedByUser());
        assertSame(queue, CloudMusic.playList);
    }

    @Test
    void suspendedSelectionRejectsEvenAMatchingSearchTicket() throws Exception {
        AmbientMusicController.onManualPlaybackControl();
        set(AmbientMusicController.class, "lastSeenLabel", "宁静");
        deliver((long) get("requestRevision"), "宁静");
        assertSame(queue, CloudMusic.playList);
    }

    @Test
    void anOldResultCannotReleaseTheNewerSearch() throws Exception {
        long ticket = pendingSearch();
        deliver(ticket - 1L, "宁静");
        assertEquals(true, get("searching"));
        assertEquals(ticket, get("requestRevision"));
    }

    @Test
    void disablingSelectionRejectsAnInflightResultBeforeAnotherTick() throws Exception {
        long ticket = pendingSearch();
        set(AmbientSettings.class, "enabled", false);
        deliver(ticket, "宁静");
        assertEquals(false, get("searching"));
        assertSame(queue, CloudMusic.playList);
    }

    @Test
    void aMoodChangeInvalidatesTheSearchBeforeItsStablePeriodExpires() throws Exception {
        long ticket = pendingSearch();
        EnvSample combat = new EnvSample();
        combat.enemyCount = 1;
        AmbientMusicController.onClientTick(combat);
        assertNotEquals(ticket, get("requestRevision"));
        assertEquals(false, get("searching"));
        assertEquals("战斗", get("lastSeenLabel"));
        deliver(ticket, "宁静");
    }

    @Test
    void switchingWorldsCannotRevokeManualPlaybackOwnership() throws Exception {
        pendingSearch();
        AmbientMusicController.onManualPlaybackControl();
        AmbientMusicController.onClientTick(null);
        assertTrue(AmbientMusicController.isSuspendedByUser());
        AmbientMusicController.onClientTick(new EnvSample());
        assertTrue(AmbientMusicController.isSuspendedByUser());
        assertEquals(false, get("searching"));
    }

    @Test
    void explicitReenableClearsTheSuspensionAndOldSearch() throws Exception {
        pendingSearch();
        AmbientMusicController.onManualPlaybackControl();
        long oldTicket = (long) get("requestRevision");
        AmbientMusicController.reset();
        assertFalse(AmbientMusicController.isSuspendedByUser());
        assertEquals(false, get("searching"));
        assertNull(get("lastSeenLabel"));
        assertNull(get("playingLabel"));
        assertNotEquals(oldTicket, get("requestRevision"));
    }

    private long pendingSearch() throws Exception {
        set(AmbientMusicController.class, "lastSeenLabel", "宁静");
        set(AmbientMusicController.class, "searching", true);
        set(AmbientMusicController.class, "requestRevision", 41L);
        return 41L;
    }

    private static void deliver(long ticket, String label) throws Exception {
        // A cancelled callback must reject the result before inspecting or playing it.
        List<Music> unreadableResult = new AbstractList<Music>() {
            @Override public Music get(int index) { throw new AssertionError("stale song inspected"); }
            @Override public int size() { throw new AssertionError("stale result inspected"); }
        };
        Method method = AmbientMusicController.class.getDeclaredMethod("deliver",
                long.class, String.class, String.class, List.class, Throwable.class);
        method.setAccessible(true);
        method.invoke(null, ticket, label, "test", unreadableResult, null);
    }

    private void saveMutableStatics(Class<?> type) throws Exception {
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
            field.setAccessible(true);
            saved.put(field, field.get(null));
        }
    }

    private static Object get(String name) throws Exception {
        return field(AmbientMusicController.class, name).get(null);
    }

    private static void set(Class<?> type, String name, Object value) throws Exception {
        field(type, name).set(null, value);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = field(sun.misc.Unsafe.class, "theUnsafe");
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    private static final class PausableDeck extends AudioPlayer {
        private boolean paused;

        private PausableDeck() { super(null); }
        @Override public boolean isFinished() { return false; }
        @Override public boolean isPausing() { return paused; }
        @Override public void pause() { paused = true; }
        @Override public void unpause() { paused = false; }
        @Override public float getCurrentTimeMillis() { return 0f; }
    }
}
