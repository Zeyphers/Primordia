package dev.jsz.primordia.editor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.jsz.primordia.Primordia;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.registry.PrimordiaEntities;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Embedded HTTP server powering the 3D Spore-style web creature editor and live spawner API.
 */
public final class EditorServer {
	private static final int PORT = 8088;
	private static HttpServer server;
	private static ServerPlayer activePlayer;

	private EditorServer() {
	}

	public static synchronized void start(ServerPlayer player) {
		activePlayer = player;
		if (server != null) return;

		try {
			server = HttpServer.create(new InetSocketAddress(PORT), 0);
			server.createContext("/", new StaticHandler());
			server.createContext("/api/spawn", new SpawnHandler());
			server.setExecutor(null);
			server.start();
			Primordia.LOGGER.info("Primordia 3D Creature Editor Server running at http://localhost:" + PORT);
		} catch (IOException e) {
			Primordia.LOGGER.error("Failed to start Primordia EditorServer", e);
		}
	}

	public static String getUrl() {
		return "http://localhost:" + PORT;
	}

	private static class StaticHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			String html = getEditorHtml();
			byte[] response = html.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(response);
			}
		}
	}

	private static class SpawnHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			String query = exchange.getRequestURI().getQuery();
			String genomeCode = null;
			if (query != null && query.contains("genome=")) {
				for (String param : query.split("&")) {
					if (param.startsWith("genome=")) {
						genomeCode = param.substring(7);
						break;
					}
				}
			}

			String responseText;
			if (genomeCode != null && activePlayer != null) {
				String finalGenome = genomeCode;
				activePlayer.level().getServer().execute(() -> {
					ServerLevel world = activePlayer.level();
					CreatureEntity creature = PrimordiaEntities.CREATURE.create(world, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
					if (creature != null) {
						// getRotationVector() is a Vec2 of pitch and yaw in 26.2; the look
						// direction this wants is getLookAngle().
						net.minecraft.world.phys.Vec3 look = activePlayer.getLookAngle();
						creature.snapTo(
								activePlayer.getX() + look.x * 3.5,
								activePlayer.getY() + 0.5,
								activePlayer.getZ() + look.z * 3.5,
								activePlayer.getYRot() + 180f, 0f);
						Genome decoded = Genome.decode(finalGenome);
						if (decoded != null) {
							creature.setGenome(decoded);
						}
						world.addFreshEntity(creature);
					}
				});
				responseText = "{\"status\":\"ok\",\"message\":\"Creature spawned in Minecraft!\"}";
			} else {
				responseText = "{\"status\":\"error\",\"message\":\"Missing genome or player context\"}";
			}

			byte[] response = responseText.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(response);
			}
		}
	}

	private static String getEditorHtml() {
		return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Primordia — 3D Spore Creature Editor</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; }
        body { background: #0b0f19; color: #e2e8f0; overflow: hidden; display: flex; height: 100vh; }
        #canvas-container { flex: 1; position: relative; background: radial-gradient(circle at center, #1e293b 0%, #0f172a 100%); }
        #controls { width: 380px; background: rgba(15, 23, 42, 0.95); backdrop-filter: blur(12px); border-left: 1px solid #334155; padding: 20px; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; }
        h1 { font-size: 1.4rem; color: #38bdf8; font-weight: 700; text-transform: uppercase; letter-spacing: 1px; border-bottom: 2px solid #38bdf8; padding-bottom: 8px; }
        .group { background: #1e293b; border-radius: 8px; padding: 12px; border: 1px solid #334155; }
        .group h2 { font-size: 0.95rem; color: #94a3b8; margin-bottom: 10px; text-transform: uppercase; letter-spacing: 0.5px; }
        .slider-row { display: flex; flex-direction: column; gap: 4px; margin-bottom: 10px; }
        .slider-row label { font-size: 0.82rem; color: #cbd5e1; display: flex; justify-content: space-between; }
        input[type="range"] { width: 100%; accent-color: #38bdf8; cursor: pointer; }
        button { background: linear-gradient(135deg, #0284c7 0%, #0369a1 100%); color: white; border: none; padding: 14px; border-radius: 8px; font-weight: 700; font-size: 1rem; cursor: pointer; transition: all 0.2s ease; box-shadow: 0 4px 14px rgba(2, 132, 199, 0.4); text-transform: uppercase; letter-spacing: 1px; }
        button:hover { transform: translateY(-2px); box-shadow: 0 6px 20px rgba(2, 132, 199, 0.6); }
        button:active { transform: translateY(0); }
        #toast { position: absolute; bottom: 20px; left: 50%; transform: translateX(-50%); background: #10b981; color: white; padding: 12px 24px; border-radius: 30px; font-weight: 600; opacity: 0; transition: opacity 0.3s; pointer-events: none; }
    </style>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/controls/OrbitControls.js"></script>
</head>
<body>
    <div id="canvas-container">
        <div id="toast">Creature Spawned in Minecraft!</div>
    </div>
    <div id="controls">
        <h1>Spore Creature Studio</h1>
        <button onclick="spawnInGame()">Spawn in Minecraft</button>
        
        <div class="group">
            <h2>Body & Form</h2>
            <div class="slider-row"><label>Size / Scale <span id="val-size">1.0</span></label><input type="range" id="size" min="0.2" max="2.5" step="0.05" value="1.0" oninput="updateMesh()"></div>
            <div class="slider-row"><label>Body Length <span id="val-length">1.0</span></label><input type="range" id="length" min="0.4" max="2.2" step="0.05" value="1.0" oninput="updateMesh()"></div>
            <div class="slider-row"><label>Girth / Width <span id="val-girth">0.5</span></label><input type="range" id="girth" min="0.2" max="1.2" step="0.05" value="0.5" oninput="updateMesh()"></div>
        </div>
        
        <div class="group">
            <h2>Limbs & Stance</h2>
            <div class="slider-row"><label>Leg Pairs (2 to 8 Legs) <span id="val-legs">2</span></label><input type="range" id="legPairs" min="1" max="4" step="1" value="2" oninput="updateMesh()"></div>
            <div class="slider-row"><label>Leg Length <span id="val-leglen">1.0</span></label><input type="range" id="legLen" min="0.4" max="2.0" step="0.05" value="1.0" oninput="updateMesh()"></div>
            <div class="slider-row"><label>Stance Width <span id="val-splay">0.4</span></label><input type="range" id="splay" min="0.1" max="0.9" step="0.05" value="0.4" oninput="updateMesh()"></div>
        </div>

        <div class="group">
            <h2>Head & Tail</h2>
            <div class="slider-row"><label>Head Size <span id="val-head">0.3</span></label><input type="range" id="headSize" min="0.1" max="0.8" step="0.05" value="0.3" oninput="updateMesh()"></div>
            <div class="slider-row"><label>Tail Length <span id="val-tail">1.0</span></label><input type="range" id="tailLen" min="0.0" max="2.5" step="0.05" value="1.0" oninput="updateMesh()"></div>
        </div>

        <div class="group">
            <h2>Color & Skin</h2>
            <div class="slider-row"><label>Hue</label><input type="range" id="hue" min="0" max="1" step="0.01" value="0.12" oninput="updateMesh()"></div>
            <div class="slider-row"><label>Saturation</label><input type="range" id="sat" min="0" max="1" step="0.01" value="0.5" oninput="updateMesh()"></div>
            <div class="slider-row"><label>Brightness</label><input type="range" id="bright" min="0" max="1" step="0.01" value="0.6" oninput="updateMesh()"></div>
        </div>
    </div>

    <script>
        let scene, camera, renderer, controls, creatureGroup;

        function init() {
            const container = document.getElementById('canvas-container');
            scene = new THREE.Scene();
            camera = new THREE.PerspectiveCamera(45, container.clientWidth / container.clientHeight, 0.1, 100);
            camera.position.set(3, 2, 4);

            renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
            renderer.setSize(container.clientWidth, container.clientHeight);
            renderer.shadowMap.enabled = true;
            container.appendChild(renderer.domElement);

            controls = new THREE.OrbitControls(camera, renderer.domElement);
            controls.enableDamping = true;

            const ambientLight = new THREE.AmbientLight(0xffffff, 0.7);
            scene.add(ambientLight);

            const dirLight = new THREE.DirectionalLight(0xffffff, 1.2);
            dirLight.position.set(5, 10, 7);
            dirLight.castShadow = true;
            scene.add(dirLight);

            const grid = new THREE.GridHelper(10, 20, 0x38bdf8, 0x334155);
            grid.position.y = -0.01;
            scene.add(grid);

            creatureGroup = new THREE.Group();
            scene.add(creatureGroup);

            updateMesh();
            animate();

            window.addEventListener('resize', () => {
                camera.aspect = container.clientWidth / container.clientHeight;
                camera.updateProjectionMatrix();
                renderer.setSize(container.clientWidth, container.clientHeight);
            });
        }

        function updateMesh() {
            while(creatureGroup.children.length > 0) creatureGroup.remove(creatureGroup.children[0]);

            const size = parseFloat(document.getElementById('size').value);
            const length = parseFloat(document.getElementById('length').value);
            const girth = parseFloat(document.getElementById('girth').value);
            const legPairs = parseInt(document.getElementById('legPairs').value);
            const legLen = parseFloat(document.getElementById('legLen').value);
            const splay = parseFloat(document.getElementById('splay').value);
            const headSize = parseFloat(document.getElementById('headSize').value);
            const tailLen = parseFloat(document.getElementById('tailLen').value);

            const hue = parseFloat(document.getElementById('hue').value);
            const sat = parseFloat(document.getElementById('sat').value);
            const bright = parseFloat(document.getElementById('bright').value);

            document.getElementById('val-size').innerText = size.toFixed(2);
            document.getElementById('val-length').innerText = length.toFixed(2);
            document.getElementById('val-girth').innerText = girth.toFixed(2);
            document.getElementById('val-legs').innerText = (legPairs * 2);
            document.getElementById('val-leglen').innerText = legLen.toFixed(2);
            document.getElementById('val-splay').innerText = splay.toFixed(2);
            document.getElementById('val-head').innerText = headSize.toFixed(2);
            document.getElementById('val-tail').innerText = tailLen.toFixed(2);

            const color = new THREE.Color().setHSL(hue, sat, bright);
            const material = new THREE.MeshStandardMaterial({ color: color, roughness: 0.4, metalness: 0.1 });

            // Torso
            const torsoGeo = new THREE.CylinderGeometry(girth * 0.4, girth * 0.45, length, 16);
            torsoGeo.rotateX(Math.PI / 2);
            const torso = new THREE.Mesh(torsoGeo, material);
            torso.position.y = legLen * 0.8;
            creatureGroup.add(torso);

            // Head
            const headGeo = new THREE.SphereGeometry(headSize, 16, 16);
            const head = new THREE.Mesh(headGeo, material);
            head.position.set(0, torso.position.y + 0.1, length * 0.55);
            creatureGroup.add(head);

            // Eyes
            const eyeMat = new THREE.MeshStandardMaterial({ color: 0x111111, roughness: 0.1 });
            for(let s of [-1, 1]) {
                const eye = new THREE.Mesh(new THREE.SphereGeometry(headSize * 0.25, 8, 8), eyeMat);
                eye.position.set(s * headSize * 0.7, head.position.y + headSize * 0.2, head.position.z + headSize * 0.4);
                creatureGroup.add(eye);
            }

            // Tail
            if (tailLen > 0.05) {
                const tailGeo = new THREE.CylinderGeometry(girth * 0.3, 0.02, tailLen, 12);
                tailGeo.rotateX(-Math.PI / 3);
                const tail = new THREE.Mesh(tailGeo, material);
                tail.position.set(0, torso.position.y - 0.1, -length * 0.5);
                creatureGroup.add(tail);
            }

            // Legs
            for(let p = 0; p < legPairs; p++) {
                const z = (p / Math.max(1, legPairs - 1) - 0.5) * (length * 0.7);
                for(let s of [-1, 1]) {
                    const legGeo = new THREE.CylinderGeometry(girth * 0.12, girth * 0.06, legLen, 12);
                    const leg = new THREE.Mesh(legGeo, material);
                    leg.position.set(s * (girth * 0.5 + splay), torso.position.y * 0.5, z);
                    leg.rotation.z = s * -splay * 0.4;
                    creatureGroup.add(leg);
                }
            }
        }

        function animate() {
            requestAnimationFrame(animate);
            controls.update();
            renderer.render(scene, camera);
        }

        function encodeGenome() {
            // Build a 28-float raw gene payload and convert to Base64
            const values = new Float32Array(28);
            values.fill(0.5);
            values[0] = parseFloat(document.getElementById('size').value) / 2.5; // SIZE
            values[1] = parseFloat(document.getElementById('length').value) / 2.2; // TORSO_LENGTH
            values[2] = parseFloat(document.getElementById('girth').value) / 1.2; // TORSO_GIRTH
            values[11] = (parseInt(document.getElementById('legPairs').value) - 1) / 3.0; // LEG_PAIRS
            values[12] = parseFloat(document.getElementById('splay').value); // LEG_SPLAY
            values[7] = parseFloat(document.getElementById('headSize').value) / 0.8; // HEAD_SIZE
            values[9] = parseFloat(document.getElementById('tailLen').value) / 2.5; // TAIL_LENGTH
            values[20] = parseFloat(document.getElementById('hue').value); // HUE
            values[21] = parseFloat(document.getElementById('sat').value); // SATURATION
            values[22] = parseFloat(document.getElementById('bright').value); // BRIGHTNESS

            // Base64 encode wire format payload
            let binary = "";
            let bytes = new Uint8Array(values.buffer);
            for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]);
            return btoa(binary);
        }

        function spawnInGame() {
            const code = encodeGenome();
            fetch('/api/spawn?genome=' + encodeURIComponent(code))
                .then(res => res.json())
                .then(data => {
                    const toast = document.getElementById('toast');
                    toast.innerText = data.message || "Spawned!";
                    toast.style.opacity = '1';
                    setTimeout(() => toast.style.opacity = '0', 2500);
                });
        }

        init();
    </script>
</body>
</html>
""";
	}
}
