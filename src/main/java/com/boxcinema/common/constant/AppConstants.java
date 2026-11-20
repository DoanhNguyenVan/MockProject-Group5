package com.boxcinema.common.constant;

public final class AppConstants {

    private AppConstants() {
    }

    public static final int MAX_SEATS_PER_BOOKING = 8;
    public static final long DEFAULT_HOLD_DURATION_SECONDS = 600L;
    public static final long MIN_HOLD_DURATION_SECONDS = 60L;
    public static final long MAX_HOLD_DURATION_SECONDS = 900L;
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_NOW_SHOWING = "NOW_SHOWING";
    public static final String STATUS_COMING_SOON = "COMING_SOON";
}
