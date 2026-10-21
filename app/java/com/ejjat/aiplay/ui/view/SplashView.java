package com.ejjat.aiplay.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.ejjat.aiplay.ui.Ui;

/**
 * The AiPlay splash: a dark cockpit, a cyan arc that locks into a play glyph, then the wordmark.
 *
 * <p>Drawn entirely with paths and shaders — no bitmaps, no animation framework, no assets. One
 * {@link ValueAnimator} drives every layer, so the whole animation costs a single invalidate per
 * frame and about 260 lines.</p>
 */
public final class SplashView extends View {

    private static final long TOTAL_MS = 1600L;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wordPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint taglinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint versionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private final Path glyph = new Path();

    private ValueAnimator animator;
    private float progress;
    private long durationMs = TOTAL_MS;
    private float centerX;
    private float centerY;
    private float radius;
    private float width;
    private float height;

    public SplashView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringGlowPaint.setStyle(Paint.Style.STROKE);
        ringGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
        glyphPaint.setColor(Color.WHITE);
        wordPaint.setColor(Color.WHITE);
        wordPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        taglinePaint.setColor(Ui.TEXT1);
        versionPaint.setColor(Ui.TEXT2);
        trackPaint.setColor(0x22FFFFFF);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        width = w;
        height = h;
        centerX = w / 2f;
        centerY = h * 0.42f;
        radius = Math.min(w, h) * 0.17f;

