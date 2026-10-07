package com.linkforge.service.implementation;

import com.linkforge.domain.workflow.implementation.TestReportItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parser for Maven Surefire XML test reports (target/surefire-reports/TEST-*.xml)
 * to discover actual test identities, durations, and outcomes.
 */
@Component
public class SurefireReportParser {

    private static final Logger log = LoggerFactory.getLogger(SurefireReportParser.class);

    public List<TestReportItem> parseReports(Path workspaceRoot) {
        return parseReports(workspaceRoot, null);
    }

    public List<TestReportItem> parseReports(
            Path workspaceRoot,
            com.linkforge.domain.workflow.implementation.ImplementationProposal proposal
    ) {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            return Collections.emptyList();
        }

        Path surefireDir = workspaceRoot.resolve("target").resolve("surefire-reports");
        if (!Files.isDirectory(surefireDir)) {
            return Collections.emptyList();
        }

        List<TestReportItem> items = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(surefireDir, "TEST-*.xml")) {
            for (Path xmlFile : stream) {
                try {
                    items.addAll(parseFile(xmlFile, proposal));
                } catch (Exception ex) {
                    log.warn("Failed to parse Surefire report {}: {}", xmlFile.getFileName(), ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.warn("Could not list surefire reports directory {}: {}", surefireDir, ex.getMessage());
        }

        return Collections.unmodifiableList(items);
    }

    private List<TestReportItem> parseFile(
            Path xmlFile,
            com.linkforge.domain.workflow.implementation.ImplementationProposal proposal
    ) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        DocumentBuilder db = dbf.newDocumentBuilder();
        List<TestReportItem> list = new ArrayList<>();

        try (InputStream is = Files.newInputStream(xmlFile)) {
            Document doc = db.parse(is);
            doc.getDocumentElement().normalize();

            String suiteName = doc.getDocumentElement().getAttribute("name");
            NodeList testCases = doc.getElementsByTagName("testcase");

            for (int i = 0; i < testCases.getLength(); i++) {
                Element testcase = (Element) testCases.item(i);
                String name = testcase.getAttribute("name");
                String classname = testcase.getAttribute("classname");
                String timeAttr = testcase.getAttribute("time");

                long durationMs = 0;
                if (timeAttr != null && !timeAttr.isBlank()) {
                    try {
                        double seconds = Double.parseDouble(timeAttr);
                        durationMs = Math.round(seconds * 1000.0);
                    } catch (NumberFormatException ignored) {}
                }

                String status = "PASSED";
                String failureMessage = null;

                NodeList failures = testcase.getElementsByTagName("failure");
                if (failures.getLength() > 0) {
                    status = "FAILED";
                    Element failure = (Element) failures.item(0);
                    failureMessage = failure.getAttribute("message");
                    if (failureMessage == null || failureMessage.isBlank()) {
                        failureMessage = failure.getTextContent();
                    }
                } else {
                    NodeList errors = testcase.getElementsByTagName("error");
                    if (errors.getLength() > 0) {
                        status = "ERROR";
                        Element error = (Element) errors.item(0);
                        failureMessage = error.getAttribute("message");
                        if (failureMessage == null || failureMessage.isBlank()) {
                            failureMessage = error.getTextContent();
                        }
                    } else {
                        NodeList skipped = testcase.getElementsByTagName("skipped");
                        if (skipped.getLength() > 0) {
                            status = "SKIPPED";
                        }
                    }
                }

                String effectiveSuite = (suiteName != null && !suiteName.isBlank()) ? suiteName : classname;

                List<String> criterionLineage = new ArrayList<>();
                if (proposal != null && proposal.changes() != null) {
                    for (var change : proposal.changes()) {
                        String changePath = change.path();
                        String fileName = Path.of(changePath).getFileName().toString();
                        String baseName = fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
                        if ((effectiveSuite != null && effectiveSuite.contains(baseName))
                                || (classname != null && classname.contains(baseName))) {
                            if (change.criterionLineage() != null && !change.criterionLineage().isBlank()) {
                                for (String part : change.criterionLineage().split("[,;\\s]+")) {
                                    if (!part.isBlank()) {
                                        criterionLineage.add(part.trim());
                                    }
                                }
                            }
                        }
                    }
                }

                list.add(new TestReportItem(effectiveSuite, name, status, durationMs, failureMessage, criterionLineage.stream().distinct().toList()));
            }
        }

        return list;
    }
}
