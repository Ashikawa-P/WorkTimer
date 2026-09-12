import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;

public class Main extends Application {
    private static final long GRAPH_INTERVAL_SECONDS = 10;
    private static final Path DATA_FILE = findDataFile();
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private Label captureLabel;
    private Button captureButton;
    private TextField note;
    private LineChart<Number, Number> captureGraph;
    private XYChart.Series<Number, Number> captureSeries;

    private DatePicker lookupDate;
    private Label exportLabel;
    private Label lookupTimeLabel;
    private Label lookupNoteLabel;
    private LineChart<Number, Number> lookupGraph;
    private XYChart.Series<Number, Number> lookupSeries;

    private final Properties savedDays = new Properties();

    private Timeline clock;
    private boolean dayStarted;
    private boolean working;
    private Instant dayStartedAt;
    private Instant workSegmentStartedAt;
    private LocalDate currentDay;
    private String currentStartTime;
    private long workedSecondsBeforeSegment;
    private long nextGraphPointAt = GRAPH_INTERVAL_SECONDS;

    @Override
    public void start(Stage stage) throws IOException {
        Parent root = FXMLLoader.load(getClass().getResource("root.fxml"));

        loadElements(root);
        initializeGraphs();
        loadSavedDays();
        registerEvents();

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.show();

        lookupDate.setValue(LocalDate.now());
    }

    @SuppressWarnings("unchecked")
    private void loadElements(Parent root) {
        captureLabel = (Label) root.lookup("#captureLabel");
        captureButton = (Button) root.lookup("#captureButton");
        note = (TextField) root.lookup("#note");
        captureGraph = (LineChart<Number, Number>) root.lookup("#captureGraph");

        lookupDate = (DatePicker) root.lookup("#lookupDate");
        exportLabel = (Label) root.lookup("#exportLabel");
        lookupTimeLabel = (Label) root.lookup("#lookupTimeLabel");
        lookupNoteLabel = (Label) root.lookup("#lookupNoteLabel");
        lookupGraph = (LineChart<Number, Number>) root.lookup("#lookupGraph");
    }

    private void initializeGraphs() {
        captureSeries = new XYChart.Series<>();
        captureSeries.setName("Arbeitszeit");
        captureSeries.getData().add(new XYChart.Data<>(0.0, 0.0));
        captureGraph.getData().setAll(captureSeries);
        captureGraph.setAnimated(false);
        captureGraph.setCreateSymbols(false);

        lookupSeries = new XYChart.Series<>();
        lookupSeries.setName("Arbeitszeit");
        lookupGraph.getData().setAll(lookupSeries);
        lookupGraph.setAnimated(false);
        lookupGraph.setCreateSymbols(false);
    }

    private void registerEvents() {
        captureButton.setOnAction(event -> onCapturePush());
        lookupDate.valueProperty().addListener(
                (observable, oldDate, newDate) -> showSavedDay(newDate)
        );
        exportLabel.setOnMouseClicked(event -> exportToExcel());
    }

    private void onCapturePush() {
        if (!dayStarted) {
            startDay();
        } else if (working) {
            pauseDay();
        } else {
            continueDay();
        }
    }

    private void startDay() {
        Instant now = Instant.now();

        dayStarted = true;
        working = true;
        dayStartedAt = now;
        workSegmentStartedAt = now;
        currentDay = LocalDate.now();
        currentStartTime = LocalTime.now().format(TIME_FORMATTER);
        workedSecondsBeforeSegment = 0;
        nextGraphPointAt = GRAPH_INTERVAL_SECONDS;

        captureLabel.setText("00:00:00");
        captureButton.setText("Pause");
        captureSeries.getData().setAll(new XYChart.Data<>(0.0, 0.0));

        clockRun();
    }

    private void pauseDay() {
        Instant now = Instant.now();

        workedSecondsBeforeSegment = workedSecondsAt(now);
        working = false;
        captureButton.setText("Continue");

        updateClockDisplay(now);
        addGraphPoint(now);
        saveToday(LocalTime.now().format(TIME_FORMATTER));
    }

    private void continueDay() {
        working = true;
        workSegmentStartedAt = Instant.now();
        captureButton.setText("Pause");
    }

    private void clockRun() {
        if (clock != null) {
            return;
        }

        clock = new Timeline(new KeyFrame(
                Duration.seconds(1),
                event -> updateClock()
        ));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
    }

