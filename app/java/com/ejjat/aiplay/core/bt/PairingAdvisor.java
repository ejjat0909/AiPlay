package com.ejjat.aiplay.core.bt;

/**
 * Turns what the unit knows about Bluetooth into a one-line status and a plain-language next step.
 *
 * <p>Why this exists: a phone learns that a unit offers wireless Android Auto from the unit's
 * Bluetooth <em>service record</em>, and Android caches a paired device's services when the two are
 * <b>bonded</b>. If the phone paired while AiPlay's service was not yet registered (the receiver was
 * stopped, or Bluetooth was off), the phone keeps a stale picture of the unit and never offers
 * wireless Android Auto, however long the unit then waits. Pairing again, with the receiver running,
 * is the fix. Measured on a real head unit: after pairing before the receiver ran, the phone asked for
 * Hands-Free, AVRCP and A2DP only and never looked for the Android Auto service.</p>
 *
 * <p>Pure Java so the wording and the decision table are covered by tests.</p>
 */
public final class PairingAdvisor {

    /** What the unit currently knows. */
    public static final class Facts {
        public final boolean bluetoothOn;
        /** The RFCOMM service is registered right now. */
        public final boolean serviceUp;
        public final int bondedPhones;
        /** A bond was created while the service was NOT registered, and is still in place. */
        public final boolean bondedWhileServiceDown;
        /** Phones that have opened the Android Auto service since the receiver started. */
        public final int sessionsSeen;

        public Facts(boolean bluetoothOn, boolean serviceUp, int bondedPhones,
                     boolean bondedWhileServiceDown, int sessionsSeen) {
            this.bluetoothOn = bluetoothOn;
            this.serviceUp = serviceUp;
            this.bondedPhones = bondedPhones;
            this.bondedWhileServiceDown = bondedWhileServiceDown;
            this.sessionsSeen = sessionsSeen;
        }
    }

    /** {@code status} is short enough for the home screen; {@code hint} is for the log. */
    public static final class Advice {
        public final String status;
        public final String hint;

        Advice(String status, String hint) {
            this.status = status;
            this.hint = hint;
        }

        @Override
        public String toString() {
            return status + " | " + hint;
        }
    }

    private PairingAdvisor() {
    }

    public static Advice advise(Facts f) {
        if (!f.bluetoothOn) {
            return new Advice("turn Bluetooth on",
                    "Bluetooth is off. AiPlay starts its service as soon as it is switched on.");
        }
        if (!f.serviceUp) {
            return new Advice("service not running",
                    "AiPlay's Bluetooth service is not registered, so a phone paired now would not "
                            + "see it. Start the receiver first, then pair.");
        }
        if (f.sessionsSeen > 0) {
            return new Advice("phone reached the unit; waiting for Wi-Fi",
                    "A phone has opened the Android Auto service this run and was given the Wi-Fi "
                            + "details. If it keeps retrying, it could not join that Wi-Fi network.");
        }
        if (f.bondedPhones == 0) {
            return new Advice("pair your phone with this unit",
                    "No phone is paired. Pair it now, while this receiver is running, so the phone "
                            + "sees the Android Auto service. Then open Android Auto on the phone.");
        }
        if (f.bondedWhileServiceDown) {
            return new Advice("re-pair the phone",
                    "A phone paired while AiPlay's service was not running, so it may have cached a "
                            + "unit without Android Auto. On the phone choose Forget for this unit, "
                            + "remove the phone from this unit's paired list, then pair again.");
        }
        return new Advice("paired; open Android Auto on the phone",
                "A phone is paired and AiPlay is waiting for it to open the Android Auto service. "
                        + "If nothing happens within a minute: check Android Auto's wireless option "
                        + "on the phone, then Forget the unit and pair again.");
    }
}
