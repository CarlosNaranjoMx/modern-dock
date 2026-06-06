package com.github.arthurdeka.cedromoderndock.util;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class NativeWindowUtils {

    // Browser executables whose --app windows back a web-app shortcut.
    private static final Set<String> BROWSER_EXECUTABLES =
            Set.of("msedge.exe", "chrome.exe", "brave.exe", "opera.exe", "vivaldi.exe");

    // Minimal info required by the popup to activate and label a window.
    public record WindowInfo(HWND hwnd, String title) {}

    public static List<WindowInfo> getOpenWindows(String executablePath) {
        List<WindowInfo> windows = new ArrayList<>();
        if (executablePath == null || executablePath.isEmpty()) {
            return windows;
        }

        // Normalize the target executable path so comparisons are stable.
        final Path targetPath = Paths.get(executablePath).toAbsolutePath().normalize();

        // Web-app shortcuts (.lnk launching a browser with --app) all share the browser executable,
        // so they can't be matched by process path. Match them by browser window title instead.
        final boolean isShortcut = executablePath.toLowerCase(Locale.ROOT).endsWith(".lnk");
        final String shortcutName = isShortcut ? shortcutMatchName(targetPath) : null;

        User32.INSTANCE.EnumWindows((hWnd, arg1) -> {
            if (User32.INSTANCE.IsWindowVisible(hWnd)) {
                char[] buffer = new char[1024];
                User32.INSTANCE.GetWindowText(hWnd, buffer, 1024);
                String title = new String(buffer).trim();

                // Skip windows without title or hidden ones (some invisible windows report visible but have empty title/rect).
                if (title.isEmpty()) {
                    return true;
                }

                boolean matches = isShortcut
                        ? isBrowserWindowWithTitle(hWnd, shortcutName, title)
                        : isWindowFromExecutable(hWnd, targetPath);
                if (matches) {
                    windows.add(new WindowInfo(hWnd, title));
                }
            }
            return true;
        }, null);

        return windows;
    }

    // Lowercased shortcut name (file name without .lnk) used to match browser window titles.
    // Returns null for names shorter than 2 chars to avoid matching almost everything (e.g. "X").
    private static String shortcutMatchName(Path shortcutPath) {
        Path fileName = shortcutPath.getFileName();
        if (fileName == null) {
            return null;
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.trim().toLowerCase(Locale.ROOT);
        return name.length() >= 2 ? name : null;
    }

    private static boolean isBrowserWindowWithTitle(HWND hWnd, String shortcutName, String title) {
        if (shortcutName == null) {
            return false;
        }
        if (!title.toLowerCase(Locale.ROOT).contains(shortcutName)) {
            return false;
        }
        Path processPath = getProcessImagePath(hWnd);
        if (processPath == null || processPath.getFileName() == null) {
            return false;
        }
        return BROWSER_EXECUTABLES.contains(processPath.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private static boolean isWindowFromExecutable(HWND hWnd, Path targetPath) {
        Path processPath = getProcessImagePath(hWnd);
        // Prefer full path match, but fall back to filename match for edge cases.
        return processPath != null && isSameExecutable(processPath, targetPath);
    }

    // Returns the normalized image path of the process that owns the given window, or null.
    private static Path getProcessImagePath(HWND hWnd) {
        IntByReference pidRef = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hWnd, pidRef);
        int pid = pidRef.getValue();

        WinNT.HANDLE process = Kernel32.INSTANCE.OpenProcess(
                WinNT.PROCESS_QUERY_LIMITED_INFORMATION,
                false,
                pid
        );
        if (process == null) {
            return null;
        }
        try {
            char[] pathBuffer = new char[1024];
            IntByReference size = new IntByReference(pathBuffer.length);
            if (Kernel32.INSTANCE.QueryFullProcessImageName(process, 0, pathBuffer, size)) {
                String processPathStr = new String(pathBuffer, 0, size.getValue());
                return Paths.get(processPathStr).toAbsolutePath().normalize();
            }
            return null;
        } finally {
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }

    private static boolean isSameExecutable(Path processPath, Path targetPath) {
        if (processPath == null || targetPath == null) {
            return false;
        }

        // Exact path match.
        if (processPath.equals(targetPath)) {
            return true;
        }

        // Case-insensitive path match (Windows path comparisons).
        String processStr = normalizePathString(processPath);
        String targetStr = normalizePathString(targetPath);
        if (processStr.equalsIgnoreCase(targetStr)) {
            return true;
        }

        // Fallback: match only the filename when the full path is not comparable.
        Path processFile = processPath.getFileName();
        Path targetFile = targetPath.getFileName();
        if (processFile != null && targetFile != null) {
            return processFile.toString().equalsIgnoreCase(targetFile.toString());
        }

        return false;
    }

    private static String normalizePathString(Path path) {
        String value = path.toString();
        // Strip Windows extended-length path prefix if present.
        if (value.startsWith("\\\\?\\")) {
            value = value.substring(4);
        }
        return value;
    }

    public static void activateWindow(HWND hwnd) {
        if (hwnd == null) return;

        // Restore window if minimized
        User32.INSTANCE.ShowWindow(hwnd, User32.SW_RESTORE);

        // Bring to front
        User32.INSTANCE.SetForegroundWindow(hwnd);
    }
}
