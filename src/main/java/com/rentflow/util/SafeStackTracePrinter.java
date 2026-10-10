package com.rentflow.util;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.springframework.boot.logging.StackTracePrinter;

/** Retains diagnostic locations without exporting arbitrary throwable messages. */
public final class SafeStackTracePrinter implements StackTracePrinter {
    @Override
    public void printStackTrace(Throwable throwable, Appendable output) throws IOException {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        StringBuilder safe = new StringBuilder();
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 8 && seen.add(current); depth++) {
            if (depth > 0) {
                safe.append("Caused by: ");
            }
            safe.append(current.getClass().getName()).append('\n');
            StackTraceElement[] frames = current.getStackTrace();
            for (int index = 0; index < Math.min(frames.length, 64); index++) {
                safe.append("\tat ").append(frames[index]).append('\n');
            }
            current = current.getCause();
        }
        output.append(safe.substring(0, Math.min(safe.length(), 16384)));
    }

    public static String sanitizeExportedStack(String stack) {
        StringBuilder safe = new StringBuilder();
        for (String line : stack.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("at ") && trimmed.matches("at [\\w.$/<>]+\\([\\w.$: -]+\\)")) {
                safe.append(trimmed).append('\n');
            } else {
                String type =
                        trimmed.replaceFirst("^(Caused by: |Suppressed: )", "").split(":", 2)[0];
                if (type.matches("[\\w.$]+(?:Exception|Error|Throwable)")) {
                    safe.append(type).append('\n');
                }
            }
            if (safe.length() >= 16384) {
                break;
            }
        }
        return safe.substring(0, Math.min(safe.length(), 16384));
    }
}
