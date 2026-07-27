package dev.jsz.primordia.body;

/**
 * Semantic tag on a piece of geometry. The mesher uses it to paint vertex colours
 * (eyes are not the same colour as flanks) and later milestones use it for hit
 * regions — a bite lands on {@link #JAW}, armour applies to {@link #PLATE}.
 */
public enum Feature {
	BODY,
	HEAD,
	JAW,
	EYE,
	LIMB,
	FOOT,
	TAIL,
	PLATE,
	CLAWS,
	HAND,
	SPINE,
	HAIR,
	EYE_STALK
}
