package com.stremio.morphe;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MorpheAccountsNavView extends LinearLayout {
    public static final int VIEW_ID = 0x4d4f52a0;
    private static final String CORE = "core";
    private static final String META = "morphe_profiles";
    private static final String ACTIVE = "morphe.active_slot";
    private static final String NAME = "name.";
    private static final String COLOR = "color.";
    private final TextView avatar;
    private final TextView label;
    private View backdrop;
    private boolean nativeMenuExpanded;

    public MorpheAccountsNavView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setId(VIEW_ID);
        setTag("morphe_accounts_nav");
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackground(containerBackground());
        // Match the native Compose tab bounds while preserving the avatar and
        // label axes used by the collapsed and expanded navigation menu. Apply
        // this after the inset background so its optical insets cannot replace
        // the content padding.
        setPadding(dp(6), dp(3), dp(8), dp(3));
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setContentDescription("Switch account");

        Typeface semibold = appTypeface("plusjakartasans_semibold", Typeface.DEFAULT_BOLD);
        // The native menu requests weight 600 from a family whose next matching
        // registered face is the app's 700-weight Plus Jakarta Sans Bold.
        Typeface menuLabel = appTypeface("plusjakartasans_bold", Typeface.DEFAULT_BOLD);

        avatar = new TextView(context);
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextAlignment(TEXT_ALIGNMENT_CENTER);
        avatar.setIncludeFontPadding(false);
        avatar.setPadding(0, 0, 0, 0);
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        avatar.setTypeface(semibold);
        avatar.setTranslationX(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 0.5f,
                getResources().getDisplayMetrics()));
        avatar.setFocusable(false);
        addView(avatar, new LinearLayout.LayoutParams(dp(32), dp(32)));

        label = new TextView(context);
        label.setTextColor(focusTextColors());
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(menuLabel);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setVisibility(INVISIBLE);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(dp(92), dp(38));
        labelParams.leftMargin = dp(7);
        addView(label, labelParams);

        setOnClickListener(new OnClickListener() {
            @Override public void onClick(View view) {
                Intent intent = new Intent();
                intent.setClassName(getContext().getPackageName(), "com.stremio.morphe.ProfileChooserActivity");
                getContext().startActivity(intent);
            }
        });
        updateProfile();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        avatar.setSelected(gainFocus);
        label.setSelected(gainFocus);
        updateLabelVisibility();
        setBackdropVisible(gainFocus);
        MorpheNavBridge.setAccountsFocused(gainFocus);
        if (gainFocus) updateProfile();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            View root = getRootView();
            int sideId = getResources().getIdentifier("side_menu_fragment", "id", getContext().getPackageName());
            View sideMenu = root.findViewById(sideId);
            if (sideMenu != null && requestFirstFocusable(sideMenu)) return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        int backdropId = getResources().getIdentifier("morphe_nav_backdrop", "id", getContext().getPackageName());
        backdrop = getRootView().findViewById(backdropId);
        MorpheNavBridge.registerAccountsView(this);
        updateProfile();
    }

    @Override
    protected void onDetachedFromWindow() {
        setBackdropVisible(false);
        MorpheNavBridge.setAccountsFocused(false);
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            updateProfile();
            if (hasFocus()) {
                setBackdropVisible(true);
                MorpheNavBridge.setAccountsFocused(true);
                updateLabelVisibility();
            }
        } else {
            setBackdropVisible(false);
            MorpheNavBridge.setAccountsFocused(false);
        }
    }

    private boolean requestFirstFocusable(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (requestFirstFocusable(group.getChildAt(i))) return true;
            }
        }
        return view.isFocusable() && view.requestFocus();
    }

    private void setBackdropVisible(boolean visible) {
        if (backdrop != null) backdrop.setVisibility(visible ? VISIBLE : GONE);
    }

    void setNativeMenuExpanded(boolean expanded) {
        nativeMenuExpanded = expanded;
        updateLabelVisibility();
    }

    private void updateLabelVisibility() {
        label.setVisibility(hasFocus() || nativeMenuExpanded ? VISIBLE : INVISIBLE);
    }

    private void updateProfile() {
        SharedPreferences core = getContext().getSharedPreferences(CORE, Context.MODE_PRIVATE);
        SharedPreferences meta = getContext().getSharedPreferences(META, Context.MODE_PRIVATE);
        String slot = core.getString(ACTIVE, "account_a");
        String fallback = "account_b".equals(slot) ? "Account B" : "Account A";
        String name = meta.getString(NAME + slot, fallback);
        if (name == null || name.trim().isEmpty()) name = fallback;
        name = name.trim();
        int color = meta.getInt(COLOR + slot, Color.rgb(116, 82, 246));
        avatar.setText(name.substring(0, 1).toUpperCase());
        avatar.setBackground(avatarBackground(color));
        label.setText(name);
        setContentDescription("Switch account, current account " + name);
    }

    private StateListDrawable avatarBackground(int color) {
        int outline = blend(color, Color.WHITE, 0.48f);
        GradientDrawable selected = oval(color);
        selected.setStroke(dp(3), outline);
        GradientDrawable normal = oval(color);
        normal.setStroke(dp(2), outline);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, selected);
        states.addState(new int[]{}, normal);
        return states;
    }

    private StateListDrawable containerBackground() {
        GradientDrawable focused = rounded(Color.rgb(211, 209, 214));
        GradientDrawable normal = rounded(Color.TRANSPARENT);
        int verticalInset = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f,
                getResources().getDisplayMetrics()));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},
                new InsetDrawable(focused, 0, verticalInset, 0, verticalInset));
        states.addState(new int[]{},
                new InsetDrawable(normal, 0, verticalInset, 0, verticalInset));
        return states;
    }

    private ColorStateList focusTextColors() {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_selected}, new int[]{}},
                new int[]{Color.rgb(20, 19, 24), Color.WHITE});
    }

    private Typeface appTypeface(String name, Typeface fallback) {
        int id = getResources().getIdentifier(name, "font", getContext().getPackageName());
        if (id == 0) return fallback;
        try { return getResources().getFont(id); }
        catch (Exception ignored) { return fallback; }
    }

    private GradientDrawable oval(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(10));
        return drawable;
    }

    private int blend(int from, int to, float amount) {
        float inverse = 1f - amount;
        return Color.rgb(
                Math.round(Color.red(from) * inverse + Color.red(to) * amount),
                Math.round(Color.green(from) * inverse + Color.green(to) * amount),
                Math.round(Color.blue(from) * inverse + Color.blue(to) * amount));
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
