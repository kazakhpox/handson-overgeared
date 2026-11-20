package net.handsonovergeared.furnace;

/** How long an Overgeared furnace burns and cooks, read from its block entity (mixed into each of them). */
public interface FurnaceTimes {
    int handson$burnTime();

    int handson$maxBurnTime();

    int handson$cookTime();

    int handson$cookTimeTotal();
}
