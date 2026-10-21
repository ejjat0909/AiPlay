package com.ejjat.aiplay.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.ejjat.aiplay.ui.Ui;

/** Readiness arc: 0..100 with a gradient sweep and a big number in the middle. */
public final class RingGauge extends View {

    private static final float START_ANGLE = 135f;
    private static final float SWEEP = 270f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private float animatedValue;
    private int targetValue;
    private String label = "";

    public RingGauge(Context context) {
        super(context);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(0xFF1B2740);
        trackPaint.setStrokeWidth(Ui.dp(context, 9));
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStrokeWidth(Ui.dp(context, 9));
        valuePaint.setColor(Ui.TEXT0);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTextSize(Ui.sp(context, 34));
        valuePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        labelPaint.setColor(Ui.TEXT2);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(Ui.sp(context, 11));
        labelPaint.setLetterSpacing(0.12f);
    }

    public void setLabel(String label) {
        this.label = label == null ? "" : label;
        invalidate();
    }

    public void setValue(int value, boolean animate) {
        int clamped = Math.max(0, Math.min(100, value));
        targetValue = clamped;
        if (!animate) {
            animatedValue = clamped;
            invalidate();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(animatedValue, clamped);
        animator.setDuration(700L);
        animator.setInterpolator(new DecelerateInterpolator(1.4f));
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                animatedValue = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float inset = Ui.dp(getContext(), 12);
        bounds.set(inset, inset, w - inset, h - inset);
        arcPaint.setShader(new LinearGradient(0, 0, w, h, Ui.ACCENT_A, Ui.ACCENT_B,
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawArc(bounds, START_ANGLE, SWEEP, false, trackPaint);
        canvas.drawArc(bounds, START_ANGLE, SWEEP * (animatedValue / 100f), false, arcPaint);

        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        Paint.FontMetrics metrics = valuePaint.getFontMetrics();
        float textHeight = metrics.descent - metrics.ascent;
        canvas.drawText(String.valueOf(Math.round(animatedValue)), centerX,
                centerY + textHeight / 4f, valuePaint);
        if (!label.isEmpty()) {
            canvas.drawText(label, centerX,
                    centerY + textHeight / 2f + Ui.dp(getContext(), 14), labelPaint);
        }
    }
}