    private void updateClock() {
        Instant now = Instant.now();
        updateClockDisplay(now);

        long elapsedSeconds = elapsedSecondsAt(now);
        if (elapsedSeconds >= nextGraphPointAt) {
            addGraphPoint(now);
            nextGraphPointAt = elapsedSeconds + GRAPH_INTERVAL_SECONDS;
        }
    }

    private void updateClockDisplay(Instant now) {
        captureLabel.setText(formatTime(workedSecondsAt(now)));
    }

    private void addGraphPoint(Instant now) {
        double elapsedHours = elapsedSecondsAt(now) / 3600.0;
        double workedHours = workedSecondsAt(now) / 3600.0;

        int lastIndex = captureSeries.getData().size() - 1;
        XYChart.Data<Number, Number> lastPoint =
                captureSeries.getData().get(lastIndex);

        if (Double.compare(lastPoint.getXValue().doubleValue(), elapsedHours) == 0) {
            lastPoint.setYValue(workedHours);
        } else {
            captureSeries.getData().add(
                    new XYChart.Data<>(elapsedHours, workedHours)
            );
        }
    }

    private long elapsedSecondsAt(Instant now) {
        return Math.max(0, java.time.Duration.between(dayStartedAt, now).getSeconds());
    }

    private long workedSecondsAt(Instant now) {
        if (!working) {
            return workedSecondsBeforeSegment;
        }

        long currentSegment = java.time.Duration
                .between(workSegmentStartedAt, now)
                .getSeconds();

        return workedSecondsBeforeSegment + Math.max(0, currentSegment);
    }

    private String formatTime(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = totalSeconds % 3600 / 60;
        long seconds = totalSeconds % 60;

        return "%02d:%02d:%02d".formatted(hours, minutes, seconds);
    }

    private void loadSavedDays() {
        if (!Files.exists(DATA_FILE)) {
            return;
        }

        try (InputStream input = Files.newInputStream(DATA_FILE)) {
            savedDays.loadFromXML(input);
        } catch (IOException exception) {
            showStorageError("Die gespeicherten Tagesdaten konnten nicht geladen werden.", exception);
        }
    }

    private void saveToday(String endTime) {
        String prefix = currentDay + ".";

        savedDays.setProperty(prefix + "start", currentStartTime);
        savedDays.setProperty(prefix + "time", captureLabel.getText());
        savedDays.setProperty(prefix + "end", endTime);
        savedDays.setProperty(prefix + "note", note.getText());
        savedDays.setProperty(prefix + "graph", serializeCaptureGraph());

        try {
            writeSavedDays();

            if (currentDay.equals(lookupDate.getValue())) {
                showSavedDay(currentDay);
            }
        } catch (IOException exception) {
            showStorageError("Der heutige Eintrag konnte nicht gespeichert werden.", exception);
        }
    }

