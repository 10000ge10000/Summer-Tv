package com.github.tvbox.osc.commercial;

import org.junit.Test;

import okhttp3.CertificatePinner;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class CommercialHttpTest {

    private static final String PRIMARY = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String BACKUP = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=";

    @Test
    public void blankPinsKeepPinningDisabled() {
        assertNull(CommercialHttp.buildCertificatePinner("example.com", "", ""));
        assertNull(CommercialHttp.buildCertificatePinner("example.com", PRIMARY, ""));
    }

    @Test
    public void twoDifferentPinsEnablePinner() {
        CertificatePinner pinner = CommercialHttp.buildCertificatePinner("example.com", PRIMARY, BACKUP);

        assertNotNull(pinner);
    }

    @Test
    public void duplicatePinsAreRejected() {
        assertNull(CommercialHttp.buildCertificatePinner("example.com", PRIMARY, PRIMARY));
    }
}
