package com.ejjat.aiplay.core;

import java.util.ArrayList;
import java.util.List;

/** One probed capability, with the evidence string the home screen shows next to it. */
public final class Capability {

    public enum State {
        OK,
        WARN,
        MISSING,
        UNKNOWN
    }

    public enum Id {
        GEARHEAD_PACKAGE,
        GEARHEAD_ENABLED,
        GEARHEAD_LAUNCHER,
        PLAY_SERVICES,
        ROOT_SHELL,
        SHIZUKU,
        ADB_WIRELESS,
        USB_HOST,
        WIFI_DIRECT,
        BLUETOOTH,
        RECEIVER_PORT
    }

    public final Id id;
    public final State state;
    public final String evidence;

    public Capability(Id id, State state, String evidence) {
        this.id = id;
        this.state = state;
        this.evidence = evidence == null ? "" : evidence;
    }

    public boolean satisfied() {
        return state == State.OK;
    }

    public static List<Capability> listOf(Capability... items) {
        List<Capability> list = new ArrayList<Capability>(items.length);
        for (Capability item : items) {
            list.add(item);
        }
        return list;
    }
}
