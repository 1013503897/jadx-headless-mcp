// jadx-headless plugin bootstrap launcher.
//
// Run in JDK "source-file mode" (JEP 330, JDK 11+):
//     java Launcher.java <dataDir> <version> <jarUrl> <sha256|placeholder> [serverArgs...]
//
// Why this exists: the jadx-headless MCP server is a ~50 MB JVM fat jar. Rather than commit
// that binary into the plugin repo (bad for a git-based / community marketplace and for the
// automated safety review), the plugin ships as plain text and this launcher downloads the
// versioned jar once from the project's GitHub Releases into ${CLAUDE_PLUGIN_DATA} (a per-plugin
// directory that survives updates), verifies its SHA-256 against the hash pinned in .mcp.json,
// caches it, and then runs it. Subsequent starts are offline and instant.
//
// Everything this launcher prints goes to stderr: stdout is reserved for the MCP JSON-RPC stream,
// which the child jar owns via inheritIO().
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class Launcher {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    public static void main(String[] argv) throws Exception {
        if (argv.length < 4) {
            System.err.println("[jadx-plugin] usage: java Launcher.java <dataDir> <version> <jarUrl> <sha256|placeholder> [serverArgs...]");
            System.exit(2);
        }
        Path dataDir = Paths.get(argv[0]);
        String version = argv[1];
        String url = argv[2];
        String sha = argv[3];
        String[] serverArgs = Arrays.copyOfRange(argv, 4, argv.length);

        Files.createDirectories(dataDir);
        Path jar = dataDir.resolve("jadx-headless-mcp-" + version + "-all.jar");
        boolean pinned = SHA256.matcher(sha).matches();

        boolean needDownload = !Files.exists(jar);
        if (!needDownload && pinned && !sha256(jar).equalsIgnoreCase(sha)) {
            System.err.println("[jadx-plugin] cached jar failed sha256 check -> re-downloading");
            needDownload = true;
        }
        if (needDownload) {
            download(url, jar);
            if (pinned) {
                String got = sha256(jar);
                if (!got.equalsIgnoreCase(sha)) {
                    Files.deleteIfExists(jar);
                    System.err.println("[jadx-plugin] FATAL: sha256 mismatch after download");
                    System.err.println("[jadx-plugin]   expected " + sha);
                    System.err.println("[jadx-plugin]   got      " + got);
                    System.exit(3);
                }
                System.err.println("[jadx-plugin] sha256 verified");
            } else {
                System.err.println("[jadx-plugin] WARNING: sha256 not pinned (\"" + sha + "\") -> integrity NOT verified.");
                System.err.println("[jadx-plugin]          Pin the real release hash in .mcp.json before publishing (see PLUGIN.md).");
            }
        }

        // Use the same JVM that launched this source file to run the jar (avoids relying on a
        // second `java` being resolvable, and guarantees the version we already validated).
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", win ? "java.exe" : "java").toString();

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-jar");
        cmd.add(jar.toString());
        cmd.addAll(Arrays.asList(serverArgs));

        Process child = new ProcessBuilder(cmd).inheritIO().start();
        Runtime.getRuntime().addShutdownHook(new Thread(child::destroy));
        System.exit(child.waitFor());
    }

    private static void download(String url, Path dest) throws Exception {
        System.err.println("[jadx-plugin] downloading " + url);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        // Whole-response timeout so a stalled download fails loudly instead of hanging the MCP
        // startup forever. 10 min is generous for a ~50 MB jar on a slow link.
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        Path tmp = Paths.get(dest.toString() + ".tmp");
        HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        if (resp.statusCode() != 200) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw new IOException("download failed: HTTP " + resp.statusCode() + " for " + url);
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        System.err.println("[jadx-plugin] saved " + dest + " (" + Files.size(dest) + " bytes)");
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
