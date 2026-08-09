package dev.jsz.primordia;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.io.File;
import java.util.jar.JarFile;
import java.util.Enumeration;
import java.util.jar.JarEntry;

class FabricMixinSearchTest {
	@Test
	void findItemInHandRendererMixins() throws Exception {
		System.out.println("=== SEARCHING FABRIC API FOR HELD ITEM / REEQUIP MIXINS ===");
		// Look in fabric-api jars
		String classpath = System.getProperty("java.class.path");
		for (String path : classpath.split(File.pathSeparator)) {
			if (path.contains("fabric-api") || path.contains("fabric-item-api")) {
				System.out.println("Searching jar: " + path);
				try (JarFile jar = new JarFile(path)) {
					Enumeration<JarEntry> entries = jar.entries();
					while (entries.hasMoreElements()) {
						JarEntry entry = entries.nextElement();
						if (entry.getName().endsWith(".class")) {
							String className = entry.getName().replace('/', '.').replace(".class", "");
							if (className.toLowerCase().contains("hand") || className.toLowerCase().contains("equip") || className.toLowerCase().contains("item")) {
								System.out.println("  Class: " + className);
							}
						}
					}
				}
			}
		}
	}
}