        basePaint.setShader(new LinearGradient(0, 0, 0, h, Ui.BG0, Ui.BG1, Shader.TileMode.CLAMP));
        glowPaint.setShader(new RadialGradient(centerX, centerY, radius * 3.4f,
                new int[]{0x5512325C, 0x2212325C, 0x00000000}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        vignettePaint.setShader(new RadialGradient(centerX, centerY, Math.max(w, h) * 0.78f,
                new int[]{0x00000000, 0x00000000, 0x8C000000}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        ringPaint.setShader(new LinearGradient(centerX - radius, centerY - radius,
                centerX + radius, centerY + radius, Ui.ACCENT_A, Ui.ACCENT_B, Shader.TileMode.CLAMP));
        ringGlowPaint.setShader(ringPaint.getShader());
        ringPaint.setStrokeWidth(Ui.dp(getContext(), 3));
        ringGlowPaint.setStrokeWidth(Ui.dp(getContext(), 11));
        ringGlowPaint.setAlpha(46);
        glyphPaint.setStrokeWidth(Ui.dp(getContext(), 9));
        wordPaint.setTextSize(Ui.sp(getContext(), 34));
        wordPaint.setLetterSpacing(0.18f);
        taglinePaint.setTextSize(Ui.sp(getContext(), 13));
        taglinePaint.setLetterSpacing(0.04f);
        versionPaint.setTextSize(Ui.sp(getContext(), 11));
        trackPaint.setStrokeWidth(Ui.dp(getContext(), 2));
        fillPaint.setStrokeWidth(Ui.dp(getContext(), 2));
        fillPaint.setShader(new LinearGradient(0, 0, Ui.dp(getContext(), 120), 0,
                Ui.ACCENT_A, Ui.ACCENT_B, Shader.TileMode.CLAMP));
        arcBounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        buildGlyph();
    }

    private void buildGlyph() {
        float size = radius * 0.66f;
        float left = centerX - size * 0.34f;
        float top = centerY - size * 0.5f;
        glyph.reset();
        glyph.moveTo(left, top);
        glyph.lineTo(left + size * 0.92f, centerY);
        glyph.lineTo(left, top + size);
        glyph.close();
    }

    /** Starts the animation; {@code onDone} runs on the UI thread when it finishes. */
    public void start(final Runnable onDone) {
        start(onDone, TOTAL_MS);
    }

    /** Starts the animation with an explicit duration (warm starts use a shorter one). */
    public void start(final Runnable onDone, long durationMillis) {
        if (animator != null) {
            return;
        }
        durationMs = durationMillis;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(durationMs);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                progress = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!cancelled && onDone != null) {
                    onDone.run();
                }
            }
        });
        animator.start();
    }

    public void finishNow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        progress = 1f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, width, height, basePaint);
        canvas.drawRect(0, 0, width, height, glowPaint);

        float ms = progress * durationMs;
        float ring = clamp(ms / 1100f);
        float glyphProgress = clamp((ms - 380f) / 520f);
        float wordProgress = clamp((ms - 620f) / 420f);
        float taglineProgress = clamp((ms - 900f) / 400f);
        float lineProgress = clamp((ms - 200f) / 1200f);

        // Ring: two arcs that sweep into place.
        float rotation = 270f * easeOutCubic(ring);
        canvas.save();
        canvas.rotate(rotation, centerX, centerY);
        float sweep = 96f * easeOutCubic(ring);
        canvas.drawArc(arcBounds, -90f, sweep, false, ringGlowPaint);
        canvas.drawArc(arcBounds, -90f, sweep, false, ringPaint);
        float second = 48f * easeOutCubic(clamp((ms - 260f) / 900f));
        canvas.drawArc(arcBounds, 40f, second, false, ringPaint);
        canvas.restore();

        // Glyph: overshoots slightly, then breathes.
        if (glyphProgress > 0f) {
            float scale = 0.6f + 0.4f * overshoot(glyphProgress);
            if (ms > 1200f) {
                scale *= 1f + 0.03f * (float) Math.sin((ms - 1200f) / 260f * Math.PI);
            }
            canvas.save();
            canvas.scale(scale, scale, centerX, centerY);
            canvas.drawPath(glyph, glyphPaint);
            canvas.restore();
        }

        // Wordmark.
        if (wordProgress > 0f) {
            float alpha = easeOutCubic(wordProgress);
            wordPaint.setAlpha((int) (255 * alpha));
            float textWidth = wordPaint.measureText("AiPlay");
            float baseline = centerY + radius * 1.72f + Ui.dp(getContext(), 12) * (1f - alpha);
            canvas.drawText("AiPlay", centerX - textWidth / 2f, baseline, wordPaint);
        }

        // Tagline.
        if (taglineProgress > 0f) {
            taglinePaint.setAlpha((int) (255 * easeOutCubic(taglineProgress)));
            String tagline = "Android Auto \u00b7 for every head unit";
            float taglineWidth = taglinePaint.measureText(tagline);
            canvas.drawText(tagline, centerX - taglineWidth / 2f,
                    centerY + radius * 1.72f + Ui.dp(getContext(), 40), taglinePaint);
        }

        // Progress hairline.
        float lineWidth = Ui.dp(getContext(), 132);
        float lineY = height * 0.82f;
        canvas.drawLine(centerX - lineWidth / 2f, lineY, centerX + lineWidth / 2f, lineY, trackPaint);
        if (lineProgress > 0f) {
            float filled = lineWidth * lineProgress;
            canvas.drawLine(centerX - lineWidth / 2f, lineY,
                    centerX - lineWidth / 2f + filled, lineY, fillPaint);
        }

        // Version, bottom right.
        String version = "v" + com.ejjat.aiplay.BuildConfig.VERSION_NAME;
        canvas.drawText(version, width - versionPaint.measureText(version) - Ui.dp(getContext(), 18),
                height - Ui.dp(getContext(), 16), versionPaint);

        canvas.drawRect(0, 0, width, height, vignettePaint);
    }

    private static float clamp(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    private static float easeOutCubic(float value) {
        float inverse = 1f - value;
        return 1f - inverse * inverse * inverse;
    }

    private static float overshoot(float value) {
        float c = 1.70158f;
        float v = value - 1f;
        return 1f + (c + 1f) * v * v * v + c * v * v;
    }
}
