package com.linkforge.service.inspection;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.Set;

/**
 * Configuration properties governing safe, read-only codebase inspection.
 */
@Configuration
@ConfigurationProperties(prefix = "linkforge.inspection")
public class CodebaseInspectionProperties {

    /**
     * Root directory under which repositories must reside.
     * Traversal outside this approved root is strictly rejected.
     */
    private String approvedRoot = System.getProperty("user.dir");

    /**
     * Maximum allowed file count in an inspected repository.
     */
    private int maxFileCount = 500;

    /**
     * Maximum total allowed size across all files in an inspected repository (default 10 MB).
     */
    private long maxTotalSizeBytes = 10L * 1024L * 1024L;

    /**
     * Maximum individual file size in bytes (default 512 KB).
     */
    private long maxFileSizeBytes = 512L * 1024L;

    /**
     * Maximum number of lines to capture for bounded snippet inspection.
     */
    private int maxSnippetLines = 40;

    /**
     * Maximum number of source file paths included in bounded sample evidence.
     */
    private int maxEvidenceFiles = 30;

    /**
     * Permitted file extensions for source files, configurations, and documentation.
     */
    private Set<String> supportedExtensions = new HashSet<>(Set.of(
            ".java", ".kt", ".groovy", ".scala",
            ".py", ".js", ".ts", ".jsx", ".tsx",
            ".html", ".css", ".scss",
            ".xml", ".json", ".yaml", ".yml", ".properties", ".toml", ".sql",
            ".md", ".txt", ".gradle",
            ".gitignore", ".gitattributes", ".env.example", ".dockerignore", "dockerfile"
    ));

    /**
     * Explicitly blocked binary, executable, and script extensions.
     */
    private Set<String> unsupportedExtensions = new HashSet<>(Set.of(
            ".exe", ".bin", ".jar", ".war", ".ear", ".class",
            ".so", ".dylib", ".dll",
            ".zip", ".tar", ".gz", ".7z", ".iso", ".dmg",
            ".sh", ".bash", ".bat", ".cmd", ".ps1"
    ));

    public String getApprovedRoot() {
        return approvedRoot;
    }

    public void setApprovedRoot(String approvedRoot) {
        if (approvedRoot != null && !approvedRoot.isBlank()) {
            this.approvedRoot = approvedRoot;
        }
    }

    public int getMaxFileCount() {
        return maxFileCount;
    }

    public void setMaxFileCount(int maxFileCount) {
        this.maxFileCount = maxFileCount;
    }

    public long getMaxTotalSizeBytes() {
        return maxTotalSizeBytes;
    }

    public void setMaxTotalSizeBytes(long maxTotalSizeBytes) {
        this.maxTotalSizeBytes = maxTotalSizeBytes;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public int getMaxSnippetLines() {
        return maxSnippetLines;
    }

    public void setMaxSnippetLines(int maxSnippetLines) {
        this.maxSnippetLines = maxSnippetLines;
    }

    public int getMaxEvidenceFiles() {
        return maxEvidenceFiles;
    }

    public void setMaxEvidenceFiles(int maxEvidenceFiles) {
        this.maxEvidenceFiles = maxEvidenceFiles;
    }

    public Set<String> getSupportedExtensions() {
        return supportedExtensions;
    }

    public void setSupportedExtensions(Set<String> supportedExtensions) {
        this.supportedExtensions = supportedExtensions;
    }

    public Set<String> getUnsupportedExtensions() {
        return unsupportedExtensions;
    }

    public void setUnsupportedExtensions(Set<String> unsupportedExtensions) {
        this.unsupportedExtensions = unsupportedExtensions;
    }
}
