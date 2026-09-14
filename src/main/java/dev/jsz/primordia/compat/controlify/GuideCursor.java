package dev.jsz.primordia.compat.controlify;

import dev.isxander.controlify.api.vmousesnapping.SnapPoint;
import dev.isxander.controlify.virtualmouse.VirtualMouseHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.joml.Vector2d;

import java.util.List;

/**
 * Steps the virtual cursor between the things on a page rather than sliding it across them.
 * <p>
 * This is what makes the field guide read as a console interface instead of a mouse being mimed
 * with a thumbstick. The cursor is kept — everything the book can be told is already expressed as a
 * click or a hover somewhere in its own code, and throwing that away to build a parallel selection
 * model would mean two ideas of what is currently under the reader's attention — but the D-pad
 * moves it in jumps, from one target to the next, the way a focus ring moves.
 * <p>
 * Controlify has this, in {@code VirtualMouseHandler.snapInDirection}. It is not used because it
 * reads its targets from {@code ISnapBehaviour} on the screen itself, which would put a Controlify
 * interface in the signature of a class that has to load without Controlify present. Doing the
 * selection here needs the same geometry either way, and buys the one thing that matters on this
 * screen: the caller is told when a step found nothing, so it can pan the family tree instead of
 * the pointer stopping dead at the edge of a view that continues past it.
 */
final class GuideCursor {

	/**
	 * How far off the direction of travel a target may sit, as a multiple of how far along it sits.
	 * <p>
	 * Without a limit, pressing down from the last tab jumps to whatever happens to be nearest in
	 * raw distance, which on a wide page is regularly something off to one side — and a pointer
	 * that answers "down" by going sideways is worse than one that does not move. The same rule and
	 * roughly the same figure as Controlify's own directional snap, so the two feel alike.
	 */
	private static final double MAX_DEVIATION = 2.0;

	/** How much a sideways offset counts against a candidate, relative to distance travelled. */
	private static final double DEVIATION_COST = 4.0;

	/** Snap radius. Only matters to Controlify's own idea of "already on this point". */
	private static final int RANGE = 8;

	private GuideCursor() {
	}

	/**
	 * Moves the cursor to the nearest target in a direction.
	 *
	 * @param dx -1, 0 or 1
	 * @param dy -1, 0 or 1
	 * @return whether anything was found to move to
	 */
	static boolean step(VirtualMouseHandler vmouse, List<ScreenRectangle> targets, int dx, int dy) {
		if (targets.isEmpty()) return false;

		// Read at 1, which is where the cursor is heading rather than where it has drifted to. They
		// are the same while the stick is still; they are not while it is being pushed, and a step
		// taken from the lagging position steps from somewhere the reader has already left.
		Vector2d scale = guiScale();
		double fromX = vmouse.getCurrentX(1f) * scale.x;
		double fromY = vmouse.getCurrentY(1f) * scale.y;

		ScreenRectangle best = null;
		double bestCost = Double.MAX_VALUE;
		for (ScreenRectangle target : targets) {
			double toX = target.left() + target.width() / 2.0;
			double toY = target.top() + target.height() / 2.0;

			// Along the direction asked for, and across it. A step of zero is the cursor already
			// sitting on this target, which is not somewhere to move to.
			double along = (toX - fromX) * dx + (toY - fromY) * dy;
			double across = Math.abs((toX - fromX) * dy + (toY - fromY) * dx);
			if (along <= 1) continue;
			if (across > along * MAX_DEVIATION) continue;

			double cost = along + across * DEVIATION_COST;
			if (cost < bestCost) {
				bestCost = cost;
				best = target;
			}
		}
		if (best == null) return false;

		vmouse.snapToPoint(new SnapPoint(
				(int) (best.left() + best.width() / 2.0),
				(int) (best.top() + best.height() / 2.0),
				RANGE), scale);
		return true;
	}

	/**
	 * GUI pixels per window pixel.
	 * <p>
	 * Snap points are in the coordinates the screen lays itself out in, and the virtual mouse holds
	 * its position in the window's, so one of the two has to be converted and this is the factor
	 * both {@code snapToPoint} and the reads above are expressed in.
	 */
	private static Vector2d guiScale() {
		var window = Minecraft.getInstance().getWindow();
		return new Vector2d(
				(double) window.getGuiScaledWidth() / window.getScreenWidth(),
				(double) window.getGuiScaledHeight() / window.getScreenHeight());
	}
}
