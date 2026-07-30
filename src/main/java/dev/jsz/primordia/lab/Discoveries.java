package dev.jsz.primordia.lab;

import dev.jsz.primordia.Primordia;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;

/**
 * Awards the advancements that mark a reader's progress.
 * <p>
 * Three of them cannot be expressed as a vanilla trigger — "saw a creature", "characterised a
 * bloodline completely", "recorded both sides of a fork" — so those are declared with the
 * {@code impossible} trigger and granted from here. The rest are ordinary inventory triggers and
 * need no code at all.
 * <p>
 * Granting is idempotent and cheap to re-attempt: the tracker refuses a criterion it already holds,
 * so callers can fire on every relevant tick without checking first.
 */
public final class Discoveries {

	/** The first creature laid eyes on. This is the nudge that starts everything else. */
	public static final Identifier SOMETHING_MOVES = Primordia.id("something_moves");
	/** A bloodline studied until it holds no more surprises. */
	public static final Identifier FULLY_CHARACTERISED = Primordia.id("fully_characterised");
	/** Two lineages on file that are demonstrably kin — a fork observed from the outside. */
	public static final Identifier WATCHED_IT_SPLIT = Primordia.id("watched_it_split");

	/** The single criterion every code-granted advancement declares. */
	private static final String CRITERION = "trigger";

	private Discoveries() {
	}

	public static void grant(ServerPlayer player, Identifier advancement) {
		AdvancementHolder entry = player.level().getServer().getAdvancements().get(advancement);
		if (entry == null) return;
		player.getAdvancements().award(entry, CRITERION);
	}

	/**
	 * Checks the milestones that depend on what a guide now contains, and awards any that have
	 * been reached. Called after a report is filed, which is the only moment they can change.
	 */
	public static void checkGuide(ServerPlayer player, GuideData data) {
		boolean mastered = data.entries().stream()
				.anyMatch(e -> e.accuracy() == DecodeAccuracy.COMPLETE);
		if (mastered) grant(player, FULLY_CHARACTERISED);

		boolean forked = Phylogeny.build(data.entries()).stream().anyMatch(n -> n.depth() > 0);
		if (forked) grant(player, WATCHED_IT_SPLIT);
	}
}
