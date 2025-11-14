package org.example.filestats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileStatsCliTest {

    static class RunResult {
        final int exitCode;
        final String out;
        final String err;

        RunResult(int exitCode, String out, String err) {
            this.exitCode = exitCode;
            this.out = out;
            this.err = err;
        }
    }

    private RunResult runCli(String... args) {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream bosOut = new ByteArrayOutputStream();
        ByteArrayOutputStream bosErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bosOut, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(bosErr, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = new picocli.CommandLine(new FileStatsCli()).execute(args);
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
        return new RunResult(code, bosOut.toString(StandardCharsets.UTF_8), bosErr.toString(StandardCharsets.UTF_8));
    }

    private static void write(Path file, String... lines) throws IOException {
        Files.createDirectories(file.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (int i = 0; i < lines.length; i++) {
                w.write(lines[i]);
                if (i < lines.length - 1) w.newLine();
            }
        }
    }

    private void seedBasicTree(Path root) throws IOException {
        write(root.resolve("A.java"),
                "  // comment java",
                "class A {}",
                "   ",
                "//comment");
        write(root.resolve("script.sh"),
                "#!/usr/bin/env bash",
                "   # header",
                "echo ok");
        write(root.resolve("notes.txt"), "one", "", "two");
        write(root.resolve("noext"), "line1");
        write(root.resolve("sub/Deep.java"), "class Deep{}");
        write(root.resolve("sub/x.tmp"), "tmp");
    }

    @Test
    void path_only_plain_no_recursive(@TempDir Path dir) throws Exception {
        seedBasicTree(dir);
        RunResult rr = runCli(dir.toString());
        assertEquals(0, rr.exitCode);
        assertTrue(rr.out.contains("java"));
        assertTrue(rr.out.contains("sh"));
        assertTrue(rr.out.contains("txt"));
        assertTrue(rr.out.contains("(noext)"));
        assertFalse(rr.out.contains("sub"));
    }

    @Test
    void recursive_full_depth_json(@TempDir Path dir) throws Exception {
        seedBasicTree(dir);
        RunResult rr = runCli(dir.toString(), "--recursive", "--output=json");
        assertEquals(0, rr.exitCode);
        assertTrue(rr.out.contains("\"java\""));
        assertTrue(rr.out.contains("\"files\""));
        assertTrue(rr.out.contains("\"bytes\""));
        assertTrue(rr.out.contains("\"lines\""));
        assertTrue(rr.out.contains("\"nonEmpty\""));
        assertTrue(rr.out.contains("\"comment\""));
    }

    @Test
    void recursive_max_depth_limits(@TempDir Path dir) throws Exception {
        seedBasicTree(dir);
        RunResult rr = runCli(dir.toString(), "--recursive", "--max-depth=1", "--output=json");
        assertEquals(0, rr.exitCode);
        assertTrue(rr.out.contains("\"java\""));
    }

    @Test
    void threads_option_is_accepted(@TempDir Path dir) throws Exception {
        seedBasicTree(dir);
        RunResult rr = runCli(dir.toString(), "--recursive", "--threads=1", "--output=plain");
        assertEquals(0, rr.exitCode);
        assertTrue(rr.out.contains("Ext"));
    }

    @Test
    void include_ext_filters(@TempDir Path dir) throws Exception {
        seedBasicTree(dir);
        RunResult rr = runCli(dir.toString(), "--recursive", "--include-ext=java,sh", "--output=json");
        assertEquals(0, rr.exitCode);
        assertTrue(rr.out.contains("\"java\""));
        assertTrue(rr.out.contains("\"sh\""));
        assertFalse(rr.out.contains("\"txt\""));
        assertFalse(rr.out.contains("\"(noext)\""));
    }

    @Test
    void exclude_ext_filters(@TempDir Path dir) throws Exception {
        seedBasicTree(dir);
        RunResult rr = runCli(dir.toString(), "--recursive", "--exclude-ext=txt", "--output=json");
        assertEquals(0, rr.exitCode);
        assertFalse(rr.out.contains("\"txt\""));
        assertTrue(rr.out.contains("\"java\""));
        assertTrue(rr.out.contains("\"sh\""));
    }

    @Test
    void include_then_exclude_precedence(@TempDir Path dir) throws Exception {
        seedBasicTree(dir);
        RunResult rr = runCli(dir.toString(), "--recursive", "--include-ext=java,sh", "--exclude-ext=sh", "--output=json");
        assertEquals(0, rr.exitCode);
        assertTrue(rr.out.contains("\"java\""));
        assertFalse(rr.out.contains("\"sh\""));
    }

    @Test
    void gitignore_respected(@TempDir Path dir) throws Exception {
        seedBasicTree(dir);
        Files.writeString(dir.resolve(".gitignore"), String.join("\n",
                "*.tmp",
                "sub/"));
        RunResult rr = runCli(dir.toString(), "--recursive", "--git-ignore", "--output=json");
        assertEquals(0, rr.exitCode);
        assertTrue(rr.out.contains("\"java\""));
        assertFalse(rr.out.contains("\"tmp\""));
    }

    @Test
    void output_xml_format(@TempDir Path dir) throws Exception {
        seedBasicTree(dir);
        RunResult rr = runCli(dir.toString(), "--recursive", "--output=xml");
        assertEquals(0, rr.exitCode);
        assertTrue(rr.out.startsWith("<stats>"));
        assertTrue(rr.out.contains("<ext name=\"java\">"));
        assertTrue(rr.out.contains("<files>"));
        assertTrue(rr.out.contains("<bytes>"));
    }

    @Test
    void comment_and_nonempty_counting(@TempDir Path dir) throws Exception {
        write(dir.resolve("C.java"),
                "   // c1",
                "class C{}",
                "   ",
                "//c2");

        write(dir.resolve("run.sh"),
                "#!/usr/bin/env bash",
                "  # only line comment",
                "echo go");

        RunResult rr = runCli(dir.toString(), "--recursive", "--include-ext=java,sh", "--output=json");
        assertEquals(0, rr.exitCode);
        assertTrue(rr.out.contains("\"java\""));
        assertTrue(rr.out.contains("\"sh\""));
    }

    @Test
    void invalid_path_returns_error(@TempDir Path dir) {
        Path noDir = dir.resolve("no_such_dir");
        RunResult rr = runCli(noDir.toString());
        assertNotEquals(0, rr.exitCode);
        assertTrue(rr.err.contains("Путь не является каталогом"));
    }
}
