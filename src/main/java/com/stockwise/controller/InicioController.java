package com.stockwise.controller;

import com.stockwise.repository.AnalisisRepository;
import com.stockwise.repository.DecisionRepository.DecisionGuardada;
import com.stockwise.service.DashboardService;
import com.stockwise.service.ResultsService;
import com.stockwise.service.ResultsService.Pregunta;
import com.stockwise.repository.ImportacionRepository.Importacion;
import com.stockwise.importer.ImportType;
import com.stockwise.util.Formatters;
import com.stockwise.util.Icons;
import com.stockwise.util.Tables;
import com.stockwise.util.Tables.Kind;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** Pantalla de inicio del proyecto: estado de los datos y el recorrido Importar → Analizar → Resultados. */
public class InicioController extends BaseController {

    private record Estado(long productos, long ventas, Optional<LocalDate> desde, Optional<LocalDate> hasta,
                          List<Importacion> importaciones, Optional<AnalisisRepository.Analisis> ultimo,
                          DashboardService.ResumenNegocio negocio, List<DecisionGuardada> decisiones,
                          boolean hayInventario) {
    }

    @FXML
    private Label titleLabel;
    @FXML
    private Label subtitleLabel;
    @FXML
    private VBox noticeBox;
    @FXML
    private HBox stepsBox;
    @FXML
    private FlowPane kpiPane;
    @FXML
    private VBox decisionesBox;
    @FXML
    private TableView<Importacion> importsTable;

