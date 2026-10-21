package com.ejjat.aiplay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class SessionStateMachineTest {

    @Test
    public void happyPathReachesProjecting() {
        SessionStateMachine machine = new SessionStateMachine();
        machine.handle(SessionStateMachine.Event.PROBE_START);
        machine.handle(SessionStateMachine.Event.PROBE_OK);
        machine.handle(SessionStateMachine.Event.START);
        machine.handle(SessionStateMachine.Event.PEER_CONNECTED);
        assertEquals(SessionState.PROJECTING, machine.state());
        assertEquals(5, machine.history().size());
    }

    @Test
    public void peerLossReturnsToReady() {
        SessionStateMachine machine = new SessionStateMachine();
        machine.handle(SessionStateMachine.Event.PROBE_START);
        machine.handle(SessionStateMachine.Event.PROBE_OK);
        machine.handle(SessionStateMachine.Event.START);
        machine.handle(SessionStateMachine.Event.PEER_CONNECTED);
        machine.handle(SessionStateMachine.Event.PEER_LOST);
        assertEquals(SessionState.READY, machine.state());
    }

    @Test
    public void stopAlwaysReturnsToIdle() {
        SessionStateMachine machine = new SessionStateMachine();
        machine.handle(SessionStateMachine.Event.PROBE_START);
        machine.handle(SessionStateMachine.Event.PROBE_OK);
        machine.handle(SessionStateMachine.Event.STOP);
        assertEquals(SessionState.IDLE, machine.state());
    }

    @Test
    public void illegalTransitionThrowsInStrictMode() {
        SessionStateMachine machine = new SessionStateMachine();
        try {
            machine.handle(SessionStateMachine.Event.PEER_CONNECTED);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("IDLE"));
        }
    }

    @Test
    public void illegalTransitionIsIgnoredInLenientMode() {
        SessionStateMachine machine = new SessionStateMachine(false);
        assertFalse(machine.handle(SessionStateMachine.Event.PEER_CONNECTED));
        assertEquals(SessionState.IDLE, machine.state());
    }

    @Test
    public void failureIsReported() {
        SessionStateMachine machine = new SessionStateMachine();
        machine.handle(SessionStateMachine.Event.PROBE_START);
        machine.handle(SessionStateMachine.Event.PROBE_FAIL);
        assertEquals(SessionState.ERROR, machine.state());
        machine.handle(SessionStateMachine.Event.PROBE_START);
        assertEquals(SessionState.PROBING, machine.state());
    }

    @Test
    public void redundantEventsDoNotChangeState() {
        SessionStateMachine machine = new SessionStateMachine();
        machine.handle(SessionStateMachine.Event.PROBE_START);
        assertFalse(machine.handle(SessionStateMachine.Event.PROBE_START));
    }
}
