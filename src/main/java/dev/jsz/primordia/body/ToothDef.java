package dev.jsz.primordia.body;

import org.joml.Vector3f;

/**
 * One tooth, as a spike rooted in the gum rather than as a lump of the body's field.
 * <p>
 * Teeth are deliberately <b>not</b> {@link SdfBlob}s. Everything in the signed distance field goes
 * through the smooth union that fairs a limb into a hip, which is exactly wrong here — it rounds a
 * tooth off and melts it into the jaw, so a mouth full of them reads as white lumps along the lip
 * rather than as teeth. They are also far finer than anything else the generator emits, and Surface
 * Nets cannot resolve a feature narrower than one sampling cell at all.
 * <p>
 * So they bypass the field completely: {@link dev.jsz.primordia.mesh.ToothMesher} emits their
 * geometry directly and rigidly to one bone, at whatever size they actually are, with edges that
 * stay sharp however coarsely the body around them happens to be meshed.
 * <p>
 * Note what is <i>not</i> stored: where the tooth ends. A tooth has to clear the gum, and how deep
 * the gum is depends on the skull's taper, the mandible's girth and how wide the jaw was grown —
 * estimating it from the bone axis left one whole row buried inside the lip on every creature.
 * The plan states the root, the direction and how far the point should stand proud of the flesh;
 * the mesher, which has the finished field in front of it, finds the surface and measures from
 * there.
 *
 * @param bone       index into {@link BodyPlan#bones} the tooth is rigidly pinned to — the skull
 *                   for an upper tooth, the mandible for a lower one, so the two rows part when
 *                   the mouth opens
 * @param root       origin inside the jaw, from which the tooth grows outward
 * @param direction  unit vector the tooth grows along
 * @param protrusion how far the point stands clear of the flesh
 * @param radius     half-width at the gum line
 * @param blunt      true for a grinder, chiselled off flat instead of coming to a point
 */
public record ToothDef(int bone, Vector3f root, Vector3f direction, float protrusion,
                       float radius, boolean blunt) {
}
