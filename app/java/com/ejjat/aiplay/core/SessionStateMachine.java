package com.ejjat.aiplay.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Guarded state machine for the receiver.
 *
 * <p>The home screen and the service both read this, so the UI can never show "projecting" while
 * the service is idle. Illegal transitions are rejected: in tests they throw, in release builds the
 * caller logs and ignores them.</p>
 */
public final class SessionStateMachine {

    public enum Event {
        PROBE_START,
        PROBE_OK,
        PROBE_FAIL,
        START,
        PEER_CONNECTED,
        PEER_LOST,
        STOP,
        FAIL
    }

    private SessionState state = SessionState.IDLE;
    private final List<String> history = new ArrayList<String>(8);
    private final boolean strict;

    public SessionStateMachine() {
        this(true);
    }

    public SessionStateMachine(boolean strict) {
        this.strict = strict;
        history.add("IDLE");
    }

    public SessionState state() {
        return state;
    }

    public List<String> history() {
        return Collections.unmodifiableList(history);
    }

    public boolean canHandle(Event event) {
        return next(state, event) != null;
    }

    /**
     * Applies an event.
     *
     * @return true when the state changed
     * @throws IllegalStateException in strict mode when the transition is not defined
     */
    public boolean handle(Event event) {
        SessionState next = next(state, event);
        if (next == null) {
            if (strict) {
                throw new IllegalStateException("illegal transition: " + state + " + " + event);
            }
            return false;
        }
        if (next == state) {
            return false;
        }
        state = next;
        history.add(next.name());
        return true;
    }

    private static SessionState next(SessionState state, Event event) {
        switch (event) {
            case PROBE_START:
                if (state == SessionState.PROBING) {
                    return SessionState.PROBING;
                }
                return state == SessionState.IDLE || state == SessionState.ERROR
                        ? SessionState.PROBING : null;
            case PROBE_OK:
                return state == SessionState.PROBING ? SessionState.READY : null;
            case PROBE_FAIL:
            case FAIL:
                return state == SessionState.IDLE ? null : SessionState.ERROR;
            case START:
                if (state == SessionState.CONNECTING || state == SessionState.PROJECTING) {
                    return state;
                }
                return state == SessionState.READY ? SessionState.CONNECTING : null;
            case PEER_CONNECTED:
                return state == SessionState.CONNECTING ? SessionState.PROJECTING : null;
            case PEER_LOST:
                return state == SessionState.PROJECTING ? SessionState.READY : null;
            case STOP:
                return state == SessionState.IDLE ? null : SessionState.IDLE;
            default:
                return null;
        }
    }
}
