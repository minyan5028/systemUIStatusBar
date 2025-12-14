package com.android.systemui.keyguard;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternUtils.RequestThrottledException;
import com.android.keyguard.KeyguardUpdateMonitor;

/**
 * Lightweight full-screen overlay that mimics the Remo6 password bouncer design while using the
 * platform {@link LockPatternUtils} backend for authentication.
 */
public class Remo6KeyguardOverlay {

    interface Callback {
        void onUnlockSuccess();
        void onForgotPasswordRequested();
    }

    private static final String TAG = "Remo6KeyguardOverlay";
    private static final String REMO6_SETTINGS_PACKAGE = "com.android.settings";
    private static final String TEXT_PROMPT = "請輸入你的密碼，以解鎖畫面";
    private static final String TEXT_UNLOCK = "解鎖";
    private static final String TEXT_FORGOT = "忘記密碼？";
    private static final String TEXT_EMPTY_ERROR = "請先輸入密碼";
    private static final String TEXT_WRONG = "密碼錯誤，請再試一次";
    private static final String TEXT_THROTTLED = "嘗試過多，請在 %d 秒後再試";

    private final Context mContext;
    private final LockPatternUtils mLockPatternUtils;
    private final Callback mCallback;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Context mRemo6ResourceContext;

    private Dialog mDialog;
    private EditText mPasswordInput;
    private TextView mErrorView;

    Remo6KeyguardOverlay(Context context,
            LockPatternUtils lockPatternUtils,
            Callback callback) {
        mContext = context;
        mLockPatternUtils = lockPatternUtils;
        mCallback = callback;
        mRemo6ResourceContext = createRemo6ResourceContext(context);
    }

    boolean isShowing() {
        return mDialog != null && mDialog.isShowing();
    }

