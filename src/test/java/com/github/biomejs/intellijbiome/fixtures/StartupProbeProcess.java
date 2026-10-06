package com.github.biomejs.intellijbiome.fixtures;

import java.nio.file.Files;
import java.nio.file.Path;

/** A real, portable child process whose lifetime is controlled by the startup regression tests. */
public final class StartupProbeProcess {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "exit":
                System.out.println(args[1]);
                System.exit(Integer.parseInt(args[2]));
                break;
            case "hang":
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(Long.MAX_VALUE);
                break;
            default:
                throw new IllegalArgumentException(args[0]);
        }
    }
}
