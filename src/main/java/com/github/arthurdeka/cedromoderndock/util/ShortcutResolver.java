package com.github.arthurdeka.cedromoderndock.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the target executable and arguments of a Windows .lnk shortcut.
 * Results are cached per shortcut path because resolution shells out to PowerShell.
 */
public final class ShortcutResolver {

    public record ShortcutTarget(String targetPath, String arguments) {}

    private static final Map<String, Optional<ShortcutTarget>> CACHE = new ConcurrentHashMap<>();

    private ShortcutResolver() {
    }

    public static ShortcutTarget resolve(Path shortcutPath) {
        String cacheKey = shortcutPath.toString().toLowerCase(Locale.ROOT);
        return CACHE.computeIfAbsent(cacheKey, ignored -> Optional.ofNullable(resolveWithPowerShell(shortcutPath)))
                .orElse(null);
    }

    // The shortcut path is passed through an environment variable to avoid quoting issues.
    private static ShortcutTarget resolveWithPowerShell(Path shortcutPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "$s = (New-Object -ComObject WScript.Shell).CreateShortcut($env:CMDDOCK_LNK); "
                            + "Write-Output ('TARGET::' + $s.TargetPath); "
                            + "Write-Output ('ARGS::' + $s.Arguments)"
            );
            pb.environment().put("CMDDOCK_LNK", shortcutPath.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String target = null;
            String arguments = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("TARGET::")) {
                        target = line.substring("TARGET::".length()).trim();
                    } else if (line.startsWith("ARGS::")) {
                        arguments = line.substring("ARGS::".length()).trim();
                    }
                }
            }
            process.waitFor();

            if (target == null || target.isEmpty()) {
                return null;
            }
            return new ShortcutTarget(target, arguments == null ? "" : arguments);
        } catch (Exception e) {
            Logger.error("Failed to resolve shortcut target for " + shortcutPath + ": " + e.getMessage());
            return null;
        }
    }
}
