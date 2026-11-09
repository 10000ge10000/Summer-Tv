package com.github.tvbox.osc.server;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class RemoteServerTest {

    @Test
    public void resolvesNormalPathWithinRoot() throws Exception {
        Path rootPath = Files.createTempDirectory("yiwantv-root");
        try {
            File resolved = RemoteServer.resolveWithin(rootPath.toFile(), "nested/file.txt");
            assertEquals(rootPath.resolve("nested/file.txt").toFile().getCanonicalPath(), resolved.getCanonicalPath());
        } finally {
            deleteRecursively(rootPath);
        }
    }

    @Test
    public void rejectsParentTraversalVariants() throws Exception {
        Path rootPath = Files.createTempDirectory("yiwantv-root");
        try {
            String[] escaped = {"../outside.txt", "nested/../../outside.txt", "./../outside.txt"};
            for (String relative : escaped) {
                assertThrows(IOException.class, () -> RemoteServer.resolveWithin(rootPath.toFile(), relative));
            }
        } finally {
            deleteRecursively(rootPath);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
