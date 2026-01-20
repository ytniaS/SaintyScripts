package com.sainty.common;

import com.osmb.api.script.ScriptDefinition;

import java.io.File;
import java.io.FileWriter;
import java.util.LinkedHashMap;
import java.util.Map;

public class ProjectScriptVersionExtractor {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalStateException("Root project directory not provided");
        }

        File projectRoot = new File(args[0]);
        if (!projectRoot.isDirectory()) {
            throw new IllegalStateException("Invalid root directory: " + projectRoot);
        }

        Map<String, Double> versions = new LinkedHashMap<>();

        String classPath = System.getProperty("java.class.path");
        String[] entries = classPath.split(File.pathSeparator);

        ClassLoader loader = ProjectScriptVersionExtractor.class.getClassLoader();

        for (String entry : entries) {
            File f = new File(entry);
            if (f.isDirectory()) {
                scan(f, f, loader, versions);
            }
        }

        writeJson(projectRoot, versions);
        System.out.println("Generated versions.json (" + versions.size() + " scripts)");
    }


    private static void scan(File file, File root, ClassLoader loader,
                             Map<String, Double> out) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    scan(f, root, loader, out);
                }
            }
            return;
        }
        if (!file.getName().endsWith(".class")) {
            return;
        }
        try {
            String abs = file.getAbsolutePath();
            String rootPath = root.getAbsolutePath();
            if (!abs.startsWith(rootPath)) {
                return;
            }
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
        }
    }

    private static void writeJson(File root, Map<String, Double> versions) throws Exception {
        File out = new File(root, "versions.json");
        try (FileWriter w = new FileWriter(out)) {
            w.write("{\n");
            int i = 0;
            for (var e : versions.entrySet()) {
                w.write("  \"" + e.getKey() + "\": " + e.getValue());
                if (++i < versions.size()) {
                    w.write(",");
                }
                w.write("\n");
            }
            w.write("}\n");
        }
    }
}
