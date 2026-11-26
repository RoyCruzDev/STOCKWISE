package com.stockwise.controller;

import com.stockwise.importer.ImportType;
import com.stockwise.repository.AnalisisRepository.Analisis;
import com.stockwise.repository.ImportacionRepository;
import com.stockwise.repository.ImportacionRepository.Importacion;
import com.stockwise.service.ImportHistoryService;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Formatters;
import com.stockwise.util.Tables;
import com.stockwise.util.Tables.Kind;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;

import java.util.List;
import java.util.Map;

/** Historial de importaciones y de análisis, con eliminación segura y previa explicación de lo afectado. */
public class HistorialController extends BaseController {

    private record Datos(List<Importacion> importaciones, long ventasSinHistorial, List<Analisis> analisis) {
    }

    @FXML
    private Label importsInfo;
    @FXML
    private Label analysisInfo;
    @FXML
    private TableView<Importacion> importsTable;
    @FXML
    private TableView<Analisis> analysisTable;
    @FXML
    private Button deleteImportButton;
    @FXML
    private Button rejectedButton;

    /** Archivo con las filas rechazadas de una importación (puede no existir). */
    private java.nio.file.Path rechazadas(Importacion i) {
        return com.stockwise.importer.FilasRechazadas.archivo(app.database().getFile(), i.id());
    }

