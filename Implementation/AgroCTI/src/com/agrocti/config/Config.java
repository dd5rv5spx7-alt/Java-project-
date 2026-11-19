package com.agrocti.config;

import java.time.LocalDateTime;

/** All fixed settings of the simulated smart-farm network and the experiments. */
public final class Config {

    private Config() { }

    public static final String[] REGIONS = {"Guntur", "Krishna", "Prakasam", "Nellore"};
    public static final int FARMS_PER_REGION = 6;
    public static final int NODES = REGIONS.length * FARMS_PER_REGION;

    /** One reading every 15 minutes. */
    public static final int STEPS_PER_DAY = 96;
    public static final int TRAIN_DAYS = 21;
    public static final int VAL_DAYS = 5;
    public static final int TEST_DAYS = 14;
    public static final int TOTAL_STEPS = (TRAIN_DAYS + VAL_DAYS + TEST_DAYS) * STEPS_PER_DAY;
    public static final int TRAIN_END = TRAIN_DAYS * STEPS_PER_DAY;              // exclusive
    public static final int VAL_END = (TRAIN_DAYS + VAL_DAYS) * STEPS_PER_DAY;    // exclusive

    /** Window used by the feature extractor (8 x 15 min = 2 hours). */
    public static final int WINDOW = 8;
    /** Longer horizon of the cumulative water-balance feature (6 hours). */
    public static final int LONG_WINDOW = 24;
    /** Sequence length fed to the CNN-LSTM baseline (3 hours). */
    public static final int SEQ_LEN = 12;

    public static final LocalDateTime START = LocalDateTime.of(2026, 7, 1, 0, 0);

    public static final String CONTROLLER_IP = "52.66.140.10";

    public static final String[] FEATURES = {"soil_moisture", "soil_temp", "air_temp", "humidity",
            "rainfall", "soil_ec", "flow_rate", "cmd_rate"};
    public static final String[] UNITS = {"%", "°C", "°C", "%", "mm", "dS/m", "L/min", "cmd"};
    public static final int SM = 0, ST = 1, AT = 2, RH = 3, RAIN = 4, EC = 5, FLOW = 6, CMD = 7;
    public static final int NF = FEATURES.length;

    public static int regionOf(int node) {
        return node / FARMS_PER_REGION;
    }

    public static int farmOf(int node) {
        return node % FARMS_PER_REGION;
    }

    public static String gatewayIp(int node) {
        return "10." + (regionOf(node) + 1) + "." + (farmOf(node) + 1) + ".1";
    }

    public static String nodeName(int node) {
        return REGIONS[regionOf(node)].substring(0, 3).toUpperCase() + "-F" + (farmOf(node) + 1);
    }

    public static double hourOf(int step) {
        return (step % STEPS_PER_DAY) / 4.0;
    }

    public static LocalDateTime timeOf(int step) {
        return START.plusMinutes(15L * step);
    }

    /** Inverse of timeOf: the 15-minute step that contains the given time. */
    public static int stepOf(LocalDateTime time) {
        return (int) Math.floorDiv(java.time.Duration.between(START, time).toMinutes(), 15L);
    }

    /** Index of a region name, or -1. */
    public static int regionIndex(String name) {
        for (int r = 0; r < REGIONS.length; r++) if (REGIONS[r].equalsIgnoreCase(name)) return r;
        return -1;
    }
}
