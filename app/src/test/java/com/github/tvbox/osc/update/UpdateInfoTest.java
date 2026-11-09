package com.github.tvbox.osc.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

/** update.json 解析与按构建变体选包 */
public class UpdateInfoTest {

    private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    private static final String JSON = "{"
            + "\"versionCode\":19,\"versionName\":\"1.0.19\",\"forceUpdate\":false,"
            + "\"releaseNote\":\"修复若干问题\","
            + "\"apks\":{"
            + "\"arm64GenericNormal\":{\"url\":\"https://github.com/x/y/releases/download/v1.0.19/a.apk\",\"sha256\":\"" + SHA_A + "\"},"
            + "\"armeabiGenericNormal\":{\"url\":\"https://github.com/x/y/releases/download/v1.0.19/b.apk\",\"sha256\":\"" + SHA_B + "\"}"
            + "}}";

    @Test
    public void selectsAssetForCurrentFlavor() {
        UpdateInfo info = new Gson().fromJson(JSON, UpdateInfo.class);
        info.selectVariant("armeabiGenericNormal");
        assertTrue(info.isValid());
        assertEquals(19, info.versionCode);
        assertTrue(info.apkUrl.endsWith("/b.apk"));
        // 校验时按小写十六进制比对
        assertEquals(SHA_B.toLowerCase(), info.sha256);
    }

    @Test
    public void unknownFlavorIsNotValid() {
        UpdateInfo info = new Gson().fromJson(JSON, UpdateInfo.class);
        info.selectVariant("arm64HisensePython");
        // 没有对应变体、也没有顶层兜底字段时不提示更新，避免装错包
        assertFalse(info.isValid());
    }

    @Test
    public void legacyFlatFormatStillWorks() {
        String legacy = "{\"versionCode\":19,\"apkUrl\":\"https://e.com/a.apk\",\"sha256\":\"" + SHA_A + "\"}";
        UpdateInfo info = new Gson().fromJson(legacy, UpdateInfo.class);
        info.selectVariant("arm64GenericNormal");
        assertTrue(info.isValid());
        assertEquals("https://e.com/a.apk", info.apkUrl);
    }
}
