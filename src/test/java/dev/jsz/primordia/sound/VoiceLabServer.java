package dev.jsz.primordia.sound;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Genome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * A browser front end for the voice synthesiser, runnable without launching Minecraft.
 * <p>
 * Every sound it produces comes from the shipped {@link VoiceProfile} and {@link VoiceSynth} on the
 * real classpath — this server only moves numbers in and PCM out. That is the whole point: tuning a
 * voice by rebuilding the mod, starting the game, finding a creature and waiting for it to feel like
 * calling is a minutes-long loop for a change that takes seconds to make, and a loop that slow means
 * the parameters never actually get tuned.
 * <p>
 * Lives in the test source set, so none of it reaches the mod jar. Start it with:
 * <pre>gradle voiceLab</pre>
 *
 * @see dev.jsz.primordia.editor.EditorServer the same idea for the body generator
 */
public final class VoiceLabServer {

	private static final int PORT = 8091;
	private static final Gson GSON = new Gson();

	private VoiceLabServer() {
	}

	/**
	 * Every tunable on {@link VoiceProfile}, with the range the UI should offer for it.
	 * <p>
	 * Declared here rather than in the page so that the sliders cannot drift out of step with the
	 * record: adding a field to the profile and forgetting the UI shows up as a missing slider, not
	 * as a control that silently does nothing.
	 */
	private record Field(String name, String group, float min, float max) {
	}

	private static final Field[] FIELDS = {
			new Field("f0", "Source", 40, 1500),
			new Field("openQuotient", "Source", 0.2f, 0.95f),
			new Field("speedQuotient", "Source", 0.2f, 0.95f),
			new Field("spectralTilt", "Source", 300, 7000),
			new Field("aspiration", "Source", 0, 1),

			new Field("formant0", "Filter", 80, 3200),
			new Field("formant1", "Filter", 200, 7000),
			new Field("formant2", "Filter", 400, 9000),
			new Field("formant3", "Filter", 700, 10000),
			new Field("bandwidth0", "Filter", 20, 900),
			new Field("bandwidth1", "Filter", 30, 1400),
			new Field("bandwidth2", "Filter", 40, 2000),
			new Field("bandwidth3", "Filter", 50, 2600),
			new Field("motion0", "Filter", -0.6f, 0.6f),
			new Field("motion1", "Filter", -0.6f, 0.6f),
			new Field("motion2", "Filter", -0.6f, 0.6f),
			new Field("motion3", "Filter", -0.6f, 0.6f),
			new Field("nasality", "Filter", 0, 1),

			new Field("chaos", "Nonlinear", 0, 1),
			new Field("subharmonic", "Nonlinear", 0, 1),
			new Field("biphonation", "Nonlinear", 0, 1),
			new Field("biphonationRatio", "Nonlinear", 1.02f, 2.5f),
			new Field("jumpChance", "Nonlinear", 0, 1),
			new Field("jitter", "Nonlinear", 0, 0.2f),
			new Field("shimmer", "Nonlinear", 0, 0.6f),

			new Field("vibratoRate", "Phrasing", 0, 14),
			new Field("vibratoDepth", "Phrasing", 0, 0.1f),
			new Field("stridulation", "Phrasing", 0, 1),
			new Field("stridulationRate", "Phrasing", 5, 140),
			new Field("syllables", "Phrasing", 1, 8),
			new Field("syllableLen", "Phrasing", 0.05f, 1.2f),
			new Field("gapLen", "Phrasing", -0.1f, 0.4f),
			new Field("attack", "Phrasing", 0.002f, 0.3f),
			new Field("release", "Phrasing", 0.02f, 0.8f),
			new Field("volume", "Phrasing", 0.2f, 1.6f),
	};

