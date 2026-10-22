package com.ejjat.aiplay.core.hw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Parses {@code ps} output into the biggest processes by resident memory.
 *
 * <p>The column layout differs between Android versions ({@code ps} before 8, toybox {@code ps -A -o
 * PID,RSS,NAME} after), so the RSS and NAME columns are found from the header, not assumed.</p>
 */
public final class ProcessTable {

    public static final class Proc {
        public final int pid;
        public final long rssKb;
        public final String name;
        public final String user;

        Proc(int pid, long rssKb, String name, String user) {
            this.pid = pid;
            this.rssKb = rssKb;
            this.name = name;
            this.user = user;
        }
    }

    public final List<Proc> all = new ArrayList<Proc>();

    public static ProcessTable parse(String text) {
        ProcessTable table = new ProcessTable();
        if (text == null) {
            return table;
        }
        int pidCol = -1;
        int rssCol = -1;
        int nameCol = -1;
        int userCol = -1;
        for (String line : text.split("\n")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length < 3) {
                continue;
            }
            if (pidCol < 0) {
                for (int i = 0; i < cols.length; i++) {
                    String head = cols[i].toUpperCase();
                    if (head.equals("PID")) {
                        pidCol = i;
                    } else if (head.equals("RSS")) {
                        rssCol = i;
                    } else if (head.equals("NAME") || head.equals("COMMAND") || head.equals("CMD")) {
                        nameCol = i;
                    } else if (head.equals("USER")) {
                        userCol = i;
                    }
                }
                if (pidCol >= 0 && rssCol >= 0 && nameCol >= 0) {
                    continue;       // header found
                }
                pidCol = rssCol = nameCol = userCol = -1;
                continue;
            }
            if (cols.length <= Math.max(Math.max(pidCol, rssCol), nameCol)) {
                continue;
            }
            try {
                int pid = Integer.parseInt(cols[pidCol]);
                long rss = Long.parseLong(cols[rssCol]);
                String name = cols[nameCol];
                String user = userCol >= 0 && userCol < cols.length ? cols[userCol] : "";
                table.all.add(new Proc(pid, rss, name, user));
            } catch (NumberFormatException skip) {
                // a wrapped or malformed row
            }
        }
        return table;
    }

    public boolean valid() {
        return !all.isEmpty();
    }

    /** The {@code count} processes with the largest resident size. */
    public List<Proc> top(int count) {
        List<Proc> sorted = new ArrayList<Proc>(all);
        Collections.sort(sorted, new Comparator<Proc>() {
            @Override
            public int compare(Proc a, Proc b) {
                return a.rssKb == b.rssKb ? 0 : (a.rssKb < b.rssKb ? 1 : -1);
            }
        });
        return sorted.subList(0, Math.min(count, sorted.size()));
    }

    /**
     * True when the list shows more than this app: the system's own processes are there. An app
     * that runs {@code ps} sees itself, the {@code sh} that started it and {@code ps}; counting
     * those as "other processes" made a one-app list look system-wide.
     */
    public boolean looksSystemWide() {
        int others = 0;
        for (Proc proc : all) {
            String name = proc.name;
            if (name.equals("system_server") || name.equals("zygote") || name.equals("zygote64")
                    || name.equals("init") || name.equals("/init") || name.equals("surfaceflinger")
                    || name.equals("/system/bin/surfaceflinger")) {
                return true;
            }
            if (!name.equals("ps") && !name.equals("sh") && !name.equals("toybox")
                    && !name.endsWith("/ps") && !name.endsWith("/sh")) {
                others++;
            }
        }
        return others >= 8;
    }

    /** Distinct process names that are not {@code ownName}: 0 means the list shows only AiPlay. */
    public int foreignCount(String ownName) {
        int count = 0;
        for (Proc proc : all) {
            if (!proc.name.equals(ownName)) {
                count++;
            }
        }
        return count;
    }
}
