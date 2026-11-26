package com.github.tvbox.osc.util;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PlaybackCompatProfileTest {

    @Test
    public void normalizePlayerType_mapsRemovedPlayersToExoHw() {
        assertEquals(PlaybackCompatProfile.PLAYER_EXO_HW, PlaybackCompatProfile.normalizePlayerType(1));
        assertEquals(PlaybackCompatProfile.PLAYER_EXO_HW, PlaybackCompatProfile.normalizePlayerType(3));
        assertEquals(PlaybackCompatProfile.PLAYER_EXO_HW, PlaybackCompatProfile.normalizePlayerType(99));
        assertEquals(PlaybackCompatProfile.PLAYER_EXO_HW, PlaybackCompatProfile.normalizePlayerType(2));
        assertEquals(PlaybackCompatProfile.PLAYER_EXO_SW, PlaybackCompatProfile.normalizePlayerType(4));
        assertEquals(PlaybackCompatProfile.PLAYER_SYSTEM, PlaybackCompatProfile.normalizePlayerType(0));
        // External players are preserved as-is
        assertEquals(10, PlaybackCompatProfile.normalizePlayerType(10));
        assertEquals(12, PlaybackCompatProfile.normalizePlayerType(12));
    }

    @Test
    public void isCompatibilityError_distinguishesDecoderVsNetworkErrors() {
        // Decoder / audio track / container errors -> compatibility error
        assertTrue(PlaybackCompatProfile.isCompatibilityError(4001, "Decoder init failed"));
        assertTrue(PlaybackCompatProfile.isCompatibilityError(4003, "Decoding failed"));
        assertTrue(PlaybackCompatProfile.isCompatibilityError(5001, "AudioTrack init failed"));
        assertTrue(PlaybackCompatProfile.isCompatibilityError(3001, "Parsing container malformed"));
        assertTrue(PlaybackCompatProfile.isCompatibilityError(-1, "MediaCodecVideoRenderer error"));
        assertTrue(PlaybackCompatProfile.isCompatibilityError(0, "Surface abandoned"));

        // Network IO / HTTP errors -> NOT compatibility error (should switch source instead)
        assertFalse(PlaybackCompatProfile.isCompatibilityError(2001, "IO network connection failed"));
        assertFalse(PlaybackCompatProfile.isCompatibilityError(2004, "Response code: 404"));
        assertFalse(PlaybackCompatProfile.isCompatibilityError(2002, "SocketTimeoutException"));
        assertFalse(PlaybackCompatProfile.isCompatibilityError(0, "HTTP 502 Bad Gateway"));
    }

    @Test
    public void nextFallback_walksFullLadderAndStopsWithoutLooping() {
        Set<String> tried = new HashSet<>();
        int curPl = PlaybackCompatProfile.PLAYER_EXO_HW;
        int curPr = PlaybackCompatProfile.RENDER_SURFACE;
        tried.add(PlaybackCompatProfile.modeKey(curPl, curPr));

        PlaybackCompatProfile.Mode m1 = PlaybackCompatProfile.nextFallback(curPl, curPr, tried, true);
        assertNotNull(m1);
        assertEquals(PlaybackCompatProfile.PLAYER_EXO_HW, m1.playerType);
        assertEquals(PlaybackCompatProfile.RENDER_TEXTURE, m1.renderType);
        tried.add(m1.key());

        PlaybackCompatProfile.Mode m2 = PlaybackCompatProfile.nextFallback(m1.playerType, m1.renderType, tried, true);
        assertNotNull(m2);
        assertEquals(PlaybackCompatProfile.PLAYER_EXO_SW, m2.playerType);
        assertEquals(PlaybackCompatProfile.RENDER_SURFACE, m2.renderType);
        tried.add(m2.key());

        PlaybackCompatProfile.Mode m3 = PlaybackCompatProfile.nextFallback(m2.playerType, m2.renderType, tried, true);
        assertNotNull(m3);
        assertEquals(PlaybackCompatProfile.PLAYER_SYSTEM, m3.playerType);
        assertEquals(PlaybackCompatProfile.RENDER_SURFACE, m3.renderType);
        tried.add(m3.key());

        // Exhausted
        assertNull(PlaybackCompatProfile.nextFallback(m3.playerType, m3.renderType, tried, true));
    }

    @Test
    public void nextFallback_skipsSystemPlayerForDashStreams() {
        Set<String> tried = new HashSet<>();
        tried.add(PlaybackCompatProfile.modeKey(PlaybackCompatProfile.PLAYER_EXO_HW, PlaybackCompatProfile.RENDER_SURFACE));
        tried.add(PlaybackCompatProfile.modeKey(PlaybackCompatProfile.PLAYER_EXO_HW, PlaybackCompatProfile.RENDER_TEXTURE));
        tried.add(PlaybackCompatProfile.modeKey(PlaybackCompatProfile.PLAYER_EXO_SW, PlaybackCompatProfile.RENDER_SURFACE));

        assertNull(PlaybackCompatProfile.nextFallback(
                PlaybackCompatProfile.PLAYER_EXO_SW,
                PlaybackCompatProfile.RENDER_SURFACE,
                tried,
                false));
    }

    @Test
    public void nextFallback_doesNotTriggerForExternalPlayers() {
        assertNull(PlaybackCompatProfile.nextFallback(10, PlaybackCompatProfile.RENDER_SURFACE, new HashSet<>(), true));
    }
}
