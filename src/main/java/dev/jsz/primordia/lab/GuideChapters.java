package dev.jsz.primordia.lab;

import java.util.List;

/**
 * The written half of the field guide: a naturalist's journal, not a manual.
 * <p>
 * Deliberately not documentation. A page that states the sequencer takes sixteen redstone has told
 * the player everything and left them nothing to find out — and the whole premise of the mod is
 * that the fauna was not designed and has to be investigated. So the guide withholds. It records
 * what the previous holder observed, in the order they came to understand it, and it hedges where
 * they were unsure. Recipes are deliberately absent: those arrive through advancements, and a
 * shopping list here would just repeat what the player already has.
 * <p>
 * Entries are <b>locked until the reader has earned them</b>. What is legible is a function of what
 * they have filed, so the book fills in as their own work advances — the first specimen opens the
 * page about first specimens, and the page on speciation stays sealed until they have actually
 * watched a lineage fork. Reading ahead is not possible, which is the point.
 * <p>
 * Tabs follow the arc of understanding rather than loose topics — Preface, then the practical craft
 * of the field, then what the animals do, then what happens whether or not anyone is watching, then
 * what the writer never worked out. Within a tab, unlocks are non-decreasing: a later page never
 * opens before an earlier one in the same tab.
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
				case THREE_SPECIES -> "Sealed. Three kinds at least, or none of this will mean anything.";
				case STUDIED -> "Sealed. One creature properly studied, not merely met.";
				case MASTERED -> "Sealed. Not until you know one of them completely.";
				case FORK_SEEN -> "Sealed. You have not yet watched a bloodline divide.";
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

	// ---- Preface ------------------------------------------------------------

	private static final List<Chapter> PREFACE = List.of(
			new Chapter("A word before you begin", List.of(
					"If you are reading this, the book has outlived me, which is as it should "
							+ "be. It was never mine to keep.",
					"",
					"I came out expecting animals. I had a naturalist's training and I expected "
							+ "it to be enough: that I should watch a thing for a season, learn "
							+ "the habits of its kind, and afterwards be able to say what that "
							+ "kind did and did not do. That is not how this place works. Two "
							+ "creatures of the same sort will share a build and a temper and "
							+ "still differ in every particular, as though each had been "
							+ "assembled fresh from the same instructions by a hand that was not "
							+ "attending to the work.",
					"",
					"So I stopped trusting my eyes. A silhouette will tell you very little and "
							+ "a colour will tell you less than that. Bring the animal back, or a "
							+ "piece of it, and read it properly. Otherwise you have learned "
							+ "nothing except where it happened to be standing."), Unlock.ALWAYS),

			new Chapter("How this book fills itself", List.of(
					"You will notice that most of it is shut.",
					"",
					"That is deliberate, and it is my doing. The pages open as you earn them, "
							+ "and they stay closed until then. I did try the other arrangement. "
							+ "In my first year I kept everything I had written where I could "
							+ "reach it, and I read ahead constantly, and it did me no good "
							+ "whatever: I had nothing yet to hang the words on, so I hung them "
							+ "on my own assumptions and had to take them down again later at "
							+ "some cost.",
					"",
					"The rest looks after itself. Whatever the lab prints finds its way in "
							+ "here without my asking, and the drawings at the back are not mine "
							+ "at all. They are yours, and they will be better than mine, because "
							+ "you will have more of them.",
					"",
					"What I have written is not instruction. It is a record of the things I "
							+ "got wrong, in roughly the order I got them wrong, which is the "
							+ "only order that turned out to be any use."), Unlock.ALWAYS)
	);

	// ---- In the field ---------------------------------------------------------

	private static final List<Chapter> IN_THE_FIELD = List.of(
			new Chapter("On approaching them", List.of(
					"They are not tame, and they are not stupid.",
					"",
					"Some will stand and let you walk up to them. Others will not, and in my "
							+ "experience you learn which sort you are dealing with only "
							+ "afterwards. Size is a poor guide to it. I have walked unmolested "
							+ "through a herd of things twice my height, and I have been put "
							+ "flat on my back by an animal no higher than my knee, and I could "
							+ "not have told you beforehand which of the two it would be.",
					"",
					"Take your sample quickly and go. It hurts them, and they remember it."),
					Unlock.ALWAYS),

			new Chapter("The first reading", List.of(
					"So you have brought one back, put it through the machines, and got "
							+ "almost nothing for your trouble. That is the honest answer, and "
							+ "you should be glad the machines are willing to give it rather than "
							+ "invent something tidier.",
					"",
					"One specimen tells you what one animal was. It cannot tell you what its "
							+ "kind is, because a kind is not a thing you can carry home in a "
							+ "bottle. The report will hedge wherever it is able to. There is "
							+ "genuinely nothing yet to hold the creature against.",
					"",
					"Go back out and bring another of the same sort. The picture does not "
							+ "sharpen because the second animal is any clearer than the first. "
							+ "It sharpens because two of them, set side by side, begin to show "
							+ "you which of their differences are worth attending to.",
					"",
					"One practical matter, since it cost me a fortnight. The machine wants "
							+ "feeding twice over: something that burns, for the reading, and "
							+ "redstone, for the reckoning. Give it only the one and it will work "
							+ "halfway through and then sit there, and you will think you have "
							+ "built it wrong."), Unlock.FIRST_SPECIMEN),

			new Chapter("A note on the dead", List.of(
					"A kill is left where it falls, and it does not stay alone for long.",
					"",
					"I once sat out an entire night beside a carcass and counted three "
							+ "separate kinds coming to it that I had never seen within a mile of "
							+ "one another. If you want to know what shares a valley, do not go "
							+ "looking for the living. Find something that died there and wait.",
					"",
					"One warning, which I give you because I learned it the expensive way. "
							+ "Fire takes them completely and leaves you nothing to read. I lost "
							+ "a specimen I had carried two days that way, and I would rather you "
							+ "did not repeat the lesson."), Unlock.STUDIED)
	);

	// ---- Habits ---------------------------------------------------------------

	private static final List<Chapter> HABITS = List.of(
			new Chapter("Hunger, and the sense to stop", List.of(
					"They eat when they are hungry and not otherwise. I have watched a "
							+ "predator walk the whole length of a herd without so much as "
							+ "turning its head, and watched what I believe was the same animal, "
							+ "some days later, take one down inside a minute.",
					"",
					"They also give up. A chase that is not going to end well simply ends, "
							+ "and the two of them walk off in opposite directions with no "
							+ "apparent ill feeling on either side.",
					"",
					"I find this more disquieting than ferocity would be. Ferocity is "
							+ "stupid, and a stupid thing can be predicted. Something that knows "
							+ "when to stop is doing a sum, and I never learned what it was "
							+ "counting."), Unlock.THREE_SPECIES),

			new Chapter("Which of them keep the night", List.of(
					"They sleep about half of each day. Not all of them keep the same "
							+ "hours, and a good number keep the night instead and are abroad "
							+ "while you are not.",
					"",
					"This is worth more of your attention than it sounds. Twice I chose a "
							+ "campsite in daylight on the evidence of an empty meadow, and "
							+ "twice I learned in the small hours what the meadow was for.",
					"",
					"Watch a place at both ends of the day before you decide anything about "
							+ "it."), Unlock.STUDIED)
	);

	// ---- The valley -------------------------------------------------------

	private static final List<Chapter> THE_VALLEY = List.of(
			new Chapter("The valley keeps its own accounts", List.of(
					"This is the observation that unsettled me most, and I want it set down "
							+ "plainly rather than dressed up.",
					"",
					"In my second year I mapped a valley thick with grazers. I counted them "
							+ "as carefully as I knew how, and I was pleased with the count. "
							+ "Then I was called away and did not come back for some weeks. When "
							+ "I did, the grazers were thin on the ground, and a long-legged "
							+ "thing I had never seen before had moved through and was itself "
							+ "thin for want of them.",
					"",
					"None of it required a witness. The herd did not wait for my return "
							+ "before it declined, and the animal that thinned it did not wait "
							+ "to be observed before it began to starve in its turn. Whatever "
							+ "governs this place does its arithmetic in an empty field and "
							+ "hands you the result when you happen to walk back into it."),
					Unlock.THREE_SPECIES),

			new Chapter("The mark they carry", List.of(
					"Every one of them carries a mark its parents carried before it. Follow "
							+ "the mark and you follow a line backwards as far as your own "
							+ "records will take you, which is not far, but it is more than "
							+ "nothing.",
					"",
					"The line does not hold still. Press a population hard enough, or leave "
							+ "it to itself long enough, and it drifts. At some point what comes "
							+ "out of the valley is far enough from what went into it that the "
							+ "mark itself changes, and the notes you took in good faith no "
							+ "longer describe the animal standing in front of you.",
					"",
					"The first time this happened I spent three days hunting for my error. "
							+ "There was no error. The creature had stopped being the thing I "
							+ "was studying, and it had done so while I was studying it."),
					Unlock.FORK_SEEN)
	);

	// ---- Doubts -----------------------------------------------------------

	private static final List<Chapter> DOUBTS = List.of(
			new Chapter("What the colours mean", List.of(
					"I spent the better part of a season convinced the colouring was a "
							+ "code, and that if I catalogued enough of it I should be able to "
							+ "read an animal's habits off its hide. I was wrong. Or I was right "
							+ "and never broke it, which from where you are sitting comes to the "
							+ "same thing.",
					"",
					"What I can say with any confidence is this. The earthy colours are "
							+ "common and the vivid ones are not. The deep blues and the violets "
							+ "I have only ever seen muted, never blazing, as though whatever "
							+ "paints these creatures can mix a rust or an ochre freely and "
							+ "arrives at a true blue only under protest.",
					"",
					"Once, and then a second time, I have seen one that carried its own "
							+ "light through the dark. I have no explanation for it and I will "
							+ "not pretend to one. If you find such a thing, do not let it go "
							+ "unstudied on my account."), Unlock.THREE_SPECIES),

			new Chapter("The shape of a thing", List.of(
					"By now you will have noticed that no two of them are put together the "
							+ "same way, and that all of them work regardless.",
					"",
					"Legs in twos, in fours, in eights. Necks like columns, and necks that "
							+ "are barely a suggestion of a neck. Whatever is doing this is "
							+ "plainly not working from a catalogue of animals. It is working "
							+ "from a catalogue of parts, and the question of how the finished "
							+ "creature is to walk appears to be settled afterwards, by trial, "
							+ "and now and then not settled at all.",
					"",
					"I have come to think of the archetypes not as species but as habits: "
							+ "postures the process falls into more often than chance alone "
							+ "would account for. I make it about ten. I was never able to "
							+ "satisfy myself that the list was closed, and I should not be at "
							+ "all surprised to hear you had turned up an eleventh."),
					Unlock.MASTERED),

			new Chapter("What I never settled", List.of(
					"I do not know what is doing this. I want that written in my own hand, "
							+ "because everything else in this book is something I saw, and "
							+ "this is the one place where I have nothing to give you.",
					"",
					"It is not random. Random does not produce an animal that can stand up, "
							+ "and stand they do, in every arrangement of legs I have described "
							+ "to you. Nor is it designed, or if it is, the designer is "
							+ "indifferent to whether the work survives its first winter, which "
							+ "is not what I was taught the word to mean.",
					"",
					"What I am sure of is that it has not stopped. Whatever process made "
							+ "these creatures was still running on the day I closed this book, "
							+ "and has gone on running since. Everything written here is "
							+ "therefore already somewhat out of date, this sentence included. "
							+ "You will have to go out and find how much."), Unlock.MASTERED)
	);

	/**
	 * The tabs, in reading order.
	 * <p>
	 * The two at the end are not written entries at all — the reference plates and the tree are
	 * generated from the reader's own records, which is the only part of this book that is
	 * certainly true.
	 */
	public static final List<Section> SECTIONS = List.of(
			new Section("Preface", "primordia:field_guide", PREFACE),
			new Section("In the field", "primordia:biopsy_kit", IN_THE_FIELD),
			new Section("Habits", "minecraft:clock", HABITS),
			new Section("The valley", "minecraft:grass_block", THE_VALLEY),
			new Section("Doubts", "minecraft:ender_eye", DOUBTS),
			new Section("Specimens", "primordia:genome_report", List.of()),
			new Section("Bloodlines", "minecraft:oak_sapling", List.of())
	);

	/** Index of the tab that lists filed species; it renders specimen plates rather than prose. */
	public static final int REFERENCE_TAB = 5;
	/** Index of the tab that draws the inferred family tree. */
	public static final int LINEAGE_TAB = 6;

	/** Every entry, flat, in the order they were written. */
	public static final List<Chapter> CHAPTERS = List.of(
			PREFACE.get(0), PREFACE.get(1),
			IN_THE_FIELD.get(0), IN_THE_FIELD.get(1), IN_THE_FIELD.get(2),
			HABITS.get(0), HABITS.get(1),
			THE_VALLEY.get(0), THE_VALLEY.get(1),
			DOUBTS.get(0), DOUBTS.get(1), DOUBTS.get(2)
	);
}
