package dev.jsz.primordia.splice;

import dev.jsz.primordia.genome.Gene;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What a spliced locus actually does to a player. <b>This is the table.</b>
 * <p>
 * {@code MD/SPLICING.md} §10 names the risk this file exists to answer: {@code SPEED} and its
 * neighbours are ecology genes, not player stats. They mean something specific to
 * {@code EnergyBudget} and to the creature AI, and mapping them onto player attributes is a
 * translation — a bad one would make the field guide lie about what a splice does. So the
 * translation lives here, once, and the guide renders its rows rather than describing them in prose
 * of its own. If a number in the guide disagrees with what the player feels, exactly one file is
 * wrong.
 * <p>
 * <b>Every locus in a block appears here, including the ones that do nothing.</b> An inert locus is
 * listed with a null effect and an honest line, because Rule 2 says you adopt the whole block and
 * the player is entitled to see the whole block — silently omitting the parts with no mechanics
 * would make the splice screen look like a menu of benefits, which is the one thing it must not be.
 */
public final class SpliceEffects {

	private SpliceEffects() {
	}

	/** How a locus reaches the player: an attribute, or something the game has no attribute for. */
	public enum Kind {
		/** Applied as an {@link AttributeModifier}. */
		ATTRIBUTE,
		/** Food burned faster. Read by {@link PlayerSplices#exhaustionMultiplier}. */
		EXHAUSTION,
		/** The player emits light. Read by the client's glow path. */
		GLOW,
		/** Carried for flavour and for the guide to show; no mechanics behind it yet. */
		INERT
	}

	/**
	 * One row of the table.
	 *
	 * @param gene      the locus
	 * @param kind      how it reaches the player
	 * @param attribute supplies the attribute it modifies, or null off {@link Kind#ATTRIBUTE}.
	 *                  <b>Deferred</b>: {@link Attributes} touches the registries, and the table is
	 *                  also the thing the field guide and the tests read to describe a block. Held
	 *                  directly, merely asking what a splice <i>says</i> it does would drag the game
	 *                  registries in behind it
	 * @param operation how the modifier combines, or null off {@link Kind#ATTRIBUTE}
	 * @param atFull    the magnitude when the locus reads 1.0 (or, when {@code centred}, the
	 *                  magnitude at either extreme against a wild-type midpoint of 0.5)
	 * @param centred   true for a locus with no good end, only a <i>matching</i> one: a climate
	 *                  preference is an adaptation to somewhere, so a donor from the wrong somewhere
	 *                  is a real cost rather than merely a smaller benefit
	 * @param goodWhenPositive whether a positive magnitude is what the player wants. False for rows
	 *                  where the number going up is the drawback, so the table can state a cost
	 *                  plainly instead of smuggling the sign into the magnitude
	 * @param unit      how to render the magnitude in the guide
	 * @param summary   what the locus does, named as flatly as possible. Deliberately not prose:
	 *                  this is read beside a signed number in a tooltip a player is scanning, and
	 *                  "Moves as it moved" made them work out that it meant movement speed. Name the
	 *                  stat, let the number say the rest
	 */
	public record Row(Gene gene, Kind kind, java.util.function.Supplier<Holder<Attribute>> attribute,
	                  AttributeModifier.Operation operation, double atFull, boolean centred,
	                  boolean goodWhenPositive, String unit, String summary) {

		/** The magnitude this locus produces at the value the player actually carries. */
		public double magnitude(float value) {
			return centred ? atFull * (value - 0.5) * 2.0 : atFull * value;
		}

		/**
		 * Whether this row, at this value, is something the player wants.
		 * <p>
		 * Asked of a value rather than of the row, because a two-sided locus is a gain or a cost
		 * depending entirely on which animal it came from - that is the point of {@link #centred}.
		 * And not simply the sign of the magnitude either: exhaustion is stored positive and is a
		 * cost, so reading the sign alone painted "+80% food burn" in the same green as "+25% speed"
		 * and turned the one honest part of the splice screen into an advertisement.
		 */
		public boolean beneficial(float value) {
			if (kind == Kind.INERT) return true;
			return (magnitude(value) >= 0) == goodWhenPositive;
		}

		/** True for a row that can ever be a cost, whichever donor happens to supply it. */
		public boolean canCost() {
			return centred || !goodWhenPositive || atFull < 0;
		}
	}

	private static final Map<Gene, Row> ROWS = new EnumMap<>(Gene.class);

	private static void attribute(Gene gene, java.util.function.Supplier<Holder<Attribute>> attribute,
	                              AttributeModifier.Operation operation, double atFull,
	                              String unit, String summary) {
		ROWS.put(gene, new Row(gene, Kind.ATTRIBUTE, attribute, operation, atFull,
				false, true, unit, summary));
	}

	/** A locus with no good end, only a matching one. See {@link Row#centred}. */
	private static void centred(Gene gene, java.util.function.Supplier<Holder<Attribute>> attribute,
	                            AttributeModifier.Operation operation, double atFull,
	                            boolean goodWhenPositive, String unit, String summary) {
		ROWS.put(gene, new Row(gene, Kind.ATTRIBUTE, attribute, operation, atFull,
				true, goodWhenPositive, unit, summary));
	}

	private static void special(Gene gene, Kind kind, double atFull, boolean goodWhenPositive,
	                            String unit, String summary) {
		ROWS.put(gene, new Row(gene, kind, null, null, atFull, false, goodWhenPositive, unit, summary));
	}

