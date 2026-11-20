package net.handsonovergeared.station;

import java.util.Locale;
import java.util.Random;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * The forging rhythm of Overgeared's anvil minigame, worked out the same way on the server and on every client that sees the anvil:
 * a marker runs to and fro along a bar of 1 to 100, and a strike lands well when the marker is in the good zone, best in the perfect
 * zone within it. Each strike makes the marker faster and the zones smaller, and moves them elsewhere.
 *
 * <p>Overgeared keeps this on the forging player's client alone. Here the station's data holds the settings, a seed and where the
 * marker was at the last strike, so that the zones after any number of strikes and the marker at any moment follow from the data:
 * everybody around sees the same bar, and the server never has to send the zones.
 */
public final class ForgeRhythm {
    public static final float BAR_MIN = 1.0F;
    public static final float BAR_MAX = 100.0F;
    private static final float LENGTH = BAR_MAX - BAR_MIN;

    /** As Overgeared places zones after a strike: tries, and how far a new place must lie from the old one. */
    private static final int MAX_PLACEMENT_ATTEMPTS = 30;
    private static final float MIN_CENTER_SHIFT = 5.0F;
    private static final float MIN_EDGE_SHIFT = 3.0F;

    /** A strike's grade, as the client judged it. */
    public enum Grade {
        MISS, GOOD, PERFECT;

        public static Grade of(int ordinal) {
            return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : MISS;
        }
    }

    /**
     * The minigame's settings for one difficulty, from Overgeared's server config.
     *
     * @param speed      bar units the marker moves per tick at first
     * @param increase   how much faster it gets per strike; Overgeared speeds up twice per hit
     * @param maxSpeed   the fastest it gets
     * @param size       the perfect zone's width at first; the good zone is three times as wide
     * @param shrink     what each strike multiplies the zones' width by
     * @param minPerfect the perfect zone never gets narrower than this
     */
    public record Settings(float speed, float increase, float maxSpeed, int size, float shrink, int minPerfect) {
        public void write(CompoundTag tag) {
            tag.putFloat("s_speed", speed);
            tag.putFloat("s_inc", increase);
            tag.putFloat("s_max", maxSpeed);
            tag.putInt("s_size", size);
            tag.putFloat("s_shrink", shrink);
            tag.putInt("s_min", minPerfect);
        }

        public static Settings read(CompoundTag tag) {
            return new Settings(tag.getFloat("s_speed"), tag.getFloat("s_inc"), tag.getFloat("s_max"), tag.getInt("s_size"),
                    tag.getFloat("s_shrink"), tag.getInt("s_min"));
        }

        /** The marker's speed after {@code strikes} strikes. */
        public float speedAfter(int strikes) {
            float value = speed;
            for (int i = 0; i < strikes * 2; i++) {
                value = Math.min(value + increase, maxSpeed);
            }
            return Math.max(value, 0.0F);
        }
    }

    /** The zones of the bar, in bar units. */
    public record Zones(float perfectStart, float perfectEnd, float goodStart, float goodEnd) {
        public Grade grade(float marker) {
            if (marker >= perfectStart && marker <= perfectEnd) {
                return Grade.PERFECT;
            }
            if (marker >= goodStart && marker <= goodEnd) {
                return Grade.GOOD;
            }
            return Grade.MISS;
        }
    }

    /** Where the marker is and which way it runs. */
    public record Marker(float position, boolean up) {
    }

    private ForgeRhythm() {
    }

