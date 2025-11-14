package org.example.filestats;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(name = "filestats",
        description = "Считает статистику по файлам в каталоге (по расширениям)",
        mixinStandardHelpOptions = true,
        version = "0.0.2")
public class FileStatsCli implements Callable<Integer> {

    @Parameters(index = "0", description = "Путь до каталога, по которому выполнять сбор статистики")
    private Path path;

    @Option(names = "--recursive", description = "Рекурсивный обход")
    private boolean recursive = false;

    @Option(names = "--max-depth", paramLabel = "<N>", description = "Глубина рекурсии (считая уровни ниже <path>)")
    private Integer maxDepth;

    @Option(names = {"--threads", "--thread"}, paramLabel = "<N>", description = "Количество потоков (по умолчанию: ядра CPU)")
    private int threads = 0;

    @Option(names = "--include-ext", split = ",", paramLabel = "<ext1,ext2,...>", description = "Обрабатывать только указанные расширения (без точки)")
    private Set<String> includeExt;

    @Option(names = "--exclude-ext", split = ",", paramLabel = "<ext1,ext2,...>", description = "Не обрабатывать указанные расширения (без точки)")
    private Set<String> excludeExt;

    @Option(names = "--git-ignore", description = "Учитывать .gitignore (упрощённая поддержка)")
    private boolean gitIgnore = false;

    public enum Output {plain, json, xml}

    @Option(names = "--output", paramLabel = "<plain|json|xml>", description = "Формат вывода (по умолчанию: plain)")
    private Output output = Output.plain;

    public static void main(String[] args) {
        int code = new CommandLine(new FileStatsCli()).execute(args);
        System.exit(code);
    }

    @Override
    public Integer call() throws Exception {
        Path root = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new CommandLine.ParameterException(new CommandLine(this), "Путь не является каталогом: " + root);
        }
        if (includeExt != null) includeExt = toLower(includeExt);
        if (excludeExt != null) excludeExt = toLower(excludeExt);

        List<Path> files = listFiles(root);
        Map<String, Stats> stats = compute(files, root);

