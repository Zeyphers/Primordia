package dev.jsz.primordia.lab;

import java.util.List;

/**
 * The written half of the field guide: a naturalist's journal, not a manual.
 * <p>
 * Deliberately not documentation. A page that states the sequencer takes sixteen redstone has told
 * the player everything and left them nothing to find out — and the whole premise of the mod is
 * that the fauna was not designed and has to be investigated. So the guide withholds. It records
 * what the previous holder observed, in the order they came to understand it, and it hedges where
 * they were unsure.
 * <p>
 * Entries are <b>locked until the reader has earned them</b>. What is legible is a function of what
 * they have filed, so the book fills in as their own work advances — the first specimen opens the
 * page about first specimens, and the page on speciation stays sealed until they have actually
 * watched a lineage fork. Reading ahead is not possible, which is the point.
 */
public final class GuideChapters {

	private GuideChapters() {
	}

	/**
	 * What a reader must have done before an entry becomes legible.
	 * <p>
	 * Measured from the guide's own contents rather than from world state, so the test runs on the
	 * client with no packet — the book knows what it holds.
	 */
	public enum Unlock {
		/** Written in the hand of whoever owned the book before. Always readable. */
		ALWAYS,
		/** Anything at all filed. */
		FIRST_SPECIMEN,
		/** Three species on file — enough to notice they differ. */
		THREE_SPECIES,
		/** One species studied past a passing glance. */
		STUDIED,
		/** One species fully characterised. */
		MASTERED,
		/** Two species close enough to be kin, which is a fork seen from the outside. */
		FORK_SEEN;

		public boolean satisfied(GuideData data) {
			return switch (this) {
				case ALWAYS -> true;
				case FIRST_SPECIMEN -> data.specimensFiled() >= 1;
				case THREE_SPECIES -> data.speciesCount() >= 3;
				case STUDIED -> data.entries().stream()
						.anyMatch(e -> e.accuracy().atLeast(DecodeAccuracy.PARTIAL));
				case MASTERED -> data.entries().stream()
						.anyMatch(e -> e.accuracy() == DecodeAccuracy.COMPLETE);
				case FORK_SEEN -> {
					var nodes = Phylogeny.build(data.entries());
					yield nodes.stream().anyMatch(n -> n.depth() > 0);
				}
			};
		}

		/** The nudge shown in place of a sealed entry. Names the work, never the answer. */
		public String hint() {
			return switch (this) {
				case ALWAYS -> "";
				case FIRST_SPECIMEN -> "Sealed. Bring something back first.";
				case THREE_SPECIES -> "Sealed. Three kinds, at least, before this makes sense.";
				case STUDIED -> "Sealed. One creature, properly studied — not merely met.";
				case MASTERED -> "Sealed. Nothing here until you know one of them completely.";
				case FORK_SEEN -> "Sealed. You have not yet seen a bloodline split.";
			};
		}
	}

	/** One entry: a title, the body, and what the reader must have done to read it. */
	public record Chapter(String title, List<String> paragraphs, Unlock unlock) {
		public boolean unlocked(GuideData data) {
			return unlock.satisfied(data);
		}
	}

	/**
	 * A tab in the guide: a heading, the item drawn on its tab, and the entries filed under it.
	 * <p>
	 * The icon is named rather than held as an item so this class stays free of the registries —
	 * it is plain data, and a test can read it without booting Minecraft.
	 */
	public record Section(String title, String iconItemId, List<Chapter> chapters) {
	}

