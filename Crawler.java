/**
 * Heyyyyyyy, so TLDR GitHub's AI wrote most of this and I have next to no clue how some of this manages to work.
 * That said, it does work, so I am not touching it. 
 * I/the AI have added in some comments so maybe this is readable. Good luck and godspeed.
 */

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PushbackReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Desktop tool for finding patients who match two diagnosis-code lists.
 *
 * <p>The patient-data file is expected to have a header row, with patient IDs
 * in column 1 and diagnosis codes in column 4. Each diagnosis-list file also
 * has a header row and codes in its first column. Matching happens across all
 * diagnosis rows for a patient, not just within one row.</p>
 */
public class Crawler {
    public static void main(String[] args) {
        // Create Swing components on the event-dispatch thread. Swing is a UI toolkit made for Java.
        javax.swing.SwingUtilities.invokeLater(Crawler::showWindow);
    }

    /**
     * Builds the application window and connects its controls to the filter.
     */
    private static void showWindow() {
        // Set window name and default close operation.
        JFrame frame = new JFrame("MIMIC-IV Data Filtering Tool");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Create input fields
        JTextField inputPath = new JTextField(32);
        JTextField firstCodesPath = new JTextField(32);
        JTextField secondCodesPath = new JTextField(32);
        JTextField outputPath = new JTextField(32);
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(6, 6, 6, 6);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        JButton inputBrowse = addCsvPathRow(frame, fields, constraints, 0,
                "Patient data CSV:", inputPath, false, () -> {
                    if (outputPath.getText().trim().isEmpty()) {
                        File input = new File(inputPath.getText().trim());
                        outputPath.setText(new File(input.getParentFile(),
                                "filtered_patients.csv").getAbsolutePath());
                    }
                });
        JButton firstCodesBrowse = addCsvPathRow(frame, fields, constraints, 1,
                "Diagnosis List 1:", firstCodesPath, false, null);
        JButton secondCodesBrowse = addCsvPathRow(frame, fields, constraints, 2,
                "Diagnosis List 2:", secondCodesPath, false, null);
        JButton outputBrowse = addCsvPathRow(frame, fields, constraints, 3,
                "Output CSV:", outputPath, true, null);

        JButton filterButton = new JButton("Create filtered CSV");
        JLabel status = new JLabel("Select the patient data and both diagnosis-code lists.");
        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Ready");
        filterButton.addActionListener(event -> {
            // Read the current text-field values before starting the background task.
            String input = inputPath.getText().trim();
            String firstCodes = firstCodesPath.getText().trim();
            String secondCodes = secondCodesPath.getText().trim();
            String outputText = outputPath.getText().trim();
            if (input.isEmpty() || firstCodes.isEmpty() || secondCodes.isEmpty()
                    || outputText.isEmpty()) {
                JOptionPane.showMessageDialog(frame,
                        "All fields are required.",
                        "Missing file", JOptionPane.WARNING_MESSAGE);
                return;
            }

            String output = ensureCsvExtension(new File(outputText)).getAbsolutePath();
            if (new File(output).exists()
                    && JOptionPane.showConfirmDialog(frame,
                            "The output file already exists. Replace it?",
                            "Confirm overwrite", JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }

            inputBrowse.setEnabled(false);
            firstCodesBrowse.setEnabled(false);
            secondCodesBrowse.setEnabled(false);
            outputBrowse.setEnabled(false);
            filterButton.setEnabled(false);
            status.setText("Reading CSV files...");
            progressBar.setValue(0);
            progressBar.setIndeterminate(true);
            progressBar.setString("Reading CSV files...");
            // File parsing may take time, so run it away from Swing's event thread.
            new SwingWorker<FilterResult, ProgressUpdate>() {
                @Override
                protected FilterResult doInBackground() throws IOException {
                    return filterPatientsByDiagnosisLists(input, firstCodes, secondCodes, output,
                            this::reportProgress);
                }

                private void reportProgress(ProgressUpdate update) {
                    publish(update);
                }

                @Override
                protected void process(List<ProgressUpdate> updates) {
                    // Apply only the newest update if the UI thread received a batch at once.
                    ProgressUpdate update = updates.get(updates.size() - 1);
                    status.setText(update.getMessage());
                    progressBar.setIndeterminate(update.isIndeterminate());
                    if (!update.isIndeterminate()) {
                        progressBar.setValue(update.getPercentComplete());
                        progressBar.setString(update.getPercentComplete() + "% ("
                                + update.getCounter() + ")");
                    } else {
                        progressBar.setString(update.getCounter());
                    }
                }

                @Override
                protected void done() {
                    // SwingWorker.done runs on the event thread, where UI updates are safe.
                    inputBrowse.setEnabled(true);
                    firstCodesBrowse.setEnabled(true);
                    secondCodesBrowse.setEnabled(true);
                    outputBrowse.setEnabled(true);
                    filterButton.setEnabled(true);
                    progressBar.setIndeterminate(false);
                    try {
                        FilterResult result = get();
                        progressBar.setValue(100);
                        progressBar.setString("Complete");
                        status.setText("Created " + output + " (" + result.getPatientCount()
                                + " patient identifiers).");
                        JOptionPane.showMessageDialog(frame,
                                "Created output with " + result.getWrittenPatientCount()
                                        + " patient identifiers.",
                                "Filtering complete", JOptionPane.INFORMATION_MESSAGE);
                    } 
                   
                    // Error handling stuff, doesnt matter too much
                    catch (InterruptedException exception) {
                        // Preserve the thread's interruption status for any code above us.
                        Thread.currentThread().interrupt();
                        progressBar.setString("Interrupted");
                        status.setText("Filtering was interrupted.");
                        JOptionPane.showMessageDialog(frame, "Filtering was interrupted.",
                                "Error", JOptionPane.ERROR_MESSAGE);
                    } catch (ExecutionException exception) {
                        // Surface the actual file/filter error rather than a generic success state.
                        Throwable cause = exception.getCause();
                        String message = cause == null ? exception.getMessage() : cause.getMessage();
                        progressBar.setString("Failed");
                        status.setText("Could not create the output CSV.");
                        JOptionPane.showMessageDialog(frame, message,
                                "Could not create output", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });

        //Make the window look nice
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        JPanel progressPanel = new JPanel(new BorderLayout(4, 4));
        progressPanel.add(progressBar, BorderLayout.NORTH);
        progressPanel.add(status, BorderLayout.CENTER);
        footer.add(progressPanel, BorderLayout.CENTER);
        footer.add(filterButton, BorderLayout.EAST);
        footer.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel content = new JPanel(new BorderLayout(8, 0));
        content.add(new LogoPanel(), BorderLayout.WEST);
        content.add(fields, BorderLayout.CENTER);
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));

        frame.add(content, BorderLayout.CENTER);
        frame.add(footer, BorderLayout.SOUTH);
        frame.pack();
        frame.setMinimumSize(frame.getSize());
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * Displays the logo image from beside the JAR (or the class output directory).
     */
    private static final class LogoPanel extends JComponent {
        private final BufferedImage logo;
        private final String loadMessage;

        private LogoPanel() {
            setPreferredSize(new Dimension(140, 170));
            File logoFile = new File(getApplicationDirectory(), "logo.png");
            BufferedImage loadedLogo = null;
            String message = null;
            if (logoFile.isFile()) {
                try {
                    loadedLogo = ImageIO.read(logoFile);
                    if (loadedLogo == null) {
                        message = "logo.png is not a readable PNG image.";
                    }
                } catch (IOException exception) {
                    message = "Could not read logo.png: " + exception.getMessage();
                }
            } else {
                message = "Add logo.png beside Crawler.jar";
            }
            logo = loadedLogo;
            loadMessage = message;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D drawing = (Graphics2D) graphics.create();
            try {
                if (logo == null) {
                    drawing.setColor(javax.swing.UIManager.getColor("Label.foreground"));
                    String message = loadMessage == null ? "logo.png unavailable" : loadMessage;
                    int textX = Math.max(4, (getWidth() - drawing.getFontMetrics()
                            .stringWidth(message)) / 2);
                    drawing.drawString(message, textX, getHeight() / 2);
                    return;
                }

                // Scale down large logos to fit while preserving their original proportions.
                double scale = Math.min((double) getWidth() / logo.getWidth(),
                        (double) getHeight() / logo.getHeight());
                int imageWidth = (int) Math.round(logo.getWidth() * scale);
                int imageHeight = (int) Math.round(logo.getHeight() * scale);
                int imageX = (getWidth() - imageWidth) / 2;
                int imageY = (getHeight() - imageHeight) / 2;
                drawing.drawImage(logo, imageX, imageY, imageWidth, imageHeight, this);
            } finally {
                drawing.dispose();
            }
        }

        private static File getApplicationDirectory() {
            try {
                File applicationLocation = new File(Crawler.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());
                return applicationLocation.isDirectory()
                        ? applicationLocation : applicationLocation.getParentFile();
            } catch (URISyntaxException exception) {
                throw new IllegalStateException("Could not locate the application folder.",
                        exception);
            }
        }
    }

    private static JButton addCsvPathRow(JFrame frame, JPanel fields,
            GridBagConstraints constraints, int row, String label, JTextField path,
            boolean save, Runnable onSelected) {
        // Share one layout/chooser implementation across every file path field.
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.weightx = 0;
        fields.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        fields.add(path, constraints);

        JButton browse = new JButton(save ? "Choose..." : "Browse...");
        browse.addActionListener(event -> {
            JFileChooser chooser = createCsvChooser();
            chooser.setDialogTitle(save ? "Choose output CSV" : "Select CSV file");
            if (save) {
                // A save chooser suggests a default filename but leaves the final path editable.
                chooser.setDialogType(JFileChooser.SAVE_DIALOG);
                chooser.setSelectedFile(path.getText().trim().isEmpty()
                        ? new File("filtered_patients.csv")
                        : new File(path.getText().trim()));
                if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
                    return;
                }
                path.setText(ensureCsvExtension(chooser.getSelectedFile()).getAbsolutePath());
            } else {
                // Input paths must refer to an existing file selected by the user.
                chooser.setDialogType(JFileChooser.OPEN_DIALOG);
                if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
                    return;
                }
                path.setText(chooser.getSelectedFile().getAbsolutePath());
            }
            if (onSelected != null) {
                // Some path choices (the patient input) have a related default to update.
                onSelected.run();
            }
        });
        constraints.gridx = 2;
        constraints.weightx = 0;
        fields.add(browse, constraints);
        return browse;
    }

    private static JFileChooser createCsvChooser() {
        JFileChooser chooser = new JFileChooser();
        // Keep the chooser focused on CSVs while still allowing users to browse other files.
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        return chooser;
    }

    private static File ensureCsvExtension(File file) {
        // Add the conventional suffix only when the user did not already provide it.
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            return new File(file.getParentFile(), file.getName() + ".csv");
        }
        return file;
    }

    /**
     * Writes one output record per patient who matches both diagnosis lists.
     *
     * <p>The output columns are the patient ID, a flag indicating whether any
     * row had a substance-induced psychosis diagnosis, and the patient's unique
     * diagnosis codes joined into one field. Patient and diagnosis code columns
     * in the input are fixed at columns 1 and 4 respectively; list-file codes
     * are read from column 1. Every file is expected to have a header row.</p>
     *
     * @return counts of qualifying patients and patient records written
     * @throws IOException if a file cannot be read, parsed, or written
     */
    public static FilterResult filterPatientsByDiagnosisLists(String inputPath,
            String firstDiagnosisListPath, String secondDiagnosisListPath, String outputPath)
            throws IOException {
        return filterPatientsByDiagnosisLists(inputPath, firstDiagnosisListPath,
                secondDiagnosisListPath, outputPath, update -> { });
    }

    private static FilterResult filterPatientsByDiagnosisLists(String inputPath,
            String firstDiagnosisListPath, String secondDiagnosisListPath, String outputPath,
            ProgressListener progressListener) throws IOException {
        // Prevent truncating any selected input if the output path points to that same file.
        File outputFile = new File(outputPath).getCanonicalFile();
        String[] inputPaths = {inputPath, firstDiagnosisListPath, secondDiagnosisListPath};
        for (String path : inputPaths) {
            if (outputFile.equals(new File(path).getCanonicalFile())) {
                throw new IllegalArgumentException("The output file must differ from all input files.");
            }
        }

        // Read the input first so it is fully available before output creation begins.
        ArrayList<ArrayList<String>> patientRows = readCsvFile(
                inputPath, "Reading patient data", progressListener);
        if (patientRows.isEmpty() || patientRows.get(0).size() < 4) {
            throw new IOException("The patient-data CSV must have a header and at least four columns.");
        }

        // List files contribute exact codes and wildcard patterns for diagnosis matching.
        DiagnosisCodeList firstCodes = readDiagnosisCodes(
                firstDiagnosisListPath, "Reading first diagnosis list", progressListener);
        DiagnosisCodeList secondCodes = readDiagnosisCodes(
                secondDiagnosisListPath, "Reading second diagnosis list", progressListener);
        if (firstCodes.isEmpty() || secondCodes.isEmpty()) {
            throw new IOException("Each diagnosis-code list must contain at least one code after its header.");
        }

        // Each patient's boolean slots track list 1, list 2, then substance-related psychosis.
        // The linked map stores distinct original diagnosis strings in first-seen order.
        Map<String, boolean[]> patientMatches = new HashMap<>();
        Map<String, LinkedHashMap<String, String>> patientDiagnoses = new HashMap<>();
        // Start after row 0 because the first patient-data record is the column header.
        int totalDataRows = Math.max(1, patientRows.size() - 1);
        // Split determinate progress evenly between the match pass and output pass.
        reportProgress(progressListener, "Matching patient diagnoses", 0, totalDataRows, 0);
        for (int rowIndex = 1; rowIndex < patientRows.size(); rowIndex++) {
            ArrayList<String> row = patientRows.get(rowIndex);
            if (!isBlankRow(row)) {
                if (row.size() < 4) {
                    throw new IOException("Patient-data row " + (rowIndex + 1)
                            + " has fewer than four columns.");
                }
                String patientId = row.get(0).trim();
                String diagnosis = normalizeCode(row.get(3));
                if (!patientId.isEmpty()) {
                    // A single patient can have many diagnosis rows; accumulate rather than overwrite.
                    boolean[] matches = patientMatches.get(patientId);
                    if (matches == null) {
                        // Indexes 0 and 1 track the two lists; index 2 tracks substance-related psychosis.
                        matches = new boolean[3];
                        patientMatches.put(patientId, matches);
                    }
                    // OR preserves a prior match if a different row has another diagnosis.
                    matches[0] |= firstCodes.contains(diagnosis);
                    matches[1] |= secondCodes.contains(diagnosis);

                    // Keep unique diagnoses in encounter order, retaining their original spelling.
                    if (!diagnosis.isEmpty()) {
                        LinkedHashMap<String, String> diagnoses = patientDiagnoses.get(patientId);
                        if (diagnoses == null) {
                            diagnoses = new LinkedHashMap<>();
                            patientDiagnoses.put(patientId, diagnoses);
                        }
                        diagnoses.putIfAbsent(diagnosis, row.get(3).trim());
                        // This flag requires the diagnosis itself to be substance-induced psychosis.
                        matches[2] |= isSubstanceRelatedPsychosisCode(diagnosis);
                    }
                }
            }
            int completedRows = rowIndex;
            if (completedRows % 1000 == 0 || completedRows == patientRows.size() - 1) {
                reportProgress(progressListener, "Matching patient diagnoses", completedRows,
                        totalDataRows, (int) (50L * completedRows / totalDataRows));
            }
        }

        Set<String> qualifyingPatients = new HashSet<>();
        for (Map.Entry<String, boolean[]> entry : patientMatches.entrySet()) {
            // The two list conditions are ANDed at the patient level, across all their rows.
            if (entry.getValue()[0] && entry.getValue()[1]) {
                qualifyingPatients.add(entry.getKey());
            }
        }

        int writtenPatients = 0;
        Set<String> writtenPatientIds = new HashSet<>();
        reportProgress(progressListener, "Writing patient results", 0, totalDataRows, 50);
        // Output codes may contain commas, so write all output fields through the CSV escaper.
        try (BufferedWriter writer = Files.newBufferedWriter(
                Paths.get(outputPath), StandardCharsets.UTF_8)) {
            // Keep the input's patient-ID heading and add names for the derived columns.
            ArrayList<String> outputHeader = new ArrayList<>();
            outputHeader.add(patientRows.get(0).get(0));
            outputHeader.add("has_substance_related_psychosis_dx");
            outputHeader.add("diagnosis_codes");
            writeCsvRow(writer, outputHeader);

            // Scan in source order: the first row for an eligible ID determines output order.
            for (int rowIndex = 1; rowIndex < patientRows.size(); rowIndex++) {
                ArrayList<String> row = patientRows.get(rowIndex);
                if (!isBlankRow(row)) {
                    String patientId = row.get(0).trim();
                    if (qualifyingPatients.contains(patientId)
                            && writtenPatientIds.add(patientId)) {
                        ArrayList<String> outputRow = new ArrayList<>();
                        outputRow.add(patientId);
                        // Use the accumulated patient-level flag and diagnosis list, not just this row.
                        outputRow.add(Boolean.toString(patientMatches.get(patientId)[2]));
                        outputRow.add(String.join(",",
                                patientDiagnoses.get(patientId).values()));
                        writeCsvRow(writer, outputRow);
                        writtenPatients++;
                    }
                }
                int completedRows = rowIndex;
                if (completedRows % 1000 == 0 || completedRows == patientRows.size() - 1) {
                    reportProgress(progressListener, "Writing patient results", completedRows,
                            totalDataRows, 50 + (int) (50L * completedRows / totalDataRows));
                }
            }
        }

        reportProgress(progressListener, "Writing patient results", totalDataRows,
                totalDataRows, 100);
        return new FilterResult(qualifyingPatients.size(), writtenPatients);
    }

    private static ArrayList<ArrayList<String>> readCsvFile(String path, String phase,
            ProgressListener progressListener) throws IOException {
        ArrayList<ArrayList<String>> rows = new ArrayList<>();
        // Record count is unknown until parsing finishes, so reading reports a live counter.
        long lastProgressTime = System.currentTimeMillis();
        // Buffer character reads and push back one character when a quote closes before a delimiter.
        try (PushbackReader reader = new PushbackReader(new BufferedReader(
                Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8)), 1)) {
            ArrayList<String> row;
            while ((row = readCsvRow(reader)) != null) {
                rows.add(row);
                long now = System.currentTimeMillis();
                // Throttle UI notifications for large files while keeping the counter responsive.
                if (rows.size() == 1 || rows.size() % 1000 == 0 || now - lastProgressTime >= 150) {
                    progressListener.onProgress(ProgressUpdate.reading(
                            phase, rows.size()));
                    lastProgressTime = now;
                }
            }
        }
        progressListener.onProgress(ProgressUpdate.reading(phase, rows.size()));
        return rows;
    }

