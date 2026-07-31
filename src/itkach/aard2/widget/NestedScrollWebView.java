package itkach.aard2.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.webkit.WebView;

import androidx.annotation.Nullable;
import androidx.core.view.NestedScrollingChild;
import androidx.core.view.NestedScrollingChildHelper;
import androidx.core.view.ViewCompat;

class NestedScrollWebView extends WebView implements NestedScrollingChild {

    private String mLastFind = null;

    private int lastMotionX = 0;
    private int lastMotionY = 0;

    private boolean multiTouch = false;

    private int[] scrollOffset = new int[2];
    private int[] scrollConsumed = new int[2];

    private int nestedOffsetY = 0;

    private NestedScrollingChildHelper childHelper = new NestedScrollingChildHelper(this);

    public void setLastFind(String find) {
        mLastFind = find;
    }

    public NestedScrollWebView(Context context) {
        this(context, null);
    }

    public NestedScrollWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                nestedOffsetY = 0;
                multiTouch = false;
                lastMotionX = (int) event.getX();
                lastMotionY = (int) event.getY();
                // A quick horizontal swipe passes the parent's touch slop on the very
                // first move, and ViewGroup lets the parent intercept before that move
                // reaches this view. Claim the gesture up front whenever the content can
                // scroll sideways at all; onSinglePointerMove releases it again as soon
                // as the direction is known and the pager should get the gesture.
                if (canScrollHorizontally(1) || canScrollHorizontally(-1)) {
                    requestDisallowInterceptTouchEvent(true);
                }
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL);
                return superTouchWithNestedOffset(event);
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // Pinch zoom: the whole gesture belongs to the WebView, so keep the parent
                // (ViewPager2) from stealing it until every finger is up.
                multiTouch = true;
                stopNestedScroll();
                requestDisallowInterceptTouchEvent(true);
                return superTouchWithNestedOffset(event);
            }
            case MotionEvent.ACTION_MOVE: {
                if (multiTouch) {
                    return superTouchWithNestedOffset(event);
                }
                return onSinglePointerMove(event);
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                multiTouch = false;
                stopNestedScroll();
                requestDisallowInterceptTouchEvent(false);
                return superTouchWithNestedOffset(event);
            }
            default:
                // Everything else (ACTION_POINTER_UP in particular) must reach the WebView,
                // otherwise its gesture detectors never see the gesture end.
                return superTouchWithNestedOffset(event);
        }
    }

    private boolean superTouchWithNestedOffset(MotionEvent event) {
        event.offsetLocation(0f, nestedOffsetY);
        return super.onTouchEvent(event);
    }

    private boolean onSinglePointerMove(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        int deltaX = (int) (lastMotionX - x);
        int deltaY = (int) (lastMotionY - y);

        if (Math.abs(deltaY) > Math.abs(deltaX)) {
            if (canScrollVertically(1) || canScrollVertically(-1)) {
                requestDisallowInterceptTouchEvent(true);
            }
        } else if (deltaX != 0) {
            // Horizontal drag: keep it while the content can still scroll that way,
            // hand it back to the pager once this edge is reached.
            requestDisallowInterceptTouchEvent(canScrollHorizontally(deltaX > 0 ? 1 : -1));
        }

        MotionEvent trackedEvent = MotionEvent.obtain(event);
        try {
            if (dispatchNestedPreScroll(0, deltaY, scrollConsumed, scrollOffset)) {
                deltaY -= scrollConsumed[1];
                trackedEvent.offsetLocation(0f, scrollOffset[1]);
                nestedOffsetY += scrollOffset[1];
            }

            lastMotionX = (int) x;
            lastMotionY = (int) (y - scrollOffset[1]);

            int oldY = getScrollY();
            int newScrollY = Math.max(0, oldY + deltaY);
            int dyConsumed = newScrollY - oldY;
            int dyUnconsumed = deltaY - dyConsumed;

            if (dispatchNestedScroll(0, dyConsumed, 0, dyUnconsumed, scrollOffset)) {
                lastMotionY -= scrollOffset[1];
                trackedEvent.offsetLocation(0f, scrollOffset[1]);
                nestedOffsetY += scrollOffset[1];
            }

            return super.onTouchEvent(trackedEvent);
        } finally {
            trackedEvent.recycle();
        }
    }

    @Override
    public void setNestedScrollingEnabled(boolean enabled) {
        childHelper.setNestedScrollingEnabled(enabled);
    }

    @Override
    public void stopNestedScroll() {
        childHelper.stopNestedScroll();
    }

    @Override
    public boolean isNestedScrollingEnabled() {
        return childHelper.isNestedScrollingEnabled();
    }

    @Override
    public boolean startNestedScroll(int axes) {
        return childHelper.startNestedScroll(axes);
    }

    @Override
    public boolean hasNestedScrollingParent() {
        return childHelper.hasNestedScrollingParent();
    }

    @Override
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, @Nullable int[] offsetInWindow) {
        return childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedPreScroll(int dx, int dy, @Nullable int[] consumed, @Nullable int[] offsetInWindow) {
        return childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedFling(float velocityX, float velocityY, boolean consumed) {
        return childHelper.dispatchNestedFling(velocityX, velocityY, consumed);
    }

    @Override
    public boolean dispatchNestedPreFling(float velocityX, float velocityY) {
        return childHelper.dispatchNestedPreFling(velocityX, velocityY);
    }
}