	private static final List<Chapter> ALL = List.of(
			new Chapter("A word before you begin", List.of(
					"If you are reading this, the book has outlived me. Good. It was never "
							+ "mine to keep.",
					"",
					"I came out here expecting animals. What I found does not breed true to "
							+ "any pattern I was taught. Two of a kind will share a build and "
							+ "a temper and still differ in every particular, as though each "
							+ "were assembled fresh from the same instructions by a careless "
							+ "hand.",
					"",
					"Do not trust a silhouette. Do not trust a colour. Bring something back "
							+ "and read it properly, or you have learned nothing but where it "
							+ "was standing."), Unlock.ALWAYS),

			new Chapter("Where to begin", List.of(
					"You will need three things, and none of them are difficult.",
					"",
					"A KIT to take the sample with. Iron, a glass bottle, a length of string. "
							+ "It goes dull with use; make a second before you need one.",
					"",
					"A LAB to read it in. Iron and glass around a furnace, with redstone and "
							+ "a comparator for the reckoning half. It wants feeding twice — "
							+ "something that burns for the reading, and redstone for the "
							+ "thinking. Give it only one and it will get halfway and stop.",
					"",
					"And this BOOK, kept on you. Whatever the lab prints will find its way in "
							+ "here on its own, and the paper is no loss.",
					"",
					"Then go and find something. That is the whole of it."), Unlock.ALWAYS),

			new Chapter("On approaching them", List.of(
					"They are not tame and they are not stupid.",
					"",
					"Some will let you walk up. Some will not, and you will know which only "
							+ "afterwards. The ones that stand their ground are rarely the "
							+ "ones you expected — size is a poor guide to temper, and I have "
							+ "been put on my back by something knee-high.",
					"",
					"Take what you need quickly. It hurts them, and they remember."),
					Unlock.ALWAYS),

			new Chapter("The first reading", List.of(
					"So you have brought one back and put it through the machines, and the "
							+ "answer was almost nothing. Good — that is the honest answer.",
					"",
					"A single specimen tells you what one animal was. It cannot tell you what "
							+ "its kind is. The machines will hedge, and the hedging is not a "
							+ "fault in them; there is genuinely nothing yet to compare "
							+ "against.",
					"",
					"Go back. Bring another of the same sort. The picture does not sharpen "
							+ "because the creature changed — it sharpens because you finally "
							+ "have two things to hold against each other."), Unlock.FIRST_SPECIMEN),

			new Chapter("What the colours mean", List.of(
					"I spent a season convinced the colouring was a code. It is not, or if "
							+ "it is I never broke it.",
					"",
					"What I can say: the earthy ones are common and the vivid ones are not, "
							+ "and the deep blues and violets I have only ever seen muted, "
							+ "never blazing. Whatever paints them seems able to make a rust "
							+ "or an ochre freely and a true blue only grudgingly.",
					"",
					"And once — twice — something that lit its own way through the dark. I "
							+ "have no explanation. If you find one, do not let it go "
							+ "unstudied."), Unlock.THREE_SPECIES),

			new Chapter("The valley keeps its own accounts", List.of(
					"Here is the thing that unsettled me most, and I want it written down "
							+ "plainly.",
					"",
					"I mapped a valley thick with grazers. I left. I came back after some "
							+ "weeks and the grazers were thin and something long-legged had "
							+ "moved through and was thin itself for want of them.",
					"",
					"Nothing was watching. Nothing needed to be. Whatever governs this place "
							+ "does its arithmetic whether or not there is anyone standing in "
							+ "the field to see the result."), Unlock.THREE_SPECIES),

			new Chapter("Sleep, hunger, and the sense to stop", List.of(
					"They eat when they are hungry and not otherwise. I have watched a "
							+ "predator walk through a herd without turning its head.",
					"",
					"They give up. A chase that is not going to end well simply ends, and "
							+ "both animals walk away. I find this more alarming than "
							+ "ferocity would be — ferocity is stupid and can be predicted.",
					"",
					"And they sleep, about half of each day, though not all at the same "
							+ "hours. Some of them keep the night. Learn which before you make "
							+ "camp."), Unlock.STUDIED),

			new Chapter("A note on the dead", List.of(
					"A kill is left where it falls, and it does not stay alone for long.",
					"",
					"I have sat out a whole night watching a carcass draw in three separate "
							+ "kinds that would not otherwise have met. If you want to see "
							+ "what shares a valley, find something that died in it.",
					"",
					"Fire takes them completely. I mention this because I once lost a "
							+ "specimen I had walked two days for, and I would rather you did "
							+ "not."), Unlock.STUDIED),

			new Chapter("The shape of a thing", List.of(
					"By now you will have noticed that no two of them are put together the "
							+ "same way, and yet they all work.",
					"",
					"Legs in twos or in fours or in eights. Necks like columns and necks that "
							+ "are barely a suggestion. Whatever is doing this is not choosing "
							+ "from a catalogue of animals — it is choosing from a catalogue of "
							+ "parts, and the walking is worked out afterwards.",
					"",
					"I have come to think of the archetypes not as species but as habits: "
							+ "postures the process falls into more often than chance would "
							+ "allow. There are perhaps ten. I never satisfied myself that the "
							+ "list was closed."), Unlock.MASTERED),

			new Chapter("Bloodlines", List.of(
					"Every one of them carries a mark that its parents carried. Follow the "
							+ "mark and you follow a line back as far as your records go.",
					"",
					"But the line is not fixed. Push a population hard enough, or leave it "
							+ "alone long enough, and it drifts — and at some point what comes "
							+ "out is far enough from what went in that the mark itself "
							+ "changes, and your notes no longer describe the animal in front "
							+ "of you.",
					"",
					"The first time this happened I assumed I had made an error. I had not. "
							+ "The creature had simply stopped being the thing I had studied, "
							+ "while I was studying it."), Unlock.FORK_SEEN),

			new Chapter("What I never settled", List.of(
					"I do not know what is doing this. I want that recorded, because "
							+ "everything else in this book is observation and this is the one "
							+ "place I have nothing.",
					"",
					"It is not random — random would not produce animals that can stand up. "
							+ "It is not designed either, or the designer is indifferent to "
							+ "whether its work survives the winter.",
					"",
					"Whatever it is, it is still running. The book you are holding is already "
							+ "out of date. Go and find out how."), Unlock.MASTERED)
	);

	/**
	 * The tabs, in reading order.
	 * <p>
	 * The two at the end are not written entries at all — the reference plates and the tree are
	 * generated from the reader's own records, which is the only part of this book that is
	 * certainly true.
	 */
	public static final List<Section> SECTIONS = List.of(
			new Section("Preface", "primordia:field_guide", List.of(ALL.get(0), ALL.get(1))),
			new Section("Fieldwork", "primordia:biopsy_kit",
					List.of(ALL.get(2), ALL.get(3), ALL.get(7))),
			new Section("Observations", "minecraft:bone", List.of(ALL.get(4), ALL.get(8))),
			new Section("The valley", "minecraft:grass_block", List.of(ALL.get(5), ALL.get(6))),
			new Section("Doubts", "minecraft:ender_eye", List.of(ALL.get(9), ALL.get(10))),
			new Section("Specimens", "primordia:genome_report", List.of()),
			new Section("Bloodlines", "minecraft:oak_sapling", List.of())
	);

	/** Index of the tab that lists filed species; it renders specimen plates rather than prose. */
	public static final int REFERENCE_TAB = 5;
	/** Index of the tab that draws the inferred family tree. */
	public static final int LINEAGE_TAB = 6;

	/** Every entry, flat, in the order they were written. */
	public static final List<Chapter> CHAPTERS = ALL;
}