    private static DiagnosisCodeList readDiagnosisCodes(String path, String phase,
            ProgressListener progressListener) throws IOException {
        ArrayList<ArrayList<String>> rows = readCsvFile(path, phase, progressListener);
        DiagnosisCodeList codes = new DiagnosisCodeList();
        // Ignore the heading, then load non-empty values from the code list's first column.
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            ArrayList<String> row = rows.get(rowIndex);
            if (!row.isEmpty()) {
                String code = normalizeCode(row.get(0));
                if (!code.isEmpty()) {
                    codes.add(code);
                }
            }
        }
        return codes;
    }

    private static void reportProgress(ProgressListener listener, String message,
            int completed, int total, int percentComplete) {
        listener.onProgress(ProgressUpdate.determinate(
                message, completed, total, percentComplete));
    }

    /**
     * Receives progress snapshots from file reading and the two patient-row passes.
     */
    private interface ProgressListener {
        void onProgress(ProgressUpdate update);
    }

    /**
     * A UI-safe snapshot of the current phase and its row counter.
     */
    private static final class ProgressUpdate {
        private final String message;
        private final String counter;
        private final int percentComplete;
        private final boolean indeterminate;

        private ProgressUpdate(String message, String counter, int percentComplete,
                boolean indeterminate) {
            this.message = message;
            this.counter = counter;
            this.percentComplete = percentComplete;
            this.indeterminate = indeterminate;
        }

        private static ProgressUpdate reading(String phase, int rowsRead) {
            return new ProgressUpdate(phase,
                    rowsRead + " CSV records read",
                    0, true);
        }

        private static ProgressUpdate determinate(String phase, int completed, int total,
                int percentComplete) {
            return new ProgressUpdate(phase, completed + " / " + total + " rows",
                    percentComplete, false);
        }

        private String getMessage() {
            return message;
        }

        private String getCounter() {
            return counter;
        }

        private int getPercentComplete() {
            return percentComplete;
        }

        private boolean isIndeterminate() {
            return indeterminate;
        }
    }

    private static String normalizeCode(String code) {
        // Ignore letter case and dots, which are sometimes omitted in source data.
        return code.trim().toUpperCase(Locale.ROOT).replace(".", "");
    }

    private static boolean isSubstanceRelatedPsychosisCode(String diagnosis) {
        // ICD-9-CM alcohol-induced psychosis and drug-induced psychosis code families.
        if (diagnosis.startsWith("2913") || diagnosis.startsWith("2915")
                || diagnosis.startsWith("29211") || diagnosis.startsWith("29212")) {
            return true;
        }

        // ICD-10-CM F1x.15, F1x.25, and F1x.95 families represent substance-induced psychoses.
        // Exclude F17 because nicotine-use codes are not the intended substance-psychosis flag.
        for (int substance = 10; substance <= 19; substance++) {
            if (substance == 17) {
                continue;
            }
            String category = "F" + substance;
            if (diagnosis.startsWith(category + "15")
                    || diagnosis.startsWith(category + "25")
                    || diagnosis.startsWith(category + "95")) {
                return true;
            }
        }
        return false;
    }

    private static final class DiagnosisCodeList {
        // Hash lookups make full-code matches fast even for long code lists.
        private final Set<String> exactCodes = new HashSet<>();
        // Patterns are kept separately because wildcard entries cannot be hashed as exact codes.
        private final ArrayList<Pattern> wildcardPatterns = new ArrayList<>();

        private void add(String code) {
            if (code.indexOf('*') < 0 && code.indexOf('?') < 0) {
                exactCodes.add(code);
            } else {
                // Translate only '*' and '?' into wildcards; quote regex syntax in literal characters.
                StringBuilder regex = new StringBuilder("^");
                for (int index = 0; index < code.length(); index++) {
                    char character = code.charAt(index);
                    regex.append(character == '*' ? ".*"
                            : character == '?' ? "."
                            : Pattern.quote(String.valueOf(character)));
                }
                regex.append('$');
                wildcardPatterns.add(Pattern.compile(regex.toString()));
            }
        }

        private boolean contains(String code) {
            if (exactCodes.contains(code)) {
                return true;
            }
            // Patterns in the supplied code lists can represent whole ICD-9 families.
            for (Pattern pattern : wildcardPatterns) {
                if (pattern.matcher(code).matches()) {
                    return true;
                }
            }
            return false;
        }

        private boolean isEmpty() {
            return exactCodes.isEmpty() && wildcardPatterns.isEmpty();
        }
    }

    private static boolean isBlankRow(ArrayList<String> row) {
        // Ignore empty physical rows rather than treating them as patient records.
        for (String field : row) {
            if (!field.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static final class FilterResult {
        private final int patientCount;
        private final int writtenPatientCount;

        // Keep the filter result small and immutable after processing finishes.
        private FilterResult(int patientCount, int writtenPatientCount) {
            this.patientCount = patientCount;
            this.writtenPatientCount = writtenPatientCount;
        }

        public int getPatientCount() {
            return patientCount;
        }

        public int getWrittenPatientCount() {
            return writtenPatientCount;
        }
    }

    private static ArrayList<String> readCsvRow(PushbackReader reader) throws IOException {
        ArrayList<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean sawAnything = false;
        int character;

        // Consume characters until an unquoted line ending or end-of-file finishes this record.
        while ((character = reader.read()) != -1) {
            sawAnything = true;
            if (inQuotes) {
                if (character == '"') {
                    int next = reader.read();
                    if (next == '"') {
                        // An escaped quote inside a quoted field is represented as two quotes.
                        field.append('"');
                    } else {
                        // A quote not followed by another quote closes the quoted field.
                        inQuotes = false;
                        if (next != -1) {
                            reader.unread(next);
                        }
                    }
                } else {
                    field.append((char) character);
                }
            } else if (character == '"' && field.length() == 0) {
                inQuotes = true;
            } else if (character == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (character == '\n' || character == '\r') {
                if (character == '\r') {
                    int next = reader.read();
                    if (next != '\n' && next != -1) {
                        reader.unread(next);
                    }
                }
                row.add(field.toString());
                return row;
            } else {
                field.append((char) character);
            }
        }

        if (inQuotes) {
            throw new IOException("CSV contains an unterminated quoted field.");
        }
        if (!sawAnything) {
            return null;
        }

        row.add(field.toString());
        return row;
    }

    private static void writeCsvRow(BufferedWriter writer, ArrayList<String> row)
            throws IOException {
        for (int index = 0; index < row.size(); index++) {
            if (index > 0) {
                writer.write(',');
            }

            String field = row.get(index);
            // Quote only fields requiring CSV escaping.
            boolean quoteField = field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                    || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0;
            if (quoteField) {
                writer.write('"');
            }
            for (int characterIndex = 0; characterIndex < field.length(); characterIndex++) {
                char character = field.charAt(characterIndex);
                if (character == '"') {
                    writer.write("\"\"");
                } else {
                    writer.write(character);
                }
            }
            if (quoteField) {
                writer.write('"');
            }
        }
        writer.newLine();
    }
}