    @FXML
    private void downloadRejected() {
        Importacion i = importsTable.getSelectionModel().getSelectedItem();
        if (i == null) {
            return;
        }
        java.nio.file.Path origen = rechazadas(i);
        if (!java.nio.file.Files.exists(origen)) {
            Dialogs.info("Sin filas rechazadas", i.errores() == 0 ? "Esta importación no tuvo filas con error."
                    : "Esta importación se hizo con la vista previa (archivo pequeño): sus observaciones se exportan "
                    + "desde la pantalla de importación antes de importar.");
            return;
        }
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setInitialFileName("filas-rechazadas-" + i.archivo().replaceAll("\\.[^.]+$", "") + ".csv");
        fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV (*.csv)", "*.csv"));
        java.io.File f = fc.showSaveDialog(shell.window());
        if (f == null) {
            return;
        }
        shell.run("Guardando filas rechazadas…", () -> {
            java.nio.file.Files.copy(origen, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return f;
        }, r -> Dialogs.info("Archivo generado", "Filas rechazadas con su motivo:\n" + r.getAbsolutePath()));
    }
    @FXML
    private Button deleteAnalysisButton;
    @FXML
    private Button openButton;

    public HistorialController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        Tables.col(importsTable, "Fecha de importación", Importacion::fecha, Kind.TEXT, 150);
        Tables.col(importsTable, "Archivo", Importacion::archivo, Kind.TEXT, 230);
        Tables.col(importsTable, "Hoja", Importacion::hoja, Kind.TEXT, 120);
        Tables.col(importsTable, "Tipo", i -> ImportType.valueOf(i.tipo()).etiqueta(), Kind.TEXT, 130);
        Tables.col(importsTable, "Periodo de la información", InicioController::periodo, Kind.TEXT, 190);
        Tables.col(importsTable, "Filas", Importacion::filas, Kind.INT, 65);
        Tables.col(importsTable, "Nuevos", Importacion::insertados, Kind.INT, 70);
        Tables.col(importsTable, "Actualizados", Importacion::actualizados, Kind.INT, 85);
        Tables.col(importsTable, "Repetidos", Importacion::duplicados, Kind.INT, 75);
        Tables.col(importsTable, "Con error", Importacion::errores, Kind.INT, 75);
        Tables.badge(importsTable, "Estado", Importacion::estado, Map.of("COMPLETADA", "success", "PARCIAL", "warning", "ELIMINADA",
                "neutral", "FALLIDA", "danger"), 110);
        Tables.col(importsTable, "Motivo de los errores", i -> i.detalle() == null ? "" : i.detalle().split("\n")[0],
                Kind.TEXT, 360);
        importsTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<Importacion> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty() && row.getItem().detalle() != null) {
                    Dialogs.info("Motivos de los errores: " + row.getItem().archivo(), row.getItem().detalle());
                }
            });
            return row;
        });
        Tables.placeholder(importsTable, "Aún no se importaron archivos en este proyecto.");
        deleteImportButton.disableProperty().bind(importsTable.getSelectionModel().selectedItemProperty().isNull());
        rejectedButton.disableProperty().bind(importsTable.getSelectionModel().selectedItemProperty().isNull());

        Tables.col(analysisTable, "#", Analisis::id, Kind.INT, 50);
        Tables.col(analysisTable, "Fecha", Analisis::fecha, Kind.TEXT, 150);
        Tables.col(analysisTable, "Datos hasta", Analisis::fechaCorte, Kind.DATE, 95);
        Tables.col(analysisTable, "Ventas usadas", Analisis::registrosVentas, Kind.INT, 100);
        Tables.col(analysisTable, "Productos", Analisis::productos, Kind.INT, 80);
        Tables.col(analysisTable, "Con pronóstico", Analisis::conPronostico, Kind.INT, 100);
        Tables.col(analysisTable, "Compra sugerida", Analisis::compraSugerida, Kind.MONEY, 120);
        Tables.col(analysisTable, "Error de predicción", Analisis::wape, Kind.PERCENT, 95);
        Tables.col(analysisTable, "Mejora vs. ingenuo", Analisis::fva, Kind.PERCENT, 120);
        Tables.col(analysisTable, "Duración (s)", a -> a.duracionMs() == null ? null : a.duracionMs() / 1000.0,
                Kind.DECIMAL, 90);
        Tables.placeholder(analysisTable, "Aún no se realizaron análisis.");
        deleteAnalysisButton.disableProperty().bind(analysisTable.getSelectionModel().selectedItemProperty().isNull());
        openButton.disableProperty().bind(analysisTable.getSelectionModel().selectedItemProperty().isNull());
        analysisTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<Analisis> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    shell.navigate(View.RESULTADOS, row.getItem().id());
                }
            });
            return row;
        });
        cargar();
    }

    private void cargar() {
        shell.run("Cargando historial…", () -> new Datos(app.historialImportaciones().listar(),
                app.historialImportaciones().ventasSinHistorial(), app.resultados().analisis()), d -> {
            importsTable.getItems().setAll(d.importaciones());
            long activas = d.importaciones().stream().filter(i -> !ImportacionRepository.ELIMINADA.equals(i.estado()))
                    .count();
            importsInfo.setText(activas + " importación(es) activa(s)." + (d.ventasSinHistorial() > 0
                    ? " " + Formatters.integer(d.ventasSinHistorial()) + " venta(s) se registraron antes del historial de "
                    + "importaciones o manualmente: se conservan siempre." : ""));
            analysisTable.getItems().setAll(d.analisis());
            analysisInfo.setText(d.analisis().size() + " análisis guardado(s). Doble clic para ver sus resultados; "
                    + "desde Resultados puede compararlos.");
        });
    }

    @FXML
    private void deleteImport() {
        Importacion imp = importsTable.getSelectionModel().getSelectedItem();
        if (imp == null) {
            return;
        }
        if (ImportacionRepository.ELIMINADA.equals(imp.estado())) {
            Dialogs.info("Importación eliminada", "Esta importación ya fue eliminada.");
            return;
        }
        shell.run("Calculando qué datos se afectan…", () -> app.historialImportaciones().impacto(imp.id()), impacto -> {
            String detalle = "Archivo: " + imp.archivo() + " (" + ImportType.valueOf(imp.tipo()).etiqueta() + ", "
                    + InicioController.periodo(imp) + ")\n\n" + String.join("\n", impacto.mensajes())
                    + (impacto.totalEliminar() > 0 ? String.format("%n%nDetalle: %d venta(s), %d devolución(es) y %d "
                            + "orden(es) de compra se eliminarán.", impacto.ventasEliminar(),
                    impacto.devolucionesEliminar(), impacto.comprasEliminar()) : "");
            if (!Dialogs.confirm("¿Eliminar esta importación?", detalle)) {
                return;
            }
            shell.run("Eliminando importación…", () -> app.historialImportaciones().eliminar(imp.id()),
                    (ImportHistoryService.Impacto r) -> {
                        Dialogs.info("Importación eliminada", r.totalEliminar() + " registro(s) eliminados. Vuelva a "
                                + "analizar para actualizar las predicciones.");
                        cargar();
                    });
        });
    }

    @FXML
    private void deleteAnalysis() {
        Analisis a = analysisTable.getSelectionModel().getSelectedItem();
        if (a == null) {
            return;
        }
        if (Dialogs.confirm("¿Eliminar los resultados del análisis #" + a.id() + "?", "Se eliminarán sus "
                + a.productos() + " resultados por producto (pronósticos, rankings y recomendaciones) y las marcas de "
                + "aplicada/descartada de sus recomendaciones.\n\nNO se eliminan ventas, compras, inventario ni "
                + "importaciones: el historial sigue disponible para futuros análisis.")) {
            shell.run("Eliminando resultados…", () -> {
                app.resultados().eliminar(a.id());
                return a.id();
            }, id -> cargar());
        }
    }

    @FXML
    private void openAnalysis() {
        Analisis a = analysisTable.getSelectionModel().getSelectedItem();
        if (a != null) {
            shell.navigate(View.RESULTADOS, a.id());
        }
    }

    @FXML
    private void importFile() {
        shell.navigate(View.IMPORTACION);
    }

    @FXML
    private void analyze() {
        shell.navigate(View.PROCESAMIENTO, ProcesamientoController.INICIAR);
    }
}