    void show() {
        if (isShowing()) {
            focusInput();
            return;
        }
        mDialog = new Dialog(mContext, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setCancelable(false);
        mDialog.setCanceledOnTouchOutside(false);
        mDialog.setContentView(createContentView());

        Window window = mDialog.getWindow();
        if (window != null) {
            window.setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
        mDialog.show();
        focusInput();
    }

    void hide() {
        if (mDialog != null) {
            if (mDialog.isShowing()) {
                View decor = mDialog.getWindow() != null ? mDialog.getWindow().getDecorView() : null;
                InputMethodManager imm = mContext.getSystemService(InputMethodManager.class);
                if (imm != null && decor != null) {
                    imm.hideSoftInputFromWindow(decor.getWindowToken(), 0);
                }
            }
            mDialog.dismiss();
            mDialog = null;
        }
        mPasswordInput = null;
        mErrorView = null;
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(mContext);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        int horizontalPadding = dp(24);
        int topPadding = dp(32);
        content.setPadding(horizontalPadding, topPadding, horizontalPadding, dp(24));
        content.setGravity(Gravity.TOP);

        LinearLayout hintRow = new LinearLayout(mContext);
        hintRow.setOrientation(LinearLayout.HORIZONTAL);
        hintRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.setMargins(dp(40), 0, dp(40), dp(24));
        hintRow.setLayoutParams(hintParams);

        ImageView lockIcon = new ImageView(mContext);
        Drawable lockDrawable = getRemo6Drawable("remo6_ic_lock");
        if (lockDrawable != null) {
            lockIcon.setImageDrawable(lockDrawable);
        } else {
            lockIcon.setImageResource(android.R.drawable.ic_lock_lock);
        }
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        iconParams.setMargins(0, 0, dp(8), 0);
        hintRow.addView(lockIcon, iconParams);

        TextView hintText = new TextView(mContext);
        hintText.setText(getRemo6String("remo6_screen_lock_remove_hint", TEXT_PROMPT));
        hintText.setTextColor(Color.BLACK);
        hintText.setTextSize(16);
        hintRow.addView(hintText);
        content.addView(hintRow);

        mPasswordInput = new EditText(mContext);
        mPasswordInput.setSingleLine(true);
        mPasswordInput.setTextSize(16);
        mPasswordInput.setInputType(EditorInfo.TYPE_CLASS_TEXT
                | EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);
        mPasswordInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        mPasswordInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        mPasswordInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        mPasswordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP)) {
                attemptUnlock();
                return true;
            }
            return false;
        });
        mPasswordInput.setGravity(Gravity.CENTER);
        GradientDrawable fieldBackground = new GradientDrawable();
        fieldBackground.setColor(Color.WHITE);
        fieldBackground.setCornerRadius(dp(8));
        fieldBackground.setStroke(dp(2), Color.BLACK);
        mPasswordInput.setBackground(fieldBackground);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40));
        inputParams.setMargins(dp(64), 0, dp(64), dp(24));
        mPasswordInput.setLayoutParams(inputParams);
        content.addView(mPasswordInput);

        mErrorView = new TextView(mContext);
        mErrorView.setTextSize(14);
        mErrorView.setTextColor(Color.BLACK);
        mErrorView.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        errorParams.setMargins(dp(64), 0, dp(64), dp(16));
        mErrorView.setLayoutParams(errorParams);
        content.addView(mErrorView);

        TextView forgot = createPillButton(
                getRemo6String("remo6_screen_lock_forgot_password", TEXT_FORGOT), false);
        forgot.setOnClickListener(v -> {
            if (mCallback != null) {
                mCallback.onForgotPasswordRequested();
            }
        });
        forgot.setPadding(dp(64), dp(12), dp(64), dp(16));
        forgot.setTextColor(Color.BLACK);
        forgot.setBackground(null);
        content.addView(forgot);

        root.addView(content);

        return root;
    }

    private TextView createPillButton(String text, boolean primary) {
        TextView button = new TextView(mContext);
        button.setText(text);
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        if (primary) {
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setPadding(0, dp(12), 0, dp(12));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(24));
            bg.setColor(Color.BLACK);
            button.setTextColor(Color.WHITE);
            button.setBackground(bg);
        } else {
            button.setPadding(0, dp(4), 0, dp(4));
            button.setTextColor(Color.BLACK);
            button.setPaintFlags(
                    button.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            button.setBackground(null);
        }
        return button;
    }

    private void attemptUnlock() {
        if (mPasswordInput == null) {
            return;
        }
        final String password = mPasswordInput.getText().toString();
        if (TextUtils.isEmpty(password)) {
            showError(getRemo6String("remo6_screen_lock_enter_password", TEXT_EMPTY_ERROR));
            return;
        }
        setInputEnabled(false);
        new Thread(() -> {
            boolean success = false;
            RequestThrottledException throttled = null;
            try {
                success = mLockPatternUtils.checkPassword(password,
                        KeyguardUpdateMonitor.getCurrentUser());
            } catch (RequestThrottledException e) {
                throttled = e;
            } catch (Exception e) {
                Log.e(TAG, "Failed to verify password", e);
            }

            RequestThrottledException finalThrottled = throttled;
            boolean finalSuccess = success;
            mMainHandler.post(() -> {
                setInputEnabled(true);
                if (!isShowing()) {
                    return;
                }
                if (finalThrottled != null) {
                    long timeoutMs = finalThrottled.getTimeoutMs();
                    int seconds = (int) Math.ceil(timeoutMs / 1000f);
                    showError(String.format(TEXT_THROTTLED, seconds));
                    return;
                }
                if (finalSuccess) {
                    mPasswordInput.getText().clear();
                    showError(null);
                    hide();
                    if (mCallback != null) {
                        mCallback.onUnlockSuccess();
                    }
                } else {
                    showError(getRemo6String("remo6_screen_lock_remove_incorrect", TEXT_WRONG));
                }
            });
        }).start();
    }


    private void showError(CharSequence message) {
        if (mErrorView == null) return;
        if (TextUtils.isEmpty(message)) {
            mErrorView.setVisibility(View.GONE);
        } else {
            mErrorView.setText(message);
            mErrorView.setVisibility(View.VISIBLE);
        }
    }

    private void setInputEnabled(boolean enabled) {
        if (mPasswordInput != null) {
            mPasswordInput.setEnabled(enabled);
        }
    }

    private void focusInput() {
        if (mPasswordInput == null) return;
        mPasswordInput.post(() -> {
            mPasswordInput.requestFocus();
            mPasswordInput.setSelection(mPasswordInput.getText().length());
            InputMethodManager imm = mContext.getSystemService(InputMethodManager.class);
            if (imm != null) {
                imm.showSoftInput(mPasswordInput, InputMethodManager.SHOW_FORCED);
            }
        });
    }

    private int dp(int value) {
        float density = mContext.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private Context createRemo6ResourceContext(Context base) {
        try {
            return base.createPackageContext(REMO6_SETTINGS_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Unable to access Remo6 resources", e);
            return null;
        }
    }

    private String getRemo6String(String name, String fallback) {
        if (mRemo6ResourceContext == null) {
            return fallback;
        }
        int resId = mRemo6ResourceContext.getResources()
                .getIdentifier(name, "string", REMO6_SETTINGS_PACKAGE);
        if (resId == 0) {
            return fallback;
        }
        try {
            return mRemo6ResourceContext.getString(resId);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load string " + name, e);
            return fallback;
        }
    }

    private Drawable getRemo6Drawable(String name) {
        if (mRemo6ResourceContext == null) {
            return null;
        }
        int resId = mRemo6ResourceContext.getResources()
                .getIdentifier(name, "drawable", REMO6_SETTINGS_PACKAGE);
        if (resId == 0) {
            return null;
        }
        try {
            return mRemo6ResourceContext.getDrawable(resId);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load drawable " + name, e);
            return null;
        }
    }
}
