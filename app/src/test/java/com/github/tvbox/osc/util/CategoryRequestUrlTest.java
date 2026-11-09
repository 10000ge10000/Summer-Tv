package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CategoryRequestUrlTest {
    @Test
    public void removesClientManagedCategoryParametersOnly() {
        assertEquals(
                "https://example.com/api.php?token=kept",
                CategoryRequestUrl.normalize("https://example.com/api.php?ac=list&t=1&pg=9&token=kept")
        );
    }

    @Test
    public void keepsMalformedCustomAddressForCompatibility() {
        assertEquals("custom-address", CategoryRequestUrl.normalize("custom-address"));
    }
}
