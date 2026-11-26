package com.stockwise.controller;

import com.stockwise.exporter.ReportTable;
import com.stockwise.service.ReportService.ReportType;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Icons;
import com.stockwise.util.Tables;
import com.stockwise.util.Tables.Kind;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ReportesController extends BaseController {

    @FXML
    private ListView<com.stockwise.service.ReportesAnalisisService.Tipo> analysisList;
    @FXML
    private ListView<ReportType> reportList;
    @FXML
    private DatePicker fromDate;
    @FXML
    private DatePicker toDate;
    @FXML
    private Button excelButton;
    @FXML
    private Button pdfButton;
    @FXML
    private Label rangeHint;
    @FXML
    private Label reportTitle;
    @FXML
    private Label reportSubtitle;
    @FXML
    private VBox summaryPane;
    @FXML
    private TableView<List<Object>> previewTable;

    private ReportTable actual;
    /** Tablas que se exportan (el informe general tiene varias). */
    private List<ReportTable> paquete = List.of();

    public ReportesController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        analysisList.getItems().setAll(com.stockwise.service.ReportesAnalisisService.Tipo.values());
        analysisList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                reportList.getSelectionModel().clearSelection();
                rangeHint.setText(b == com.stockwise.service.ReportesAnalisisService.Tipo.INFORME
                        ? "Informe con resumen, acciones, compras, más vendidos y pronósticos (recomendado en PDF)."
                        : "Cifras del último análisis guardado (no usa el rango de fechas).");
                shell.run("Generando reporte…", () -> app.reportesAnalisis().generar(b), t -> {
                    mostrar(t.get(0));
                    paquete = t;
                    if (t.size() > 1) {
                        summaryPane.getChildren().add(new Label("Incluye " + t.size() + " secciones: "
                                + String.join(", ", t.stream().map(ReportTable::titulo).toList()) + "."));
                    }
                });
            }
        });
        reportList.getItems().setAll(ReportType.values());
        reportList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                analysisList.getSelectionModel().clearSelection();
                rangeHint.setText(b.usaRango() ? "Este reporte usa el rango de fechas seleccionado."
                        : "Este reporte se calcula a la fecha de corte de los datos (no usa el rango de fechas).");
                generate();
            }
        });
        excelButton.setGraphic(Icons.DOWNLOAD.create(14));
        pdfButton.setGraphic(Icons.FILE.create(14));
        excelButton.setDisable(true);
        pdfButton.setDisable(true);
        Tables.placeholder(previewTable, "Seleccione un reporte para ver la vista previa.");
        shell.run("Preparando…", () -> app.analysis().fechaCorte(), corte -> {
            fromDate.setValue(corte.minusMonths(12).plusDays(1));
            toDate.setValue(corte);
            reportList.getSelectionModel().selectFirst();
        });
    }

    @FXML
    private void generate() {
        ReportType tipo = reportList.getSelectionModel().getSelectedItem();
        if (tipo == null) {
            return;
        }
        LocalDate d = fromDate.getValue();
        LocalDate h = toDate.getValue();
        shell.run("Generando reporte…", () -> app.reportes().generar(tipo, d, h), this::mostrar);
    }

    private void mostrar(ReportTable t) {
        actual = t;
        paquete = List.of(t);
        excelButton.setDisable(false);
        pdfButton.setDisable(false);
        reportTitle.setText(t.titulo());
        reportSubtitle.setText(t.subtitulo() + " · " + t.filas().size() + " fila(s)");
        summaryPane.getChildren().clear();
        for (String s : t.resumen()) {
            Label l = new Label(s);
            l.setWrapText(true);
            l.getStyleClass().add("muted");
            summaryPane.getChildren().add(l);
        }
        previewTable.getColumns().clear();
        for (int i = 0; i < t.columnas().size(); i++) {
            int idx = i;
            Kind kind = switch (t.tipos().get(i)) {
                case ENTERO -> Kind.INT;
                case DECIMAL -> Kind.DECIMAL;
                case MONEDA -> Kind.MONEY;
                case PORCENTAJE -> Kind.PERCENT;
                case TEXTO -> Kind.TEXT;
            };
            int largo = t.columnas().get(i).length();
            for (List<Object> f : t.filas().subList(0, Math.min(200, t.filas().size()))) {
                largo = Math.max(largo, String.valueOf(f.get(idx)).length());
            }
            Tables.col(previewTable, t.columnas().get(i), fila -> fila.get(idx), kind,
                    kind == Kind.TEXT ? Math.max(120, Math.min(440, 7 * largo + 24)) : 110);
        }
        previewTable.getItems().setAll(t.filas());
    }

    private File elegir(String nombre, String extension, String descripcion) {
        FileChooser fc = new FileChooser();
        String base = nombre.toLowerCase().replaceAll("[^a-z0-9áéíóúñ]+", "-");
        fc.setInitialFileName(base + "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "." + extension);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(descripcion, "*." + extension));
        return fc.showSaveDialog(shell.window());
    }

    @FXML
    private void exportExcel() {
        if (actual == null) {
            return;
        }
        File f = elegir(actual.titulo(), "xlsx", "Excel (*.xlsx)");
        if (f != null) {
            ReportTable t = actual;
            shell.run("Exportando a Excel…", () -> {
                app.reportes().exportarExcel(paquete, f.toPath());
                return f;
            }, r -> Dialogs.info("Reporte exportado", "Archivo generado:\n" + r.getAbsolutePath()));
        }
    }

    @FXML
    private void exportPdf() {
        if (actual == null) {
            return;
        }
        File f = elegir(actual.titulo(), "pdf", "PDF (*.pdf)");
        if (f != null) {
            ReportTable t = actual;
            shell.run("Exportando a PDF…", () -> {
                app.reportes().exportarPdf(paquete, f.toPath());
                return f;
            }, r -> Dialogs.info("Reporte exportado", "Archivo generado:\n" + r.getAbsolutePath()));
        }
    }

    @FXML
    private void exportAll() {
        LocalDate d = fromDate.getValue();
        LocalDate h = toDate.getValue();
        if (d == null || h == null || d.isAfter(h)) {
            Dialogs.warning("Rango inválido", "Seleccione un rango de fechas válido.");
            return;
        }
        File f = elegir("reportes-stockwise", "xlsx", "Excel (*.xlsx)");
        if (f == null) {
            return;
        }
        shell.run("Generando todos los reportes…", () -> {
            List<ReportTable> tablas = new ArrayList<>();
            for (ReportType t : ReportType.values()) {
                tablas.add(app.reportes().generar(t, d, h));
            }
            app.reportes().exportarExcel(tablas, f.toPath());
            return f;
        }, r -> Dialogs.info("Reportes exportados", "Se generó un libro con " + ReportType.values().length
                + " hojas:\n" + r.getAbsolutePath()));
    }
}