    private void writeSavedDays() throws IOException {
        Files.createDirectories(DATA_FILE.getParent());
        Path temporaryFile = Files.createTempFile(
                DATA_FILE.getParent(),
                "workdays-",
                ".tmp"
        );

        try {
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                savedDays.storeToXML(output, "WorkTimer-Tagesdaten", "UTF-8");
            }

            try {
                Files.move(
                        temporaryFile,
                        DATA_FILE,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporaryFile,
                        DATA_FILE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private String serializeCaptureGraph() {
        StringBuilder result = new StringBuilder();

        for (XYChart.Data<Number, Number> point : captureSeries.getData()) {
            if (!result.isEmpty()) {
                result.append(';');
            }

            result.append(point.getXValue().doubleValue())
                    .append(',')
                    .append(point.getYValue().doubleValue());
        }

        return result.toString();
    }

    private void showSavedDay(LocalDate date) {
        lookupSeries.getData().clear();

        if (date == null) {
            lookupTimeLabel.setText("Kein Datum ausgewÃ¤hlt");
            lookupNoteLabel.setText("");
            return;
        }

        String prefix = date + ".";
        String savedTime = savedDays.getProperty(prefix + "time");

        if (savedTime == null) {
            lookupTimeLabel.setText("FÃ¼r dieses Datum existiert kein Eintrag");
            lookupNoteLabel.setText("");
            return;
        }

        lookupTimeLabel.setText("Arbeitszeit: " + savedTime);

        String savedNote = savedDays.getProperty(prefix + "note", "");
        lookupNoteLabel.setText(
                savedNote.isBlank() ? "Keine Notiz" : savedNote
        );

        String savedGraph = savedDays.getProperty(prefix + "graph", "");
        loadGraphPoints(savedGraph);
    }

    private void loadGraphPoints(String savedGraph) {
        if (savedGraph.isBlank()) {
            return;
        }

        try {
            for (String savedPoint : savedGraph.split(";")) {
                String[] coordinates = savedPoint.split(",", 2);

                double elapsedHours = Double.parseDouble(coordinates[0]);
                double workedHours = Double.parseDouble(coordinates[1]);

                lookupSeries.getData().add(
                        new XYChart.Data<>(elapsedHours, workedHours)
                );
            }
        } catch (RuntimeException exception) {
            lookupSeries.getData().clear();
            lookupNoteLabel.setText(
                    lookupNoteLabel.getText() + "\nDer gespeicherte Graph ist beschÃ¤digt."
            );
        }
    }

    private void showStorageError(String message, Exception exception) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText(message);
        alert.setContentText(
                exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage()
        );
        alert.show();
    }

    private void exportToExcel() {
        SortedSet<LocalDate> savedDates = getSavedDates();

        if (savedDates.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setHeaderText("Noch keine Tagesdaten vorhanden");
            alert.setContentText("Starte einen Tag und pausiere ihn mindestens einmal.");
            alert.show();
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Excel-Export speichern");
        fileChooser.setInitialFileName("workdays-export.csv");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Excel-kompatible CSV", "*.csv")
        );

        Path dataDirectory = DATA_FILE.getParent();
        if (dataDirectory != null && Files.isDirectory(dataDirectory)) {
            fileChooser.setInitialDirectory(dataDirectory.toFile());
        }

        File selectedFile = fileChooser.showSaveDialog(
                exportLabel.getScene().getWindow()
        );

        if (selectedFile == null) {
            return;
        }

        try {
            writeExcelCsv(selectedFile.toPath(), savedDates);

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setHeaderText("Excel-Export erstellt");
            alert.setContentText(selectedFile.getAbsolutePath());
            alert.show();
        } catch (IOException exception) {
            showStorageError("Der Excel-Export konnte nicht erstellt werden.", exception);
        }
    }

    private SortedSet<LocalDate> getSavedDates() {
        SortedSet<LocalDate> dates = new TreeSet<>();

        for (Object rawKey : savedDays.keySet()) {
            String key = rawKey.toString();

            if (!key.endsWith(".time")) {
                continue;
            }

            String dateText = key.substring(0, key.length() - ".time".length());

            try {
                dates.add(LocalDate.parse(dateText));
            } catch (RuntimeException ignored) {
                // Unbekannte EintrÃ¤ge werden beim Export Ã¼bersprungen.
            }
        }

        return dates;
    }

    private void writeExcelCsv(
            Path exportFile,
            SortedSet<LocalDate> savedDates
    ) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                exportFile,
                StandardCharsets.UTF_8
        )) {
            writer.write('\uFEFF');
            writer.write("Datum;Start;Gearbeitete Zeit;Ende;Notiz");
            writer.newLine();

            for (LocalDate date : savedDates) {
                String prefix = date + ".";

                writer.write(csvValue(date.format(DATE_FORMATTER)));
                writer.write(';');
                writer.write(csvValue(savedDays.getProperty(prefix + "start", "")));
                writer.write(';');
                writer.write(csvValue(savedDays.getProperty(prefix + "time", "")));
                writer.write(';');
                writer.write(csvValue(savedDays.getProperty(prefix + "end", "")));
                writer.write(';');
                writer.write(csvValue(savedDays.getProperty(prefix + "note", "")));
                writer.newLine();
            }
        }
    }

    private String csvValue(String value) {
        String singleLineValue = value
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("\"", "\"\"");

        return "\"" + singleLineValue + "\"";
    }

    private static Path findDataFile() {
        Path projectDirectory = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();

        String sourcePath = Main.class.getName()
                .replace('.', File.separatorChar) + ".java";

        Path[] possibleSourceFiles = {
                projectDirectory.resolve(sourcePath),
                projectDirectory.resolve("src").resolve(sourcePath),
                projectDirectory.resolve("src/main/java").resolve(sourcePath)
        };

        for (Path possibleSourceFile : possibleSourceFiles) {
            if (Files.isRegularFile(possibleSourceFile)) {
                return possibleSourceFile.resolveSibling("workdays.xml");
            }
        }

        return projectDirectory.resolve("workdays.xml");
    }

    @Override
    public void stop() {
        if (clock != null) {
            clock.stop();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}