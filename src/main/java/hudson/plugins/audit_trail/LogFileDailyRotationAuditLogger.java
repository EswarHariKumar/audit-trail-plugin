package hudson.plugins.audit_trail;

import hudson.Extension;
import hudson.model.Descriptor;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.apache.commons.io.FilenameUtils;
import org.kohsuke.stapler.DataBoundConstructor;

public class LogFileDailyRotationAuditLogger extends AbstractLogFileAuditLogger {

    private static final Logger LOGGER = Logger.getLogger(LogFileDailyRotationAuditLogger.class.getName());
    static final String DAILY_ROTATING_FILE_REGEX_PATTERN = "-[0-9]{4}-[0-9]{2}-[0-9]{2}" + ".*" + "(?<!lck)$";

    private transient ZonedDateTime initInstant;
    private transient Path basePattern;

    String getLogFilePath() {
        return computePattern();
    }

    @Override
    FileHandler getLogFileHandler() throws IOException {
        return new FileHandler(getLogFilePath(), 0, 1, true);
    }

    @DataBoundConstructor
    public LogFileDailyRotationAuditLogger(String log, int count, String logSeparator) {
        super(log, count, logSeparator);
        this.basePattern = Paths.get(log);
        initializeDailyRotation();
    }

    Object readResolve() {
        this.basePattern = Paths.get(getLog());
        super.readResolve();
        initializeDailyRotation();
        return this;
    }

    /**
     * Initializes the logger to today's deterministic file name.
     *
     * Older versions recursively scanned the log directory to find the latest daily
     * file on disk. That made Jenkins startup depend on the size and latency of the
     * log filesystem. Since the daily file name is derived from today's date, a
     * restart during the same day will still append to the same file without scanning.
     */
    private void initializeDailyRotation() {
        initInstant = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS);
        configure();
    }

    String computePattern() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
        String formattedInstant = formatter.format(initInstant);
        String computedFileName =
                String.format("%s-%s", FilenameUtils.getName(basePattern.toString()), formattedInstant);
        Path parentFolder = basePattern.getParent();
        if (parentFolder != null) {
            return parentFolder.resolve(computedFileName).toString();
        }
        return computedFileName;
    }

    private boolean shouldRotate() {
        return ZonedDateTime.now().isAfter(initInstant.plus(Duration.ofDays(1)));
    }

    /**
     * Rotates the daily rotation logger
     */
    private void rotate() {
        if (getHandler() != null) {
            getHandler().close();
        }
        initInstant = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS);
        configure();
        // After rotating remove old files
        removeOldFiles();
    }

    private void removeOldFiles() {
        Path directoryPath = basePattern.getParent();
        if (directoryPath != null) {
            try {
                List<Path> files = listDailyRotationFiles(directoryPath);
                if (files.size() > getCount()) {
                    files.sort(Comparator.comparing(this::lastModifiedMillis).reversed());
                    List<Path> toDelete = files.subList(getCount(), files.size());
                    for (Path file : toDelete) {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException e) {
                            LOGGER.log(
                                    Level.SEVERE,
                                    "File " + file.getFileName() + " could not be removed on rotate operation",
                                    e);
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not inspect daily rotation files for cleanup", e);
            }
        }
    }

    private List<Path> listDailyRotationFiles(Path directoryPath) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(directoryPath)) {
            return files;
        }

        Pattern fileNamePattern = Pattern.compile(
                ".*" + Pattern.quote(FilenameUtils.getName(basePattern.toString())) + DAILY_ROTATING_FILE_REGEX_PATTERN);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directoryPath)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)
                        && fileNamePattern.matcher(path.getFileName().toString()).matches()) {
                    files.add(path);
                }
            }
        }
        return files;
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not read last modified time for " + path, e);
            return 0L;
        }
    }

    @Override
    public void log(String event) {
        // to avoid synchronizing the whole method
        if (shouldRotate()) {
            synchronized (this) {
                if (shouldRotate()) rotate();
            }
        }
        super.log(event);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<AuditLogger> {

        @Override
        public String getDisplayName() {
            return "Log file daily rotation";
        }
    }
}
