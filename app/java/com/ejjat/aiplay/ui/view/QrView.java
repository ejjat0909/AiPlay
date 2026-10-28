package com.ejjat.aiplay.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import com.ejjat.aiplay.core.qr.QrCode;

/**
 * Draws a QR code, dark on white with a quiet zone, large enough for a phone camera across a dashboard.
 * The white background is deliberate: scanners need the contrast whatever the app theme is.
 */
public final class QrView extends View {

    private static final int QUIET_MODULES = 3;

    private final Paint paint = new Paint();
    private QrCode code;

    public QrView(Context context) {
        super(context);
        paint.setAntiAlias(false);
    }

    public void setCode(QrCode code) {
        this.code = code;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int side = Math.min(w == 0 ? h : w, h == 0 ? w : h);
        setMeasuredDimension(side, side);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        if (code == null) {
            return;
        }
        int modules = code.size() + 2 * QUIET_MODULES;
        // Whole pixels per module keep the edges crisp; centre the remainder.
        int cell = Math.max(1, Math.min(getWidth(), getHeight()) / modules);
        int offset = (Math.min(getWidth(), getHeight()) - cell * modules) / 2;
        paint.setColor(Color.BLACK);
        for (int y = 0; y < code.size(); y++) {
            for (int x = 0; x < code.size(); x++) {
                if (code.get(x, y)) {
                    int left = offset + (x + QUIET_MODULES) * cell;
                    int top = offset + (y + QUIET_MODULES) * cell;
                    canvas.drawRect(left, top, left + cell, top + cell, paint);
                }
            }
        }
    }
}