	public static void main(String[] args) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
		server.createContext("/api/meta", VoiceLabServer::handleMeta);
		server.createContext("/api/random", VoiceLabServer::handleRandom);
		server.createContext("/api/render", VoiceLabServer::handleRender);
		server.createContext("/", VoiceLabServer::handlePage);
		server.setExecutor(null);
		server.start();
		System.out.println("Voice lab: http://127.0.0.1:" + PORT + "/");
	}

	// ------------------------------------------------------------------ endpoints

	/** The slider table, the call types, and one starting voice. */
	private static void handleMeta(HttpExchange ex) throws IOException {
		JsonObject root = new JsonObject();

		JsonArray fields = new JsonArray();
		for (Field f : FIELDS) {
			JsonObject o = new JsonObject();
			o.addProperty("name", f.name());
			o.addProperty("group", f.group());
			o.addProperty("min", f.min());
			o.addProperty("max", f.max());
			fields.add(o);
		}
		root.add("fields", fields);

		JsonArray calls = new JsonArray();
		for (CallType c : CallType.VALUES) calls.add(c.name());
		root.add("calls", calls);

		root.add("profile", toJson(sample(new Random(7)).profile()));
		send(ex, 200, "application/json", GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
	}

	/** A batch of genuinely random creatures, drawn the way the game draws founders. */
	private static void handleRandom(HttpExchange ex) throws IOException {
		int count = 50;
		String query = ex.getRequestURI().getQuery();
		if (query != null && query.startsWith("count=")) {
			try {
				count = Math.min(200, Math.max(1, Integer.parseInt(query.substring(6))));
			} catch (NumberFormatException ignored) {
				// keep the default
			}
		}

		Random random = new Random();
		JsonArray out = new JsonArray();
		for (int i = 0; i < count; i++) {
			Sample s = sample(random);
			JsonObject o = new JsonObject();
			o.addProperty("label", s.label());
			o.add("profile", toJson(s.profile()));
			out.add(o);
		}
		send(ex, 200, "application/json", GSON.toJson(out).getBytes(StandardCharsets.UTF_8));
	}

	/** Renders one call and returns it as a WAV. */
	private static void handleRender(HttpExchange ex) throws IOException {
		JsonObject body;
		try (InputStream in = ex.getRequestBody()) {
			body = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
					.getAsJsonObject();
		}

		CallType call = CallType.AMBIENT;
		if (body.has("call")) {
			try {
				call = CallType.valueOf(body.get("call").getAsString());
			} catch (IllegalArgumentException ignored) {
				// keep the default
			}
		}
		int variant = body.has("variant") ? body.get("variant").getAsInt() : 0;

		byte[] pcm = VoiceSynth.render(fromJson(body.getAsJsonObject("profile")), call, variant);
		send(ex, 200, "audio/wav", wav(pcm));
	}

	private static void handlePage(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		if (path == null || path.equals("/")) path = "/index.html";
		try (InputStream in = VoiceLabServer.class.getResourceAsStream("/voicelab" + path)) {
			if (in == null) {
				send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
				return;
			}
			send(ex, 200, path.endsWith(".html") ? "text/html; charset=utf-8" : "text/plain",
					in.readAllBytes());
		}
	}

	// ------------------------------------------------------------------ creatures

	private record Sample(String label, VoiceProfile profile) {
	}

	/**
	 * One founder creature, complete with the body its genome builds.
	 * <p>
	 * Drawn from {@link Archetype} rather than from a uniform random genome, because a uniform draw
	 * is statistically always the same mid-sized quadruped — the archetypes are what give a batch of
	 * fifty an actual spread of body plans to listen to.
	 */
	private static Sample sample(Random random) {
		Archetype archetype = Archetype.VALUES[random.nextInt(Archetype.VALUES.length)];
		Genome genome = archetype.create(random);
		BodyPlan plan = BodyPlanBuilder.build(genome);
		String label = String.format("%s · mass %.2f · hip %.2f · %d legs",
				archetype.name().toLowerCase().replace('_', ' '),
				plan.mass, plan.hipHeight, plan.legs == null ? 0 : plan.legs.length);
		return new Sample(label, VoiceProfile.of(genome, plan));
	}

	// ------------------------------------------------------------------ profile json

	private static JsonObject toJson(VoiceProfile v) {
		JsonObject o = new JsonObject();
		o.addProperty("hash", v.hash());
		o.addProperty("f0", v.f0());
		for (int i = 0; i < 4; i++) {
			o.addProperty("formant" + i, v.formantHz()[i]);
			o.addProperty("bandwidth" + i, v.formantBw()[i]);
			o.addProperty("motion" + i, v.formantMotion()[i]);
		}
		o.addProperty("openQuotient", v.openQuotient());
		o.addProperty("speedQuotient", v.speedQuotient());
		o.addProperty("spectralTilt", v.spectralTilt());
		o.addProperty("aspiration", v.aspiration());
		o.addProperty("chaos", v.chaos());
		o.addProperty("subharmonic", v.subharmonic());
		o.addProperty("biphonation", v.biphonation());
		o.addProperty("biphonationRatio", v.biphonationRatio());
		o.addProperty("jitter", v.jitter());
		o.addProperty("shimmer", v.shimmer());
		o.addProperty("jumpChance", v.jumpChance());
		o.addProperty("vibratoRate", v.vibratoRate());
		o.addProperty("vibratoDepth", v.vibratoDepth());
		o.addProperty("stridulation", v.stridulation());
		o.addProperty("stridulationRate", v.stridulationRate());
		o.addProperty("syllables", v.syllables());
		o.addProperty("syllableLen", v.syllableLen());
		o.addProperty("gapLen", v.gapLen());
		o.addProperty("attack", v.attack());
		o.addProperty("release", v.release());
		o.addProperty("nasality", v.nasality());
		o.addProperty("volume", v.volume());
		return o;
	}

	private static VoiceProfile fromJson(JsonObject o) {
		float[] hz = new float[4], bw = new float[4], motion = new float[4];
		for (int i = 0; i < 4; i++) {
			hz[i] = f(o, "formant" + i, 500);
			bw[i] = f(o, "bandwidth" + i, 120);
			motion[i] = f(o, "motion" + i, 0);
		}
		return new VoiceProfile(
				o.has("hash") ? o.get("hash").getAsInt() : 1,
				f(o, "f0", 200), hz, bw, motion,
				f(o, "openQuotient", 0.6f), f(o, "speedQuotient", 0.5f),
				f(o, "spectralTilt", 2000), f(o, "aspiration", 0.2f),
				f(o, "chaos", 0.2f), f(o, "subharmonic", 0.2f),
				f(o, "biphonation", 0.1f), f(o, "biphonationRatio", 1.3f),
				f(o, "jitter", 0.02f), f(o, "shimmer", 0.06f), f(o, "jumpChance", 0.1f),
				f(o, "vibratoRate", 5), f(o, "vibratoDepth", 0.01f),
				f(o, "stridulation", 0), f(o, "stridulationRate", 40),
				Math.round(f(o, "syllables", 2)), f(o, "syllableLen", 0.25f), f(o, "gapLen", 0.06f),
				f(o, "attack", 0.03f), f(o, "release", 0.12f), f(o, "nasality", 0.1f),
				f(o, "volume", 1f));
	}

	private static float f(JsonObject o, String key, float fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsFloat() : fallback;
	}

	// ------------------------------------------------------------------ plumbing

	/** Minimal 16-bit mono WAV container around the raw PCM the synthesiser emits. */
	private static byte[] wav(byte[] pcm) throws IOException {
		int sr = VoiceSynth.SAMPLE_RATE;
		ByteArrayOutputStream o = new ByteArrayOutputStream(pcm.length + 44);
		o.write("RIFF".getBytes(StandardCharsets.US_ASCII));
		le32(o, 36 + pcm.length);
		o.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
		le32(o, 16);
		le16(o, 1);
		le16(o, 1);
		le32(o, sr);
		le32(o, sr * 2);
		le16(o, 2);
		le16(o, 16);
		o.write("data".getBytes(StandardCharsets.US_ASCII));
		le32(o, pcm.length);
		o.write(pcm);
		return o.toByteArray();
	}

	private static void le32(ByteArrayOutputStream o, int v) {
		o.write(v);
		o.write(v >> 8);
		o.write(v >> 16);
		o.write(v >> 24);
	}

	private static void le16(ByteArrayOutputStream o, int v) {
		o.write(v);
		o.write(v >> 8);
	}

	private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
		ex.getResponseHeaders().set("Content-Type", type);
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		ex.sendResponseHeaders(code, body.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(body);
		}
	}
}