    public InicioController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        titleLabel.setText(shell.proyecto() == null ? "Inicio" : shell.proyecto());
        Tables.col(importsTable, "Fecha", Importacion::fecha, Kind.TEXT, 140);
        Tables.col(importsTable, "Archivo", Importacion::archivo, Kind.TEXT, 240);
        Tables.col(importsTable, "Tipo", i -> ImportType.valueOf(i.tipo()).etiqueta(), Kind.TEXT, 140);
        Tables.col(importsTable, "Periodo", InicioController::periodo, Kind.TEXT, 190);
        Tables.col(importsTable, "Nuevos", Importacion::insertados, Kind.INT, 75);
        Tables.col(importsTable, "Repetidos", Importacion::duplicados, Kind.INT, 80);
        Tables.badge(importsTable, "Estado", Importacion::estado, java.util.Map.of("COMPLETADA", "success", "PARCIAL", "warning",
                "ELIMINADA", "neutral"), 110);
        Tables.placeholder(importsTable, "Aún no se importaron archivos en este proyecto.");
        shell.run("Cargando proyecto…", () -> new Estado(app.productos().listarActivos().size(),
                app.ventaRepository().count(), app.ventaRepository().fechaMinima(), app.ventaRepository().fechaMaxima(),
                app.historialImportaciones().listar(), app.resultados().ultimo(), app.dashboard().resumenNegocio(),
                app.resultados().ultimo().map(a -> app.resultados().resultados(a.id())).orElse(List.of()),
                app.hayInventario()), this::render);
    }

    static String periodo(Importacion i) {
        return i.periodoDesde() == null ? "—" : Formatters.date(i.periodoDesde()) + " – "
                + Formatters.date(i.periodoHasta());
    }

    private void render(Estado e) {
        long meses = e.desde().isPresent() ? ChronoUnit.MONTHS.between(e.desde().get().withDayOfMonth(1),
                e.hasta().get().withDayOfMonth(1)) + 1 : 0;
        subtitleLabel.setText(e.ventas() == 0
                ? "Empiece importando su archivo de ventas (Excel o CSV). Puede agregar meses nuevos cuando quiera: el "
                + "historial se acumula y los registros repetidos se detectan automáticamente."
                : String.format("%s ventas registradas del %s al %s (%d meses de historial) · %s productos activos.",
                Formatters.integer(e.ventas()), Formatters.date(e.desde().get()), Formatters.date(e.hasta().get()),
                meses, Formatters.integer(e.productos())));

        boolean hayVentas = e.ventas() > 0;
        boolean hayAnalisis = e.ultimo().isPresent();
        boolean desactualizado = hayAnalisis && e.ultimo().get().registrosVentas() != e.ventas();
        noticeBox.getChildren().clear();
        if (desactualizado) {
            long dif = e.ventas() - e.ultimo().get().registrosVentas();
            aviso((dif > 0 ? dif + " venta(s) nuevas" : "Cambios en las ventas") + " desde el último análisis ("
                    + e.ultimo().get().fecha() + "). Vuelva a analizar para actualizar las predicciones con todo el "
                    + "historial; el análisis anterior se conserva para compararlo.", "warning");
        }
        int siguiente = !hayVentas ? 1 : (!hayAnalisis || desactualizado) ? 2 : 3;

        Button importar = new Button("Importar archivo", Icons.IMPORT.create(14));
        importar.setOnAction(ev -> shell.navigate(View.IMPORTACION));
        Button analizar = new Button("Analizar", Icons.ANALYTICS.create(14));
        analizar.setDisable(!hayVentas);
        analizar.setOnAction(ev -> shell.navigate(View.PROCESAMIENTO, ProcesamientoController.INICIAR));
        Button ver = new Button("Ver resultados", Icons.REPORT.create(14));
        ver.setDisable(!hayAnalisis);
        ver.setOnAction(ev -> shell.navigate(View.RESULTADOS));
        (siguiente == 1 ? importar : siguiente == 2 ? analizar : ver).getStyleClass().add("primary");
        stepsBox.getChildren().setAll(
                paso(1, "Importar datos", "Ventas, compras, inventario o productos desde Excel (.xlsx, .xls) o CSV. "
                        + "Las columnas se reconocen solas y se recuerdan para la próxima vez.", importar, siguiente == 1),
                paso(2, "Analizar", hayVentas ? "Evalúa varios modelos por producto con todo el historial acumulado y "
                        + "elige el más preciso mediante backtesting." : "Disponible cuando haya ventas importadas.",
                        analizar, siguiente == 2),
                paso(3, "Ver resultados", hayAnalisis ? "Qué comprar, cuánto comprar y qué dejar de comprar, con la "
                        + "explicación de cada recomendación." : "Disponible después del primer análisis.", ver,
                        siguiente == 3));

        renderDecisiones(e.decisiones(), desactualizado, e.hayInventario());
        DashboardService.ResumenNegocio n = e.negocio();
        String hasta = n.corte() == null ? "Sin ventas" : "12 meses hasta el " + Formatters.date(n.corte());
        kpiPane.getChildren().setAll(
                DashboardController.kpi("Ventas", n.corte() == null ? "—" : Formatters.money(n.ventas12m()), hasta,
                        null, Icons.SALES, "green"),
                DashboardController.kpi("Utilidad bruta", n.corte() == null ? "—" : Formatters.money(n.utilidad12m()),
                        n.margen() == null ? "Pendiente: sin ventas" : String.format("Margen %.1f %% · según costo "
                                + "registrado de cada producto", n.margen() * 100), null, Icons.MONEY, "blue"),
                DashboardController.kpi("Inventario valorizado", n.unidadesInventario() == 0 ? "Pendiente"
                        : Formatters.money(n.valorInventario()), n.unidadesInventario() == 0 ? "Importe su inventario"
                        : Formatters.integer(n.unidadesInventario()) + " unidades al costo", null, Icons.INVENTORY,
                        "orange"),
                DashboardController.kpi("Último análisis", hayAnalisis ? e.ultimo().get().fecha().substring(0, 10) : "Pendiente",
                        hayAnalisis ? e.ultimo().get().fecha().substring(11, Math.min(16, e.ultimo().get().fecha().length())) + " · corte " + Formatters.date(e.ultimo().get().fechaCorte()) + " · "
                                + e.ultimo().get().conPronostico() + " productos con pronóstico" : "Pulse Analizar",
                        null, Icons.ANALYTICS, "blue"));
        importsTable.getItems().setAll(e.importaciones().stream().limit(8).toList());
    }

    /** Preguntas que se muestran en Inicio como decisiones clave. */
    static final List<Pregunta> CLAVE = List.of(Pregunta.COMPRAR, Pregunta.PODRIAN_AGOTARSE, Pregunta.MAYOR_DEMANDA,
            Pregunta.EXCESO, Pregunta.NO_RECOMPRAR);

    /** Decisiones clave del último análisis, cada una con acceso directo a su lista en Resultados. */
    private void renderDecisiones(List<DecisionGuardada> r, boolean desactualizado, boolean hayInventario) {
        boolean hay = !r.isEmpty();
        decisionesBox.setVisible(hay);
        decisionesBox.setManaged(hay);
        if (!hay) {
            return;
        }
        Label t = new Label("Decisiones clave del último análisis" + (desactualizado ? " (hay datos nuevos: vuelva "
                + "a analizar)" : ""));
        t.getStyleClass().add("card-title");
        Region esp = new Region();
        HBox.setHgrow(esp, Priority.ALWAYS);
        Button acciones = new Button("Ver acciones recomendadas");
        acciones.getStyleClass().add("primary");
        acciones.setOnAction(ev -> shell.navigate(View.ACCIONES));
        HBox cab = new HBox(10, t, esp, acciones);
        cab.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        decisionesBox.getChildren().setAll(cab);
        for (Pregunta q : CLAVE) {
            List<DecisionGuardada> l = ResultsService.pregunta(r, q, hayInventario);
            Label pregunta = new Label(q.toString());
            pregunta.getStyleClass().add("strong");
            pregunta.setMinWidth(260);
            Label cifra = new Label(!hayInventario && q.dependeDelStock() ? ResultsService.SIN_INVENTARIO
                    : l.size() + " producto(s) · " + ResultsService.resumen(q, l));
            cifra.setWrapText(true);
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            Button ver = new Button("Ver lista");
            ver.setDisable(l.isEmpty());
            ver.setOnAction(ev -> shell.navigate(View.RESULTADOS, q));
            HBox fila = new HBox(12, pregunta, cifra, sp, ver);
            fila.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            decisionesBox.getChildren().add(fila);
        }
    }

    private void aviso(String texto, String tipo) {
        Label l = new Label(texto);
        l.getStyleClass().addAll("notice", tipo);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setWrapText(true);
        noticeBox.getChildren().add(l);
    }

    private static VBox paso(int n, String titulo, String texto, Button accion, boolean destacado) {
        Label num = new Label(n + ".");
        num.getStyleClass().add("step-number");
        Label t = new Label(titulo);
        t.getStyleClass().add("step-title");
        HBox cab = new HBox(10, num, t);
        cab.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label d = new Label(texto);
        d.setWrapText(true);
        d.getStyleClass().add("muted");
        Region sp = new Region();
        VBox.setVgrow(sp, Priority.ALWAYS);
        accion.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(10, cab, d, sp, accion);
        box.getStyleClass().add("step-card");
        if (destacado) {
            box.getStyleClass().add("destacado");
        }
        box.setPrefWidth(320);
        box.setMinHeight(170);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }
}
