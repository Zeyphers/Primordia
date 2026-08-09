package dev.jsz.primordia.editor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jsz.primordia.anim.AnimationContext;
import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.SkeletonPlan;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.MeshData;
import dev.jsz.primordia.mesh.SkinBinder;
import dev.jsz.primordia.skeleton.Skeleton;
import dev.jsz.primordia.util.MathX;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;

/**
 * Localhost viewer for the creature pipeline.
 * <p>
 * <b>The browser draws; this decides.</b> Every number that determines what a creature looks like —
 * the body plan, the signed distance field, the extraction, the vertex colours, the skin weights,
 * the bind-pose skeleton — is computed here by the same classes the game calls, and shipped to the
 * page as finished arrays. The page owns the camera, the pose and the gizmos, and nothing else.
 * <p>
 * That split is the whole point, and it is why the previous editor was deleted: it was a Three.js
 * genome editor that reimplemented the generator in JavaScript, so it agreed with the game exactly
 * until the day either side changed. Anything the page cannot obtain from this server it is not
 * allowed to invent.
 * <p>
 * Ships in the jar so {@code /primordia editor} can start it, but it is <b>inert until asked for</b>:
 * nothing binds a port until the command is run, and the listener is bound to {@code 127.0.0.1} so it
 * is unreachable from anywhere but the machine running the game.
 * <pre>
 * /primordia editor                                   # from in game
 * ~/dev/tools/gradle-9.6.1/bin/gradle editor          # standalone, no Minecraft
 * </pre>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /} — the page itself, read from disk on every request so editing the HTML needs
 *       no rebuild.</li>
 *   <li>{@code GET /api/meta} — archetype names, gene names and their defaults; lets the page build
 *       its controls from the real {@link Gene} enum rather than a copy that can rot.</li>
 *   <li>{@code GET /api/bake} — bakes one creature and returns geometry, skin weights and the
 *       bind-pose skeleton.</li>
 * </ul>
 */
public final class EditorServer {

	private static final int PORT = 8090;

	/**
	 * Serialises every bake. {@link MeshBaker#setVoxelSize} and {@link MeshBaker#setGradientWeight}
	 * are process-wide statics — the game pushes them in from its settings screen — so two requests
	 * baking at once would read each other's settings.
	 */
	private static final Object BAKE_LOCK = new Object();

	private EditorServer() {
	}

	/** The running instance, or null. One per process; the command starts it on demand. */
	private static volatile HttpServer running;

	/** Standalone entry point for the Gradle {@code editor} task. */
	public static void main(String[] args) throws IOException {
		start();
	}

