package com.ejjat.aiplay.core;

/** Receiver lifecycle states. Shared by the service and the home screen indicator. */
public enum SessionState {
    IDLE,
    PROBING,
    READY,
    CONNECTING,
    PROJECTING,
    ERROR
}
