package com.github.arthurdeka.cedromoderndock.util;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.nio.file.Files;
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
        final Path shortcutOrExePath = Paths.get(executablePath).toAbsolutePath().normalize();

        // Folder dock items pass their directory path here: they are "open" when an Explorer
        // window is showing that folder (matched by window title, which Explorer sets to the
        // folder name or full path).
        if (Files.isDirectory(shortcutOrExePath)) {
            return getOpenFolderWindows(shortcutOrExePath);
        }

        // Web-app shortcuts (.lnk launching a browser with --app) all share the browser executable,
        // so they can't be matched by process path. Match them by browser window title instead.
        // Shortcuts to regular programs (e.g. Steam.lnk) are resolved to their target executable
        // and matched by process path like any other program.
        boolean matchByTitle = false;
        Path resolvedTarget = shortcutOrExePath;
        if (executablePath.toLowerCase(Locale.ROOT).endsWith(".lnk")) {
            ShortcutResolver.ShortcutTarget shortcutTarget = ShortcutResolver.resolve(shortcutOrExePath);
            boolean isWebApp = shortcutTarget != null
                    && isBrowserExecutable(shortcutTarget.targetPath())
                    && shortcutTarget.arguments().contains("--app");
            if (shortcutTarget != null && !isWebApp && !shortcutTarget.targetPath().isEmpty()) {
                resolvedTarget = Paths.get(shortcutTarget.targetPath()).toAbsolutePath().normalize();
            } else {
                matchByTitle = true;
            }
        }
        final boolean isTitleMatch = matchByTitle;
        final String shortcutName = matchByTitle ? shortcutMatchName(shortcutOrExePath) : null;
        final Path targetPath = resolvedTarget;

        User32.INSTANCE.EnumWindows((hWnd, arg1) -> {
            if (User32.INSTANCE.IsWindowVisible(hWnd)) {
                char[] buffer = new char[1024];
                User32.INSTANCE.GetWindowText(hWnd, buffer, 1024);
                String title = new String(buffer).trim();

                // Skip windows without title or hidden ones (some invisible windows report visible but have empty title/rect).
                if (title.isEmpty()) {
                    return true;
                }

                boolean matches = isTitleMatch
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

    private static List<WindowInfo> getOpenFolderWindows(Path folderPath) {
        List<WindowInfo> windows = new ArrayList<>();
        Path leaf = folderPath.getFileName();
        if (leaf == null) {
            return windows;
        }
        final String leafName = leaf.toString().toLowerCase(Locale.ROOT);
        final String fullPath = normalizePathString(folderPath).toLowerCase(Locale.ROOT);

        User32.INSTANCE.EnumWindows((hWnd, arg1) -> {
            if (User32.INSTANCE.IsWindowVisible(hWnd)) {
                char[] buffer = new char[1024];
                User32.INSTANCE.GetWindowText(hWnd, buffer, 1024);
                String title = new String(buffer).trim();
                if (!title.isEmpty() && isExplorerWindowForFolder(hWnd, leafName, fullPath, title)) {
                    windows.add(new WindowInfo(hWnd, title));
                }
            }
            return true;
        }, null);

        return windows;
    }

    // Explorer titles the window with the folder name ("Music"), folder name plus suffix
    // ("Music - File Explorer") or the full path, depending on user settings.
    private static boolean isExplorerWindowForFolder(HWND hWnd, String leafName, String fullPath, String title) {
        String titleLower = title.toLowerCase(Locale.ROOT);
        boolean titleMatches = titleLower.equals(leafName)
                || titleLower.startsWith(leafName + " - ")
                || titleLower.equals(fullPath)
                || titleLower.startsWith(fullPath + " - ");
        if (!titleMatches) {
            return false;
        }
        Path processPath = getProcessImagePath(hWnd);
        if (processPath == null || processPath.getFileName() == null) {
            return false;
        }
        return processPath.getFileName().toString().equalsIgnoreCase("explorer.exe");
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

    private static boolean isBrowserExecutable(String executablePath) {
        if (executablePath == null || executablePath.isEmpty()) {
            return false;
        }
        Path fileName = Paths.get(executablePath).getFileName();
        return fileName != null && BROWSER_EXECUTABLES.contains(fileName.toString().toLowerCase(Locale.ROOT));
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
        if (processPath == null) {
            return false;
        }
        // Prefer full path match, but fall back to filename match for edge cases.
        return isSameExecutable(processPath, targetPath) || isHelperProcessWindow(processPath, targetPath);
    }

    // Some apps render their windows from helper processes (e.g. Steam's windows belong to
    // steamwebhelper.exe, not steam.exe). Match a window when its process lives under the target's
    // install directory AND its name starts with the target's base name, so unrelated programs
    // that merely share a folder (e.g. WINWORD next to POWERPNT) don't cross-match.
    private static boolean isHelperProcessWindow(Path processPath, Path targetPath) {
        Path installDir = targetPath.getParent();
        Path processFile = processPath.getFileName();
        Path targetFile = targetPath.getFileName();
        if (installDir == null || processFile == null || targetFile == null) {
            return false;
        }

        String targetBaseName = stripExeExtension(targetFile.toString());
        if (targetBaseName.length() < 3) {
            return false;
        }
        String processBaseName = stripExeExtension(processFile.toString());
        if (!processBaseName.toLowerCase(Locale.ROOT).startsWith(targetBaseName.toLowerCase(Locale.ROOT))) {
            return false;
        }

        String installDirStr = normalizePathString(installDir) + "\\";
        String processStr = normalizePathString(processPath);
        return processStr.regionMatches(true, 0, installDirStr, 0, installDirStr.length());
    }

    private static String stripExeExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
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
