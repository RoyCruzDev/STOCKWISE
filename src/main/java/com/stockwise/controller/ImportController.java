package com.stockwise.controller;

import com.stockwise.exporter.ReportTable;
import com.stockwise.importer.ImportService;
import com.stockwise.importer.ResumenErrores;
import com.stockwise.importer.PlantillasImportacion;
import com.stockwise.importer.LectorStreaming;
import com.stockwise.service.Progreso;
import com.stockwise.importer.FieldDef;
import com.stockwise.importer.ImportPreview;
import com.stockwise.importer.ImportResult;
import com.stockwise.importer.ImportRow;
import com.stockwise.importer.ImportType;
import com.stockwise.importer.SampleDataGenerator;
import com.stockwise.importer.SheetData;
import com.stockwise.importer.TemplateGenerator;
import com.stockwise.service.ValidationException;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Formatters;
import com.stockwise.util.Tables;
import com.stockwise.util.Tables.Kind;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ImportController extends BaseController {

    private static final String NO_IMPORTAR = "(no importar)";
    private static final String[] FILTROS = {"Todas las filas", "Válidas", "Con error", "Duplicadas"};

    @FXML
    private Label fileLabel;
    @FXML
    private VBox mappingCard;
    @FXML
    private ComboBox<String> sheetCombo;
    @FXML
    private ComboBox<ImportType> typeCombo;
    @FXML
    private Label sheetInfo;
    @FXML
    private GridPane mappingGrid;
    @FXML
    private ComboBox<PlantillasImportacion.Plantilla> templateCombo;
    @FXML
    private javafx.scene.control.CheckBox separateLinesCheck;
    @FXML
    private VBox previewCard;
    @FXML
    private Label newLabel;
    @FXML
    private Label updateLabel;
    @FXML
    private Label duplicateLabel;
    @FXML
    private Label errorLabel;
    @FXML
    private ComboBox<String> filterCombo;
    @FXML
    private Button importButton;
    @FXML
    private TableView<ImportRow> previewTable;

    @FXML
    private VBox progressCard;
    @FXML
    private Label progressTitle;
    @FXML
    private Label progressPercent;
    @FXML
    private Label progressDetail;
    @FXML
    private javafx.scene.control.ProgressBar progressBar;
    @FXML
    private Button cancelButton;

    private List<SheetData> hojas = List.of();
    /** Hojas inspeccionadas por flujo (total de filas y si la muestra contiene todas). */
    private List<LectorStreaming.Hoja> info = List.of();
    private Path rutaActual;
    private String archivoActual;
    /** Cancelación cooperativa de la tarea en curso (no interrumpe un bloque a medias). */
    private final java.util.concurrent.atomic.AtomicBoolean cancelar = new java.util.concurrent.atomic.AtomicBoolean();
    private boolean tareaEnCurso;
    private final Label estructuraLabel = new Label();
    private final Map<String, ComboBox<String>> mappingCombos = new LinkedHashMap<>();
    private ImportPreview preview;

    public ImportController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        typeCombo.getItems().setAll(ImportType.values());
        filterCombo.getItems().setAll(FILTROS);
        filterCombo.getSelectionModel().selectFirst();
        filterCombo.valueProperty().addListener((o, a, b) -> renderPreviewRows());
        sheetCombo.valueProperty().addListener((o, a, b) -> onSheetChanged());
        typeCombo.valueProperty().addListener((o, a, b) -> rebuildMapping());
        templateCombo.valueProperty().addListener((o, a, b) -> aplicarPlantilla(b));
        separateLinesCheck.selectedProperty().addListener((o, a, b) -> {
            lineasSeparadas = b;
            limpiarPreview();
        });
        Tables.placeholder(previewTable, "Valide una hoja para ver la vista previa.");
    }

    private SheetData hojaActual() {
        return hojas.stream().filter(h -> h.nombre().equals(sheetCombo.getValue())).findFirst().orElse(null);
    }

    @FXML
    private void selectFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Seleccionar archivo Excel o CSV (reportes ERP)");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Excel o texto delimitado (*.xlsx, *.xls, *.csv, *.txt)", "*.xlsx", "*.xls",
                        "*.csv", "*.txt"),
                new FileChooser.ExtensionFilter("Libros de Excel (*.xlsx, *.xls)", "*.xlsx", "*.xls"),
                new FileChooser.ExtensionFilter("CSV / texto delimitado (*.csv, *.txt)", "*.csv", "*.txt"));
        File f = fc.showOpenDialog(shell.window());
        if (f != null) {
            cargarArchivo(f.toPath());
        }
    }

    /**
     * Ejecuta un trabajo largo en segundo plano mostrando su avance real (porcentaje y detalle) y permitiendo
     * cancelarlo: la cancelación se consulta entre bloques, de modo que nunca queda un bloque guardado a medias.
     */
    private <T> void conProgreso(String titulo, java.util.function.Function<Progreso, T> trabajo,
                                 java.util.function.Consumer<T> alTerminar) {
        if (tareaEnCurso) {
            return;
        }
        tareaEnCurso = true;
        cancelar.set(false);
        progressTitle.setText(titulo);
        progressPercent.setText("0 %");
        progressDetail.setText("Iniciando…");
        progressBar.setProgress(0);
        cancelButton.setDisable(false);
        progressCard.setVisible(true);
        progressCard.setManaged(true);
        mappingCard.setDisable(true);
        previewCard.setDisable(true);
        javafx.concurrent.Task<T> task = new javafx.concurrent.Task<>() {
            @Override
            protected T call() {
                return trabajo.apply(new Progreso() {
                    @Override
                    public void avance(double porcentaje, String mensaje) {
                        updateProgress(porcentaje, 100);
                        updateMessage(mensaje);
                    }

                    @Override
                    public boolean cancelado() {
                        return cancelar.get();
                    }
                });
            }
        };
        task.progressProperty().addListener((o, a, b) -> {
            double pct = Math.max(0, b.doubleValue());
            progressBar.setProgress(pct);
            progressPercent.setText(String.format("%.0f %%", Math.floor(pct * 100)));
        });
        task.messageProperty().addListener((o, a, b) -> progressDetail.setText(b));
        Runnable fin = () -> {
            tareaEnCurso = false;
            progressCard.setVisible(false);
            progressCard.setManaged(false);
            mappingCard.setDisable(hojas.isEmpty());
            previewCard.setDisable(preview == null);
        };
        task.setOnSucceeded(e -> {
            fin.run();
            alTerminar.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            fin.run();
            Throwable ex = task.getException();
            if (ex instanceof java.util.concurrent.CancellationException) {
                shell.status("Operación cancelada");
                return;
            }
            Dialogs.error(ex);
        });
        Thread t = new Thread(task, "stockwise-importacion");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void cancelTask() {
        cancelar.set(true);
        cancelButton.setDisable(true);
        progressDetail.setText("Cancelando al terminar el bloque en curso…");
    }

    private LectorStreaming.Hoja infoActual() {
        return info.stream().filter(h -> h.nombre().equals(sheetCombo.getValue())).findFirst().orElse(null);
    }

    private void cargarArchivo(Path archivo) {
        conProgreso("Leyendo " + archivo.getFileName(), p -> LectorStreaming.inspeccionar(archivo,
                LectorStreaming.MUESTRA, p), leidas -> {
            info = leidas;
            hojas = leidas.stream().map(LectorStreaming.Hoja::muestra).toList();
            rutaActual = archivo;
            archivoActual = archivo.getFileName().toString();
            long total = leidas.stream().mapToLong(LectorStreaming.Hoja::totalFilas).sum();
            fileLabel.setText(String.format("%s · %d hoja(s) con datos · %,d filas", archivo.getFileName(),
                    leidas.size(), total));
            mappingCard.setDisable(false);
            sheetCombo.getItems().setAll(hojas.stream().map(SheetData::nombre).toList());
            sheetCombo.getSelectionModel().select(hojas.stream()
                    .filter(h -> ImportType.detectar(h.nombre(), h.encabezados()) != null).map(SheetData::nombre).findFirst()
                    .orElse(hojas.get(0).nombre()));
            limpiarPreview();
            shell.status("Archivo leído: " + archivo.getFileName());
        });
    }

    private void onSheetChanged() {
        SheetData h = hojaActual();
        if (h == null) {
            return;
        }
        LectorStreaming.Hoja hi = infoActual();
        sheetInfo.setText(hi != null && !hi.completa()
                ? String.format("%,d filas · encabezados en la fila %d · archivo grande: se importa por bloques (la vista "
                + "previa valida las primeras %,d)", hi.totalFilas(), h.filaEncabezado(), h.totalFilas())
                : String.format("%,d filas de datos · encabezados en la fila %d", h.totalFilas(), h.filaEncabezado()));
        ImportType detectado = ImportType.detectar(h.nombre(), h.encabezados());
        if (detectado != null && detectado != typeCombo.getValue()) {
            typeCombo.setValue(detectado);
        } else {
            rebuildMapping();
        }
        limpiarPreview();
    }

    private void rebuildMapping() {
        mappingGrid.getChildren().clear();
        mappingCombos.clear();
        SheetData h = hojaActual();
        ImportType tipo = typeCombo.getValue();
        if (h == null || tipo == null) {
            return;
        }
        Map<String, Integer> sugerido = app.importacion().mapeoSugerido(tipo, h.encabezados());
        java.util.Optional<String> origen = app.importacion().origenSugerencia(tipo, h.encabezados());
        boolean perfil = origen.isPresent();
        java.util.Optional<String> cambio = perfil ? java.util.Optional.empty()
                : app.importacion().cambioEstructura(tipo, h.encabezados());
        estructuraLabel.setText(cambio.orElse(origen.orElse("")));
        estructuraLabel.getStyleClass().setAll("notice", cambio.isPresent() ? "warning" : "success");
        estructuraLabel.setWrapText(true);
        estructuraLabel.setMaxWidth(Double.MAX_VALUE);
        cargarPlantillas(tipo);
        separateLinesCheck.setVisible(tipo == ImportType.VENTAS || tipo == ImportType.COMPRAS);
        separateLinesCheck.setManaged(separateLinesCheck.isVisible());
        boolean mostrar = cambio.isPresent() || perfil;
        estructuraLabel.setVisible(mostrar);
        estructuraLabel.setManaged(mostrar);
        if (!mappingCard.getChildren().contains(estructuraLabel)) {
            mappingCard.getChildren().add(1, estructuraLabel);
        }
        List<String> opciones = new ArrayList<>();
        opciones.add(NO_IMPORTAR);
        for (int i = 0; i < h.encabezados().size(); i++) {
            opciones.add(columna(i) + ": " + h.encabezados().get(i));
        }
        int i = 0;
        for (FieldDef f : tipo.campos()) {
            Label l = new Label(f.etiqueta() + (f.obligatorio() ? " *" : ""));
            l.getStyleClass().add("form-label");
            ComboBox<String> cb = new ComboBox<>();
            cb.getItems().setAll(opciones);
            cb.setPrefWidth(260);
            Integer col = sugerido.get(f.clave());
            cb.getSelectionModel().select(col == null ? 0 : col + 1);
            if (f.descripcion() != null && !f.descripcion().isBlank()) {
                javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip(f.descripcion());
                l.setTooltip(tip);
                cb.setTooltip(tip);
            }
            int fila = i / 3;
            int base = (i % 3) * 2;
            mappingGrid.add(l, base, fila);
            mappingGrid.add(cb, base + 1, fila);
            mappingCombos.put(f.clave(), cb);
            cb.valueProperty().addListener((o, a, b) -> limpiarPreview());
            i++;
        }
        limpiarPreview();
    }

    private boolean cargandoPlantillas;
    /** Copia de la casilla de líneas idénticas, legible desde las tareas en segundo plano. */
    private volatile boolean lineasSeparadas;
    /** Archivo con todas las filas rechazadas de la última importación grande (nulo si no hubo). */
    private java.nio.file.Path ultimasRechazadas;

    private ImportService.Opciones opciones(boolean crear) {
        return new ImportService.Opciones(crear, lineasSeparadas);
    }

    /** Plantillas guardadas del tipo elegido; al elegir una se aplica su relación de columnas. */
    private void cargarPlantillas(ImportType tipo) {
        cargandoPlantillas = true;
        templateCombo.getItems().setAll((PlantillasImportacion.Plantilla) null);
        templateCombo.getItems().addAll(app.importacion().plantillas().listar(tipo));
        templateCombo.setConverter(com.stockwise.util.Forms.converter(p -> p == null ? "Detección automática"
                : p.nombre()));
        templateCombo.getSelectionModel().selectFirst();
        cargandoPlantillas = false;
    }

    private void aplicarPlantilla(PlantillasImportacion.Plantilla p) {
        SheetData h = hojaActual();
        ImportType tipo = typeCombo.getValue();
        if (cargandoPlantillas || h == null || tipo == null) {
            return;
        }
        Map<String, Integer> m = p == null ? com.stockwise.importer.ColumnMapper.sugerir(tipo, h.encabezados())
                : PlantillasImportacion.aplicar(p, h.encabezados());
        mappingCombos.forEach((campo, cb) -> {
            Integer col = m.get(campo);
            cb.getSelectionModel().select(col == null ? 0 : col + 1);
        });
        long faltan = p == null ? 0 : p.campos().keySet().stream().filter(c -> !m.containsKey(c)).count();
        shell.status(p == null ? "Relación de columnas automática"
                : "Plantilla \"" + p.nombre() + "\" aplicada" + (faltan > 0 ? " · " + faltan
                + " columna(s) de la plantilla no están en este archivo" : ""));
    }

    @FXML
    private void saveMappingTemplate() {
        SheetData h = hojaActual();
        ImportType tipo = typeCombo.getValue();
        if (h == null || tipo == null) {
            return;
        }
        javafx.scene.control.TextInputDialog d = new javafx.scene.control.TextInputDialog(
                tipo.etiqueta() + " - " + (archivoActual == null ? "mi reporte" : archivoActual.replaceAll("\\.[^.]+$", "")));
        d.setTitle("STOCKWISE");
        d.setHeaderText("Guardar la relación de columnas como plantilla");
        d.setContentText("Nombre:");
        Dialogs.style(d);
        d.showAndWait().filter(n -> !n.isBlank()).ifPresent(nombre -> {
            var p = app.importacion().plantillas().guardar(tipo, nombre, h.encabezados(), mapeoActual());
            cargarPlantillas(tipo);
            templateCombo.getItems().stream().filter(x -> x != null && x.id().equals(p.id())).findFirst()
                    .ifPresent(x -> {
                        cargandoPlantillas = true;
                        templateCombo.setValue(x);
                        cargandoPlantillas = false;
                    });
            shell.status("Plantilla \"" + nombre + "\" guardada: se sugerirá en archivos con columnas parecidas.");
        });
    }

    private static String columna(int indice) {
        StringBuilder sb = new StringBuilder();
        int n = indice + 1;
        while (n > 0) {
            int r = (n - 1) % 26;
            sb.insert(0, (char) ('A' + r));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    private Map<String, Integer> mapeoActual() {
        Map<String, Integer> m = new LinkedHashMap<>();
        mappingCombos.forEach((k, cb) -> {
            int idx = cb.getSelectionModel().getSelectedIndex();
            if (idx > 0) {
                m.put(k, idx - 1);
            }
        });
        return m;
    }

    @FXML
    private void validate() {
        SheetData h = hojaActual();
        ImportType tipo = typeCombo.getValue();
        if (h == null || tipo == null) {
            Dialogs.warning("Faltan datos", "Seleccione la hoja y el tipo de información.");
            return;
        }
        Map<String, Integer> mapeo = mapeoActual();
        shell.run("Validando " + h.totalFilas() + " filas…", () -> app.importacion().previsualizar(h, tipo, mapeo, opciones(false)), p -> {
            preview = p;
            renderPreview();
            shell.status("Validación: " + p.resumen());
        });
    }

    private void limpiarPreview() {
        preview = null;
        crearFaltantes = false;
        ultimasRechazadas = null;
        previewTable.getItems().clear();
        previewTable.getColumns().clear();
        previewCard.setDisable(true);
        newLabel.setText("0 nuevos");
        updateLabel.setText("0 actualizaciones");
        duplicateLabel.setText("0 duplicados");
        errorLabel.setText("0 con error");
    }

    private void renderPreview() {
        previewCard.setDisable(false);
        previewTable.getColumns().clear();
        Tables.col(previewTable, "Fila", ImportRow::numeroFila, Kind.INT, 55);
        Tables.badge(previewTable, "Estado", r -> r.estado().toString(), Tables.ESTADOS, 140);
        for (FieldDef f : preview.tipo().campos()) {
            if (preview.mapeo().containsKey(f.clave())) {
                Tables.col(previewTable, f.etiqueta(), r -> mostrar(r.get(f.clave())), Kind.TEXT, 120);
            }
        }
        Tables.col(previewTable, "Observaciones", ImportRow::mensajesTexto, Kind.TEXT, 380);
        newLabel.setText(preview.count(ImportRow.Estado.NUEVO) + " nuevos");
        updateLabel.setText(preview.count(ImportRow.Estado.ACTUALIZA) + " actualizaciones");
        duplicateLabel.setText(preview.count(ImportRow.Estado.DUPLICADO) + " duplicados");
        errorLabel.setText(preview.count(ImportRow.Estado.ERROR) + " con error");
        LectorStreaming.Hoja hi = infoActual();
        boolean grande = hi != null && !hi.completa() && preview.hoja().equals(hi.nombre());
        if (grande) {
            importButton.setText(String.format("Importar las %,d filas por bloques", hi.totalFilas()));
            importButton.setDisable(false);
        } else {
            importButton.setText("Importar " + preview.importables() + " registro(s)");
            importButton.setDisable(preview.importables() == 0);
        }
        renderPreviewRows();
    }

    private static Object mostrar(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof LocalDate d) {
            return Formatters.date(d);
        }
        if (v instanceof YearMonth ym) {
            return ym.toString();
        }
        if (v instanceof Double d) {
            return Formatters.number(d);
        }
        return v;
    }

    private void renderPreviewRows() {
        if (preview == null) {
            return;
        }
        String filtro = filterCombo.getValue();
        List<ImportRow> filas = preview.filas().stream().filter(r -> switch (filtro == null ? FILTROS[0] : filtro) {
            case "Válidas" -> r.importable();
            case "Con error" -> r.estado() == ImportRow.Estado.ERROR;
            case "Duplicadas" -> r.estado() == ImportRow.Estado.DUPLICADO;
            default -> true;
        }).toList();
        previewTable.getItems().setAll(filas);
    }

    /** El usuario eligió crear los productos y sucursales de las ventas que no existan. */
    private boolean crearFaltantes;

    /**
     * Antes de importar ventas con productos o sucursales desconocidos, explica la causa con ejemplos y ofrece
     * soluciones (en lugar de importar y terminar con todas las filas en error).
     *
     * @return verdadero si se puede seguir con la importación
     */
    private boolean resolverFaltantes() {
        if (preview.tipo() != ImportType.VENTAS || crearFaltantes) {
            return true;
        }
        ResumenErrores causas = ResumenErrores.de(preview.filas());
        long prod = causas.filas("producto");
        long suc = causas.filas("sucursal");
        if (prod + suc == 0) {
            return true;
        }
        long errores = preview.count(ImportRow.Estado.ERROR);
        boolean conSucursal = mappingCombos.containsKey("sucursal")
                && mappingCombos.get("sucursal").getSelectionModel().getSelectedIndex() > 0;
        List<String> opciones = new ArrayList<>();
        opciones.add("Crear los que falten e importar");
        if (suc > 0 && conSucursal) {
            opciones.add("Usar la sucursal principal");
        }
        if (preview.importables() > 0) {
            opciones.add(String.format("Importar solo las %,d filas válidas", preview.importables()));
        }
        String msg = String.format("En la vista previa (%,d filas) hay %,d fila(s) que no se podrían guardar:%n%n%s"
                        + "%n%nQué significa cada opción:"
                        + "%n• Crear los que falten: se registran los productos (con el código, nombre, categoría, precio y "
                        + "costo del archivo) y las sucursales que no existan. No se borra ni cambia nada existente."
                        + (suc > 0 && conSucursal ? "%n• Usar la sucursal principal: se ignora la columna de sucursal y todas "
                        + "las ventas se registran en la sucursal principal." : "")
                        + "%n• Cancelar: puede revisar la relación de columnas (p. ej. relacionar \"Código de producto\" "
                        + "con la columna que tiene el mismo código que su catálogo).",
                preview.filas().size(), errores, String.join("\n", causas.lineas(5)));
        int r = Dialogs.elegir("Hay productos o sucursales que no están registrados", msg,
                opciones.toArray(String[]::new));
        if (r < 0) {
            return false;
        }
        String elegida = opciones.get(r);
        if (elegida.startsWith("Crear")) {
            SheetData h = hojaActual();
            ImportType tipo = preview.tipo();
            Map<String, Integer> mapeo = preview.mapeo();
            shell.run("Validando con creación de faltantes…", () -> app.importacion().previsualizar(h, tipo, mapeo, opciones(true)),
                    p -> {
                        preview = p;
                        crearFaltantes = true;
                        renderPreview();
                        importPreview();
                    });
            return false;
        }
        if (elegida.startsWith("Usar")) {
            mappingCombos.get("sucursal").getSelectionModel().select(0);
            validate();
            shell.status("Se usará la sucursal principal. Revise la vista previa y pulse Importar.");
            return false;
        }
        return true;
    }

    @FXML
    private void importPreview() {
        if (preview == null) {
            return;
        }
        if (!resolverFaltantes()) {
            return;
        }
        String msg = preview.resumen() + "\n\nSe guardarán " + preview.importables() + " registro(s) de tipo '"
                + preview.tipo().etiqueta() + "'. Las filas con error o duplicadas se omitirán.";
        if (preview.tasaError() > 0.3) {
            msg += "\n\nATENCIÓN: más del 30% de las filas tienen errores. Verifique que la hoja y la relación de "
                    + "columnas sean correctas antes de continuar.";
        }
        var solapadas = app.importacion().importacionesSolapadas(preview);
        if (!solapadas.isEmpty()) {
            msg += "\n\nEl periodo de este archivo se superpone con " + solapadas.size() + " importación(es) anterior(es) "
                    + "(" + String.join(", ", solapadas.stream().map(i -> i.archivo() + " " + InicioController.periodo(i))
                    .toList()) + "). No se duplicará nada: cada registro se identifica por fecha, producto, sucursal, "
                    + "cantidad, precio y documento, y los repetidos se omiten.";
        }
        LectorStreaming.Hoja hi = infoActual();
        if (hi != null && !hi.completa() && preview.hoja().equals(hi.nombre())) {
            importarGrande(hi, msg);
            return;
        }
        if (!Dialogs.confirm("¿Confirmar importación?", msg)) {
            return;
        }
        ImportPreview p = preview;
        SheetData hoja = hojaActual();
        String archivo = archivoActual;
        shell.run("Guardando registros…", () -> {
            ImportResult res = app.importacion().importar(p, archivo, hoja == null ? null : hoja.encabezados());
            if (hoja != null) {
                app.importacion().guardarPerfil(p.tipo(), hoja.encabezados(), p.mapeo());
            }
            return res;
        }, r -> {
            shell.status(r.resumen());
            limpiarPreview();
            ofrecerAnalisis("Importación completada", r.resumen());
        });
    }

    @FXML
    private void importAll() {
        if (hojas.isEmpty()) {
            return;
        }
        List<SheetData> reconocidas = new ArrayList<>();
        for (ImportType t : ImportType.values()) {
            hojas.stream().filter(h -> ImportType.detectar(h.nombre(), h.encabezados()) == t).findFirst().ifPresent(reconocidas::add);
        }
        if (reconocidas.isEmpty()) {
            Dialogs.warning("Sin hojas reconocidas", "Ninguna hoja tiene un nombre reconocible (Productos, Ventas, "
                    + "Inventario, Sucursales, Proveedores, Órdenes de compra, Presupuestos). Importe cada hoja manualmente.");
            return;
        }
        if (!Dialogs.confirm("Importar todas las hojas reconocidas",
                "Se validarán e importarán en orden las hojas: " + String.join(", ",
                        reconocidas.stream().map(SheetData::nombre).toList())
                        + ".\nLas columnas se relacionarán automáticamente y las filas con error se omitirán.")) {
            return;
        }
        Path ruta = rutaActual;
        List<LectorStreaming.Hoja> infos = info;
        conProgreso("Importando hojas de " + archivoActual, prog -> {
            List<String> resumen = new ArrayList<>();
            for (int i = 0; i < reconocidas.size(); i++) {
                SheetData h = reconocidas.get(i);
                ImportType t = ImportType.detectar(h.nombre(), h.encabezados());
                Progreso sub = parcial(prog, 100.0 * i / reconocidas.size(), 100.0 / reconocidas.size(),
                        "Hoja " + (i + 1) + " de " + reconocidas.size() + " (" + h.nombre() + ")");
                try {
                    prog.verificar();
                    LectorStreaming.Hoja hi = infos.stream().filter(x -> x.nombre().equals(h.nombre())).findFirst()
                            .orElse(null);
                    var mapeo = app.importacion().mapeoSugerido(t, h.encabezados());
                    if (hi != null && !hi.completa()) {
                        var r = app.importacion().importarPorBloques(ruta, hi, t, mapeo, sub);
                        resumen.add("• " + h.nombre() + ": " + r.resumen() + (r.completa() ? "" : " " + r.mensaje())
                                + (r.causas().vacio() ? "" : "\n   Motivos: " + String.join("\n   ", r.causas().lineas(3))));
                        if (!r.completa()) {
                            break;
                        }
                        continue;
                    }
                    sub.avance(0, "Validando");
                    ImportPreview p = app.importacion().previsualizar(h, t, mapeo);
                    if (p.importables() == 0) {
                        ResumenErrores ce = ResumenErrores.de(p.filas());
                        resumen.add("• " + h.nombre() + ": sin filas válidas (" + p.resumen() + ")"
                                + (ce.vacio() ? "" : "\n   Motivos: " + String.join("\n   ", ce.lineas(3))));
                        continue;
                    }
                    sub.avance(50, "Guardando");
                    ImportResult r = app.importacion().importar(p, archivoActual, h.encabezados());
                    resumen.add("• " + h.nombre() + ": " + r.resumen()
                            + (p.count(ImportRow.Estado.ERROR) > 0 ? " Errores: " + p.count(ImportRow.Estado.ERROR) : ""));
                } catch (ValidationException e) {
                    resumen.add("• " + h.nombre() + ": no importada - " + e.getMessage());
                } catch (java.util.concurrent.CancellationException e) {
                    resumen.add("• Cancelado antes de importar " + h.nombre() + " y las hojas siguientes.");
                    break;
                }
            }
            return String.join("\n", resumen);
        }, texto -> {
            shell.status("Importación de hojas finalizada");
            ofrecerAnalisis("Resultado de la importación", texto);
        });
    }

    /** Avance de una parte del trabajo: [base, base + rango] del total, con un prefijo en el mensaje. */
    private static Progreso parcial(Progreso p, double base, double rango, String prefijo) {
        return new Progreso() {
            @Override
            public void avance(double porcentaje, String mensaje) {
                p.avance(base + rango * Math.min(100, porcentaje) / 100.0, prefijo + " · " + mensaje);
            }

            @Override
            public boolean cancelado() {
                return p.cancelado();
            }
        };
    }

    /**
     * Importación por bloques de una hoja grande: lee por flujo, valida y guarda cada bloque en su transacción y
     * muestra el avance real. Al terminar muestra todas las observaciones encontradas (hasta el máximo conservado).
     */
    private void importarGrande(LectorStreaming.Hoja hi, String resumenMuestra) {
        String msg = String.format("El archivo tiene %,d filas: se leerá por flujo y se guardará en bloques de %,d filas, "
                        + "cada uno en su propia transacción. La vista previa corresponde a las primeras %,d filas; todas las "
                        + "filas se validan al importar (mismas reglas, repetidos y relación de columnas).%n%nMuestra: %s"
                        + "%n%nSi cancela, se conservan los bloques ya guardados (importación PARCIAL); puede completarla "
                        + "importando otra vez el archivo (lo guardado se detecta como repetido) o eliminarla desde el Historial.",
                hi.totalFilas(), ImportService.TAMANO_BLOQUE, hi.muestra().totalFilas(), resumenMuestra.split("\n")[0]);
        if (!Dialogs.confirm("¿Importar archivo grande por bloques?", msg)) {
            return;
        }
        ImportType tipo = preview.tipo();
        Map<String, Integer> mapeo = preview.mapeo();
        Path ruta = rutaActual;
        boolean crear = crearFaltantes;
        conProgreso("Importando " + archivoActual + " (" + hi.nombre() + ")", p -> {
            var r = app.importacion().importarPorBloques(ruta, hi, tipo, mapeo, opciones(crear), p);
            app.importacion().guardarPerfil(tipo, hi.encabezados(), mapeo);
            return r;
        }, r -> {
            shell.status(r.resumen());
            preview = r.comoPreview(mapeo);
            ultimasRechazadas = r.filasRechazadas();
            renderPreview();
            importButton.setDisable(true);
            importButton.setText("Importación terminada");
            crearFaltantes = false;
            String texto = r.explicacion() + (r.observaciones().size() >= ImportService.MAX_OBSERVACIONES
                    ? String.format("%n%nLa tabla muestra las primeras %,d filas con observaciones (puede exportarlas).",
                    ImportService.MAX_OBSERVACIONES) : "");
            if (r.fallida()) {
                Dialogs.warning("No se guardó ningún registro", texto);
            } else if (r.insertados() + r.actualizados() > 0 && r.completa()) {
                ofrecerAnalisis("Importación completada", texto);
            } else {
                Dialogs.info(r.completa() ? "Importación completada" : "Importación detenida", texto);
            }
        });
    }

    /** Tras importar, ofrece actualizar el análisis con todo el historial acumulado. */
    private void ofrecerAnalisis(String titulo, String resumen) {
        if (app.ventaRepository().count() > 0 && Dialogs.confirm(titulo, resumen + "\n\nLos datos se agregaron al "
                + "historial del proyecto. ¿Desea analizar ahora con todo el historial acumulado?")) {
            shell.navigate(View.PROCESAMIENTO, ProcesamientoController.INICIAR);
        }
    }

    @FXML
    private void exportIssues() {
        if (ultimasRechazadas != null && java.nio.file.Files.exists(ultimasRechazadas)) {
            // Importación grande: todas las filas rechazadas, con su motivo y los valores originales.
            File f = saveChooser("filas-rechazadas.csv", "*.csv");
            if (f == null) {
                return;
            }
            java.nio.file.Path origen = ultimasRechazadas;
            shell.run("Guardando filas rechazadas…", () -> {
                java.nio.file.Files.copy(origen, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return f;
            }, r -> Dialogs.info("Archivo generado", "Todas las filas rechazadas (con su motivo y los valores "
                    + "originales) se guardaron en:\n" + r.getAbsolutePath() + "\n\nPuede abrirlo en Excel, corregirlas "
                    + "e importarlas de nuevo."));
            return;
        }
        if (preview == null) {
            return;
        }
        List<ImportRow> obs = preview.filas().stream().filter(r -> !r.mensajes().isEmpty()).toList();
        if (obs.isEmpty()) {
            Dialogs.info("Sin observaciones", "Todas las filas son válidas y no tienen observaciones.");
            return;
        }
        File f = saveChooser("observaciones-importacion.xlsx", "*.xlsx");
        if (f == null) {
            return;
        }
        List<List<Object>> filas = obs.stream().<List<Object>>map(r -> List.of(r.numeroFila(), r.estado().toString(),
                r.mensajesTexto())).toList();
        ReportTable t = new ReportTable("Observaciones de importación", "Hoja " + preview.hoja() + " · "
                + preview.tipo().etiqueta(), List.of("Fila", "Estado", "Observaciones"),
                List.of(ReportTable.ColumnType.ENTERO, ReportTable.ColumnType.TEXTO, ReportTable.ColumnType.TEXTO),
                filas, List.of(preview.resumen()));
        shell.run("Exportando…", () -> {
            app.reportes().exportarExcel(List.of(t), f.toPath());
            return f;
        }, r -> Dialogs.info("Archivo generado", "Observaciones exportadas a:\n" + r.getAbsolutePath()));
    }

    @FXML
    private void saveTemplate() {
        File f = saveChooser("plantilla-stockwise.xlsx", "*.xlsx");
        if (f == null) {
            return;
        }
        shell.run("Generando plantilla…", () -> {
            TemplateGenerator.generar(f.toPath());
            return f;
        }, r -> Dialogs.info("Plantilla generada", "La plantilla oficial se guardó en:\n" + r.getAbsolutePath()));
    }

    @FXML
    private void saveDemo() {
        File f = saveChooser("demo-stockwise.xlsx", "*.xlsx");
        if (f == null) {
            return;
        }
        shell.run("Generando datos de demostración…", () -> {
            SampleDataGenerator.generar(f.toPath());
            return f;
        }, r -> {
            if (Dialogs.confirm("Archivo de demostración generado",
                    "Se guardó en:\n" + r.getAbsolutePath() + "\n\nContiene datos sintéticos de 24 meses. "
                            + "¿Desea abrirlo ahora para importarlo?")) {
                cargarArchivo(r.toPath());
            }
        });
    }

    private File saveChooser(String nombre, String ext) {
        FileChooser fc = new FileChooser();
        fc.setInitialFileName(nombre);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel (" + ext + ")", ext));
        return fc.showSaveDialog(shell.window());
    }
}
