package org.kman.srtctest.rtc;

import androidx.annotation.NonNull;

import java.util.Locale;

public class SimulcastLayer {
    public SimulcastLayer(@NonNull String name,
                          int width, int height,
                          int framesPerSecond,
                          int kilobitPerSecond) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.framesPerSecond = framesPerSecond;
        this.kilobitPerSecond = kilobitPerSecond;
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(Locale.US,
                "SimulcastLayer(name=%s, width=%d, height=%d, kilobitPerSecond=%d)",
                name, width, height, kilobitPerSecond);

    }

    @NonNull
    public final String name;
    public final int width;
    public final int height;
    public final int framesPerSecond;
    public final int kilobitPerSecond;
}
