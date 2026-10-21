package com.ejjat.aiplay.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Design tokens and view helpers.
 *
 * <p>The whole visual language lives here: colours, spacing, corner radii and the four widget
 * builders. Screens are built in code rather than XML so the tokens cannot drift between layouts —
 * which is how a 1 MB app stays consistent.</p>
 */
public final class Ui {

    public static final int BG0 = 0xFF05070D;
    public static final int BG1 = 0xFF0A1020;
    public static final int SURFACE = 0xFF111A2C;
    public static final int SURFACE_HI = 0xFF16233A;
    public static final int LINE = 0xFF1E2C45;
    public static final int TEXT0 = 0xFFF2F6FF;
    public static final int TEXT1 = 0xFF9DB0CC;
    public static final int TEXT2 = 0xFF5F7292;
    public static final int ACCENT_A = 0xFF22D3EE;
    public static final int ACCENT_B = 0xFF3B82F6;
    public static final int WARN = 0xFFF59E0B;
    public static final int BAD = 0xFFEF4444;
    public static final int OK = 0xFF34D399;

    public static final int RADIUS_CARD_DP = 18;
    public static final int RADIUS_BUTTON_DP = 14;
    public static final int BUTTON_HEIGHT_DP = 56;

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    public static int sp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, value, context.getResources().getDisplayMetrics()));
    }

    public static TextView text(Context context, CharSequence value, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(dp(context, 2), 1f);
        return view;
    }

    public static TextView heading(Context context, CharSequence value) {
        TextView view = text(context, value, 22, TEXT0);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    public static TextView caption(Context context, CharSequence value) {
        return text(context, value, 12, TEXT2);
    }

    public static LinearLayout column(Context context, int paddingDp) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, paddingDp), dp(context, paddingDp),
                dp(context, paddingDp), dp(context, paddingDp));
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static View spacer(Context context, int heightDp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)));
        return view;
    }

    public static GradientDrawable rounded(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable outlined(Context context, int fill, float radiusDp,
                                            int strokeColor, float strokeDp) {
        GradientDrawable drawable = rounded(context, fill, radiusDp);
        drawable.setStroke(dp(context, strokeDp), strokeColor);
        return drawable;
    }

    public static GradientDrawable gradient(Context context, int start, int end, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{start, end});
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    /** Filled accent button (primary action). */
    public static Button primaryButton(Context context, CharSequence label) {
        Button button = baseButton(context, label, Color.WHITE);
        button.setBackground(ripple(context, gradient(context, ACCENT_A, ACCENT_B,
                RADIUS_BUTTON_DP), 0x33FFFFFF));
        return button;
    }

    /** Outlined secondary button. */
    public static Button secondaryButton(Context context, CharSequence label) {
        Button button = baseButton(context, label, TEXT0);
        button.setBackground(ripple(context, outlined(context, SURFACE, RADIUS_BUTTON_DP,
                LINE, 1f), 0x22FFFFFF));
        return button;
    }

    /** Compact chip for header actions. */
    public static Button chip(Context context, CharSequence label) {
        Button button = baseButton(context, label, TEXT1);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setBackground(ripple(context, outlined(context, SURFACE, 999, LINE, 1f), 0x22FFFFFF));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 40));
        params.setMargins(dp(context, 4), 0, dp(context, 4), 0);
        button.setLayoutParams(params);
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        return button;
    }

    private static Button baseButton(Context context, CharSequence label, int textColor) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(textColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setTypeface(button.getTypeface(), android.graphics.Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setStateListAnimator(null);
        button.setMinHeight(dp(context, BUTTON_HEIGHT_DP));
        button.setPadding(dp(context, 20), 0, dp(context, 20), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, BUTTON_HEIGHT_DP));
        button.setLayoutParams(params);
        return button;
    }

    private static RippleDrawable ripple(Context context, GradientDrawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    public static void immersive(Activity activity) {
        activity.getWindow().setStatusBarColor(BG0);
        activity.getWindow().setNavigationBarColor(BG0);
        activity.getWindow().setBackgroundDrawable(rounded(activity, BG1, 0));
        if (activity.getWindow().getAttributes() != null) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        }
    }

    public static void fadeIn(Activity activity) {
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
