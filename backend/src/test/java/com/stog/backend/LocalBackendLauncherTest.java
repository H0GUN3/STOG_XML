package com.stog.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalBackendLauncherTest {
    @Test
    void privateDirectoryBehaviorPreservesContentRestrictsAclAndRejectsReparse() throws Exception {
        Process process = new ProcessBuilder(
                "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", "scripts/Test-LocalPrivateDirectory.ps1")
            .redirectErrorStream(true)
            .start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.exitValue(), output);
        assertEquals("PASS", output);
    }
}
