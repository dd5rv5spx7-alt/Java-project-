package com.agrocti.data;

import com.agrocti.config.Config;
import com.agrocti.exception.DataFormatException;
import com.agrocti.model.ActuatorEvent;
import com.agrocti.model.AttackEpisode;
import com.agrocti.model.AttackType;
import com.agrocti.model.ContextEvent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Saves the dataset as three CSV files and reads them back:
 * telemetry.csv (one row per node per 15 minutes), actuator_log.csv and attack_episodes.csv.
 * Any dataset in the same format (for example real farm logs) can be loaded the same way.
 */
public final class CsvStore {

    public static final String TELEMETRY = "telemetry.csv";
    public static final String ACTUATOR = "actuator_log.csv";
    public static final String EPISODES = "attack_episodes.csv";

    private CsvStore() { }

    public static void save(Dataset ds, Path dir) throws IOException {
        Files.createDirectories(dir);
        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve(TELEMETRY), StandardCharsets.UTF_8)) {
            w.write("node_id,node_name,step,timestamp," + String.join(",", Config.FEATURES) + ",label,context\n");
            for (int t = 0; t < ds.steps; t++) {
                for (int n = 0; n < ds.nodes; n++) {
                    double[] v = ds.values[n][t];
                    StringBuilder sb = new StringBuilder();
                    sb.append(n).append(',').append(Config.nodeName(n)).append(',').append(t).append(',')
                      .append(Config.timeOf(t)).append(',');
                    for (double x : v) sb.append(String.format(Locale.ROOT, "%.4f", x)).append(',');
                    sb.append(ds.labels[n][t].name()).append(',').append(ds.context[n][t].name()).append('\n');
                    w.write(sb.toString());
                }
            }
        }
        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve(ACTUATOR), StandardCharsets.UTF_8)) {
            w.write("step,node_id,command,source_ip\n");
            for (ActuatorEvent e : ds.allEvents()) w.write(e + "\n");
        }
        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve(EPISODES), StandardCharsets.UTF_8)) {
            w.write("id,type,node_id,start_step,end_step,campaign,detail\n");
            for (AttackEpisode e : ds.getEpisodes()) {
                w.write(e.getId() + "," + e.getType().name() + "," + e.getNode() + "," + e.getStart() + ","
                        + e.getEnd() + "," + e.getCampaign() + "," + e.getDetail().replace(',', ';') + "\n");
            }
        }
    }

    public static Dataset load(Path dir) throws IOException, DataFormatException {
        Dataset ds = new Dataset(Config.NODES, Config.TOTAL_STEPS, Config.NF);
        boolean[][] seen = new boolean[Config.NODES][Config.TOTAL_STEPS];
        try (BufferedReader r = Files.newBufferedReader(dir.resolve(TELEMETRY), StandardCharsets.UTF_8)) {
            String line = r.readLine();
            int no = 1;
            while ((line = r.readLine()) != null) {
                no++;
                String[] c = line.split(",");
                if (c.length != 6 + Config.NF) throw new DataFormatException(TELEMETRY, no, "expected " + (6 + Config.NF) + " columns");
                try {
                    int n = Integer.parseInt(c[0]);
                    int t = Integer.parseInt(c[2]);
                    if (n < 0 || n >= Config.NODES || t < 0 || t >= Config.TOTAL_STEPS) {
                        throw new DataFormatException(TELEMETRY, no, "node or step out of range");
                    }
                    for (int f = 0; f < Config.NF; f++) ds.values[n][t][f] = Double.parseDouble(c[4 + f]);
                    ds.labels[n][t] = AttackType.valueOf(c[4 + Config.NF]);
                    ds.context[n][t] = ContextEvent.valueOf(c[5 + Config.NF]);
                    seen[n][t] = true;
                } catch (IllegalArgumentException e) {      // NumberFormatException is a subclass
                    throw new DataFormatException(TELEMETRY, no, e.getMessage());
                }
            }
        }
        for (int n = 0; n < Config.NODES; n++) {
            for (int t = 0; t < Config.TOTAL_STEPS; t++) {
                if (!seen[n][t]) throw new DataFormatException(TELEMETRY, 0, "missing reading for node " + n + " step " + t);
            }
        }
        try (BufferedReader r = Files.newBufferedReader(dir.resolve(ACTUATOR), StandardCharsets.UTF_8)) {
            String line = r.readLine();
            int no = 1;
            while ((line = r.readLine()) != null) {
                no++;
                String[] c = line.split(",");
                if (c.length != 4) throw new DataFormatException(ACTUATOR, no, "expected 4 columns");
                try {
                    ds.addEvent(new ActuatorEvent(Integer.parseInt(c[0]), Integer.parseInt(c[1]), c[2], c[3]));
                } catch (NumberFormatException e) {
                    throw new DataFormatException(ACTUATOR, no, e.getMessage());
                }
            }
        }
        try (BufferedReader r = Files.newBufferedReader(dir.resolve(EPISODES), StandardCharsets.UTF_8)) {
            String line = r.readLine();
            int no = 1;
            while ((line = r.readLine()) != null) {
                no++;
                String[] c = line.split(",", 7);
                if (c.length != 7) throw new DataFormatException(EPISODES, no, "expected 7 columns");
                try {
                    ds.addEpisode(new AttackEpisode(Integer.parseInt(c[0]), AttackType.valueOf(c[1]), Integer.parseInt(c[2]),
                            Integer.parseInt(c[3]), Integer.parseInt(c[4]), Integer.parseInt(c[5]), c[6]));
                } catch (IllegalArgumentException e) {
                    throw new DataFormatException(EPISODES, no, e.getMessage());
                }
            }
        }
        return ds;
    }
}
