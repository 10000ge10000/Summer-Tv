package com.github.tvbox.osc.update;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** GitHub 地址依次走直连和加速镜像，其他地址不加前缀 */
public class UpdateMirrorTest {

    @Test
    public void githubUrlTriesDirectThenMirrors() {
        List<String> list = UpdateMirrors.candidates("https://github.com/a/b/releases/latest/download/update.json");
        assertEquals(Arrays.asList(UpdateMirrors.PREFIXES), list);
        assertEquals("", list.get(0));
    }

    @Test
    public void rememberedMirrorGoesFirst() {
        UpdateMirrors.remember("https://ghfast.top/");
        try {
            List<String> list = UpdateMirrors.candidates("https://github.com/a/b/x.apk");
            assertEquals("https://ghfast.top/", list.get(0));
            assertEquals(UpdateMirrors.PREFIXES.length, list.size());
        } finally {
            UpdateMirrors.remember("");
        }
    }

    @Test
    public void otherHostsAreNotProxied() {
        assertEquals(Collections.singletonList(""), UpdateMirrors.candidates("https://example.com/update.json"));
    }
}