	static {
		// ---- Physiology ----------------------------------------------------
		// The block that carries its own cost: a fast animal is an expensive animal to run, and
		// METABOLISM is exactly the locus EnergyBudget already charges it on.
		attribute(Gene.SPEED, () -> Attributes.MOVEMENT_SPEED,
				AttributeModifier.Operation.ADD_MULTIPLIED_BASE, 0.25, "%",
				"Movement speed");
		attribute(Gene.STAMINA, () -> Attributes.MAX_HEALTH,
				AttributeModifier.Operation.ADD_VALUE, 4.0, " hp",
				"Max health");
		special(Gene.METABOLISM, Kind.EXHAUSTION, 0.80, false, "%",
				"Food drain");
		special(Gene.DIET, Kind.INERT, 0, true, "",
				"No effect");

		// ---- Disposition ---------------------------------------------------
		// The interesting package: FEAR and TERRITORIALITY pull the same attribute in opposite
		// directions, so a bold animal and a skittish one give genuinely different splices out of
		// what looks on the guide like the same branch.
		attribute(Gene.AGGRESSION, () -> Attributes.ATTACK_DAMAGE,
				AttributeModifier.Operation.ADD_VALUE, 3.0, "",
				"Attack damage");
		attribute(Gene.TERRITORIALITY, () -> Attributes.KNOCKBACK_RESISTANCE,
				AttributeModifier.Operation.ADD_VALUE, 0.30, "",
				"Knockback resistance");
		attribute(Gene.FEAR, () -> Attributes.KNOCKBACK_RESISTANCE,
				AttributeModifier.Operation.ADD_VALUE, -0.40, "",
				"Knockback resistance");
		attribute(Gene.CURIOSITY, () -> Attributes.LUCK,
				AttributeModifier.Operation.ADD_VALUE, 2.0, "",
				"Luck");
		attribute(Gene.SOCIABILITY, () -> Attributes.ENTITY_INTERACTION_RANGE,
				AttributeModifier.Operation.ADD_VALUE, 1.0, " m",
				"Interaction range");

		// ---- Climate -------------------------------------------------------
		attribute(Gene.ARMOR, () -> Attributes.ARMOR,
				AttributeModifier.Operation.ADD_VALUE, 6.0, "",
				"Armour");
		// Two-sided, because a climate preference is an adaptation to somewhere rather than a
		// quality. A donor off a hot region shrugs off fire; one off a cold region catches worse
		// than wild type, and the player who wanted its armour takes that with it. This is where
		// Climate gets a real cost instead of being three benefits in a coat.
		centred(Gene.TEMP_PREFERENCE, () -> Attributes.BURNING_TIME,
				AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, -0.50, false, "%",
				"Time on fire");
		centred(Gene.HUMIDITY_PREFERENCE, () -> Attributes.WATER_MOVEMENT_EFFICIENCY,
				AttributeModifier.Operation.ADD_VALUE, 0.50, true, "",
				"Swim speed");

		// ---- Colour --------------------------------------------------------
		// Deliberately empty of mechanics. This is the tutorial branch: it exists so the player
		// learns the mechanic on something that cannot hurt them, and wearing your own creature's
		// colours is a real reward for a first Complete without being a stat.
		for (Gene gene : SpliceBranch.COLOUR.genes()) {
			special(gene, Kind.INERT, 0, true, "", "Appearance only");
		}

		// ---- Light ---------------------------------------------------------
		special(Gene.BIOLUMINESCENCE, Kind.GLOW, 1.0, true, "",
				"Emits light");
		special(Gene.GLOW_REGION, Kind.INERT, 0, true, "", "Glow location");
		special(Gene.GLOW_HUE, Kind.INERT, 0, true, "", "Glow colour");

		// ---- Habit ---------------------------------------------------------
		attribute(Gene.BURROWING, () -> Attributes.BLOCK_BREAK_SPEED,
				AttributeModifier.Operation.ADD_MULTIPLIED_BASE, 0.40, "%",
				"Mining speed");
		attribute(Gene.NEST_BUILDING, () -> Attributes.BLOCK_INTERACTION_RANGE,
				AttributeModifier.Operation.ADD_VALUE, 1.0, " m",
				"Block reach");
		special(Gene.GRAZING_IMPACT, Kind.EXHAUSTION, 0.40, false, "%",
				"Food drain");
	}

	/** The row for a locus, or null where the locus is in no block. */
	public static Row rowFor(Gene gene) {
		return ROWS.get(gene);
	}

	/** Every row in a branch, in enum order, inert ones included. */
	public static List<Row> rowsFor(SpliceBranch branch) {
		List<Row> out = new ArrayList<>();
		for (Gene gene : branch.genes()) {
			Row row = ROWS.get(gene);
			if (row != null) out.add(row);
		}
		return out;
	}

	/**
	 * Renders a magnitude the way the guide shows it.
	 * <p>
	 * Percent rows are stored as fractions because that is what {@link AttributeModifier} wants, and
	 * shown as percentages because that is what a player reads, so the conversion belongs here
	 * rather than at the screen — the screen quoting this table is the whole point of the file.
	 */
	public static String render(Row row, float value) {
		double magnitude = row.magnitude(value);
		if (row.kind() == Kind.INERT) return "—";
		if (row.kind() == Kind.GLOW) return value >= 0.5f ? "lit" : "faint";
		if ("%".equals(row.unit())) {
			return String.format("%+.0f%%", magnitude * 100.0);
		}
		return String.format("%+.2f%s", magnitude, row.unit());
	}
}
