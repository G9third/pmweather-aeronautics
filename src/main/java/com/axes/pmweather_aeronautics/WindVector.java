package com.axes.pmweather_aeronautics;

/** Three-dimensional PMWeather wind in the mod's mph-style units. */
public record WindVector(double x, double y, double z) {
    public static final WindVector ZERO = new WindVector(0.0, 0.0, 0.0);

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
