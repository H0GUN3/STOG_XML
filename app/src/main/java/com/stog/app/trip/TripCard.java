package com.stog.app.trip;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import com.stog.app.R;
import com.stog.app.auth.AuthApiClient;
import com.stog.app.databinding.ItemTripBinding;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

/** Renders the one trip card shared by Home and My Trips. */
public final class TripCard {
    /** Shown until a trip has its own cover; chosen so they do not claim a specific place. */
    private static final int[] DEFAULT_COVERS = {R.drawable.trip_cover_1, R.drawable.trip_cover_2, R.drawable.trip_cover_3};
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    public interface CoverLoader { void load(AuthApiClient.Cover cover, AuthApiClient.CoverImageCallback callback); }

    private TripCard() { }

    public static void bind(ItemTripBinding card, AuthApiClient.Trip trip, CoverLoader covers) {
        Context context = card.getRoot().getContext();
        String region = trip.regionLabel() == null ? context.getString(R.string.trip_region_missing) : trip.regionLabel();
        card.tripRowStatus.setText(statusLabel(context, trip));
        card.tripRowName.setText(trip.name() == null ? context.getString(R.string.trip_title_missing) : trip.name());
        card.tripRowMeta.setText(dateLabel(context, trip));
        card.tripRowPlace.setText(context.getString(R.string.home_trip_place, region, trip.memberCount()));
        bindCover(card, trip, covers);
    }

    private static void bindCover(ItemTripBinding card, AuthApiClient.Trip trip, CoverLoader covers) {
        int fallback = DEFAULT_COVERS[Math.floorMod(trip.tripId().hashCode(), DEFAULT_COVERS.length)];
        AuthApiClient.Cover cover = trip.cover();
        String expected = cover == null ? null : cover.coverId();
        card.tripRowCover.setTag(expected);
        card.tripRowCoverStatus.setVisibility(View.GONE);
        if (cover == null) {
            card.tripRowCover.setImageResource(fallback);
            return;
        }
        card.tripRowCover.setImageDrawable(null);
        covers.load(cover, result -> {
            if (!Objects.equals(expected, card.tripRowCover.getTag())) {
                if (result.bitmap() != null) result.bitmap().recycle();
                return;
            }
            if (result.outcome() == AuthApiClient.CoverImageOutcome.CONTENT) {
                card.tripRowCover.setImageBitmap(result.bitmap());
                return;
            }
            card.tripRowCover.setImageResource(fallback);
            card.tripRowCoverStatus.setVisibility(result.outcome() == AuthApiClient.CoverImageOutcome.ERROR ? View.VISIBLE : View.GONE);
        });
    }

    private static String statusLabel(Context context, AuthApiClient.Trip trip) {
        Long start = utcDay(trip.startDate());
        if (start == null || "PAST".equals(trip.status())) return context.getString(status(trip.status()));
        int days = (int) ((start - today()) / DAY_MILLIS);
        if ("UPCOMING".equals(trip.status())) {
            return days > 0 ? context.getString(R.string.home_trip_upcoming, days) : context.getString(R.string.home_trip_today);
        }
        return context.getString(R.string.home_trip_ongoing, Math.max(1, 1 - days));
    }

    private static String dateLabel(Context context, AuthApiClient.Trip trip) {
        Long start = utcDay(trip.startDate());
        Long end = utcDay(trip.endDate());
        if (start == null || end == null) return context.getString(R.string.trip_dates_pick);
        SimpleDateFormat format = new SimpleDateFormat(context.getString(R.string.home_trip_date_format), Locale.KOREAN);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        int nights = (int) ((end - start) / DAY_MILLIS);
        String length = nights == 0 ? context.getString(R.string.trip_dates_same_day) : context.getString(R.string.trip_dates_nights, nights, nights + 1);
        return context.getString(R.string.home_trip_dates, format.format(new Date(start)), format.format(new Date(end)), length);
    }

    private static long today() {
        Calendar local = Calendar.getInstance();
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
        return utc.getTimeInMillis();
    }

    @Nullable private static Long utcDay(@Nullable String iso) {
        if (iso == null) return null;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date parsed = format.parse(iso);
            return parsed == null ? null : parsed.getTime();
        } catch (ParseException invalid) {
            return null;
        }
    }

    private static int status(String value) {
        return switch (value) {
            case "UPCOMING" -> R.string.trip_status_upcoming;
            case "PAST" -> R.string.trip_status_past;
            default -> R.string.trip_status_ongoing;
        };
    }
}
