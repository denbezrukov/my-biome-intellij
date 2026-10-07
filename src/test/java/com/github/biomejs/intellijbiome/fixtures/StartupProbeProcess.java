package com.github.biomejs.intellijbiome.fixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.BufferedInputStream;
import java.util.regex.Pattern;

/** A real, portable child process whose lifetime is controlled by the startup regression tests. */
public final class StartupProbeProcess {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "lsp":
                serveLsp();
                break;
            case "exit":
                System.out.println(args[1]);
                System.exit(Integer.parseInt(args[2]));
                break;
            case "hang":
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(Long.MAX_VALUE);
                break;
            case "hang-child":
                new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), StartupProbeProcess.class.getName(),
                    "hang", args[2]).inheritIO().start();
                while (!Files.exists(Path.of(args[2]))) Thread.sleep(10);
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(Long.MAX_VALUE);
                break;
            case "hang-child-ignore-interrupt":
                new ProcessBuilder("/bin/sh", "-c", "trap '' INT; exec \"$@\"", "biome-probe-child",
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), StartupProbeProcess.class.getName(),
                    "hang", args[2]).inheritIO().start();
                while (!Files.exists(Path.of(args[2]))) Thread.sleep(10);
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(Long.MAX_VALUE);
                break;
            default:
                throw new IllegalArgumentException(args[0]);
        }
    }

    /** Minimal controlled wire peer so startup fixtures can use the real SDK manager lifecycle. */
    private static void serveLsp() throws Exception {
        var input = new BufferedInputStream(System.in);
        var idPattern = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]+\"|\\d+)");
        while (true) {
            int length = -1;
            var line = new StringBuilder();
            while (true) {
                int next = input.read();
                if (next == -1) return;
                if (next == '\n') {
                    String header = line.toString().trim();
                    line.setLength(0);
                    if (header.isEmpty()) break;
                    if (header.startsWith("Content-Length:")) length = Integer.parseInt(header.substring(15).trim());
                } else line.append((char) next);
            }
            if (length < 0) throw new IllegalStateException("Missing content length");
            String message = new String(input.readNBytes(length), StandardCharsets.UTF_8);
            var id = idPattern.matcher(message);
            if (id.find()) {
                String result = message.contains("\"initialize\"") ? "{\"capabilities\":{}}" : "null";
                byte[] response = ("{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + ",\"result\":" + result + "}")
                    .getBytes(StandardCharsets.UTF_8);
                System.out.print("Content-Length: " + response.length + "\r\n\r\n");
                System.out.write(response);
                System.out.flush();
            } else if (message.contains("\"exit\"")) return;
        }
    }
}
