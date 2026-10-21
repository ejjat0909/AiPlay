package com.ejjat.aiplay.core.audio;

import com.ejjat.aiplay.core.aa.AaChannel;

/**
 * Decides how loud each audio channel plays: music is turned down while a navigation prompt or an
 * assistant reply is speaking, and restored afterwards.
 *
 * <p>This is AiPlay's own design, not something read from aasdk or OpenAuto (OpenAuto does not
 * duck). The signal used is that the phone has <em>started</em> a speech or system stream, which a
 * phone does per prompt as far as is known; whether every phone and app does is unverified.</p>
 */
public final class AudioMixPolicy {

    /** Music volume while speech plays, as a fraction of normal. */
    public static final float DUCKED = 0.25f;
    public static final float NORMAL = 1.0f;

    private boolean speechActive;
    private boolean systemActive;

    public synchronized void setActive(int channel, boolean active) {
        if (channel == AaChannel.SPEECH_AUDIO) {
            speechActive = active;
        } else if (channel == AaChannel.SYSTEM_AUDIO) {
            systemActive = active;
        }
    }

    public synchronized void reset() {
        speechActive = false;
        systemActive = false;
    }

    /** Volume (0..1) for the given channel right now. Speech and system sounds are never ducked. */
    public synchronized float volumeFor(int channel) {
        if (channel == AaChannel.MEDIA_AUDIO && (speechActive || systemActive)) {
            return DUCKED;
        }
        return NORMAL;
    }
}
