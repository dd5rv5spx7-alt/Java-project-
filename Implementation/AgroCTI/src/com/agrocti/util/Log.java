package com.agrocti.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Prints time-stamped log lines. synchronized so lines from different threads do not mix. */
public final class Log {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Log() { }

    public static synchronized void info(String msg) {
        System.out.println("[" + LocalTime.now().format(F) + "] " + msg);
    }

    public static synchronized void warn(String msg) {
        System.out.println("[" + LocalTime.now().format(F) + "] WARNING: " + msg);
    }
}