	/** @return the URL to open, whether this call started the server or found it already up. */
	public static synchronized String start() throws IOException {
		if (running != null) return url();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
		server.createContext("/api/meta", EditorServer::handleMeta);
		server.createContext("/api/bake", EditorServer::handleBake);
		server.createContext("/api/solve", EditorServer::handleSolve);
		server.createContext("/api/reach", EditorServer::handleReach);
		server.createContext("/api/anim", EditorServer::handleAnim);
		server.createContext("/assets/", EditorServer::handleAsset);
		server.createContext("/", EditorServer::handlePage);
		// Single thread: bakes are serialised anyway (see BAKE_LOCK) and this keeps the ordering of
		// rapid slider drags predictable rather than letting a stale bake land after a fresh one.
		server.setExecutor(Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "primordia-editor");
			// Daemon: inside the game this thread must never be the reason the JVM refuses to exit.
			t.setDaemon(true);
			return t;
		}));
		server.start();
		running = server;
		System.out.println("Primordia editor on " + url());
		return url();
	}

	public static synchronized void stop() {
		if (running == null) return;
		running.stop(0);
		running = null;
	}

	public static boolean isRunning() {
		return running != null;
	}

	public static String url() {
		return "http://127.0.0.1:" + PORT + "/";
	}

	// ------------------------------------------------------------------ routes

	private static void handlePage(HttpExchange ex) throws IOException {
		byte[] page = resource("index.html");
		if (page == null) {
			send(ex, 404, "text/plain", "editor page missing".getBytes(StandardCharsets.UTF_8));
			return;
		}
		send(ex, 200, "text/html; charset=utf-8", page);
	}

	/**
	 * Reads an editor file, preferring the working copy on disk and falling back to the one baked
	 * into the jar.
	 * <p>
	 * The disk path is what makes the tool worth using while developing it: edit the HTML, hit
	 * reload, no rebuild and no restart. It only exists when the game is being run out of the
	 * project directory, which is exactly when that matters. Everywhere else — a real install, where
	 * there is no {@code src/} — the classpath copy is the only one and is used silently.
	 */
	private static byte[] resource(String name) throws IOException {
		Path onDisk = Path.of("src/main/resources/editor", name);
		if (Files.isRegularFile(onDisk)) return Files.readAllBytes(onDisk);
		try (java.io.InputStream in =
				     EditorServer.class.getResourceAsStream("/editor/" + name)) {
			return in == null ? null : in.readAllBytes();
		}
	}

	/**
	 * Static files beside the page — the Monocraft face and the logo. Served from disk like the page
	 * itself, so dropping a file in the assets folder is all it takes.
	 */
	private static void handleAsset(HttpExchange ex) throws IOException {
		String name = ex.getRequestURI().getPath().substring("/assets/".length());
		// Nothing but a plain filename: no traversal out of the assets folder.
		if (name.isEmpty() || name.contains("/") || name.contains("..")) {
			send(ex, 400, "text/plain", "bad asset".getBytes(StandardCharsets.UTF_8));
			return;
		}
		byte[] body = resource("assets/" + name);
		if (body == null) {
			send(ex, 404, "text/plain", ("missing " + name).getBytes(StandardCharsets.UTF_8));
			return;
		}
		String type = name.endsWith(".ttf") ? "font/ttf"
				: name.endsWith(".png") ? "image/png"
				: "application/octet-stream";
		ex.getResponseHeaders().add("Content-Type", type);
		ex.getResponseHeaders().add("Cache-Control", "max-age=86400");
		ex.sendResponseHeaders(200, body.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(body);
		}
	}

	private static void handleMeta(HttpExchange ex) throws IOException {
		StringBuilder b = new StringBuilder(4096);
		b.append("{\"archetypes\":[");
		Archetype[] archetypes = Archetype.values();
		for (int i = 0; i < archetypes.length; i++) {
			if (i > 0) b.append(',');
			b.append('"').append(archetypes[i].name()).append('"');
		}
		b.append("],\"genes\":[");
		Gene[] genes = Gene.values();
		for (int i = 0; i < genes.length; i++) {
			if (i > 0) b.append(',');
			b.append("{\"name\":\"").append(genes[i].name()).append("\"}");
		}
		b.append("],\"tiers\":[\"NEAR\",\"MID\",\"FAR\",\"DISTANT\"]}");
		send(ex, 200, "application/json", b.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static void handleBake(HttpExchange ex) throws IOException {
		try {
			Map<String, String> q = query(ex);
			long seed = parseLong(q.get("seed"), 1L);
			int tier = clamp(parseInt(q.get("tier"), LodTier.NEAR), 0, LodTier.COUNT - 1);
			float voxel = parseFloat(q.get("voxel"), 0f);
			float smoothing = clamp(parseFloat(q.get("smoothing"), 0.75f), 0f, 1f);
			boolean skeleton = "1".equals(q.get("skeleton"));

			Genome genome = resolveGenome(q, seed);

			BodyPlan plan;
			MeshData mesh;
			float previousVoxel;
			float previousSmoothing;
			synchronized (BAKE_LOCK) {
				previousVoxel = MeshBaker.voxelSize();
				previousSmoothing = MeshBaker.gradientWeight();
				try {
					MeshBaker.setVoxelSize(voxel);
					MeshBaker.setGradientWeight(smoothing);
					plan = BodyPlanBuilder.build(genome);
					// The remains of this exact animal, through the exact pipeline the game uses.
					// Everything the page then draws — mesh, bones, joints, blend groups — is read
					// off the plan that was baked, so the bones on screen are the ones that would
					// be lying in the world rather than an illustration of them.
					if (skeleton) plan = SkeletonPlan.of(plan);
					// Deliberately not GenomeMeshCache: it keys on the genome alone, so it would
					// hand back a mesh baked at whatever resolution and voxel size some earlier
					// request happened to use, and the settings sliders would appear to do nothing.
					mesh = MeshBaker.bake(plan, LodTier.resolutionFor(tier));
				} finally {
					MeshBaker.setVoxelSize(previousVoxel);
					MeshBaker.setGradientWeight(previousSmoothing);
				}
			}

			send(ex, 200, "application/json", encode(genome, plan, mesh).getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			StringBuilder b = new StringBuilder();
			b.append("{\"error\":\"").append(escape(String.valueOf(e))).append("\"}");
			send(ex, 500, "application/json", b.toString().getBytes(StandardCharsets.UTF_8));
			e.printStackTrace();
		}
	}

	/**
	 * Either the exact genome the page is holding, or a fresh archetype roll.
	 * <p>
	 * The page sends explicit gene values back on every edit so that a slider change alters one
	 * locus and nothing else. Rolling from the seed each time instead would re-randomise the whole
	 * animal on every drag.
	 */
	private static Genome resolveGenome(Map<String, String> q, long seed) {
		String packed = q.get("genes");
		if (packed != null && !packed.isEmpty()) {
			String[] parts = packed.split(",");
			if (parts.length == Gene.COUNT) {
				float[] values = new float[Gene.COUNT];
				for (int i = 0; i < Gene.COUNT; i++) {
					values[i] = clamp(Float.parseFloat(parts[i]), 0f, 1f);
				}
				return new Genome(values, seed, 0L, 0);
			}
		}
		String archetype = q.getOrDefault("archetype", "CHAOS");
		if (archetype == null || archetype.isEmpty()) archetype = "CHAOS";
		Archetype arch;
		try {
			arch = Archetype.valueOf(archetype);
		} catch (IllegalArgumentException e) {
			arch = Archetype.CHAOS;
		}
		return arch.create(new Random(seed));
	}

	/**
	 * Inverse kinematics for the <i>genome</i>: given a limb the user has dragged to a new length,
	 * find the gene value that produces it.
	 * <p>
	 * There is no closed form. A limb's length falls out of {@code SIZE}, {@code LEG_LENGTH},
	 * {@code LEG_SEGMENTS}, {@code LEG_ARCH} and the hip-spacing reconciliation all at once, and
	 * {@link BodyPlanBuilder} is a long procedure rather than an equation. So this inverts it the way
	 * you invert any black box that is cheap to evaluate: sweep the one gene across its range, build
	 * the body plan at each step, measure, and keep whichever value lands nearest the target.
	 * <p>
	 * A sweep rather than a bisection deliberately. Limb length is <i>mostly</i> monotonic in its gene
	 * but not reliably so — the leg-thinning that pays for hip crowding can reverse it — and a
	 * bisection on a non-monotonic function silently converges on nonsense, whereas a sweep just finds
	 * the best of what it saw. A coarse pass then a fine pass around the winner costs a few dozen body
	 * plans, which is milliseconds, because nothing here meshes anything.
	 * <p>
	 * The result is genuinely a genome, not a display tweak — which is the whole point, since the
	 * creature has to be breedable with the wild population afterwards.
	 */
	private static void handleSolve(HttpExchange ex) throws IOException {
		try {
			Map<String, String> q = query(ex);
			long seed = parseLong(q.get("seed"), 1L);
			Genome base = resolveGenome(q, seed);
			Gene gene = Gene.valueOf(q.get("gene"));
			int group = parseInt(q.get("group"), -1);
			float target = parseFloat(q.get("target"), 0f);

			float bestValue = base.raw(gene);
			float bestError = Float.MAX_VALUE;
			float bestMetric = 0f;

			for (int pass = 0; pass < 2; pass++) {
				float lo = pass == 0 ? 0f : Math.max(0f, bestValue - 0.03f);
				float hi = pass == 0 ? 1f : Math.min(1f, bestValue + 0.03f);
				int steps = pass == 0 ? 40 : 24;
				for (int i = 0; i <= steps; i++) {
					float v = lo + (hi - lo) * i / steps;
					float metric = measure(BodyPlanBuilder.build(base.with(gene, v)), group);
					float error = Math.abs(metric - target);
					if (error < bestError) {
						bestError = error;
						bestValue = v;
						bestMetric = metric;
					}
				}
			}

			StringBuilder b = new StringBuilder();
			b.append("{\"gene\":\"").append(gene.name()).append('"')
					.append(",\"value\":").append(fmt(bestValue))
					.append(",\"achieved\":").append(fmt(bestMetric))
					.append(",\"target\":").append(fmt(target))
					// Tells the page when the drag asked for something outside what the gene can express,
					// so it can say so instead of appearing to ignore the mouse.
					.append(",\"clamped\":").append(bestError > Math.max(0.01f, target * 0.06f))
					.append('}');
			send(ex, 200, "application/json", b.toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			send(ex, 500, "application/json",
					("{\"error\":\"" + escape(String.valueOf(e)) + "\"}").getBytes(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Total bind-pose length of one limb (all bones sharing a blend group), or of the whole trunk when
	 * no group is given. This is what "how long is that leg" means to the solver.
	 */
	private static float measure(BodyPlan plan, int group) {
		float total = 0f;
		for (BoneDef bone : plan.bones) {
			if (!bone.emitsGeometry) continue;
			if (group >= 0 ? bone.blendGroup == group : bone.blendGroup == BoneDef.AXIAL) {
				total += bone.length();
			}
		}
		return total;
	}

	/**
	 * Drags one joint toward an arbitrary point and finds the genome that puts it there.
	 * <p>
	 * The generalisation of {@link #handleSolve}: instead of one named gene driving one limb's length,
	 * this asks "which loci move <i>this</i> joint at all, and what values bring it closest to where
	 * the mouse let go". Two stages, both of them just building body plans and measuring:
	 * <ol>
	 *   <li><b>Sensitivity.</b> Nudge every gene in turn and see how far the joint moves. Most of the
	 *       88 do nothing to any given joint — colour, diet, nocturnality — and finding that out by
	 *       measurement rather than by a hand-written table means it stays true as the generator
	 *       changes.</li>
	 *   <li><b>Coordinate descent.</b> Sweep the handful that matter, one at a time, keeping whatever
	 *       lands nearest the target, and repeat. Crude, but the search space is a few axes wide and
	 *       every evaluation is a body plan, not a mesh.</li>
	 * </ol>
	 * Joints are identified <b>by name</b>, never by index. Some genes ({@code LEG_SEGMENTS},
	 * {@code SPINE_SEGMENTS}, {@code LEG_PAIRS}) change how many bones exist, so an index means a
	 * different joint either side of a nudge; a candidate value that makes the named bone vanish is
	 * rejected rather than silently measured against the wrong joint.
	 * <p>
	 * Left and right mirror for free, and not by any effort here: a gene is a property of the whole
	 * animal, so moving a joint means growing the trait on both sides. There is no way to express a
	 * one-sided limb in this genome, which is why dragging one always moves its opposite too.
	 */
	private static void handleReach(HttpExchange ex) throws IOException {
		try {
			Map<String, String> q = query(ex);
			long seed = parseLong(q.get("seed"), 1L);
			Genome base = resolveGenome(q, seed);
			String boneName = q.getOrDefault("bone", "");
			float tx = parseFloat(q.get("x"), 0f);
			float ty = parseFloat(q.get("y"), 0f);
			float tz = parseFloat(q.get("z"), 0f);

			BodyPlan basePlan = BodyPlanBuilder.build(base);
			Map<String, float[]> baseJoints = allJoints(basePlan);
			java.util.Set<String> exempt = limbOf(basePlan, boneName);
			float[] start = jointOf(basePlan, boneName);
			if (start == null) {
				send(ex, 400, "application/json",
						"{\"error\":\"unknown bone\"}".getBytes(StandardCharsets.UTF_8));
				return;
			}

			Gene[] all = Gene.values();
			int[] candidates;
			String supplied = q.get("candidates");
			if (supplied != null && !supplied.isEmpty()) {
				// Handed back by the page mid-drag. Which loci move this joint does not change while
				// the mouse is down, and the sensitivity sweep is by far the expensive half, so
				// re-deriving it every frame would be the difference between a live drag and a
				// stuttering one.
				String[] names = supplied.split(",");
				candidates = new int[names.length];
				for (int i = 0; i < names.length; i++) candidates[i] = Gene.valueOf(names[i]).ordinal();
			} else {
				// Ranked by *locality*, not raw influence: how far this gene moves the grabbed joint
				// against how much it disturbs everything else.
				//
				// Raw influence puts SIZE at the top of every list, because scaling the whole animal
				// moves every joint including this one. Solving with it produces a creature that has
				// technically put the joint under the cursor by growing or shrinking bodily, which is
				// what made dragging feel arbitrary — you pull a knee and the entire animal changes.
				// Dividing by the collateral movement prefers the gene that moves this joint and
				// little else, which is the one you meant.
				float[] sensitivity = new float[all.length];
				for (int g = 0; g < all.length; g++) {
					float v = base.raw(all[g]);
					float probe = v > 0.5f ? v - 0.12f : v + 0.12f;
					BodyPlan trial = BodyPlanBuilder.build(base.with(all[g], probe));
					float[] moved = jointOf(trial, boneName);
					if (moved == null) continue;
					float here = dist(start, moved);
					float elsewhere = collateral(baseJoints, allJoints(trial), exempt);
					sensitivity[g] = here / (1f + LOCALITY_WEIGHT * elsewhere);
				}
				// The few loci worth searching. More than this and the descent spends its time on
				// genes that move the joint by less than the mesh can show.
				candidates = new int[CANDIDATE_GENES];
				for (int k = 0; k < CANDIDATE_GENES; k++) {
					int best = -1;
					for (int g = 0; g < all.length; g++) {
						if (sensitivity[g] <= 1e-4f) continue;
						boolean taken = false;
						for (int j = 0; j < k; j++) if (candidates[j] == g) taken = true;
						if (!taken && (best < 0 || sensitivity[g] > sensitivity[best])) best = g;
					}
					candidates[k] = best;
				}
			}

			float[] target = {tx, ty, tz};
			Genome current = base;
			float bestError = dist(start, target);
			float bestCost = bestError;
			for (int pass = 0; pass < DESCENT_PASSES; pass++) {
				for (int slot : candidates) {
					if (slot < 0) continue;
					Gene gene = all[slot];
					Genome bestGenome = current;
					for (int i = 0; i <= DESCENT_STEPS; i++) {
						float v = (float) i / DESCENT_STEPS;
						Genome trial = current.with(gene, v);
						BodyPlan plan = BodyPlanBuilder.build(trial);
						float[] p = jointOf(plan, boneName);
						if (p == null) continue;
						float error = dist(p, target);
						// The same regularisation the ranking uses, applied to the search itself:
						// get the joint to the cursor, and hold the rest of the animal still while
						// doing it. Without the second term the descent will happily rescale the
						// whole creature to shave a centimetre off the first.
						float cost = error + LOCALITY_WEIGHT
								* collateral(baseJoints, allJoints(plan), exempt);
						if (cost < bestCost) {
							bestCost = cost;
							bestError = error;
							bestGenome = trial;
						}
					}
					current = bestGenome;
				}
			}

			// Baking in the same request rather than making the page come back for it: a drag that
			// has to round-trip twice before anything moves does not read as direct manipulation.
			if ("1".equals(q.get("bake"))) {
				int tier = clamp(parseInt(q.get("tier"), LodTier.NEAR), 0, LodTier.COUNT - 1);
				float voxel = parseFloat(q.get("voxel"), 0f);
				float smoothing = clamp(parseFloat(q.get("smoothing"), 0.75f), 0f, 1f);
				BodyPlan plan;
				MeshData mesh;
				synchronized (BAKE_LOCK) {
					float pv = MeshBaker.voxelSize(), ps = MeshBaker.gradientWeight();
					try {
						MeshBaker.setVoxelSize(voxel);
						MeshBaker.setGradientWeight(smoothing);
						plan = BodyPlanBuilder.build(current);
						mesh = MeshBaker.bake(plan, LodTier.resolutionFor(tier));
					} finally {
						MeshBaker.setVoxelSize(pv);
						MeshBaker.setGradientWeight(ps);
					}
				}
				String payload = encode(current, plan, mesh);
				StringBuilder extra = new StringBuilder();
				extra.append(",\"residual\":").append(fmt(bestError)).append(",\"candidates\":[");
				int w2 = 0;
				for (int slot : candidates) {
					if (slot < 0) continue;
					if (w2++ > 0) extra.append(',');
					extra.append('"').append(all[slot].name()).append('"');
				}
				extra.append(']');
				// Splice onto the bake payload so the page can adopt() it unchanged.
				send(ex, 200, "application/json",
						(payload.substring(0, payload.length() - 1) + extra + "}").getBytes(StandardCharsets.UTF_8));
				return;
			}

			float[] achieved = jointOf(BodyPlanBuilder.build(current), boneName);
			StringBuilder b = new StringBuilder();
			b.append("{\"genes\":[");
			for (int i = 0; i < all.length; i++) {
				if (i > 0) b.append(',');
				num(b, current.raw(all[i]));
			}
			b.append("],\"residual\":").append(fmt(bestError))
					.append(",\"achieved\":[").append(fmt(achieved[0])).append(',')
					.append(fmt(achieved[1])).append(',').append(fmt(achieved[2])).append(']')
					.append(",\"moved\":[");
			int written = 0;
			for (int slot : candidates) {
				if (slot < 0) continue;
				if (Math.abs(current.raw(all[slot]) - base.raw(all[slot])) < 1e-4f) continue;
				if (written++ > 0) b.append(',');
				b.append('"').append(all[slot].name()).append('"');
			}
			b.append("]}");
			send(ex, 200, "application/json", b.toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			send(ex, 500, "application/json",
					("{\"error\":\"" + escape(String.valueOf(e)) + "\"}").getBytes(StandardCharsets.UTF_8));
			e.printStackTrace();
		}
	}

	/**
	 * How heavily the solver is penalised for moving parts of the creature it was not asked to.
	 * <p>
	 * Zero reproduces the old behaviour — reach the target by any means, including rescaling the
	 * whole animal. Large values refuse to move anything and the joint never reaches. This sits far
	 * enough up that a whole-body gene has to be clearly the only way before it is chosen.
	 */
	private static final float LOCALITY_WEIGHT = 2.5f;

	/** Gait cycles run before recording, so the captured frames are periodic rather than settling. */
	private static final int WARMUP_CYCLES = 8;

	/** How many of the 88 loci the descent is allowed to search. */
	private static final int CANDIDATE_GENES = 6;
	private static final int DESCENT_PASSES = 2;
	private static final int DESCENT_STEPS = 16;

	/**
	 * How much of the animal moved that was not asked to. Mean displacement of every joint except
	 * the one being dragged, against the pose the drag started from.
	 */
	private static float collateral(Map<String, float[]> before, Map<String, float[]> after,
	                                java.util.Set<String> exempt) {
		float total = 0f;
		int counted = 0;
		for (Map.Entry<String, float[]> e : before.entrySet()) {
			if (exempt.contains(e.getKey())) continue;
			float[] now = after.get(e.getKey());
			if (now == null) continue;
			total += dist(e.getValue(), now);
			counted++;
		}
		return counted == 0 ? 0f : total / counted;
	}

	/**
	 * The dragged limb's own bones, which are not counted as collateral.
	 * <p>
	 * Grabbing a knee and having the shin and foot follow is the limb behaving like a limb — that is
	 * the point of the drag, not a side effect. Penalising it makes the solver refuse to move
	 * anything at all, because every gene that lengthens a leg necessarily moves the rest of that
	 * leg. What should cost is the <i>torso, head and other limbs</i> shifting, which is what
	 * rescaling the whole animal does and what made dragging feel arbitrary.
	 */
	private static java.util.Set<String> limbOf(BodyPlan plan, String boneName) {
		java.util.Set<String> out = new java.util.HashSet<>();
		out.add(boneName);
		int group = -1;
		for (BoneDef bone : plan.bones) {
			if (bone.name.equals(boneName)) group = bone.blendGroup;
		}
		if (group <= BoneDef.AXIAL) return out;
		for (BoneDef bone : plan.bones) {
			if (bone.blendGroup == group) out.add(bone.name);
		}
		return out;
	}

	private static Map<String, float[]> allJoints(BodyPlan plan) {
		Map<String, float[]> out = new HashMap<>();
		for (BoneDef bone : plan.bones) {
			out.put(bone.name, new float[]{bone.head.x, bone.head.y, bone.head.z});
		}
		return out;
	}

	/** Bind-pose head of the named bone, or null when this genome does not grow that bone at all. */
	private static float[] jointOf(BodyPlan plan, String name) {
		for (BoneDef bone : plan.bones) {
			if (bone.name.equals(name)) return new float[]{bone.head.x, bone.head.y, bone.head.z};
		}
		return null;
	}

	private static float dist(float[] a, float[] b) {
		return (float) Math.sqrt((a[0]-b[0])*(a[0]-b[0]) + (a[1]-b[1])*(a[1]-b[1]) + (a[2]-b[2])*(a[2]-b[2]));
	}

	/**
	 * A gait cycle from the real {@link CreatureAnimator}, as skinning palettes the page can play back.
	 * <p>
	 * The animator is fed the same {@link AnimationContext} a posed specimen gets in game — stationary
	 * but carrying a nominal speed — which is exactly what {@code PoseWalkTest} does. Sending finished
	 * matrices rather than gait parameters is the same rule the rest of this server follows: the walk
	 * has to be the game's walk, and a second implementation in JavaScript would drift from it.
	 */
	private static void handleAnim(HttpExchange ex) throws IOException {
		try {
			Map<String, String> q = query(ex);
			long seed = parseLong(q.get("seed"), 1L);
			float speed = parseFloat(q.get("speed"), 1.4f);
			int frames = clamp(parseInt(q.get("frames"), 48), 2, 240);
			float seconds = Math.max(0.2f, parseFloat(q.get("seconds"), 2f));

			BodyPlan plan = BodyPlanBuilder.build(resolveGenome(q, seed));
			CreatureAnimator animator = new CreatureAnimator(plan);
			Skeleton skeleton = animator.skeleton();

			// The clip is exactly one gait cycle long, so playback loops without a hitch.
			//
			// The cadence formula is duplicated from CreatureAnimator.updateGait because gaitPhase is
			// private. Duplication is normally how two copies of a rule drift apart, but this one
			// fails loudly rather than silently: get it wrong and the loop visibly jumps, which is
			// the first thing anyone watching a walk cycle notices.
			float strideLength = Math.max(0.25f, plan.hipHeight * 1.35f);
			float minFreq = clamp(0.25f / Math.max(0.5f, plan.hipHeight * 0.35f), 0.18f, 0.45f);
			float stepFrequency = clamp(speed / strideLength, minFreq, 3.2f);
			seconds = 1f / Math.max(0.01f, stepFrequency);

			StringBuilder b = new StringBuilder(1 << 18);
			b.append("{\"frames\":").append(frames)
					.append(",\"bones\":").append(plan.bones.length)
					.append(",\"travel\":").append(fmt(seconds * speed))
					.append(",\"seconds\":").append(fmt(seconds))
					.append(",\"matrices\":[");
			float[] scratch = new float[16];

			// Settle before recording. The animator damps toward its targets rather than snapping —
			// foot plants, body bob and lean all ease in — so a creature started from rest spends the
			// first few cycles converging. Recording that transient is what made playback jump on
			// every repeat: the last frame was still drifting toward a steady state the first frame
			// had not reached. Running it silently through several cycles first means the frames that
			// do get recorded are periodic, and the clip loops because the motion genuinely repeats.
			float dt = seconds / frames;
			for (int w = 0; w < WARMUP_CYCLES * frames; w++) {
				AnimationContext warm = new AnimationContext();
				warm.time = dt * w;
				warm.z = warm.time * speed;
				warm.speed = speed;
				warm.tier = LodTier.NEAR;
				animator.update(warm);
			}
			float warmEnd = dt * WARMUP_CYCLES * frames;

			for (int f = 0; f < frames; f++) {
				AnimationContext ctx = new AnimationContext();
				ctx.time = warmEnd + seconds * f / frames;
				// The creature genuinely travels, rather than being held at the origin with a speed
				// it never acts on. Feet are planted in *world* space: standing still while claiming
				// to walk leaves every foot target on top of its own last one, so the legs paddle
				// forward and snap back instead of striding. Moving the body is what gives the IK
				// ground to grab — the animator's own treadmill, and it costs nothing on screen
				// because the skinning palette stays in model space and the creature stays centred.
				ctx.z = ctx.time * speed;
				ctx.speed = speed;
				ctx.tier = LodTier.NEAR;
				animator.update(ctx);
				for (int i = 0; i < plan.bones.length; i++) {
					skeleton.skinMatrix(i).get(scratch);
					for (float v : scratch) {
						if (b.charAt(b.length() - 1) != '[') b.append(',');
						num(b, v);
					}
				}
			}
			b.append("]}");
			send(ex, 200, "application/json", b.toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			send(ex, 500, "application/json",
					("{\"error\":\"" + escape(String.valueOf(e)) + "\"}").getBytes(StandardCharsets.UTF_8));
			e.printStackTrace();
		}
	}

	// ------------------------------------------------------------------ encoding

	private static String encode(Genome genome, BodyPlan plan, MeshData mesh) {
		StringBuilder b = new StringBuilder(1 << 20);
		b.append('{');

		b.append("\"genes\":[");
		Gene[] genes = Gene.values();
		for (int i = 0; i < genes.length; i++) {
			if (i > 0) b.append(',');
			num(b, genome.raw(genes[i]));
		}
		b.append(']');

		b.append(",\"code\":\"").append(escape(genome.encode())).append('"');

		// Faces bridging two different limbs, flagged here because blendGroup is a Java concept and
		// re-deriving "which limb owns this vertex" in the page would be a second opinion that can
		// disagree with the one the game acts on. This is the defect chased through SkinBinder and
		// SurfaceNets; being able to see it beats counting it in a test log.
		b.append(",\"crossLimbQuads\":[");
		int flagged = 0;
		for (int q = 0; q < mesh.quadCount; q++) {
			int limb = -1;
			boolean spans = false;
			for (int c = 0; c < 4 && !spans; c++) {
				int v = mesh.quads[q * 4 + c];
				int dominant = -1;
				float best = 0f;
				for (int i = 0; i < SkinBinder.MAX_INFLUENCES; i++) {
					float w = mesh.boneWeights[v * SkinBinder.MAX_INFLUENCES + i];
					if (w > best) { best = w; dominant = mesh.boneIndices[v * SkinBinder.MAX_INFLUENCES + i]; }
				}
				if (dominant < 0) continue;
				int g = plan.bones[dominant].blendGroup;
				if (g == BoneDef.AXIAL) continue;
				if (limb == -1) limb = g;
				else if (g != limb) spans = true;
			}
			if (spans) {
				if (flagged++ > 0) b.append(',');
				b.append(q);
			}
		}
		b.append(']');

		b.append(",\"meta\":{")
				.append("\"vertexCount\":").append(mesh.vertexCount)
				.append(",\"quadCount\":").append(mesh.quadCount)
				.append(",\"boneCount\":").append(plan.bones.length)
				.append(",\"hipHeight\":").append(fmt(plan.hipHeight))
				.append(",\"bodyLength\":").append(fmt(plan.bodyLength))
				.append(",\"mass\":").append(fmt(plan.mass))
				.append(",\"blendRadius\":").append(fmt(plan.blendRadius))
				.append(",\"minLimbRadius\":").append(fmt(plan.minLimbRadius))
				.append(",\"legs\":").append(plan.legs.length)
				.append(",\"arms\":").append(plan.arms.length)
				.append(",\"teeth\":").append(plan.teeth.length)
				.append(",\"rootBone\":").append(plan.rootBone)
				.append(",\"headBone\":").append(plan.headBone)
				.append(",\"jawBone\":").append(plan.jawBone)
				.append(",\"crossLimbCount\":").append(flagged)
				.append(",\"boundsMin\":[").append(fmt(plan.boundsMin.x)).append(',')
				.append(fmt(plan.boundsMin.y)).append(',').append(fmt(plan.boundsMin.z)).append(']')
				.append(",\"boundsMax\":[").append(fmt(plan.boundsMax.x)).append(',')
				.append(fmt(plan.boundsMax.y)).append(',').append(fmt(plan.boundsMax.z)).append(']')
				.append('}');

		floats(b, ",\"positions\":", mesh.positions);
		floats(b, ",\"normals\":", mesh.normals);
		floats(b, ",\"colors\":", mesh.colors);
		floats(b, ",\"emissive\":", mesh.emissive);
		ints(b, ",\"quads\":", mesh.quads);
		ints(b, ",\"boneIndices\":", mesh.boneIndices);
		floats(b, ",\"boneWeights\":", mesh.boneWeights);

		// The skeleton, including the two matrices the page needs to skin exactly as the game does.
		// Shipping bindLocal and bindWorldInverse rather than head/tail alone means the page never
		// has to reproduce MathX.rotationBetween — get that subtly wrong and every pose is subtly
		// wrong in a way that looks like a rendering bug.
		Matrix4f[] bindWorld = bindWorld(plan);
		b.append(",\"bones\":[");
		for (int i = 0; i < plan.bones.length; i++) {
			BoneDef bone = plan.bones[i];
			if (i > 0) b.append(',');
			b.append("{\"name\":\"").append(escape(bone.name)).append('"')
					.append(",\"parent\":").append(bone.parent)
					.append(",\"feature\":\"").append(bone.feature.name()).append('"')
					.append(",\"blendGroup\":").append(bone.blendGroup)
					.append(",\"emits\":").append(bone.emitsGeometry)
					.append(",\"length\":").append(fmt(bone.length()))
					.append(",\"radiusHead\":").append(fmt(bone.radiusHead))
					.append(",\"radiusTail\":").append(fmt(bone.radiusTail))
					.append(",\"head\":[").append(fmt(bone.head.x)).append(',')
					.append(fmt(bone.head.y)).append(',').append(fmt(bone.head.z)).append(']')
					.append(",\"tail\":[").append(fmt(bone.tail.x)).append(',')
					.append(fmt(bone.tail.y)).append(',').append(fmt(bone.tail.z)).append(']');
			Matrix4f bindLocal = bone.parent < 0
					? new Matrix4f(bindWorld[i])
					: new Matrix4f(bindWorld[bone.parent]).invert().mul(bindWorld[i]);
			matrix(b, ",\"bindLocal\":", bindLocal);
			matrix(b, ",\"bindWorldInverse\":", new Matrix4f(bindWorld[i]).invert());
			b.append('}');
		}
		b.append(']');

		b.append(",\"limbs\":[");
		int written = 0;
		for (LimbChain leg : plan.legs) written = limb(b, leg, "leg", written);
		for (LimbChain arm : plan.arms) written = limb(b, arm, "arm", written);
		b.append(']');

		b.append('}');
		return b.toString();
	}

	private static int limb(StringBuilder b, LimbChain limb, String kind, int written) {
		if (written > 0) b.append(',');
		b.append("{\"kind\":\"").append(kind).append('"')
				.append(",\"side\":").append(limb.side)
				.append(",\"pair\":").append(limb.pairIndex)
				.append(",\"weightBearing\":").append(limb.weightBearing)
				.append(",\"bones\":[");
		for (int i = 0; i < limb.bones.length; i++) {
			if (i > 0) b.append(',');
			b.append(limb.bones[i]);
		}
		b.append("],\"origin\":[").append(fmt(limb.origin.x)).append(',')
				.append(fmt(limb.origin.y)).append(',').append(fmt(limb.origin.z))
				.append("],\"restEffector\":[").append(fmt(limb.restEffector.x)).append(',')
				.append(fmt(limb.restEffector.y)).append(',').append(fmt(limb.restEffector.z))
				.append("]}");
		return written + 1;
	}

	/**
	 * Bind frames, built with the identical formula {@link Skeleton} uses: a bone's local axis is +Y,
	 * so its bind world frame is {@code translation(head) * rotationBetween(+Y, tail - head)}, and its
	 * bind local frame is that taken relative to its parent's.
	 * <p>
	 * Reproduced here rather than read off a {@code Skeleton} because the skeleton keeps them private,
	 * and widening them purely for a dev tool would be the tool leaking into shipped code. It calls
	 * the same {@link MathX#rotationBetween} so there is no second convention to keep in step.
	 */
	private static Matrix4f[] bindWorld(BodyPlan plan) {
		Matrix4f[] out = new Matrix4f[plan.bones.length];
		Quaternionf rot = new Quaternionf();
		Vector3f dir = new Vector3f();
		for (int i = 0; i < plan.bones.length; i++) {
			BoneDef bone = plan.bones[i];
			dir.set(bone.tail).sub(bone.head);
			if (dir.lengthSquared() < 1e-10f) dir.set(0f, 1f, 0f);
			else dir.normalize();
			MathX.rotationBetween(MathX.Y_AXIS, dir, rot);
			out[i] = new Matrix4f().translation(bone.head).rotate(rot);
		}
		return out;
	}

	// ------------------------------------------------------------------ json helpers

	private static void floats(StringBuilder b, String key, float[] values) {
		b.append(key).append('[');
		for (int i = 0; i < values.length; i++) {
			if (i > 0) b.append(',');
			num(b, values[i]);
		}
		b.append(']');
	}

	private static void ints(StringBuilder b, String key, int[] values) {
		b.append(key).append('[');
		for (int i = 0; i < values.length; i++) {
			if (i > 0) b.append(',');
			b.append(values[i]);
		}
		b.append(']');
	}

	private static void matrix(StringBuilder b, String key, Matrix4f m) {
		float[] v = new float[16];
		m.get(v);
		floats(b, key, v);
	}

	/**
	 * Five significant decimals. The payload is a megabyte of numbers and full float printing very
	 * nearly doubles it for precision far below a pixel at any camera distance this tool allows.
	 */
	private static void num(StringBuilder b, float value) {
		if (value == 0f) {
			b.append('0');
		} else if (value == (int) value && Math.abs(value) < 1e7f) {
			b.append((int) value);
		} else {
			b.append(fmt(value));
		}
	}

	private static String fmt(float value) {
		return String.format(java.util.Locale.ROOT, "%.5g", value);
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
	}

	// ------------------------------------------------------------------ plumbing

	private static Map<String, String> query(HttpExchange ex) {
		Map<String, String> out = new HashMap<>();
		String raw = ex.getRequestURI().getRawQuery();
		if (raw == null) return out;
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			if (eq < 0) continue;
			out.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
					java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return out;
	}

	private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
		ex.getResponseHeaders().add("Content-Type", type);
		ex.getResponseHeaders().add("Cache-Control", "no-store");
		ex.sendResponseHeaders(code, body.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(body);
		}
	}

	private static int parseInt(String s, int fallback) {
		try {
			return s == null ? fallback : Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static long parseLong(String s, long fallback) {
		try {
			return s == null ? fallback : Long.parseLong(s);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static float parseFloat(String s, float fallback) {
		try {
			return s == null ? fallback : Float.parseFloat(s);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
