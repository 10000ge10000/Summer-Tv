package com.github.tvbox.osc.commercial;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CommercialConfigRepositoryTest {

    @Test
    public void compareVersionUsesNumericSegments() {
        assertTrue(CommercialConfigRepository.compareVersion("1.0.9", "1.0.10") < 0);
        assertTrue(CommercialConfigRepository.compareVersion("1.0.10", "1.0.9") > 0);
    }

    @Test
    public void compareVersionIgnoresSuffixAndMissingSegments() {
        assertEquals(0, CommercialConfigRepository.compareVersion("1.0", "1.0.0"));
        assertEquals(0, CommercialConfigRepository.compareVersion("1.0.17-beta", "1.0.17+build"));
        assertTrue(CommercialConfigRepository.compareVersion("2", "1.99.99") > 0);
    }
}
