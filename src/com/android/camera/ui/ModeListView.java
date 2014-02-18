/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera.ui;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.android.camera.app.CameraAppUI;
import com.android.camera.util.CameraUtil;
import com.android.camera.util.Gusterpolator;
import com.android.camera.widget.AnimationEffects;
import com.android.camera.widget.SettingsButton;
import com.android.camera2.R;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * ModeListView class displays all camera modes and settings in the form
 * of a list. A swipe to the right will bring up this list. Then tapping on
 * any of the items in the list will take the user to that corresponding mode
 * with an animation. To dismiss this list, simply swipe left or select a mode.
 */
public class ModeListView extends FrameLayout
        implements PreviewStatusListener.PreviewAreaSizeChangedListener {

    private static final String TAG = "ModeListView";

    // Animation Durations
    private static final int DEFAULT_DURATION_MS = 200;
    private static final int FLY_IN_DURATION_MS = 850;
    private static final int HOLD_DURATION_MS = 0;
    private static final int FLY_OUT_DURATION_MS = 850;
    private static final int START_DELAY_MS = 100;
    private static final int TOTAL_DURATION_MS = FLY_IN_DURATION_MS + HOLD_DURATION_MS
            + FLY_OUT_DURATION_MS;

    private static final float ROWS_TO_SHOW_IN_LANDSCAPE = 4.5f;
    private static final int NO_ITEM_SELECTED = -1;

    // Scrolling states
    private static final int IDLE = 0;
    private static final int FULLY_SHOWN = 1;
    private static final int ACCORDION_ANIMATION = 2;
    private static final int SCROLLING = 3;
    private static final int MODE_SELECTED = 4;

    // Scrolling delay between non-focused item and focused item
    private static final int DELAY_MS = 30;
    // If the fling velocity exceeds this threshold, snap to full screen at a constant
    // speed. Unit: pixel/ms.
    private static final float VELOCITY_THRESHOLD = 2f;

    /**
     * A factor to change the UI responsiveness on a scroll.
     * e.g. A scroll factor of 0.5 means UI will move half as fast as the finger.
     */
    private static final float SCROLL_FACTOR = 0.5f;
    // 30% transparent black background.
    private static final int BACKGROUND_TRANSPARENTCY = (int) (0.3f * 255);
    private static final int PREVIEW_DOWN_SAMPLE_FACTOR = 4;
    // Threshold, below which snap back will happen.
    private static final float SNAP_BACK_THRESHOLD_RATIO = 0.33f;

    private final GestureDetector mGestureDetector;
    private final int mIconBlockWidth;
    private final RectF mPreviewArea = new RectF();
    private final RectF mUncoveredPreviewArea = new RectF();

    private int mListBackgroundColor;
    private LinearLayout mListView;
    private SettingsButton mSettingsButton;
    private int mState = IDLE;
    private int mTotalModes;
    private ModeSelectorItem[] mModeSelectorItems;
    private AnimatorSet mAnimatorSet;
    private int mFocusItem = NO_ITEM_SELECTED;
    private AnimationEffects mCurrentEffect;
    private ModeListOpenListener mModeListOpenListener;
    private CameraAppUI.CameraModuleScreenShotProvider mScreenShotProvider = null;
    private int[] mInputPixels;
    private int[] mOutputPixels;

    // Width and height of this view. They get updated in onLayout()
    // Unit for width and height are pixels.
    private int mWidth;
    private int mHeight;
    private float mScrollTrendX = 0f;
    private float mScrollTrendY = 0f;
    private ModeSwitchListener mModeSwitchListener = null;
    private ArrayList<Integer> mSupportedModes;
    private final LinkedList<TimeBasedPosition> mPositionHistory
            = new LinkedList<TimeBasedPosition>();
    private long mCurrentTime;
    private float mVelocityX; // Unit: pixel/ms.
    private final Animator.AnimatorListener mModeListAnimatorListener =
            new Animator.AnimatorListener() {

        @Override
        public void onAnimationStart(Animator animation) {
            setVisibility(VISIBLE);
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            mAnimatorSet = null;
            if (mState == ACCORDION_ANIMATION || mState == IDLE) {
                resetModeSelectors();
                setVisibility(INVISIBLE);
                mState = IDLE;
            }
        }

        @Override
        public void onAnimationCancel(Animator animation) {
        }

        @Override
        public void onAnimationRepeat(Animator animation) {

        }
    };
    private boolean mAdjustPositionWhenUncoveredPreviewAreaChanges = false;

    @Override
    public void onPreviewAreaSizeChanged(RectF previewArea) {
        mPreviewArea.set(previewArea);
    }

    private final CameraAppUI.UncoveredPreviewAreaSizeChangedListener
            mUncoveredPreviewAreaSizeChangedListener =
            new CameraAppUI.UncoveredPreviewAreaSizeChangedListener() {

                @Override
                public void uncoveredPreviewAreaChanged(RectF uncoveredPreviewArea) {
                    mUncoveredPreviewArea.set(uncoveredPreviewArea);
                    mSettingsButton.uncoveredPreviewAreaChanged(uncoveredPreviewArea);
                    if (mAdjustPositionWhenUncoveredPreviewAreaChanges) {
                        mAdjustPositionWhenUncoveredPreviewAreaChanges = false;
                        centerModeDrawerInUncoveredPreview(getMeasuredWidth(), getMeasuredHeight());
                    }
                }
            };

    public interface ModeSwitchListener {
        public void onModeSelected(int modeIndex);
        public int getCurrentModeIndex();
        public void onSettingsSelected();
    }

    public interface ModeListOpenListener {
        /**
         * Mode list will open to full screen after current animation.
         */
        public void onOpenFullScreen();

        /**
         * Updates the listener with the current progress of mode drawer opening.
         *
         * @param progress progress of the mode drawer opening, ranging [0f, 1f]
         *                 0 means mode drawer is fully closed, 1 indicates a fully
         *                 open mode drawer.
         */
        public void onModeListOpenProgress(float progress);

        /**
         * Gets called when mode list is completely closed.
         */
        public void onModeListClosed();
    }

    /**
     * This class aims to help store time and position in pairs.
     */
    private static class TimeBasedPosition {
        private final float mPosition;
        private final long mTimeStamp;
        public TimeBasedPosition(float position, long time) {
            mPosition = position;
            mTimeStamp = time;
        }

        public float getPosition() {
            return mPosition;
        }

        public long getTimeStamp() {
            return mTimeStamp;
        }
    }

    /**
     * This is a highly customized interpolator. The purpose of having this subclass
     * is to encapsulate intricate animation timing, so that the actual animation
     * implementation can be re-used with other interpolators to achieve different
     * animation effects.
     *
     * The accordion animation consists of three stages:
     * 1) Animate into the screen within a pre-specified fly in duration.
     * 2) Hold in place for a certain amount of time (Optional).
     * 3) Animate out of the screen within the given time.
     *
     * The accordion animator is initialized with 3 parameter: 1) initial position,
     * 2) how far out the view should be before flying back out,  3) end position.
     * The interpolation output should be [0f, 0.5f] during animation between 1)
     * to 2), and [0.5f, 1f] for flying from 2) to 3).
     */
    private final TimeInterpolator mAccordionInterpolator = new TimeInterpolator() {
        @Override
        public float getInterpolation(float input) {

            float flyInDuration = (float) FLY_OUT_DURATION_MS / (float) TOTAL_DURATION_MS;
            float holdDuration = (float) (FLY_OUT_DURATION_MS + HOLD_DURATION_MS)
                    / (float) TOTAL_DURATION_MS;
            if (input == 0) {
                return 0;
            }else if (input < flyInDuration) {
                // Stage 1, project result to [0f, 0.5f]
                input /= flyInDuration;
                float result = Gusterpolator.INSTANCE.getInterpolation(input);
                return result * 0.5f;
            } else if (input < holdDuration) {
                // Stage 2
                return 0.5f;
            } else {
                // Stage 3, project result to [0.5f, 1f]
                input -= holdDuration;
                input /= (1 - holdDuration);
                float result = Gusterpolator.INSTANCE.getInterpolation(input);
                return 0.5f + result * 0.5f;
            }
        }
    };

    /**
     * The listener that is used to notify when gestures occur.
     * Here we only listen to a subset of gestures.
     */
    private final GestureDetector.OnGestureListener mOnGestureListener
            = new GestureDetector.SimpleOnGestureListener(){
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                float distanceX, float distanceY) {

            if (mState == ACCORDION_ANIMATION) {
                // Scroll happens during accordion animation.
                if (isRunningAccordionAnimation()) {
                    mAnimatorSet.cancel();
                }
                setVisibility(VISIBLE);
            }

            if (mState == IDLE) {
                resetModeSelectors();
                setVisibility(VISIBLE);
            }

            mState = SCROLLING;
            // Scroll based on the scrolling distance on the currently focused
            // item.
            scroll(mFocusItem, distanceX * SCROLL_FACTOR, distanceY * SCROLL_FACTOR);
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent ev) {
            if (mState != FULLY_SHOWN) {
                // Only allows tap to choose mode when the list is fully shown
                return false;
            }

            // Ignore the tap if it happens outside of the mode list linear layout.
            float x = ev.getX() - mListView.getX();
            float y = ev.getY() - mListView.getY();
            if (x < 0 || x > mListView.getWidth() || y < 0 || y > mListView.getHeight()) {
                return false;
            }

            int index = getFocusItem(ev.getX(), ev.getY());
            // Validate the selection
            if (index != NO_ITEM_SELECTED) {
                final int modeId = getModeIndex(index);
                // Un-highlight all the modes.
                for (int i = 0; i < mModeSelectorItems.length; i++) {
                    mModeSelectorItems[i].setHighlighted(false);
                }
                // Select the focused item.
                mModeSelectorItems[index].setSelected(true);
                mState = MODE_SELECTED;
                PeepholeAnimationEffect effect = new PeepholeAnimationEffect();
                effect.setSize(mWidth, mHeight);
                effect.setAnimationEndAction(new Runnable() {
                    @Override
                    public void run() {
                        setVisibility(INVISIBLE);
                        mCurrentEffect = null;
                        snapBack(false);
                    }
                });

                // Calculate the position of the icon in the selected item, and
                // start animation from that position.
                int[] location = new int[2];
                // Gets icon's center position in relative to the window.
                mModeSelectorItems[index].getIconCenterLocationInWindow(location);
                int iconX = location[0];
                int iconY = location[1];
                // Gets current view's top left position relative to the window.
                getLocationInWindow(location);
                // Calculate icon location relative to this view
                iconX -= location[0];
                iconY -= location[1];

                effect.setAnimationStartingPosition(iconX, iconY);
                if (mScreenShotProvider != null) {
                    effect.setBackground(mScreenShotProvider
                            .getPreviewFrame(PREVIEW_DOWN_SAMPLE_FACTOR), mPreviewArea);
                    effect.setBackgroundOverlay(mScreenShotProvider.getPreviewOverlayAndControls());
                }
                mCurrentEffect = effect;
                invalidate();

                // Post mode selection runnable to the end of the message queue
                // so that current UI changes can finish before mode initialization
                // clogs up UI thread.
                post(new Runnable() {
                    @Override
                    public void run() {
                        onModeSelected(modeId);
                    }
                });
            }
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            // Cache velocity in the unit pixel/ms.
            mVelocityX = velocityX / 1000f * SCROLL_FACTOR;
            return true;
        }
    };

    public ModeListView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mGestureDetector = new GestureDetector(context, mOnGestureListener);
        mIconBlockWidth = getResources()
                .getDimensionPixelSize(R.dimen.mode_selector_icon_block_width);
        mListBackgroundColor = getResources().getColor(R.color.mode_list_background);
    }

    public CameraAppUI.UncoveredPreviewAreaSizeChangedListener
            getUncoveredPreviewAreaSizeChangedListener() {
        return mUncoveredPreviewAreaSizeChangedListener;
    }

    /**
     * Sets the alpha on the list background. This is called whenever the list
     * is scrolling or animating, so that background can adjust its dimness.
     *
     * @param alpha new alpha to be applied on list background color
     */
    private void setBackgroundAlpha(int alpha) {
        // Make sure alpha is valid.
        alpha = alpha & 0xFF;
        // Change alpha on the background color.
        mListBackgroundColor = mListBackgroundColor & 0xFFFFFF;
        mListBackgroundColor = mListBackgroundColor | (alpha << 24);
        // Set new color to list background.
        setBackgroundColor(mListBackgroundColor);
    }

    /**
     * Initialize mode list with a list of indices of supported modes.
     *
     * @param modeIndexList a list of indices of supported modes
     */
    public void init(List<Integer> modeIndexList) {
        int[] modeSequence = getResources()
                .getIntArray(R.array.camera_modes_in_nav_drawer_if_supported);
        int[] visibleModes = getResources()
                .getIntArray(R.array.camera_modes_always_visible);

        // Mark the supported modes in a boolean array to preserve the
        // sequence of the modes
        SparseArray<Boolean> modeIsSupported = new SparseArray<Boolean>();
        for (int i = 0; i < modeIndexList.size(); i++) {
            int mode = modeIndexList.get(i);
            modeIsSupported.put(mode, true);
        }
        for (int i = 0; i < visibleModes.length; i++) {
            int mode = visibleModes[i];
            modeIsSupported.put(mode, true);
        }

        // Put the indices of supported modes into an array preserving their
        // display order.
        mSupportedModes = new ArrayList<Integer>();
        for (int i = 0; i < modeSequence.length; i++) {
            int mode = modeSequence[i];
            if (modeIsSupported.get(mode, false)) {
                mSupportedModes.add(mode);
            }
        }
        mTotalModes = mSupportedModes.size();
        initializeModeSelectorItems();
        mSettingsButton = (SettingsButton) findViewById(R.id.settings_button);
        mSettingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mModeSwitchListener.onSettingsSelected();
            }
        });
    }

    /**
     * Sets the screen shot provider for getting a preview frame and a bitmap
     * of the controls and overlay.
     */
    public void setCameraModuleScreenShotProvider(
            CameraAppUI.CameraModuleScreenShotProvider provider) {
        mScreenShotProvider = provider;
    }

    private void initializeModeSelectorItems() {
        mModeSelectorItems = new ModeSelectorItem[mTotalModes];
        // Inflate the mode selector items and add them to a linear layout
        LayoutInflater inflater = (LayoutInflater) getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mListView = (LinearLayout) findViewById(R.id.mode_list);
        for (int i = 0; i < mTotalModes; i++) {
            ModeSelectorItem selectorItem =
                    (ModeSelectorItem) inflater.inflate(R.layout.mode_selector, null);
            mListView.addView(selectorItem);
            // Sets the top padding of the top item to 0.
            if (i == 0) {
                selectorItem.setPadding(selectorItem.getPaddingLeft(), 0,
                        selectorItem.getPaddingRight(), selectorItem.getPaddingBottom());
            }
            // Sets the bottom padding of the bottom item to 0.
            if (i == mTotalModes - 1) {
                selectorItem.setPadding(selectorItem.getPaddingLeft(), selectorItem.getPaddingTop(),
                        selectorItem.getPaddingRight(), 0);
            }

            int modeId = getModeIndex(i);
            selectorItem.setHighlightColor(getResources()
                    .getColor(CameraUtil.getCameraThemeColorId(modeId, getContext())));

            // Set image
            selectorItem.setImageResource(CameraUtil.getCameraModeIconResId(modeId, getContext()));

            // Set text
            selectorItem.setText(CameraUtil.getCameraModeText(modeId, getContext()));

            // Set content description (for a11y)
            selectorItem.setContentDescription(CameraUtil
                    .getCameraModeContentDescription(modeId, getContext()));

            mModeSelectorItems[i] = selectorItem;
        }

        resetModeSelectors();
    }

    /**
     * Maps between the UI mode selector index to the actual mode id.
     *
     * @param modeSelectorIndex the index of the UI item
     * @return the index of the corresponding camera mode
     */
    private int getModeIndex(int modeSelectorIndex) {
        if (modeSelectorIndex < mTotalModes && modeSelectorIndex >= 0) {
            return mSupportedModes.get(modeSelectorIndex);
        }
        Log.e(TAG, "Invalid mode selector index: " + modeSelectorIndex + ", total modes: "
                + mTotalModes);
        return getResources().getInteger(R.integer.camera_mode_photo);
    }

    /** Notify ModeSwitchListener, if any, of the mode change. */
    private void onModeSelected(int modeIndex) {
        if (mModeSwitchListener != null) {
            mModeSwitchListener.onModeSelected(modeIndex);
        }
    }

    /**
     * Sets a listener that listens to receive mode switch event.
     *
     * @param listener a listener that gets notified when mode changes.
     */
    public void setModeSwitchListener(ModeSwitchListener listener) {
        mModeSwitchListener = listener;
    }

    /**
     * Sets a listener that gets notified when the mode list is open full screen.
     *
     * @param listener a listener that listens to mode list open events
     */
    public void setModeListOpenListener(ModeListOpenListener listener) {
        mModeListOpenListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mCurrentEffect != null) {
            return mCurrentEffect.onTouchEvent(ev);
        }

        super.onTouchEvent(ev);
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mVelocityX = 0;
            if  (mState == ACCORDION_ANIMATION) {
                // Let taps go through to take a capture during the accordion
                return false;
            }
            getParent().requestDisallowInterceptTouchEvent(true);
            if (mState == FULLY_SHOWN) {
                mFocusItem = NO_ITEM_SELECTED;
                setSwipeMode(false);
            } else {
                mFocusItem = getFocusItem(ev.getX(), ev.getY());
                setSwipeMode(true);
            }
        } else if (mState == ACCORDION_ANIMATION) {
            // This is a swipe during accordion animation
            mFocusItem = getFocusItem(ev.getX(), ev.getY());
            setSwipeMode(true);

        }
        // Pass all touch events to gesture detector for gesture handling.
        mGestureDetector.onTouchEvent(ev);
        if (ev.getActionMasked() == MotionEvent.ACTION_UP ||
                ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            snap();
            mFocusItem = NO_ITEM_SELECTED;
        }
        return true;
    }

    /**
     * Sets the swipe mode to indicate whether this is a swiping in
     * or out, and therefore we can have different animations.
     *
     * @param swipeIn indicates whether the swipe should reveal/hide the list.
     */
    private void setSwipeMode(boolean swipeIn) {
        for (int i = 0 ; i < mModeSelectorItems.length; i++) {
            mModeSelectorItems[i].onSwipeModeChanged(swipeIn);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mWidth = right - left;
        mHeight = bottom - top - getPaddingTop() - getPaddingBottom();
        if (mCurrentEffect != null) {
            mCurrentEffect.setSize(mWidth, mHeight);
        }
    }

    /**
     * Here we calculate the children size based on the orientation, change
     * their layout parameters if needed before propagating onMeasure call
     * to the children, so the newly changed params will take effect in this
     * pass.
     *
     * @param widthMeasureSpec Horizontal space requirements as imposed by the
     *        parent
     * @param heightMeasureSpec Vertical space requirements as imposed by the
     *        parent
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        centerModeDrawerInUncoveredPreview(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec));
    }

    @Override
    public void draw(Canvas canvas) {
        if (mCurrentEffect != null) {
            mCurrentEffect.drawBackground(canvas);
            super.draw(canvas);
            mCurrentEffect.drawForeground(canvas);
        } else {
            super.draw(canvas);
        }
    }

    /**
     * This starts the accordion animation, unless it's already running, in which
     * case the start animation call will be ignored.
     */
    public void startAccordionAnimation() {
        if (mState != IDLE) {
            return;
        }
        if (mAnimatorSet != null && mAnimatorSet.isRunning()) {
            return;
        }
        mState = ACCORDION_ANIMATION;
        resetModeSelectors();
        animateListToWidth(START_DELAY_MS, TOTAL_DURATION_MS, mAccordionInterpolator,
                0, mIconBlockWidth, 0);
    }

    /**
     * This starts the accordion animation with a delay.
     *
     * @param delay delay in milliseconds before starting animation
     */
    public void startAccordionAnimationWithDelay(int delay) {
        postDelayed(new Runnable() {
            @Override
            public void run() {
                startAccordionAnimation();
            }
        }, delay);
    }

    /**
     * Resets the visible width of all the mode selectors to 0.
     */
    private void resetModeSelectors() {
        for (int i = 0; i < mModeSelectorItems.length; i++) {
            mModeSelectorItems[i].setVisibleWidth(0);
        }
        // Visible width has been changed to 0
        onVisibleWidthChanged(0);
    }

    private boolean isRunningAccordionAnimation() {
        return mAnimatorSet != null && mAnimatorSet.isRunning();
    }

    /**
     * Calculate the mode selector item in the list that is at position (x, y).
     * If the position is above the top item or below the bottom item, return
     * the top item or bottom item respectively.
     *
     * @param x horizontal position
     * @param y vertical position
     * @return index of the item that is at position (x, y)
     */
    private int getFocusItem(float x, float y) {
        // Convert coordinates into child view's coordinates.
        x -= mListView.getX();
        y -= mListView.getY();

        for (int i = 0; i < mModeSelectorItems.length; i++) {
            if (y <= mModeSelectorItems[i].getBottom()) {
                return i;
            }
        }
        return mModeSelectorItems.length - 1;
    }

    @Override
    public void onVisibilityChanged(View v, int visibility) {
        super.onVisibilityChanged(v, visibility);
        if (visibility == VISIBLE) {
            centerModeDrawerInUncoveredPreview(getMeasuredWidth(), getMeasuredHeight());
            // Highlight current module
            if (mModeSwitchListener != null) {
                int modeId = mModeSwitchListener.getCurrentModeIndex();
                int parentMode = CameraUtil.getCameraModeParentModeId(modeId, getContext());
                // Find parent mode in the nav drawer.
                for (int i = 0; i < mSupportedModes.size(); i++) {
                    if (mSupportedModes.get(i) == parentMode) {
                        mModeSelectorItems[i].setHighlighted(true);
                    }
                }
            }
        } else {
            if (mModeSelectorItems != null) {
                // When becoming invisible/gone after initializing mode selector items.
                for (int i = 0; i < mModeSelectorItems.length; i++) {
                    mModeSelectorItems[i].setHighlighted(false);
                    mModeSelectorItems[i].setSelected(false);
                }
            }
            if (mModeSwitchListener != null) {
                mModeListOpenListener.onModeListClosed();
            }
        }
    }

    /**
     * Center mode drawer in the portion of camera preview that is not covered by
     * bottom bar.
     */
    // TODO: Combine SettingsButton logic into here if UX design does not change
    // for another week.
    private void centerModeDrawerInUncoveredPreview(int measuredWidth, int measuredHeight) {

        // Assuming the preview is centered in the space aside from bottom bar.
        float previewAreaWidth = mUncoveredPreviewArea.right + mUncoveredPreviewArea.left;
        float previewAreaHeight = mUncoveredPreviewArea.top + mUncoveredPreviewArea.bottom;
        if (measuredWidth > measuredHeight && previewAreaWidth < previewAreaHeight
                || measuredWidth < measuredHeight && previewAreaWidth > previewAreaHeight) {
            // Cached preview area is stale, update mode drawer position on next
            // layout pass.
            mAdjustPositionWhenUncoveredPreviewAreaChanges = true;
        } else {
            // Align left:
            mListView.setTranslationX(mUncoveredPreviewArea.left);
            // Align center vertical:
            mListView.setTranslationY(mUncoveredPreviewArea.centerY()
                    - mListView.getMeasuredHeight() / 2);
        }
    }

    private void scroll(int itemId, float deltaX, float deltaY) {
        // Scrolling trend on X and Y axis, to track the trend by biasing
        // towards latest touch events.
        mScrollTrendX = mScrollTrendX * 0.3f + deltaX * 0.7f;
        mScrollTrendY = mScrollTrendY * 0.3f + deltaY * 0.7f;

        // TODO: Change how the curve is calculated below when UX finalize their design.
        mCurrentTime = SystemClock.uptimeMillis();
        float longestWidth;
        if (itemId != NO_ITEM_SELECTED) {
            longestWidth = mModeSelectorItems[itemId].getVisibleWidth() - deltaX;
        } else {
            longestWidth = mModeSelectorItems[0].getVisibleWidth() - deltaX;
        }
        insertNewPosition(longestWidth, mCurrentTime);

        for (int i = 0; i < mModeSelectorItems.length; i++) {
            mModeSelectorItems[i].setVisibleWidth(calculateVisibleWidthForItem(i,
                    (int) longestWidth));
        }
        if (longestWidth <= 0) {
            reset();
        }

        itemId = itemId == NO_ITEM_SELECTED ? 0 : itemId;
        onVisibleWidthChanged(mModeSelectorItems[itemId].getVisibleWidth());
    }

    /**
     * Calculate the width of a specified item based on its position relative to
     * the item with longest width.
     */
    private int calculateVisibleWidthForItem(int itemId, int longestWidth) {
        if (itemId == mFocusItem || mFocusItem == NO_ITEM_SELECTED) {
            return longestWidth;
        }

        int delay = Math.abs(itemId - mFocusItem) * DELAY_MS;
        return (int) getPosition(mCurrentTime - delay);
    }

    /**
     * Insert new position and time stamp into the history position list, and
     * remove stale position items.
     *
     * @param position latest position of the focus item
     * @param time  current time in milliseconds
     */
    private void insertNewPosition(float position, long time) {
        // TODO: Consider re-using stale position objects rather than
        // always creating new position objects.
        mPositionHistory.add(new TimeBasedPosition(position, time));

        // Positions that are from too long ago will not be of any use for
        // future position interpolation. So we need to remove those positions
        // from the list.
        long timeCutoff = time - (mTotalModes - 1) * DELAY_MS;
        while (mPositionHistory.size() > 0) {
            // Remove all the position items that are prior to the cutoff time.
            TimeBasedPosition historyPosition = mPositionHistory.getFirst();
            if (historyPosition.getTimeStamp() < timeCutoff) {
                mPositionHistory.removeFirst();
            } else {
                break;
            }
        }
    }

    /**
     * Gets the interpolated position at the specified time. This involves going
     * through the recorded positions until a {@link TimeBasedPosition} is found
     * such that the position the recorded before the given time, and the
     * {@link TimeBasedPosition} after that is recorded no earlier than the given
     * time. These two positions are then interpolated to get the position at the
     * specified time.
     */
    private float getPosition(long time) {
        int i;
        for (i = 0; i < mPositionHistory.size(); i++) {
            TimeBasedPosition historyPosition = mPositionHistory.get(i);
            if (historyPosition.getTimeStamp() > time) {
                // Found the winner. Now interpolate between position i and position i - 1
                if (i == 0) {
                    return historyPosition.getPosition();
                } else {
                    TimeBasedPosition prevTimeBasedPosition = mPositionHistory.get(i - 1);
                    // Start interpolation
                    float fraction = (float) (time - prevTimeBasedPosition.getTimeStamp()) /
                            (float) (historyPosition.getTimeStamp() - prevTimeBasedPosition.getTimeStamp());
                    float position = fraction * (historyPosition.getPosition()
                            - prevTimeBasedPosition.getPosition()) + prevTimeBasedPosition.getPosition();
                    return position;
                }
            }
        }
        // It should never get here.
        Log.e(TAG, "Invalid time input for getPosition(). time: " + time);
        if (mPositionHistory.size() == 0) {
            Log.e(TAG, "TimeBasedPosition history size is 0");
        } else {
            Log.e(TAG, "First position recorded at " + mPositionHistory.getFirst().getTimeStamp()
            + " , last position recorded at " + mPositionHistory.getLast().getTimeStamp());
        }
        assert (i < mPositionHistory.size());
        return i;
    }

    private void reset() {
        resetModeSelectors();
        mScrollTrendX = 0f;
        mScrollTrendY = 0f;
        mCurrentEffect = null;
        setVisibility(INVISIBLE);
    }

    /**
     * When visible width of list is changed, the background of the list needs
     * to darken/lighten correspondingly.
     */
    private void onVisibleWidthChanged(int focusItemWidth) {
        // When the longest mode item is entirely shown (across the screen), the
        // background should be 50% transparent.
        int maxVisibleWidth = mModeSelectorItems[0].getMaxVisibleWidth();
        focusItemWidth = Math.min(maxVisibleWidth, focusItemWidth);
        float openRatio = (float) focusItemWidth / maxVisibleWidth;
        setBackgroundAlpha((int) (BACKGROUND_TRANSPARENTCY * openRatio));
        if (mModeListOpenListener != null) {
            mModeListOpenListener.onModeListOpenProgress(openRatio);
        }
        if (mSettingsButton != null) {
            mSettingsButton.setAlpha(openRatio);
        }
    }

    @Override
    public void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            // Reset mode list if the window is no longer visible.
            reset();
            mState = IDLE;
        }
    }

    /**
     * The list view should either snap back or snap to full screen after a gesture.
     * This function is called when an up or cancel event is received, and then based
     * on the current position of the list and the gesture we can decide which way
     * to snap.
     */
    private void snap() {
        if (mState == SCROLLING) {
            int itemId = Math.max(0, mFocusItem);
            if (mModeSelectorItems[itemId].getVisibleWidth()
                    < mModeSelectorItems[itemId].getMaxVisibleWidth() * SNAP_BACK_THRESHOLD_RATIO) {
                snapBack();
            } else if (Math.abs(mScrollTrendX) > Math.abs(mScrollTrendY) && mScrollTrendX > 0) {
                snapBack();
            } else {
                snapToFullScreen();
            }
        }
    }

    /**
     * Snaps back out of the screen.
     *
     * @param withAnimation whether snapping back should be animated
     */
    public void snapBack(boolean withAnimation) {
        if (withAnimation) {
            if (mVelocityX > -VELOCITY_THRESHOLD * SCROLL_FACTOR) {
                animateListToWidth(0);
            } else {
                animateListToWidthAtVelocity(mVelocityX, 0);
            }
            mState = IDLE;
        } else {
            setVisibility(INVISIBLE);
            resetModeSelectors();
            mState = IDLE;
        }
    }

    /**
     * Snaps the mode list back out with animation.
     */
    private void snapBack() {
        snapBack(true);
    }

    private void snapToFullScreen() {
        int focusItem = mFocusItem == NO_ITEM_SELECTED ? 0 : mFocusItem;
        int fullWidth = mModeSelectorItems[focusItem].getMaxVisibleWidth();
        if (mVelocityX <= VELOCITY_THRESHOLD * SCROLL_FACTOR) {
            animateListToWidth(fullWidth);
        } else {
            // If the fling velocity exceeds this threshold, snap to full screen
            // at a constant speed.
            animateListToWidthAtVelocity(mVelocityX, fullWidth);
        }
        mState = FULLY_SHOWN;
        if (mModeListOpenListener != null) {
            mModeListOpenListener.onOpenFullScreen();
        }
    }

    /**
     * Overloaded function to provide a simple way to start animation. Animation
     * will use default duration, and a value of <code>null</code> for interpolator
     * means linear interpolation will be used.
     *
     * @param width a set of values that the animation will animate between over time
     */
    private void animateListToWidth(int... width) {
        animateListToWidth(0, DEFAULT_DURATION_MS, null, width);
    }

    /**
     * Animate the mode list between the given set of visible width.
     *
     * @param delay start delay between consecutive mode item
     * @param duration duration for the animation of each mode item
     * @param interpolator interpolator to be used by the animation
     * @param width a set of values that the animation will animate between over time
     */
    private void animateListToWidth(int delay, int duration,
                                    TimeInterpolator interpolator, int... width) {
        if (mAnimatorSet != null && mAnimatorSet.isRunning()) {
            mAnimatorSet.end();
        }

        ArrayList<Animator> animators = new ArrayList<Animator>();
        int focusItem = mFocusItem == NO_ITEM_SELECTED ? 0 : mFocusItem;
        for (int i = 0; i < mTotalModes; i++) {
            ObjectAnimator animator = ObjectAnimator.ofInt(mModeSelectorItems[i],
                    "visibleWidth", width);
            animator.setDuration(duration);
            animator.setStartDelay(i * delay);
            animators.add(animator);
            if (i == focusItem) {
                animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        onVisibleWidthChanged((Integer) animation.getAnimatedValue());
                    }
                });
            }
        }

        mAnimatorSet = new AnimatorSet();
        mAnimatorSet.playTogether(animators);
        mAnimatorSet.setInterpolator(interpolator);
        mAnimatorSet.addListener(mModeListAnimatorListener);
        mAnimatorSet.start();
    }

    /**
     * Animate the mode list to the given width at a constant velocity.
     *
     * @param velocity the velocity that animation will be at
     * @param width final width of the list
     */
    private void animateListToWidthAtVelocity(float velocity, int width) {
        if (mAnimatorSet != null && mAnimatorSet.isRunning()) {
            mAnimatorSet.end();
        }

        ArrayList<Animator> animators = new ArrayList<Animator>();
        int focusItem = mFocusItem == NO_ITEM_SELECTED ? 0 : mFocusItem;
        for (int i = 0; i < mTotalModes; i++) {
            ObjectAnimator animator = ObjectAnimator.ofInt(mModeSelectorItems[i],
                    "visibleWidth", width);
            int duration = (int) ((float) width / velocity);
            animator.setDuration(duration);
            animators.add(animator);
            if (i == focusItem) {
                animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        onVisibleWidthChanged((Integer) animation.getAnimatedValue());
                    }
                });
            }
        }

        mAnimatorSet = new AnimatorSet();
        mAnimatorSet.playTogether(animators);
        mAnimatorSet.setInterpolator(null);
        mAnimatorSet.addListener(mModeListAnimatorListener);
        mAnimatorSet.start();
    }

    /**
     * Called when the back key is pressed.
     *
     * @return Whether the UI responded to the key event.
     */
    public boolean onBackPressed() {
        if (mState == FULLY_SHOWN) {
            snapBack();
            return true;
        } else {
            return false;
        }
    }

    public void startModeSelectionAnimation() {
        if (mState != MODE_SELECTED || mCurrentEffect == null) {
            setVisibility(INVISIBLE);
            snapBack(false);
            mCurrentEffect = null;
        } else {
            mCurrentEffect.startAnimation();
        }

    }

    private class PeepholeAnimationEffect extends AnimationEffects {

        private final static int UNSET = -1;
        private final static int PEEP_HOLE_ANIMATION_DURATION_MS = 300;

        private final Paint mMaskPaint = new Paint();
        private final Paint mBackgroundPaint = new Paint();
        private final RectF mBackgroundDrawArea = new RectF();

        private int mWidth;
        private int mHeight;
        private int mPeepHoleCenterX = UNSET;
        private int mPeepHoleCenterY = UNSET;
        private float mRadius = 0f;
        private ValueAnimator mPeepHoleAnimator;
        private Runnable mEndAction;
        private Bitmap mBackground;
        private Bitmap mBlurredBackground;
        private Bitmap mBackgroundOverlay;

        public PeepholeAnimationEffect() {
            mMaskPaint.setAlpha(0);
            mMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        }

        @Override
        public void setSize(int width, int height) {
            mWidth = width;
            mHeight = height;
        }

        @Override
        public void drawForeground(Canvas canvas) {
            // Draw the circle in clear mode
            if (mPeepHoleAnimator != null) {
                // Draw a transparent circle using clear mode
                canvas.drawCircle(mPeepHoleCenterX, mPeepHoleCenterY, mRadius, mMaskPaint);
            }
        }

        public void setAnimationStartingPosition(int x, int y) {
            mPeepHoleCenterX = x;
            mPeepHoleCenterY = y;
        }

        /**
         * Sets the bitmap to be drawn in the background and the drawArea to draw
         * the bitmap. In the meantime, start processing the image in a background
         * thread to get a blurred background image.
         *
         * @param background image to be drawn in the background
         * @param drawArea area to draw the background image
         */
        public void setBackground(Bitmap background, RectF drawArea) {
            mBackground = background;
            mBackgroundDrawArea.set(drawArea);
            new BlurTask().execute(Bitmap.createScaledBitmap(background, background.getWidth(),
                    background.getHeight(), true));
        }

        /**
         * Sets the overlay image to be drawn on top of the background.
         */
        public void setBackgroundOverlay(Bitmap overlay) {
            mBackgroundOverlay = overlay;
        }

        /**
         * This gets called when a blurred image of the background is generated.
         * Start an animation to fade in the blur.
         *
         * @param blur blurred image of the background.
         */
        public void setBlurredBackground(Bitmap blur) {
            mBlurredBackground = blur;
            // Start fade in.
            ObjectAnimator alpha = ObjectAnimator.ofInt(mBackgroundPaint, "alpha", 80, 255);
            alpha.setDuration(250);
            alpha.setInterpolator(Gusterpolator.INSTANCE);
            alpha.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    invalidate();
                }
            });
            alpha.start();
            invalidate();
        }

        @Override
        public void drawBackground(Canvas canvas) {
            if (mBackground != null && mBackgroundOverlay != null) {
                canvas.drawARGB(255, 0, 0, 0);
                canvas.drawBitmap(mBackground, null, mBackgroundDrawArea, null);
                if (mBlurredBackground != null) {
                    canvas.drawBitmap(mBlurredBackground, null, mBackgroundDrawArea, mBackgroundPaint);
                }
                canvas.drawBitmap(mBackgroundOverlay, 0, 0, null);
            }
        }

        @Override
        public void startAnimation() {
            if (mPeepHoleAnimator != null && mPeepHoleAnimator.isRunning()) {
                return;
            }
            if (mPeepHoleCenterY == UNSET || mPeepHoleCenterX == UNSET) {
                mPeepHoleCenterX = mWidth / 2;
                mPeepHoleCenterY = mHeight / 2;
            }

            int horizontalDistanceToFarEdge = Math.max(mPeepHoleCenterX, mWidth - mPeepHoleCenterX);
            int verticalDistanceToFarEdge = Math.max(mPeepHoleCenterY, mHeight - mPeepHoleCenterY);
            int endRadius = (int) (Math.sqrt(horizontalDistanceToFarEdge * horizontalDistanceToFarEdge
                    + verticalDistanceToFarEdge * verticalDistanceToFarEdge));
            int startRadius = getResources().getDimensionPixelSize(
                    R.dimen.mode_selector_icon_block_width) / 2;

            mPeepHoleAnimator = ValueAnimator.ofFloat(0, endRadius);
            mPeepHoleAnimator.setDuration(PEEP_HOLE_ANIMATION_DURATION_MS);
            mPeepHoleAnimator.setInterpolator(Gusterpolator.INSTANCE);
            mPeepHoleAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    // Modify mask by enlarging the hole
                    mRadius = (Float) mPeepHoleAnimator.getAnimatedValue();
                    invalidate();
                }
            });

            mPeepHoleAnimator.addListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {

                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mEndAction != null) {
                        post(mEndAction);
                        mEndAction = null;
                        post(new Runnable() {
                            @Override
                            public void run() {
                                mPeepHoleAnimator = null;
                                mRadius = 0;
                                mPeepHoleCenterX = UNSET;
                                mPeepHoleCenterY = UNSET;
                            }
                        });
                    } else {
                        mPeepHoleAnimator = null;
                        mRadius = 0;
                        mPeepHoleCenterX = UNSET;
                        mPeepHoleCenterY = UNSET;
                    }
                }

                @Override
                public void onAnimationCancel(Animator animation) {

                }

                @Override
                public void onAnimationRepeat(Animator animation) {

                }
            });
            mPeepHoleAnimator.start();
        }

        public void setAnimationEndAction(Runnable runnable) {
            mEndAction = runnable;
        }

        private class BlurTask extends AsyncTask<Bitmap, Integer, Bitmap> {

            // Gaussian blur mask size.
            private static final int MASK_SIZE = 7;
            @Override
            protected Bitmap doInBackground(Bitmap... params) {

                Bitmap intermediateBitmap = params[0];
                int factor = 4;
                Bitmap lowResPreview = Bitmap.createScaledBitmap(intermediateBitmap,
                        intermediateBitmap.getWidth() / factor,
                        intermediateBitmap.getHeight() / factor, true);

                int width = lowResPreview.getWidth();
                int height = lowResPreview.getHeight();

                if (mInputPixels == null || mInputPixels.length < width * height) {
                    mInputPixels = new int[width * height];
                    mOutputPixels = new int[width * height];
                }
                lowResPreview.getPixels(mInputPixels, 0, width, 0, 0, width, height);
                CameraUtil.blur(mInputPixels, mOutputPixels, width, height, MASK_SIZE);
                lowResPreview.setPixels(mOutputPixels, 0, width, 0, 0, width, height);

                intermediateBitmap.recycle();
                return Bitmap.createScaledBitmap(lowResPreview, width * factor,
                        height * factor, true);
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                setBlurredBackground(bitmap);
            }
        };
    }
}