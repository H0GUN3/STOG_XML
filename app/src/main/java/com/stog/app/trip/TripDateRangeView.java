package com.stog.app.trip;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.textview.MaterialTextView;
import com.stog.app.R;

import java.util.Calendar;
import java.util.TimeZone;

/** Monochrome inline range calendar. Dates are UTC midnight millis, matching the trip draft's ISO dates. */
public final class TripDateRangeView extends LinearLayout {
    public interface Listener { void onRangeChanged(@Nullable Long start, @Nullable Long end); }

    private static final int WEEKS = 6;
    private static final int DAYS = 7;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private final TextView[] cells = new TextView[WEEKS * DAYS];
    private final Calendar month = Calendar.getInstance(UTC);
    private final long today;
    private final TextView title;
    private final int ink;
    private final int inverse;
    private final int band;
    private final int outline;
    private final float markInset;
    @Nullable private Listener listener;
    @Nullable private Long start;
    @Nullable private Long end;

    public TripDateRangeView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_trip_date_range, this, true);
        ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface);
        inverse = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface);
        band = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer);
        outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline);
        markInset = getResources().getDimension(R.dimen.trip_calendar_mark_inset);
        Calendar local = Calendar.getInstance();
        today = utcDate(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
        setVisibleMonth(today);
        title = findViewById(R.id.calendar_month);
        ImageButton previous = findViewById(R.id.calendar_prev);
        ImageButton next = findViewById(R.id.calendar_next);
        previous.setOnClickListener(ignored -> shiftMonth(-1));
        next.setOnClickListener(ignored -> shiftMonth(1));
        buildGrid(findViewById(R.id.calendar_grid));
        render();
    }

    public void setListener(@Nullable Listener value) { listener = value; }

    /** Applies an externally known range; the visible month is left to the caller. */
    public void setRange(@Nullable Long newStart, @Nullable Long newEnd) {
        start = newStart;
        end = newEnd;
        render();
    }

    public long visibleMonth() { return month.getTimeInMillis(); }

    public void showMonth(long utcMillis) {
        setVisibleMonth(utcMillis);
        render();
    }

    private void setVisibleMonth(long utcMillis) {
        month.setTimeInMillis(utcMillis);
        month.set(Calendar.DAY_OF_MONTH, 1);
    }

    private void shiftMonth(int delta) {
        month.add(Calendar.MONTH, delta);
        render();
    }

    private void buildGrid(LinearLayout grid) {
        int rowHeight = getResources().getDimensionPixelSize(R.dimen.trip_calendar_cell_height);
        LinearLayout header = new LinearLayout(getContext());
        String[] weekdays = getResources().getStringArray(R.array.trip_dates_weekdays);
        for (String weekday : weekdays) {
            MaterialTextView label = new MaterialTextView(getContext());
            label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
            label.setTextColor(outline);
            label.setGravity(Gravity.CENTER);
            label.setText(weekday);
            header.addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        }
        grid.addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        for (int week = 0; week < WEEKS; week++) {
            LinearLayout row = new LinearLayout(getContext());
            for (int day = 0; day < DAYS; day++) {
                MaterialTextView cell = new MaterialTextView(getContext());
                cell.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
                cell.setGravity(Gravity.CENTER);
                cells[week * DAYS + day] = cell;
                row.addView(cell, new LayoutParams(0, rowHeight, 1));
            }
            grid.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, rowHeight));
        }
    }

    private void onDayTapped(long day) {
        if (start == null || end != null || day < start) {
            start = day;
            end = null;
        } else {
            end = day;
        }
        render();
        if (listener != null) listener.onRangeChanged(start, end);
    }

    private void render() {
        int year = month.get(Calendar.YEAR);
        int monthIndex = month.get(Calendar.MONTH);
        title.setText(getResources().getString(R.string.trip_dates_month, year, monthIndex + 1));
        int offset = month.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        int length = month.getActualMaximum(Calendar.DAY_OF_MONTH);
        int usedWeeks = (offset + length + DAYS - 1) / DAYS;
        for (int week = 0; week < WEEKS; week++) {
            ((android.view.View) cells[week * DAYS].getParent()).setVisibility(week < usedWeeks ? VISIBLE : GONE);
        }
        for (int index = 0; index < cells.length; index++) {
            TextView cell = cells[index];
            int dayOfMonth = index - offset + 1;
            if (dayOfMonth < 1 || dayOfMonth > length) {
                cell.setText(null);
                cell.setBackground(null);
                cell.setOnClickListener(null);
                cell.setClickable(false);
                cell.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                continue;
            }
            long day = utcDate(year, monthIndex, dayOfMonth);
            boolean isStart = start != null && day == start;
            boolean isEnd = end != null && day == end;
            boolean inRange = start != null && end != null && day > start && day < end;
            cell.setText(String.valueOf(dayOfMonth));
            cell.setTextColor(isStart || isEnd ? inverse : ink);
            cell.setBackground(new DayDrawable(isStart, isEnd, inRange, day == today,
                    start != null && end != null && !start.equals(end)));
            cell.setSelected(isStart || isEnd);
            cell.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            cell.setContentDescription(getResources().getString(R.string.trip_dates_day_a11y, year, monthIndex + 1, dayOfMonth));
            cell.setOnClickListener(ignored -> onDayTapped(day));
        }
    }

    private static long utcDate(int year, int monthIndex, int day) {
        Calendar value = Calendar.getInstance(UTC);
        value.clear();
        value.set(year, monthIndex, day);
        return value.getTimeInMillis();
    }

    private final class DayDrawable extends Drawable {
        private final boolean isStart;
        private final boolean isEnd;
        private final boolean inRange;
        private final boolean isToday;
        private final boolean spansDays;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DayDrawable(boolean isStart, boolean isEnd, boolean inRange, boolean isToday, boolean spansDays) {
            this.isStart = isStart;
            this.isEnd = isEnd;
            this.inRange = inRange;
            this.isToday = isToday;
            this.spansDays = spansDays;
        }

        @Override public void draw(@NonNull Canvas canvas) {
            float width = getBounds().width();
            float height = getBounds().height();
            float radius = Math.min(width, height) / 2f - markInset;
            float centerX = width / 2f;
            float centerY = height / 2f;
            paint.setStyle(Paint.Style.FILL);
            if (spansDays && (inRange || isStart || isEnd)) {
                paint.setColor(band);
                float left = isStart ? centerX : 0f;
                float right = isEnd ? centerX : width;
                canvas.drawRect(left, centerY - radius, right, centerY + radius, paint);
            }
            if (isStart || isEnd) {
                paint.setColor(ink);
                canvas.drawCircle(centerX, centerY, radius, paint);
            } else if (isToday) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(markInset / 2f);
                paint.setColor(outline);
                canvas.drawCircle(centerX, centerY, radius, paint);
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable ColorFilter filter) { paint.setColorFilter(filter); }
        @SuppressWarnings("deprecation")
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
