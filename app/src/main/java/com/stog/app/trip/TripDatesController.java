package com.stog.app.trip;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.stog.app.R;
import com.stog.app.databinding.ViewTripDatesBinding;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Keeps the date card and its calendar in step with the draft's ISO start and end fields. */
final class TripDatesController {
    private static final String VISIBLE_MONTH_KEY = "trip_dates_visible_month";
    private static final long DAY_MILLIS = 86_400_000L;
    private final ViewTripDatesBinding card;
    private final EditText startField;
    private final EditText endField;

    TripDatesController(ViewTripDatesBinding card, EditText startField, EditText endField, @Nullable Bundle state) {
        this.card = card;
        this.startField = startField;
        this.endField = endField;
        render();
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { render(); }
        };
        startField.addTextChangedListener(watcher);
        endField.addTextChangedListener(watcher);
        card.tripDateRange.setListener((start, end) -> {
            startField.setText(start == null ? "" : formatIso(start));
            endField.setText(end == null ? "" : formatIso(end));
        });
        Long draftStart = parseIso(text(startField));
        if (state != null && state.containsKey(VISIBLE_MONTH_KEY)) card.tripDateRange.showMonth(state.getLong(VISIBLE_MONTH_KEY));
        else if (draftStart != null) card.tripDateRange.showMonth(draftStart);
    }

    void save(@NonNull Bundle state) { state.putLong(VISIBLE_MONTH_KEY, card.tripDateRange.visibleMonth()); }

    void release() { card.tripDateRange.setListener(null); }

    private void render() {
        Context context = card.getRoot().getContext();
        Long start = parseIso(text(startField));
        Long end = parseIso(text(endField));
        card.tripStartDisplay.setText(start == null ? context.getString(R.string.trip_dates_pick) : formatDisplay(context, start));
        card.tripEndDisplay.setText(end == null ? context.getString(R.string.trip_dates_pick) : formatDisplay(context, end));
        card.tripDateRange.setRange(start, end);
        if (start != null && end != null && end >= start) {
            int nights = (int) ((end - start) / DAY_MILLIS);
            card.tripDatesDuration.setText(nights == 0 ? context.getString(R.string.trip_dates_same_day)
                    : context.getString(R.string.trip_dates_nights, nights, nights + 1));
        } else {
            card.tripDatesDuration.setText(start == null ? R.string.trip_dates_pick_start : R.string.trip_dates_pick_end);
        }
    }

    private static String text(EditText field) { return field.getText() == null ? "" : field.getText().toString(); }

    private static SimpleDateFormat isoFormat() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        format.setLenient(false);
        return format;
    }

    private static String formatIso(long utcMillis) { return isoFormat().format(new Date(utcMillis)); }

    @Nullable private static Long parseIso(String value) {
        if (value.isEmpty()) return null;
        try {
            Date parsed = isoFormat().parse(value);
            return parsed == null ? null : parsed.getTime();
        } catch (ParseException invalid) {
            return null;
        }
    }

    private static String formatDisplay(Context context, long utcMillis) {
        SimpleDateFormat format = new SimpleDateFormat(context.getString(R.string.trip_dates_display_format), Locale.KOREAN);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(utcMillis));
    }
}
