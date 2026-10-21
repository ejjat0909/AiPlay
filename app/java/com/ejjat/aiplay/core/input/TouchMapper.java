package com.ejjat.aiplay.core.input;

/**
 * Maps a point on the head unit's screen to the phone's coordinate space.
 *
 * <p>The phone expects touch coordinates in the width x height it was told in service discovery
 * ({@code TouchConfig}), which AiPlay sets to the video resolution (1280x720). The video is
 * letterboxed inside the screen, so a screen point has to be taken relative to the <em>video
 * rectangle</em>, not the screen, or every tap lands in the wrong place on a panel that is not 16:9.
 * (That the phone wants the video's coordinate space is the convention in aasdk-based head units, not
 * something the protobuf files state.)</p>
 */
public final class TouchMapper {

    private final int targetWidth;
    private final int targetHeight;
    private float left;
    private float top;
    private float width;
    private float height;

    public TouchMapper(int targetWidth, int targetHeight) {
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
    }

    /** The video's rectangle in screen pixels. */
    public synchronized void setVideoRect(float left, float top, float width, float height) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
    }

    /** True when the screen point is on the video, not on a letterbox bar. */
    public synchronized boolean inside(float x, float y) {
        return width > 0 && height > 0 && x >= left && x < left + width
                && y >= top && y < top + height;
    }

    /** Phone x for a screen x, clamped to 0..targetWidth-1. */
    public synchronized int mapX(float x) {
        return clamp(Math.round((x - left) / width * targetWidth), targetWidth);
    }

    /** Phone y for a screen y, clamped to 0..targetHeight-1. */
    public synchronized int mapY(float y) {
        return clamp(Math.round((y - top) / height * targetHeight), targetHeight);
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

    /**
     * The largest rectangle of the given aspect ratio that fits the container, centred: where the
     * video is drawn.
     */
    public static float[] fit(float containerWidth, float containerHeight, float aspect) {
        float w = containerWidth;
        float h = w / aspect;
        if (h > containerHeight) {
            h = containerHeight;
            w = h * aspect;
        }
        return new float[]{(containerWidth - w) / 2f, (containerHeight - h) / 2f, w, h};
    }
}
