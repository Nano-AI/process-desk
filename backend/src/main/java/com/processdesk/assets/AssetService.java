package com.processdesk.assets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads and writes the BPMN/DMN files on disk. */
@Service
public class AssetService {

    /** Guards against path traversal: a name, an extension, nothing else. */
    private static final Pattern SAFE_NAME = Pattern.compile("[\\w.-]+\\.(bpmn|bpmn2|dmn)");

    private final Path assetsDir;

    public AssetService(@Value("${processdesk.assets-dir:./assets}") String assetsDir) {
        this.assetsDir = Path.of(assetsDir).toAbsolutePath().normalize();
    }

    public List<String> list() {
        try (Stream<Path> files = Files.list(assetsDir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> SAFE_NAME.matcher(name).matches())
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String read(String name) throws IOException {
        return Files.readString(resolve(name), StandardCharsets.UTF_8);
    }

    public void write(String name, String xml) throws IOException {
        Files.writeString(resolve(name), xml, StandardCharsets.UTF_8);
    }

    private Path resolve(String name) {
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid asset name: " + name);
        }
        Path resolved = assetsDir.resolve(name).normalize();
        if (!resolved.startsWith(assetsDir)) {
            throw new IllegalArgumentException("Invalid asset name: " + name);
        }
        return resolved;
    }
}
