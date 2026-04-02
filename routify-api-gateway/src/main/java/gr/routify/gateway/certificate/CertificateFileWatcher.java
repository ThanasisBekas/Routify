package gr.routify.gateway.certificate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loads PEM certificates from file and directory sources into {@link CertificateRegistry}
 * at startup, then keeps them hot-reloaded via an NIO {@code WatchService} daemon
 * (virtual thread) plus a scheduled polling fallback for Kubernetes ConfigMap
 * symlink rotations.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class CertificateFileWatcher implements SchedulingConfigurer {

    private static final Set<String> CERT_EXTENSIONS = Set.of(".pem", ".cer", ".crt");

    private final CertificateRegistry registry;
    private final CertificateStoreProperties properties;
    private final ResourceLoader resourceLoader;

    private final ConcurrentHashMap<String, Long> lastModifiedTimes = new ConcurrentHashMap<>();
    private final Map<Path, List<CertificateStoreProperties.FileSource>> watchedFileDirs = new HashMap<>();
    private final Map<Path, CertificateStoreProperties.DirectorySource> watchedDirectorySources = new HashMap<>();
    private final AtomicBoolean watcherStarted = new AtomicBoolean(false);

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        var interval = properties.getFileWatchInterval();
        log.info("Scheduling certificate file-change poll every {}", interval);
        taskRegistrar.addFixedDelayTask(this::pollForFileChanges, interval);
    }

    /** Loads all configured sources into the registry on startup, then starts the file watcher. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void initialize() {
        var fileSources = properties.getFileSources();
        var dirSources  = properties.getDirectorySources();

        boolean hasAnything = (fileSources != null && !fileSources.isEmpty())
                || (dirSources != null && !dirSources.isEmpty());

        if (!hasAnything) {
            log.info("No file-based or directory-based certificate sources configured");
            return;
        }
        log.info("Initializing certificate file watcher — fileSources={}, directorySources={}",
                fileSources == null ? 0 : fileSources.size(),
                dirSources  == null ? 0 : dirSources.size());
        loadSources();
        startWatchServiceDaemon();
    }

    /** Re-reads all sources into the registry. Safe to call at any time. */
    public void reloadSources() {
        var fileSources = properties.getFileSources();
        var dirSources  = properties.getDirectorySources();
        log.info("Reloading certificate sources — fileSources={}, directorySources={}",
                fileSources == null ? 0 : fileSources.size(),
                dirSources  == null ? 0 : dirSources.size());
        loadSources();
    }

    private void loadSources() {
        var fileSources = properties.getFileSources();
        if (fileSources != null) fileSources.forEach(this::loadFileSource);

        var dirSources = properties.getDirectorySources();
        if (dirSources != null) dirSources.forEach(this::loadDirectorySource);
    }

    private void loadFileSource(CertificateStoreProperties.FileSource source) {
        try {
            var certPath = resolveWatchPath(source.getCertificatePath());
            var pemContent = readText(source.getCertificatePath());
            var certs = PemCertificateParser.parseCertificates(pemContent);
            PrivateKey pk = resolvePrivateKey(source.getPrivateKeyPath(), source.getPrivateKeyPassword());
            var src = "file:%s".formatted(source.getCertificatePath());
            for (var cert : certs) {
                registry.register(source.getLogicalId(), cert, pk, src);
            }
            if (certPath != null && Files.exists(certPath)) {
                // Use a normalised path string as key so that poll and watch use the same key
                lastModifiedTimes.put(certPath.toAbsolutePath().normalize().toString(),
                        Files.getLastModifiedTime(certPath).toMillis());
            }
        } catch (Exception e) {
            log.error("Failed to load cert from file: id='{}', path='{}'",
                    source.getLogicalId(), source.getCertificatePath(), e);
        }
    }

    private void loadDirectorySource(CertificateStoreProperties.DirectorySource source) {
        var dirPath = resolveDirectoryPath(source.getDirectoryPath());
        if (dirPath == null || !Files.isDirectory(dirPath)) {
            log.warn("Directory source path does not exist or is not a directory: '{}'",
                    source.getDirectoryPath());
            return;
        }
        try (var stream = Files.list(dirPath)) {
            stream.filter(this::isCertificateFile)
                  .forEach(file -> loadCertificateFromDirectoryEntry(file, source));
        } catch (IOException e) {
            log.error("Failed to scan directory '{}': {}", source.getDirectoryPath(), e.getMessage(), e);
        }
    }

    private void loadCertificateFromDirectoryEntry(Path file,
                                                    CertificateStoreProperties.DirectorySource source) {
        var logicalId = resolveLogicalId(source.getLogicalId(), file);
        try {
            var pemContent = Files.readString(file, StandardCharsets.UTF_8);
            var certs = PemCertificateParser.parseCertificates(pemContent);
            PrivateKey pk = resolvePrivateKey(source.getPrivateKeyPath(), source.getPrivateKeyPassword());
            var src = "dir:%s!%s".formatted(source.getDirectoryPath(), file.getFileName());
            for (var cert : certs) {
                registry.register(logicalId, cert, pk, src);
            }
            lastModifiedTimes.put(file.toAbsolutePath().normalize().toString(),
                    Files.getLastModifiedTime(file).toMillis());
            log.debug("Loaded certificate from directory entry: logicalId='{}', file='{}'", logicalId, file);
        } catch (Exception e) {
            log.error("Failed to load cert from directory entry: logicalId='{}', file='{}'",
                    logicalId, file, e);
        }
    }

    private void startWatchServiceDaemon() {
        if (!watcherStarted.compareAndSet(false, true)) {
            log.debug("WatchService daemon already running — skipping duplicate start");
            return;
        }

        var fileSources = properties.getFileSources();
        if (fileSources != null) {
            for (var source : fileSources) {
                if (!source.isWatchForChanges()) continue;
                var resolved = resolveWatchPath(source.getCertificatePath());
                if (resolved == null) {
                    log.info("Skipping watch for non-filesystem resource: '{}' (id={})",
                            source.getCertificatePath(), source.getLogicalId());
                    continue;
                }
                var dir = resolved.getParent();
                if (dir != null) watchedFileDirs.computeIfAbsent(dir, k -> new ArrayList<>()).add(source);
            }
        }

        var dirSources = properties.getDirectorySources();
        if (dirSources != null) {
            for (var source : dirSources) {
                if (!source.isWatchForChanges()) continue;
                var dir = resolveDirectoryPath(source.getDirectoryPath());
                if (dir != null && Files.isDirectory(dir)) {
                    watchedDirectorySources.put(dir, source);
                } else {
                    log.warn("Skipping watch for missing directory source: '{}'", source.getDirectoryPath());
                }
            }
        }

        if (watchedFileDirs.isEmpty() && watchedDirectorySources.isEmpty()) {
            watcherStarted.set(false);
            return;
        }

        Thread.ofVirtual().name("cert-file-watcher").start(this::runWatchService);
        log.info("Started virtual-thread certificate watcher — fileDirs={}, directorySourceDirs={}",
                watchedFileDirs.size(), watchedDirectorySources.size());
    }

    private void runWatchService() {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            for (var dir : watchedFileDirs.keySet()) {
                if (Files.exists(dir)) dir.register(ws,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_CREATE);
            }
            for (var dir : watchedDirectorySources.keySet()) {
                if (Files.exists(dir)) dir.register(ws,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
            }

            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = ws.take();
                var watchedDir = (Path) key.watchable();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    var changed = watchedDir.resolve((Path) event.context());
                    handleWatchEvent(event.kind(), watchedDir, changed);
                }
                if (!key.reset()) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Certificate file watcher error", e);
        }
    }

    private void handleWatchEvent(WatchEvent.Kind<?> kind, Path watchedDir, Path changed) {
        var fileSources = watchedFileDirs.get(watchedDir);
        if (fileSources != null) {
            fileSources.stream()
                    .filter(s -> matchesResolvedPath(s.getCertificatePath(), changed))
                    .forEach(s -> {
                        log.info("File change detected [{}]: id='{}', file={}", kind.name(),
                                s.getLogicalId(), changed);
                        loadFileSource(s);
                    });
        }

        var dirSource = watchedDirectorySources.get(watchedDir);
        if (dirSource != null) {
            if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                handleDirectoryEntryDeleted(changed, dirSource);
            } else if (isCertificateFile(changed)) {
                log.info("Directory cert {} [{}]: dir='{}', file='{}'",
                        kind == StandardWatchEventKinds.ENTRY_CREATE ? "added" : "modified",
                        dirSource.getDirectoryPath(), changed.getFileName(), kind.name());
                loadCertificateFromDirectoryEntry(changed, dirSource);
            }
        }
    }

    private void handleDirectoryEntryDeleted(Path deleted, CertificateStoreProperties.DirectorySource source) {
        if (!isCertificateFile(deleted)) return;
        var logicalId = resolveLogicalId(source.getLogicalId(), deleted);
        var expectedSrc = "dir:%s!%s".formatted(source.getDirectoryPath(), deleted.getFileName());
        log.info("Directory cert removed: logicalId='{}', file='{}' — deactivating matching registry entries",
                logicalId, deleted.getFileName());
        registry.getAllVersions(logicalId).stream()
                .filter(v -> expectedSrc.equals(v.source()))
                .forEach(v -> registry.deactivate(logicalId, v.version()));
        lastModifiedTimes.remove(deleted.toAbsolutePath().normalize().toString());
    }

    public void pollForFileChanges() {
        var fileSources = properties.getFileSources();
        if (fileSources != null) {
            for (var src : fileSources) {
                if (!src.isWatchForChanges()) continue;
                var resolved = resolveWatchPath(src.getCertificatePath());
                if (resolved == null) continue;
                pollFile(resolved.toAbsolutePath().normalize().toString(), () -> loadFileSource(src));
            }
        }

        var dirSources = properties.getDirectorySources();
        if (dirSources != null) {
            for (var dirSource : dirSources) {
                if (!dirSource.isWatchForChanges()) continue;
                var dir = resolveDirectoryPath(dirSource.getDirectoryPath());
                if (dir == null || !Files.isDirectory(dir)) continue;
                try (var stream = Files.list(dir)) {
                    stream.filter(this::isCertificateFile).forEach(file -> {
                        pollFile(file.toAbsolutePath().normalize().toString(),
                                () -> loadCertificateFromDirectoryEntry(file, dirSource));
                    });
                } catch (IOException e) {
                    log.warn("Poll error scanning directory '{}': {}", dirSource.getDirectoryPath(),
                            e.getMessage());
                }
            }
        }
    }

    private void pollFile(String pathKey, Runnable onChanged) {
        try {
            var p = Path.of(pathKey);
            if (!Files.exists(p)) return;
            long cur = Files.getLastModifiedTime(p).toMillis();
            var prev = lastModifiedTimes.get(pathKey);
            if (prev == null) {
                // First time we see this file during polling — record baseline; don't reload
                // (it was already loaded at startup via loadSources).
                lastModifiedTimes.put(pathKey, cur);
            } else if (cur > prev) {
                log.info("Polling detected change: path='{}'", pathKey);
                lastModifiedTimes.put(pathKey, cur);
                onChanged.run();
            }
        } catch (Exception e) {
            log.warn("Poll error for '{}': {}", pathKey, e.getMessage());
        }
    }

    private boolean isCertificateFile(Path path) {
        var name = path.getFileName().toString().toLowerCase();
        return CERT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private String resolveLogicalId(String configured, Path file) {
        if (configured != null && !configured.isBlank()) return configured;
        var name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private PrivateKey resolvePrivateKey(String keyPath, String password) {
        if (keyPath == null) return null;
        var keyContent = readTextIfExists(keyPath);
        if (keyContent == null) return null;
        char[] pw = password != null ? password.toCharArray() : null;
        return PemCertificateParser.parsePrivateKey(keyContent, pw);
    }

    private boolean matchesResolvedPath(String location, Path changed) {
        var resolved = resolveWatchPath(location);
        return resolved != null && (changed.equals(resolved)
                || changed.getFileName().equals(resolved.getFileName()));
    }

    private String readText(String location) throws IOException {
        var resource = resourceLoader.getResource(location);
        if (resource.exists()) {
            try (var inputStream = resource.getInputStream()) {
                return StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
            }
        }
        try {
            var path = Path.of(location);
            if (Files.exists(path)) return Files.readString(path);
        } catch (InvalidPathException ignored) {}
        throw new IOException("Certificate resource does not exist: " + location);
    }

    private String readTextIfExists(String location) {
        try {
            return readText(location);
        } catch (IOException e) {
            return null;
        }
    }

    private Path resolveWatchPath(String location) {
        if (location == null) return null;
        // classpath: resources are not watchable via NIO WatchService
        if (location.startsWith("classpath:")) return null;
        // Strip the "file:" scheme prefix so the remainder can be used as a filesystem path
        String path = location.startsWith("file:") ? location.substring("file:".length()) : location;
        try {
            return Path.of(path);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private Path resolveDirectoryPath(String directoryPath) {
        if (directoryPath == null) return null;
        try {
            return Path.of(directoryPath);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}

