package dev.jsz.primordia.ecology.region;

/**
 * Read access to the regions around one region — everything the simulation needs to know about the
 * rest of the world.
 * <p>
 * Exists so that {@link RegionSimulation} and {@link RegionFounder} do not depend on
 * {@link RegionLedger}, which is a {@code PersistentState} and therefore drags a live server world
 * behind it. The population model is arithmetic and should be testable as arithmetic: given a set
 * of records and a number of steps, the result is a set of records, and no part of that needs
 * Minecraft to be running.
 * <p>
 * Migration and inherited founders are the only two things that ever look outside their own region,
 * and both only ever read.
 */
public interface RegionNeighbourhood {
	/** The record for this region, or null if the world has never visited it. */
	RegionRecord existing(RegionPos pos);
}
