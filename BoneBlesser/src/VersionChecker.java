package com.osmb.script.boneblesser;

import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VersionChecker {
	
	private static final String VERSIONS_URL =
			"https://raw.githubusercontent.com/ytniaS/SaintyScripts/main/versions.json";
	
	private static Pattern entryPattern(String name) {
		return Pattern.compile(
				"\""+ Pattern.quote(name) +"\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)"
		                      );
	}
	
	private VersionChecker() {}
	
	public static boolean isExactVersion(Script script) {
		
		ScriptDefinition def =
				script.getClass().getAnnotation(ScriptDefinition.class);
		
		if (def == null)
			return false; // no annotation = stop
		
		String name = def.name();
		double local = def.version();
		
		try {
			String json = fetch(VERSIONS_URL);
			
			script.log(name, "Local version: " + local);
			
			Matcher m = entryPattern(name).matcher(json);
			if (!m.find()) {
				script.log(name,
				           "Version entry NOT FOUND in versions.json — stopping");
				return false;
			}
			
			double remote = Double.parseDouble(m.group(1));
			script.log(name, "Remote version: " + remote);
			
			if (Double.compare(local, remote) != 0) {
				script.log(name,
				           "Version mismatch — local=" + local +
						           " remote=" + remote + " — stopping");
				return false;
			}
			
			script.log(name, "Version OK");
			return true;
			
		} catch (Exception e) {
			script.log(name,
			           "Version check failed (network) — allowing run");
			return true; // allow if GitHub is down
		}
	}
	
	private static String fetch(String url) throws Exception {
		HttpURLConnection conn =
				(HttpURLConnection) new URL(url).openConnection();
		conn.setConnectTimeout(3000);
		conn.setReadTimeout(3000);
		
		try (BufferedReader r =
				     new BufferedReader(
						     new InputStreamReader(conn.getInputStream()))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = r.readLine()) != null)
				sb.append(line);
			return sb.toString();
		}
	}
}