    /** The zones after {@code strikes} strikes of a forging started with {@code seed}: Overgeared's own placement, replayed. */
    public static Zones zones(Settings settings, long seed, int strikes) {
        Random random = new Random(seed);
        int size = Math.max(1, settings.size());
        int perfectStart = (100 - size) / 2;
        int perfectEnd = (100 + size) / 2;
        int goodStart = Math.max((100 - size * 3) / 2, 1);
        int goodEnd = Math.min((100 + size * 3) / 2, 100);
        // A random centre for the first strike, 20 to 80.
        int center = (int) (20 + random.nextFloat() * 60);
        int halfPerfect = (perfectEnd - perfectStart) / 2;
        int halfGood = (goodEnd - goodStart) / 2;
        perfectStart = Mth.clamp(center - halfPerfect, 0, 100);
        perfectEnd = Mth.clamp(center + halfPerfect, 0, 100);
        goodStart = Mth.clamp(center - halfGood, 0, 100);
        goodEnd = Mth.clamp(center + halfGood, 0, 100);

        float perfectSize = perfectEnd - perfectStart;
        float goodSize = goodEnd - goodStart;
        for (int strike = 0; strike < strikes; strike++) {
            perfectSize = Math.max(settings.minPerfect(), perfectSize * settings.shrink());
            goodSize = Math.max(perfectSize * 3, goodSize * settings.shrink());
            float oldPerfectCenter = (perfectStart + perfectEnd) / 2.0F;
            float oldGoodCenter = (goodStart + goodEnd) / 2.0F;
            float halfP = perfectSize / 2;
            float halfG = goodSize / 2;
            for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
                // Towards the middle, but anywhere on the bar.
                float weighted = (float) Math.pow(random.nextFloat(), 1.5);
                float c = 50.0F + (weighted - 0.5F) * 100.0F;
                int pStart = Mth.clamp(Math.round(c - halfP), 0, 100);
                int pEnd = Mth.clamp(Math.round(c + halfP), 0, 100);
                int gStart = Mth.clamp(Math.round(c - halfG), 0, 100);
                int gEnd = Mth.clamp(Math.round(c + halfG), 0, 100);
                if (tooClose(pStart, pEnd, perfectStart, perfectEnd, oldPerfectCenter)
                        || tooClose(gStart, gEnd, goodStart, goodEnd, oldGoodCenter)) {
                    continue;
                }
                perfectStart = pStart;
                perfectEnd = pEnd;
                goodStart = gStart;
                goodEnd = gEnd;
                break;
            }
        }
        return new Zones(perfectStart, perfectEnd, goodStart, goodEnd);
    }

    private static boolean tooClose(int newStart, int newEnd, int oldStart, int oldEnd, float oldCenter) {
        return Math.abs((newStart + newEnd) / 2.0F - oldCenter) < MIN_CENTER_SHIFT
                || Math.abs(newStart - oldStart) < MIN_EDGE_SHIFT
                || Math.abs(newEnd - oldEnd) < MIN_EDGE_SHIFT;
    }

    /** The marker {@code ticks} after it stood at {@code from}, running at {@code speed} units a tick, bouncing off the bar's ends. */
    public static Marker marker(Marker from, float speed, float ticks) {
        float start = Mth.clamp(from.position(), BAR_MIN, BAR_MAX) - BAR_MIN;
        float unfolded = start + (from.up() ? 1.0F : -1.0F) * speed * Math.max(0.0F, ticks);
        float period = LENGTH * 2.0F;
        float m = unfolded % period;
        if (m < 0.0F) {
            m += period;
        }
        boolean rising = m < LENGTH;
        float position = rising ? m : period - m;
        boolean up = from.up() == rising;
        return new Marker(BAR_MIN + position, up);
    }

    /** Overgeared's final quality from how the strikes landed: a perfect counts whole, a good 0.6, a miss nothing. */
    public static String quality(int perfect, int good, int miss, double perfectScore, double expertScore, double wellScore) {
        int total = perfect + good + miss;
        float score = total > 0 ? (perfect + good * 0.6F) / total : 0.0F;
        if (score > perfectScore) {
            return "perfect";
        }
        if (score > expertScore) {
            return "expert";
        }
        if (score > wellScore) {
            return "well";
        }
        return "poor";
    }

    public static String normalized(String quality) {
        return quality == null ? "none" : quality.toLowerCase(Locale.ROOT);
    }
}
