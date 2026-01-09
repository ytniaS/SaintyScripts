package tools;

import com.osmb.api.script.ScriptDefinition;

import java.io.File;
import java.io.FileWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashMap;
import java.util.Map;

public class ProjectScriptVersionExtractor {
	
	private static final File PRODUCTION_DIR = new File("out/production");
	
	public static void main(String[] args) throws Exception {
		
		if (!PRODUCTION_DIR.exists())
			throw new IllegalStateException("Missing out/production directory");
		
		Map<String, Double> versions = new LinkedHashMap<>();
		
		File[] modules = PRODUCTION_DIR.listFiles(File::isDirectory);
		if (modules == null || modules.length == 0)
			throw new IllegalStateException("No modules found in out/production");
		
		// Each module is its own classpath root
		for (File module : modules) {
			
			URLClassLoader loader = new URLClassLoader(
					new URL[]{module.toURI().toURL()},
					ProjectScriptVersionExtractor.class.getClassLoader()
			);
			
			scan(module, module, loader, versions);
		}
		
		writeJson(versions);
		
		System.out.println("Generated versions.json (" + versions.size() + " scripts)");
	}
	
	private static void scan(File file, File root, ClassLoader loader,
	                         Map<String, Double> out) {
		
		if (file.isDirectory()) {
			for (File f : file.listFiles()) {
				scan(f, root, loader, out);
			}
			return;
		}
		
		if (!file.getName().endsWith(".class"))
			return;
		
		try {
			String abs = file.getAbsolutePath();
			String rootPath = root.getAbsolutePath();
			
			if (!abs.startsWith(rootPath))
				return;
			
			String className = abs
					.substring(rootPath.length() + 1)
					.replace(File.separatorChar, '.')
					.replace(".class", "");
			
			Class<?> cls = Class.forName(className, false, loader);
			
			ScriptDefinition def = cls.getAnnotation(ScriptDefinition.class);
			if (def != null) {
				out.put(def.name(), def.version());
			}
			
		} catch (Throwable ignored) {
			// Uncomment if debugging:
			// ignored.printStackTrace();
		}
	}
	
	private static void writeJson(Map<String, Double> versions) throws Exception {
		
		try (FileWriter w = new FileWriter("versions.json")) {
			w.write("{\n");
			
			int i = 0;
			for (var e : versions.entrySet()) {
				w.write("  \"" + e.getKey() + "\": " + e.getValue());
				if (++i < versions.size())
					w.write(",");
				w.write("\n");
			}
			
			w.write("}\n");
		}
	}
}
