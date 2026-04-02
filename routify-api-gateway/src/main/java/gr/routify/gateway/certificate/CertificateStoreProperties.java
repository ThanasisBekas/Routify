package gr.routify.gateway.certificate;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Binds {@code certificate-store.*} — configures file sources and directory sources
 * for certificate hot-reload.
 */
@Data
@ConfigurationProperties(prefix = "certificate-store")
public class CertificateStoreProperties {

    /**
     * How far in advance to warn about expiring certs.
     * Plain numbers treated as days (e.g. {@code 30}, {@code 14d}).
     */
    @DurationUnit(ChronoUnit.DAYS)
    private Duration expiryWarning = Duration.ofDays(30);

    /**
     * How often to poll for file changes.
     * Plain numbers treated as seconds (e.g. {@code 30}, {@code 5m}).
     */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration fileWatchInterval = Duration.ofSeconds(30);

    /** Individual certificate files to load. */
    private List<FileSource> fileSources = new ArrayList<>();

    /**
     * Directories to scan — all .pem/.cer/.crt files inside are loaded and watched automatically.
     */
    private List<DirectorySource> directorySources = new ArrayList<>();

    @Data
    public static class FileSource {
        private String logicalId;
        private String certificatePath;
        private String privateKeyPath;
        private String privateKeyPassword;
        private boolean watchForChanges = false;
    }

    /**
     * Loads every .pem/.cer/.crt file in a directory into the registry.
     * When {@code logicalId} is blank, the filename stem is used as the logical ID.
     */
    @Data
    public static class DirectorySource {
        private String directoryPath;
        /** Logical ID for all certs in this directory. Leave blank to use each file's stem as its ID. */
        private String logicalId;
        /** Optional private key shared by all certs in the directory. */
        private String privateKeyPath;
        private String privateKeyPassword;
        private boolean watchForChanges = false;
    }
}
