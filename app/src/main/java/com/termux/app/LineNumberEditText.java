package com.termux.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatEditText;

public class LineNumberEditText extends AppCompatEditText {
    private Rect rect;
    private Paint paint;
    private int lineNumberColor = Color.parseColor("#5A6072");

    public LineNumberEditText(Context context) {
        super(context);
        init();
    }

    public LineNumberEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LineNumberEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        rect = new Rect();
        paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(lineNumberColor);
        paint.setTextSize(getTextSize() * 0.8f);
        paint.setAntiAlias(true);
        // Add left padding for line numbers
        setPadding(120, getPaddingTop(), getPaddingRight(), getPaddingBottom());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getLayout() == null) {
            super.onDraw(canvas);
            return;
        }

        int firstLine = getLayout().getLineForVertical(getScrollY());
        int lastLine = getLayout().getLineForVertical(getScrollY() + getHeight());

        for (int i = firstLine; i <= lastLine; i++) {
            // Only draw line number if it's the first line of a wrapped paragraph, or just draw for every line
            // For simplicity, we draw for every line, but typically code editors draw per paragraph.
            int baseline = getLayout().getLineBaseline(i);
            canvas.drawText(String.valueOf(i + 1), 30, baseline, paint);
        }
        super.onDraw(canvas);
    }
}