        switch (output) {
            case json -> {
                System.out.println(toJson(stats));
            }
            case xml -> {
                System.out.println(toXml(stats));
            }
            default -> {
                System.out.println(toPlain(stats));
            }
        }
        return 0;
    }

    private List<Path> listFiles(Path root) throws IOException {
        int depth = recursive ? (maxDepth != null ? Math.max(1, maxDepth) : Integer.MAX_VALUE) : 1;
        try (Stream<Path> stream = Files.walk(root, depth, FileVisitOption.FOLLOW_LINKS)) {
            List<Path> all = stream.filter(p -> !Files.isDirectory(p)).collect(Collectors.toList());
            if (includeExt != null && !includeExt.isEmpty()) {
                all = all.stream().filter(p -> includeExt.contains(extOf(p))).collect(Collectors.toList());
            }
            if (excludeExt != null && !excludeExt.isEmpty()) {
                all = all.stream().filter(p -> !excludeExt.contains(extOf(p))).collect(Collectors.toList());
            }
            if (gitIgnore) {
                GitIgnore gi = GitIgnore.load(root);
                all = all.stream().filter(p -> !gi.ignored(root.relativize(p))).collect(Collectors.toList());
            }
            return all;
        }
    }

    private Map<String, Stats> compute(List<Path> files, Path root) throws Exception {
        Map<String, Stats> statsByExt = new ConcurrentHashMap<>();
        int nThreads = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (Path f : files) {
                tasks.add(() -> {
                    processFile(f, root, statsByExt);
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (Exception ignore) {
                }
            }
        } finally {
            pool.shutdown();
        }
        return statsByExt;
    }

    private void processFile(Path file, Path root, Map<String, Stats> statsByExt) {
        try {
            long bytes = Files.size(file);
            String ext = extOf(file);
            boolean javaLike = ext.equals("java");
            boolean bashLike = ext.equals("sh") || ext.equals("bash");

            long total = 0, nonEmpty = 0, comment = 0;
            try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    total++;
                    String trimmedRight = trimRight(line);
                    String ltrim = ltrim(line);
                    if (!trimmedRight.isBlank()) {
                        nonEmpty++;
                        if (javaLike && ltrim.startsWith("//")) comment++;
                        else if (bashLike && ltrim.startsWith("#")) comment++;
                    }
                }
            } catch (Exception e) {
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                    int b;
                    long lines = 0;
                    while ((b = in.read()) != -1) if (b == '\n') lines++;
                    total = lines;
                } catch (Exception ignored) {
                }
            }

            Stats st = statsByExt.computeIfAbsent(ext, k -> new Stats());
            st.files.increment();
            st.bytes.add(bytes);
            st.totalLines.add(total);
            st.nonEmptyLines.add(nonEmpty);
            st.commentLines.add(comment);
        } catch (Exception e) {
            System.err.println("Не удалось обработать файл: " + root.relativize(file) + " -> " + e.getMessage());
        }
    }

    private static String extOf(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "(noext)";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String ltrim(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    private static String trimRight(String s) {
        int i = s.length() - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return s.substring(0, i + 1);
    }

    private static Set<String> toLower(Set<String> in) {
        return in.stream().map(x -> x.startsWith(".") ? x.substring(1) : x).map(x -> x.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private String toPlain(Map<String, Stats> map) {
        List<String> exts = new ArrayList<>(map.keySet());
        Collections.sort(exts);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-8s %-7s %-11s %-7s %-9s %-8s%n", "Ext", "Files", "Bytes", "Lines", "NonEmpty", "Comment"));
        for (String ext : exts) {
            Stats s = map.get(ext);
            sb.append(String.format("%-8s %-7d %-11d %-7d %-9d %-8d%n",
                    ext, s.files.sum(), s.bytes.sum(), s.totalLines.sum(), s.nonEmptyLines.sum(), s.commentLines.sum()));
        }
        return sb.toString();
    }

    private String toJson(Map<String, Stats> map) throws IOException {
        ObjectMapper om = new ObjectMapper();
        Map<String, SimpleStats> dto = map.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> SimpleStats.from(e.getValue())
        ));
        return om.writerWithDefaultPrettyPrinter().writeValueAsString(dto);
    }

    private String toXml(Map<String, Stats> map) throws IOException {
        List<ExtEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Stats> e : map.entrySet()) {
            entries.add(ExtEntry.from(e.getKey(), e.getValue()));
        }
        StatsXml xml = new StatsXml(entries.stream()
                .sorted((a, b) -> a.name.compareTo(b.name)).collect(Collectors.toList()));
        XmlMapper xm = new XmlMapper();
        return xm.writerWithDefaultPrettyPrinter().writeValueAsString(xml);
    }

    static class Stats {
        LongAdder files = new LongAdder();
        LongAdder bytes = new LongAdder();
        LongAdder totalLines = new LongAdder();
        LongAdder nonEmptyLines = new LongAdder();
        LongAdder commentLines = new LongAdder();
    }

    static class SimpleStats {
        public long files, bytes, lines, nonEmpty, comment;

        static SimpleStats from(Stats s) {
            SimpleStats r = new SimpleStats();
            r.files = s.files.sum();
            r.bytes = s.bytes.sum();
            r.lines = s.totalLines.sum();
            r.nonEmpty = s.nonEmptyLines.sum();
            r.comment = s.commentLines.sum();
            return r;
        }
    }

    @JacksonXmlRootElement(localName = "stats")
    static class StatsXml {
        @JacksonXmlElementWrapper(useWrapping = false)
        @JsonProperty("ext")
        public List<ExtEntry> items;

        public StatsXml() {
        }

        public StatsXml(List<ExtEntry> items) {
            this.items = items;
        }
    }

    static class ExtEntry {
        @JacksonXmlProperty(isAttribute = true, localName = "name")
        public String name;
        public long files, bytes, lines, nonEmpty, comment;

        public ExtEntry() {
        }

        static ExtEntry from(String name, Stats s) {
            ExtEntry e = new ExtEntry();
            e.name = name;
            e.files = s.files.sum();
            e.bytes = s.bytes.sum();
            e.lines = s.totalLines.sum();
            e.nonEmpty = s.nonEmptyLines.sum();
            e.comment = s.commentLines.sum();
            return e;
        }
    }

    static class GitIgnore {
        private final List<Rule> rules;

        private GitIgnore(List<Rule> rules) {
            this.rules = rules;
        }

        static GitIgnore load(Path root) {
            Path gi = root.resolve(".gitignore");
            if (!Files.exists(gi)) return new GitIgnore(List.of());
            List<Rule> rs = new ArrayList<>();
            try (BufferedReader br = Files.newBufferedReader(gi, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    String raw = line.trim();
                    if (raw.isEmpty() || raw.startsWith("#")) continue;
                    boolean neg = raw.startsWith("!");
                    String pat = neg ? raw.substring(1) : raw;
                    boolean dirOnly = pat.endsWith("/");
                    if (dirOnly) pat = pat.substring(0, pat.length() - 1);
                    Pattern regex = toRegex(pat);
                    rs.add(new Rule(regex, neg, dirOnly));
                }
            } catch (IOException ignore) {
            }
            return new GitIgnore(rs);
        }

        boolean ignored(Path relativePath) {
            String unix = relativePath.toString().replace('\\', '/');
            boolean isDir = false;
            boolean ignored = false;
            for (Rule r : rules) {
                if (r.dirOnly && !isDir) {}
                if (r.pattern.matcher(unix).matches()) {
                    ignored = !r.negated;
                }
            }
            return ignored;
        }

        private static Pattern toRegex(String gitPat) {
            boolean anchored = gitPat.startsWith("/");
            String p = anchored ? gitPat.substring(1) : gitPat;
            StringBuilder sb = new StringBuilder();
            if (!anchored) sb.append("(.*/)?");
            int i = 0;
            while (i < p.length()) {
                char c = p.charAt(i);
                if (c == '*') {
                    if (i + 1 < p.length() && p.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i += 2;
                    } else {
                        sb.append("[^/]*");
                        i++;
                    }
                } else if (c == '?') {
                    sb.append("[^/]");
                    i++;
                } else if (c == '.') {
                    sb.append("\\.");
                    i++;
                } else if (c == '/') {
                    sb.append("/");
                    i++;
                } else {
                    if ("+()^$|[]{}".indexOf(c) >= 0) sb.append('\\');
                    sb.append(c);
                    i++;
                }
            }
            String regex = "^" + sb + "(/.*)?$";
            return Pattern.compile(regex);
        }

        static class Rule {
            final Pattern pattern;
            final boolean negated;
            final boolean dirOnly;

            Rule(Pattern p, boolean n, boolean d) {
                pattern = p;
                negated = n;
                dirOnly = d;
            }
        }
    }
}
