package com.stockwise.controller;

import com.stockwise.exporter.ReportTable;
import com.stockwise.exporter.ReportTable.ColumnType;
import com.stockwise.prediction.DemandPattern;
import com.stockwise.prediction.HorizonForecast;
import com.stockwise.recommendation.DecisionEstrategica;
import com.stockwise.repository.AnalisisRepository.Analisis;
import com.stockwise.repository.DecisionRepository;
import com.stockwise.repository.DecisionRepository.DecisionGuardada;
import com.stockwise.repository.DecisionRepository.DetalleAnalisis;
import com.stockwise.service.ResultadosBuilder;
import com.stockwise.service.ResultsService;
import com.stockwise.service.ResultsService.Pregunta;
import com.stockwise.service.ResultsService.Ranking;
import com.stockwise.util.Charts;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Formatters;
import com.stockwise.util.Forms;
import com.stockwise.util.Icons;
import com.stockwise.util.Tables;
import com.stockwise.util.Tables.Kind;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Resultados de una corrida de análisis para el gerente: indicadores, recomendaciones de compra (qué comprar,
 * cuánto, qué mantener y qué dejar de comprar), rankings, detalle de predicción por producto y comparación con
 * análisis anteriores. Todo se lee de la corrida guardada, por lo que también sirve para consultar análisis
 * anteriores.
 */
public class ResultadosController extends BaseController {

    /** Argumento de navegación: abre el detalle de un producto. */
    public record VerProducto(long productoId) {
    }

    private VerProducto verProducto;

    private record Corrida(Analisis analisis, List<DecisionGuardada> resultados, List<Analisis> todas,
                           List<ResultsService.FuenteDatos> fuentes, boolean hayInventario) {
    }

    @FXML
    private javafx.scene.control.ScrollPane contentBox;
    @FXML
    private VBox emptyBox;
    @FXML
    private Label subtitleLabel;
    private final ComboBox<Analisis> runCombo = new ComboBox<>();
    @FXML
    private FlowPane kpiPane;
    @FXML
    private VBox notesBox;
    @FXML
    private TabPane tabs;

    private Corrida corrida;
    private boolean cargando;

    // Recomendaciones
    private final ToggleGroup preguntas = new ToggleGroup();
    private final FlowPane preguntasPane = new FlowPane(8, 8);
    private final TableView<DecisionGuardada> recTable = new TableView<>();
    private final VBox explicacion = new VBox(6);
    // Rankings
    private final ToggleGroup rankings = new ToggleGroup();
    private final FlowPane rankingsPane = new FlowPane(8, 8);
    private final Label rankingDesc = muted("");
    private final TableView<DecisionGuardada> rankTable = new TableView<>();
    // Detalle
    private final TextField buscar = new TextField();
    private final ListView<DecisionGuardada> productosList = new ListView<>();
    private final VBox detalleBox = new VBox(12);
    // Comparación
    private final ComboBox<Analisis> compararCombo = new ComboBox<>();
    private final VBox compResumen = new VBox(6);
    private final ComboBox<String> compFiltro = new ComboBox<>();
    private final TableView<ResultsService.CambioProducto> compTable = new TableView<>();
    private ResultsService.Comparacion comparacion;

    public ResultadosController(Shell shell) {
        super(shell);
    }

    private static Label muted(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("muted");
        l.setWrapText(true);
        return l;
    }

    @FXML
    private void initialize() {
        runCombo.setConverter(Forms.converter(a -> a == null ? "" : etiqueta(a)));
        compararCombo.setConverter(Forms.converter(a -> a == null ? "" : etiqueta(a)));
        runCombo.valueProperty().addListener((o, a, b) -> {
            if (b != null && !cargando && (corrida == null || corrida.analisis().id() != b.id())) {
                cargar(b.id());
            }
        });
        tabs.getTabs().setAll(new Tab("Decisiones", tabRecomendaciones()),
                new Tab("Rankings", tabRankings()), new Tab("Detalle de producto", tabDetalle()),
                new Tab("Comparar análisis", tabComparar()));
        Object arg = shell.argumento();
        if (arg instanceof Pregunta q) {
            preguntaInicial = q;
        } else if (arg instanceof VerProducto v) {
            verProducto = v;
        }
        cargar(arg instanceof Long id ? id : null);
    }

    private static String etiqueta(Analisis a) {
        return "#" + a.id() + " · " + a.fecha() + " · corte " + Formatters.date(a.fechaCorte());
    }

    private void cargar(Long id) {
        shell.run("Cargando resultados…", () -> {
            List<Analisis> todas = app.resultados().analisis();
            Analisis a = id == null ? (todas.isEmpty() ? null : todas.get(0))
                    : app.resultados().buscar(id).orElse(todas.isEmpty() ? null : todas.get(0));
            if (a == null) {
                return null;
            }
            List<DecisionGuardada> res = app.resultados().resultados(a.id());
            return new Corrida(a, res, todas, app.resultados().fuentes(a, res), app.hayInventario());
        }, c -> {
            boolean vacio = c == null;
            contentBox.setVisible(!vacio);
            contentBox.setManaged(!vacio);
            emptyBox.setVisible(vacio);
            emptyBox.setManaged(vacio);
            if (!vacio) {
                corrida = c;
                render();
            }
        });
    }

    // ================================================================== encabezado e indicadores

    private void render() {
        Analisis a = corrida.analisis();
        cargando = true;
        runCombo.getItems().setAll(corrida.todas());
        runCombo.setValue(corrida.todas().stream().filter(x -> x.id() == a.id()).findFirst().orElse(a));
        cargando = false;
        boolean ultimo = !corrida.todas().isEmpty() && corrida.todas().get(0).id() == a.id();
        // Cabecera mínima: solo se avisa si se está viendo un análisis anterior. Los datos técnicos van en
        // "Información del análisis".
        subtitleLabel.setText(ultimo ? "Con tus ventas hasta el " + Formatters.date(a.fechaCorte())
                : "Estás viendo un análisis anterior (ventas hasta el " + Formatters.date(a.fechaCorte()) + ").");
        renderTarjetas();
        notesBox.getChildren().setAll(panelInformacion(a, ultimo));
        renderPreguntas();
        renderRankings();
        renderDetalleLista();
        renderComparar();
        javafx.application.Platform.runLater(() -> contentBox.setVvalue(0));
        if (verProducto != null) {
            long pid = verProducto.productoId();
            verProducto = null;
            corrida.resultados().stream().filter(x -> x.productoId() == pid).findFirst()
                    .ifPresentOrElse(this::irADetalle, () -> Dialogs.info("Sin análisis del producto",
                            "El producto no figura en el último análisis. Vuelva a analizar para incluirlo."));
        }
    }

    /** Tarjetas con las preguntas del gerente: cantidad de productos y cifra clave; al pulsar muestran la lista. */
    private void renderTarjetas() {
        Pregunta sel = preguntaActual();
        kpiPane.getChildren().clear();
        for (Pregunta q : Pregunta.values()) {
            List<DecisionGuardada> l = ResultsService.pregunta(corrida.resultados(), q, corrida.hayInventario());
            Label titulo = new Label(q.toString());
            titulo.getStyleClass().add("pregunta-titulo");
            titulo.setWrapText(true);
            boolean pendiente = !corrida.hayInventario() && q.dependeDelStock();
            Label valor = new Label(pendiente ? "—" : Formatters.integer(l.size()));
            valor.getStyleClass().add("pregunta-valor");
            Label resumen = new Label(ResultsService.resumen(q, l, corrida.hayInventario()));
            resumen.getStyleClass().add("pregunta-resumen");
            resumen.setWrapText(true);
            VBox card = new VBox(2, titulo, valor, resumen);
            card.getStyleClass().addAll("pregunta-card", pendiente ? "pregunta-pendiente" : "pregunta-" + color(q));
            if (q == sel) {
                card.getStyleClass().add("pregunta-actual");
            }
            card.setOnMouseClicked(e -> {
                seleccionar(q);
                tabs.getSelectionModel().select(0);
            });
            kpiPane.getChildren().add(card);
        }
    }

    private static String color(Pregunta q) {
        return switch (q) {
            case COMPRAR, MAYOR_DEMANDA -> "blue";
            case PODRIAN_AGOTARSE -> "red";
            case PERDIENDO_DEMANDA, EXCESO, NO_RECOMPRAR -> "orange";
            case CRECIMIENTO, MANTENER -> "green";
        };
    }

    /** Pregunta pedida al abrir la pantalla (p. ej. desde Inicio). */
    private Pregunta preguntaInicial;

    private Pregunta preguntaActual() {
        Pregunta inicial = corrida != null && !corrida.hayInventario() ? Pregunta.MAYOR_DEMANDA : Pregunta.COMPRAR;
        return preguntas.getSelectedToggle() == null ? (preguntaInicial == null ? inicial : preguntaInicial)
                : (Pregunta) preguntas.getSelectedToggle().getUserData();
    }

    private void seleccionar(Pregunta q) {
        preguntas.getToggles().stream().filter(t -> t.getUserData() == q).findFirst()
                .ifPresent(t -> t.setSelected(true));
        filtrarPregunta();
        renderTarjetas();
    }

    /**
     * Información secundaria del análisis (plegada): fecha y hora, historial usado, ventas procesadas, tiempo, error
     * medio, selector de análisis anteriores, datos usados con sus limitaciones y notas.
     */
    private TitledPane panelInformacion(Analisis a, boolean ultimo) {
        GridPane g = new GridPane();
        g.setHgap(14);
        g.setVgap(4);
        String[][] filas = {
                {"Fecha del análisis", a.fecha()},
                {"Ventas hasta", Formatters.date(a.fechaCorte()) + (a.ventasDesde() == null ? ""
                        : " (historial desde " + Formatters.date(a.ventasDesde()) + ")")},
                {"Ventas procesadas", Formatters.integer(a.registrosVentas())},
                {"Productos con predicción", a.conPronostico() + " de " + a.productos()},
                {"Error de predicción medio", a.wape() == null ? "no medible"
                        : String.format("%.0f %% (WAPE, comprobado con meses pasados)", a.wape() * 100)},
                {"Tiempo de procesamiento", a.duracionMs() == null ? "—"
                        : Formatters.number(a.duracionMs() / 1000.0) + " s"}};
        for (int i = 0; i < filas.length; i++) {
            Label k = new Label(filas[i][0]);
            k.getStyleClass().add("accion-campo");
            g.addRow(i, k, new Label(filas[i][1]));
        }
        runCombo.setPrefWidth(320);
        HBox otro = new HBox(10, new Label("Ver otro análisis:"), runCombo);
        otro.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox box = new VBox(10, g, otro, panelFuentes(corrida.fuentes()));
        if (a.notas() != null && !a.notas().isBlank()) {
            VBox lista = new VBox(4);
            for (String n : a.notas().split("\n")) {
                lista.getChildren().add(muted("• " + n));
            }
            TitledPane tp = new TitledPane("Notas del análisis (" + lista.getChildren().size() + ")", lista);
            tp.setExpanded(false);
            box.getChildren().add(tp);
        }
        long limitaciones = corrida.fuentes().stream().filter(f -> !f.disponible()).count();
        TitledPane info = new TitledPane("Información del análisis" + (limitaciones == 0 ? ""
                : " · " + limitaciones + " limitación(es) en los datos"), box);
        info.setExpanded(!ultimo);
        return info;
    }

    @FXML
    private void verAcciones() {
        shell.navigate(View.ACCIONES);
    }

    /** Qué datos se usaron y qué limita los resultados (nunca se inventan ventas). */
    private static TitledPane panelFuentes(List<ResultsService.FuenteDatos> fuentes) {
        javafx.scene.layout.GridPane g = new javafx.scene.layout.GridPane();
        g.setHgap(14);
        g.setVgap(6);
        int fila = 0;
        for (ResultsService.FuenteDatos f : fuentes) {
            Label n = new Label((f.disponible() ? "✓ " : "⚠ ") + f.nombre());
            n.getStyleClass().add(f.disponible() ? "strong" : "texto-aviso");
            n.setMinWidth(170);
            Label estado = new Label(f.estado());
            estado.setWrapText(true);
            g.addRow(fila++, n, estado, muted(f.uso()));
        }
        long avisos = fuentes.stream().filter(f -> !f.disponible()).count();
        TitledPane tp = new TitledPane("Datos usados y limitaciones" + (avisos == 0 ? " · completos"
                : " · " + avisos + " limitación(es)"), g);
        tp.setExpanded(false);
        return tp;
    }

    // ================================================================== utilidades de presentación

    private static DetalleAnalisis det(DecisionGuardada r) {
        return r.detalle();
    }

    static String tendencia(DecisionGuardada r) {
        if (det(r) == null || det(r).tendencia() == null) {
            return "—";
        }
        return switch (det(r).tendencia()) {
            case "CRECIENTE" -> "▲ Creciente";
            case "DECRECIENTE" -> "▼ Decreciente";
            case "ESTABLE" -> "● Estable";
            default -> "Indeterminada";
        };
    }

    static String recomendacion(DecisionGuardada r) {
        return r.decision().etiqueta() + (r.cantidadSugerida() > 0 ? " " + r.cantidadSugerida() + " u" : "");
    }

    static Double p30(DecisionGuardada r) {
        return det(r) == null ? null : det(r).p30();
    }

    static String patron(DecisionGuardada r) {
        if (r.patron() == null) {
            return "—";
        }
        try {
            return DemandPattern.valueOf(r.patron()).etiqueta();
        } catch (IllegalArgumentException e) {
            return r.patron();
        }
    }

    private static Prioridad prioridad(DecisionGuardada r) {
        try {
            return Prioridad.valueOf(r.prioridad());
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private enum Prioridad {
        CRITICA("Crítica"), ALTA("Alta"), MEDIA("Media"), BAJA("Baja");
        final String etiqueta;

        Prioridad(String e) {
            etiqueta = e;
        }
    }

    /** Explicación en lenguaje de negocio con los datos que respaldan la recomendación. */
    static String datos(DecisionGuardada r) {
        DetalleAnalisis d = det(r);
        List<String> l = new ArrayList<>();
        l.add(String.format("Stock %s u + en tránsito %s u = posición %s u.", Formatters.integer(r.stock()),
                Formatters.integer(r.enTransito()), Formatters.integer(r.stock() + r.enTransito())));
        if (d != null && d.p30() != null) {
            l.add(String.format("Podrías vender: %s u en 30 días y %s u en 90 días (modelo %s%s).",
                    Formatters.number(d.p30()), Formatters.number(d.p90()), r.modelo(), d.wape() == null ? ""
                            : String.format(", error de predicción %.0f%%", d.wape() * 100)));
        } else {
            l.add("Sin pronóstico confiable: historial de ventas insuficiente.");
        }
        if (d != null) {
            l.add(String.format("Vendidas: %s u en 90 días, %s u en 12 meses · tendencia %s.",
                    Formatters.number(d.unidades90()), Formatters.number(d.unidades365()),
                    tendencia(r).replaceAll("^[▲▼●] ", "").toLowerCase()));
            if (d.leadTime() != null) {
                l.add(String.format("Tiempo de entrega %d días · reserva para no quedarte sin stock %s u · punto de pedido %s u · "
                                + "nivel objetivo %s u.", d.leadTime(), Formatters.number(r.stockSeguridad()),
                        Formatters.number(r.puntoReorden()), Formatters.number(r.nivelObjetivo())));
            }
        }
        if (r.cantidadSugerida() > 0) {
            l.add(String.format("Comprar %d u (inversión estimada %s).", r.cantidadSugerida(),
                    Formatters.money(r.cantidadSugerida() * r.costoUnitario())));
        }
        return String.join("\n", l);
    }

    private static <T> TableView<T> preparar(TableView<T> t, String vacio) {
        VBox.setVgrow(t, Priority.ALWAYS);
        Tables.placeholder(t, vacio);
        return t;
    }

    // ================================================================== recomendaciones

    private Node tabRecomendaciones() {
        Tables.badge(recTable, "Prioridad", r -> prioridad(r) == null ? "" : prioridad(r).etiqueta, Tables.ESTADOS, 80);
        Tables.col(recTable, "Producto", DecisionGuardada::producto, Kind.TEXT, 220);
        Tables.badge(recTable, "Recomendación", r -> r.decision().etiqueta(), Tables.ESTADOS, 140);
        Tables.col(recTable, "Comprar (u)", r -> r.cantidadSugerida() > 0 ? r.cantidadSugerida() : null, Kind.INT, 90);
        Tables.col(recTable, "Inversión", r -> r.cantidadSugerida() > 0 ? r.cantidadSugerida() * r.costoUnitario()
                : null, Kind.MONEY, 100);
        Tables.col(recTable, "Stock", DecisionGuardada::stock, Kind.INT, 65);
        Tables.col(recTable, "En camino", DecisionGuardada::enTransito, Kind.INT, 75);
        Tables.col(recTable, "Podrías vender (30 días)", ResultadosController::p30, Kind.DECIMAL, 150);
        Tables.col(recTable, "Días de stock", r -> det(r) == null ? null : det(r).coberturaDias(), Kind.DECIMAL, 95);
        Tables.col(recTable, "Tendencia", ResultadosController::tendencia, Kind.TEXT, 110);
        preparar(recTable, "No hay productos en esta categoría.");
        recTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> mostrarExplicacion(b));
        explicacion.getStyleClass().add("explicacion");
        mostrarExplicacion(null);

        Button verDetalle = new Button("Ver detalle del producto");
        verDetalle.setOnAction(e -> irADetalle(recTable.getSelectionModel().getSelectedItem()));
        Button decidir = new Button("Decidir en Acciones recomendadas", Icons.CHECK.create(14));
        decidir.getStyleClass().add("primary");
        decidir.setOnAction(e -> shell.navigate(View.ACCIONES));
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox acciones = new HBox(8, muted("Aceptar, posponer o rechazar cada recomendación se hace en Acciones "
                + "recomendadas."), sp, verDetalle, decidir);
        acciones.getStyleClass().add("toolbar-row");
        ScrollPane exp = new ScrollPane(explicacion);
        exp.setFitToWidth(true);
        exp.getStyleClass().add("scroll-page");
        SplitPane split = new SplitPane(recTable, exp);
        split.setDividerPositions(0.66);
        VBox.setVgrow(split, Priority.ALWAYS);
        preguntaTitulo.getStyleClass().add("card-title");
        return pad(new VBox(6, preguntaTitulo, preguntaDesc, split, acciones));
    }

    private final Label preguntaTitulo = new Label();
    private final Label preguntaDesc = muted("");

    private void renderPreguntas() {
        Pregunta sel = preguntaActual();
        preguntasPane.getChildren().clear();
        for (Pregunta q : Pregunta.values()) {
            ToggleButton b = chip(q.toString(), q, preguntas);
            preguntasPane.getChildren().add(b);
            if (q == sel) {
                b.setSelected(true);
            }
        }
        filtrarPregunta();
    }

    private ToggleButton chip(String texto, Object dato, ToggleGroup g) {
        ToggleButton b = new ToggleButton(texto);
        b.getStyleClass().setAll("chip");
        b.setUserData(dato);
        b.setToggleGroup(g);
        b.setOnAction(e -> {
            if (!b.isSelected()) {
                b.setSelected(true);
            }
            if (g == preguntas) {
                filtrarPregunta();
            } else {
                filtrarRanking();
            }
        });
        return b;
    }

    private void filtrarPregunta() {
        if (corrida == null || preguntas.getSelectedToggle() == null) {
            return;
        }
        Pregunta q = (Pregunta) preguntas.getSelectedToggle().getUserData();
        recTable.getItems().setAll(ResultsService.pregunta(corrida.resultados(), q, corrida.hayInventario()));
        // Sin inventario no se muestran columnas que dependen del stock (serían cifras inventadas).
        for (int c : new int[]{0, 2, 3, 4, 5, 6, 8}) {
            recTable.getColumns().get(c).setVisible(corrida.hayInventario());
        }
        preguntaTitulo.setText(q + " · " + recTable.getItems().size() + " producto(s)");
        preguntaDesc.setText(q.descripcion() + " Seleccione un producto para ver la explicación.");
        if (!recTable.getItems().isEmpty()) {
            recTable.getSelectionModel().select(0);
        } else {
            mostrarExplicacion(null);
        }
    }

    private void mostrarExplicacion(DecisionGuardada r) {
        explicacion.getChildren().clear();
        if (r == null) {
            explicacion.getChildren().add(muted("Seleccione un producto para ver por qué se recomienda y los datos que "
                    + "lo respaldan."));
            return;
        }
        Label t = new Label(r.codigo() + " · " + r.producto() + " — " + recomendacion(r));
        t.getStyleClass().add("card-title");
        t.setWrapText(true);
        Label sencilla = new Label(corrida != null && !corrida.hayInventario() ? ResultsService.explicacionSinInventario(r)
                : ResultsService.explicacionSencilla(r));
        sencilla.setWrapText(true);
        sencilla.getStyleClass().add("explicacion-sencilla");
        VBox tecnico = new VBox(6);
        Label motivo = new Label("Motivo: " + r.motivo());
        motivo.setWrapText(true);
        Label accion = new Label("Acción sugerida: " + r.accion());
        accion.setWrapText(true);
        tecnico.getChildren().addAll(motivo, accion, muted(datos(r)));
        TitledPane tp = new TitledPane("Datos y detalles técnicos", tecnico);
        tp.setExpanded(false);
        explicacion.getChildren().addAll(t, sencilla, tp);
    }

    // ================================================================== rankings

    private static final int TOP_GRAFICO = 10;
    private static final int POR_PAGINA = 25;
    private final javafx.scene.chart.BarChart<Number, String> rankChart =
            new javafx.scene.chart.BarChart<>(new NumberAxis(), new CategoryAxis());
    private final Label rankChartTitulo = new Label();
    private final TextField rankBuscar = new TextField();
    private final ComboBox<String> rankOrden = new ComboBox<>();
    private final javafx.scene.control.Pagination rankPaginas = new javafx.scene.control.Pagination(1, 0);
    private final TitledPane rankTodos = new TitledPane();
    private List<DecisionGuardada> rankLista = List.of();
    private List<DecisionGuardada> rankFiltrada = List.of();

    private Ranking rankingActual() {
        return rankings.getSelectedToggle() == null ? Ranking.MAS_VENDIDOS
                : (Ranking) rankings.getSelectedToggle().getUserData();
    }

    /** Cifra del ranking legible (unidades sin decimales salvo valores pequeños). */
    static String cifra(double v) {
        return Formatters.number(Math.abs(v) >= 10 ? Math.round(v) : Math.round(v * 10) / 10.0);
    }

    static String corto(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private Node tabRankings() {
        rankChart.getStyleClass().add("ranking-chart");
        rankChart.setLegendVisible(false);
        rankChart.setAnimated(false);
        rankChart.setCategoryGap(6);
        rankChart.setMinHeight(380);
        rankChart.setPrefHeight(400);
        rankChartTitulo.getStyleClass().add("card-title");
        VBox grafico = new VBox(6, rankChartTitulo, rankChart);
        grafico.getStyleClass().add("card");

        Tables.col(rankTable, "#", r -> rankFiltrada.indexOf(r) + 1, Kind.INT, 50);
        Tables.col(rankTable, "Producto", DecisionGuardada::producto, Kind.TEXT, 260);
        Tables.col(rankTable, "Cifra", r -> cifra(ResultsService.valorRanking(r, rankingActual())), Kind.TEXT, 190);
        Tables.col(rankTable, "Vendidas en 12 meses", r -> det(r) == null ? null : det(r).unidades365(), Kind.DECIMAL, 140);
        Tables.col(rankTable, "Podrías vender (30 días)", ResultadosController::p30, Kind.DECIMAL, 160);
        Tables.col(rankTable, "Stock", DecisionGuardada::stock, Kind.INT, 70);
        Tables.col(rankTable, "Tendencia", ResultadosController::tendencia, Kind.TEXT, 120);
        Tables.badge(rankTable, "Recomendación", r -> r.decision().etiqueta(), Tables.ESTADOS, 160);
        rankTable.getColumns().forEach(c -> c.setSortable(false));
        Tables.placeholder(rankTable, "Ningún producto cumple este criterio.");
        rankTable.setPrefHeight(40 + 30 * 12);
        rankTable.setMinHeight(rankTable.getPrefHeight());
        rankTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<DecisionGuardada> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    irADetalle(row.getItem());
                }
            });
            return row;
        });
        rankBuscar.setPromptText("Buscar producto…");
        rankBuscar.setPrefWidth(240);
        rankBuscar.textProperty().addListener((o, a, b) -> paginarRanking());
        rankOrden.getItems().setAll("Por la cifra del ranking", "Por nombre (A-Z)", "Por unidades vendidas",
                "Por stock");
        rankOrden.setValue(rankOrden.getItems().get(0));
        rankOrden.valueProperty().addListener((o, a, b) -> paginarRanking());
        rankPaginas.currentPageIndexProperty().addListener((o, a, b) -> mostrarPagina());
        Label ayuda = muted("Doble clic en un producto para ver su predicción.");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox filtros = new HBox(10, rankBuscar, rankOrden, sp, ayuda);
        filtros.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        rankTodos.setContent(new VBox(8, filtros, rankTable, rankPaginas));
        rankTodos.setExpanded(false);
        return pad(new VBox(10, rankingsPane, rankingDesc, grafico, rankTodos));
    }

    private void renderRankings() {
        Ranking sel = rankingActual();
        rankingsPane.getChildren().clear();
        for (Ranking k : ResultsService.visibles(corrida.hayInventario())) {
            ToggleButton b = chip(k.etiqueta(), k, rankings);
            rankingsPane.getChildren().add(b);
            if (k == sel) {
                b.setSelected(true);
            }
        }
        filtrarRanking();
    }

    private void filtrarRanking() {
        if (corrida == null || rankings.getSelectedToggle() == null) {
            return;
        }
        Ranking k = rankingActual();
        rankingDesc.setText(k.descripcion());
        rankLista = ResultsService.ranking(corrida.resultados(), k, app.resultados().diasSinMovimiento(), 0);
        List<DecisionGuardada> top = rankLista.subList(0, Math.min(TOP_GRAFICO, rankLista.size()));
        rankChartTitulo.setText(top.isEmpty() ? k.etiqueta() + ": ningún producto"
                : (rankLista.size() > TOP_GRAFICO ? "Los " + top.size() + " primeros" : "Todos") + " · "
                + ResultsService.unidadRanking(k));
        XYChart.Series<Number, String> serie = new XYChart.Series<>();
        for (int i = top.size() - 1; i >= 0; i--) {
            DecisionGuardada r = top.get(i);
            double v = ResultsService.valorRanking(r, k);
            serie.getData().add(new XYChart.Data<>(v, (i + 1) + ". " + corto(r.producto(), 34) + "  ·  " + cifra(v)));
        }
        rankChart.getData().setAll(List.of(serie));
        rankChart.setPrefHeight(Math.max(160, 60 + 34 * top.size()));
        rankChart.setMinHeight(rankChart.getPrefHeight());
        rankChart.setVisible(!top.isEmpty());
        rankChart.setManaged(!top.isEmpty());
        rankTodos.setText("Ver todos los productos de este ranking (" + Formatters.integer(rankLista.size()) + ")");
        rankTable.getColumns().get(2).setText(capitalizar(ResultsService.unidadRanking(k)));
        // No repetir la misma cifra en dos columnas.
        rankTable.getColumns().get(3).setVisible(k != Ranking.MAS_VENDIDOS);
        rankTable.getColumns().get(4).setVisible(k != Ranking.MAYOR_DEMANDA);
        paginarRanking();
    }

    private void paginarRanking() {
        String q = rankBuscar.getText() == null ? "" : rankBuscar.getText().trim().toLowerCase(java.util.Locale.ROOT);
        String ord = rankOrden.getValue() == null ? "" : rankOrden.getValue();
        Comparator<DecisionGuardada> orden = switch (ord) {
            case "Por nombre (A-Z)" -> Comparator.comparing(DecisionGuardada::producto, String.CASE_INSENSITIVE_ORDER);
            case "Por unidades vendidas" -> Comparator.comparingDouble(
                    (DecisionGuardada r) -> det(r) == null ? 0 : -det(r).unidades365());
            case "Por stock" -> Comparator.comparingDouble((DecisionGuardada r) -> -r.stock());
            default -> null;
        };
        java.util.stream.Stream<DecisionGuardada> s = rankLista.stream().filter(r -> q.isEmpty()
                || r.producto().toLowerCase(java.util.Locale.ROOT).contains(q)
                || r.codigo().toLowerCase(java.util.Locale.ROOT).contains(q));
        rankFiltrada = orden == null ? s.toList() : s.sorted(orden).toList();
        rankPaginas.setPageCount(Math.max(1, (rankFiltrada.size() + POR_PAGINA - 1) / POR_PAGINA));
        rankPaginas.setCurrentPageIndex(0);
        mostrarPagina();
    }

    private static String capitalizar(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void mostrarPagina() {
        int desde = rankPaginas.getCurrentPageIndex() * POR_PAGINA;
        rankTable.getItems().setAll(rankFiltrada.subList(Math.min(desde, rankFiltrada.size()),
                Math.min(desde + POR_PAGINA, rankFiltrada.size())));
        rankTable.refresh();
    }


    // ================================================================== detalle de producto

    private Node tabDetalle() {
        buscar.setPromptText("Buscar producto…");
        buscar.textProperty().addListener((o, a, b) -> renderDetalleLista());
        productosList.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(DecisionGuardada r, boolean empty) {
                super.updateItem(r, empty);
                setText(empty || r == null ? null : r.codigo() + " - " + r.producto());
            }
        });
        productosList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> renderDetalle(b));
        VBox.setVgrow(productosList, Priority.ALWAYS);
        VBox izquierda = new VBox(8, buscar, productosList);
        izquierda.setMinWidth(260);
        izquierda.setPrefWidth(300);
        detalleBox.getChildren().add(muted("Seleccione un producto."));
        ScrollPane derecha = new ScrollPane(detalleBox);
        derecha.setFitToWidth(true);
        derecha.getStyleClass().add("scroll-page");
        SplitPane split = new SplitPane(izquierda, derecha);
        split.setDividerPositions(0.24);
        return split;
    }

    private void renderDetalleLista() {
        if (corrida == null) {
            return;
        }
        String q = buscar.getText() == null ? "" : buscar.getText().trim().toLowerCase();
        DecisionGuardada sel = productosList.getSelectionModel().getSelectedItem();
        List<DecisionGuardada> l = corrida.resultados().stream()
                .filter(r -> q.isEmpty() || (r.codigo() + " " + r.producto()).toLowerCase().contains(q))
                .sorted(Comparator.comparingDouble((DecisionGuardada r) -> det(r) == null ? 0 : -det(r).unidades365())
                        .thenComparing(DecisionGuardada::codigo)).limit(500).toList();
        productosList.getItems().setAll(l);
        if (sel != null && l.stream().anyMatch(r -> r.productoId() == sel.productoId())) {
            l.stream().filter(r -> r.productoId() == sel.productoId()).findFirst()
                    .ifPresent(r -> productosList.getSelectionModel().select(r));
        } else if (!l.isEmpty()) {
            productosList.getSelectionModel().select(0);
        }
    }

    private void irADetalle(DecisionGuardada r) {
        if (r == null) {
            return;
        }
        buscar.setText("");
        tabs.getSelectionModel().select(2);
        productosList.getItems().stream().filter(x -> x.productoId() == r.productoId()).findFirst()
                .ifPresentOrElse(x -> {
                    productosList.getSelectionModel().select(x);
                    productosList.scrollTo(x);
                }, () -> {
                    productosList.getItems().add(0, r);
                    productosList.getSelectionModel().select(r);
                });
    }

    private void renderDetalle(DecisionGuardada r) {
        detalleBox.getChildren().clear();
        if (r == null) {
            detalleBox.getChildren().add(muted("Seleccione un producto."));
            return;
        }
        DetalleAnalisis d = det(r);
        Label titulo = new Label(r.codigo() + " · " + r.producto());
        titulo.getStyleClass().add("page-title");
        titulo.setWrapText(true);
        detalleBox.getChildren().add(titulo);
        if (d == null) {
            detalleBox.getChildren().add(muted("Este resultado se guardó con una versión anterior y no incluye el detalle "
                    + "de predicción. Vuelva a analizar para obtenerlo."));
            return;
        }
        Label perfil = muted("Comportamiento: " + (d.perfil() == null ? "—" : d.perfil()) + " · patrón " + patron(r)
                + " · " + tendencia(r) + " · confianza " + confianza(d.confianza())
                + (d.categoria() == null ? "" : " · categoría " + d.categoria()));
        detalleBox.getChildren().add(perfil);

        // Recomendación primero (lo más importante para el gerente)
        VBox rec = new VBox(6);
        rec.getStyleClass().add("explicacion");
        Label rt = new Label("Recomendación: " + recomendacion(r));
        rt.getStyleClass().add("card-title");
        Label ac = new Label(r.accion());
        ac.setWrapText(true);
        ac.getStyleClass().add("strong");
        Label mo = new Label(r.motivo());
        mo.setWrapText(true);
        rec.getChildren().addAll(rt, ac, mo, muted(datos(r)));
        detalleBox.getChildren().add(rec);

        // Pronósticos por horizonte con incertidumbre
        TableView<HorizonForecast.Pronostico> hz = new TableView<>();
        Tables.col(hz, "Horizonte", p -> p.dias() + " días", Kind.TEXT, 110);
        Tables.col(hz, "Podrías vender (u)", HorizonForecast.Pronostico::valor, Kind.DECIMAL, 170);
        Tables.col(hz, "Mínimo probable (80%)", HorizonForecast.Pronostico::inferior, Kind.DECIMAL, 160);
        Tables.col(hz, "Máximo probable (80%)", HorizonForecast.Pronostico::superior, Kind.DECIMAL, 160);
        Tables.placeholder(hz, "Sin pronóstico: historial insuficiente (no se inventan datos).");
        hz.getItems().setAll(ResultadosBuilder.pronosticosGuardados(d, r.fechaCorte()));
        hz.setPrefHeight(190);
        hz.setMinHeight(190);
        String textoInc;
        if (ResultadosBuilder.PROVISIONAL.equals(d.confianza())) {
            textoInc = "Estimación provisional: producto nuevo, se proyecta su ritmo de venta observado. No hay meses "
                    + "suficientes para validar modelos, así que la precisión no está demostrada; el rango es solo "
                    + "orientativo (variación aleatoria de las ventas).";
        } else if (d.rangos() != null) {
            textoInc = "El rango indica dónde caería la demanda real 8 de cada 10 veces. Se calcula con los errores "
                    + "reales que tuvo el modelo al pronosticar meses pasados (comprobado: 82 % de aciertos).";
        } else if (d.sigmaMensual() != null) {
            textoInc = String.format("Incertidumbre: error típico de ±%s u por mes medido en backtesting; el rango "
                    + "indica dónde caería la demanda real 8 de cada 10 veces.", Formatters.number(d.sigmaMensual()));
        } else {
            textoInc = "Incertidumbre: no medible (sin meses suficientes de validación).";
        }
        detalleBox.getChildren().addAll(seccion("Pronóstico desde el " + Formatters.date(r.fechaCorte())), hz,
                muted(textoInc));

        // Precisión según qué tan lejos se pronostica
        List<ResultadosBuilder.MetricaHorizonte> ph = ResultadosBuilder.leerMetricasHorizonte(d.metricasHorizonte());
        if (ph.stream().anyMatch(x -> x.puntos() > 0)) {
            TableView<ResultadosBuilder.MetricaHorizonte> pt = new TableView<>();
            Tables.col(pt, "Pronóstico a", x -> x.meses() == 1 ? "1 mes" : x.meses() + " meses", Kind.TEXT, 110);
            Tables.col(pt, "Error de predicción", ResultadosBuilder.MetricaHorizonte::wape, Kind.PERCENT, 140);
            Tables.col(pt, "MAE (u)", ResultadosBuilder.MetricaHorizonte::mae, Kind.DECIMAL, 90);
            Tables.col(pt, "RMSE (u)", ResultadosBuilder.MetricaHorizonte::rmse, Kind.DECIMAL, 90);
            Tables.col(pt, "Sesgo", ResultadosBuilder.MetricaHorizonte::sesgo, Kind.PERCENT, 80);
            Tables.col(pt, "Meses evaluados", x -> String.valueOf(x.puntos()), Kind.TEXT, 120);
            pt.getItems().setAll(ph);
            pt.setPrefHeight(40 + 32 * ph.size());
            pt.setMinHeight(pt.getPrefHeight());
            TitledPane tp = new TitledPane("Precisión según el horizonte (backtesting)", new VBox(6, pt,
                    muted("Error de la demanda de cada mes futuro al pronosticar 1, 2 o 3 meses hacia adelante. "
                            + "Sesgo positivo = el modelo sobreestima.")));
            tp.setExpanded(false);
            detalleBox.getChildren().add(tp);
        }

        // Gráfico real vs. predicción
        detalleBox.getChildren().addAll(seccion("Ventas reales frente a predicciones"), grafico(r, d));

        // Modelos comparados
        TableView<ResultadosBuilder.ModeloComparado> mt = new TableView<>();
        Tables.col(mt, "Modelo", m -> (m.seleccionado() ? "✓ " : "") + m.nombre(), Kind.TEXT, 280);
        Tables.col(mt, "MAE", ResultadosBuilder.ModeloComparado::mae, Kind.DECIMAL, 80);
        Tables.col(mt, "RMSE", ResultadosBuilder.ModeloComparado::rmse, Kind.DECIMAL, 80);
        Tables.col(mt, "WAPE", ResultadosBuilder.ModeloComparado::wape, Kind.PERCENT, 80);
        Tables.col(mt, "Sesgo", ResultadosBuilder.ModeloComparado::sesgo, Kind.PERCENT, 80);
        Tables.col(mt, "Tipo", m -> m.referencia() ? "Referencia" : m.complejidad() >= 3 ? "Avanzado"
                : m.complejidad() == 2 ? "Intermedio" : "Simple", Kind.TEXT, 100);
        mt.getItems().setAll(ResultadosBuilder.leerModelos(d.modelos()));
        mt.getItems().sort(Comparator.comparing((ResultadosBuilder.ModeloComparado m) -> !m.seleccionado())
                .thenComparingDouble(m -> m.mae() == null ? Double.MAX_VALUE : m.mae()));
        Tables.placeholder(mt, "No se compararon modelos (historial insuficiente).");
        mt.setPrefHeight(Math.min(320, 40 + 32 * Math.max(1, mt.getItems().size())));
        mt.setMinHeight(mt.getPrefHeight());
        String metricas = d.mae() == null ? "Métricas no disponibles: el historial no permite medir el error."
                : String.format("Backtesting walk-forward (%d meses): MAE %s · RMSE %s · WAPE %s · MAPE %s · sesgo %s%s.",
                d.puntosValidacion() == null ? 0 : d.puntosValidacion(), Formatters.number(d.mae()),
                fmt(d.rmse(), false), fmt(d.wape(), true), fmt(d.mape(), true), fmt(d.sesgo(), true),
                d.maeIngenuo() == null || d.maeIngenuo() <= 0 ? "" : String.format(" · mejora frente al ingenuo %+.0f%%",
                        (1 - d.mae() / d.maeIngenuo()) * 100));
        TitledPane avanzada = new TitledPane("Información avanzada: modelos comparados y métricas de precisión",
                new VBox(8, mt, muted(metricas)));
        avanzada.setExpanded(false);
        detalleBox.getChildren().add(avanzada);
        if (d.advertencias() != null && !d.advertencias().isBlank()) {
            VBox adv = new VBox(4);
            for (String a : d.advertencias().split("\n")) {
                adv.getChildren().add(muted("• " + a));
            }
            TitledPane tp = new TitledPane("Observaciones sobre los datos", adv);
            tp.setExpanded(false);
            detalleBox.getChildren().add(tp);
        }
    }

    private static String fmt(Double v, boolean pct) {
        return v == null ? "n/d" : pct ? Formatters.percent(v) : Formatters.number(v);
    }

    private static String confianza(String c) {
        if (c == null) {
            return "—";
        }
        return switch (c) {
            case "ALTA" -> "alta";
            case "MEDIA" -> "media";
            case "BAJA" -> "baja";
            case "PROVISIONAL" -> "provisional (producto nuevo, sin validar)";
            default -> "datos insuficientes";
        };
    }

    private static Label seccion(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("section-label");
        return l;
    }

    private static Node grafico(DecisionGuardada r, DetalleAnalisis d) {
        LineChart<String, Number> chart = new LineChart<>(new CategoryAxis(), new NumberAxis());
        chart.setAnimated(false);
        chart.setMinHeight(280);
        chart.setPrefHeight(300);
        chart.setCreateSymbols(true);
        List<String> per = ResultadosBuilder.leerTextos(d.seriePeriodos());
        double[] real = ResultadosBuilder.leerNumeros(d.serieValores());
        double[] aj = ResultadosBuilder.leerNumeros(d.ajustes());
        List<String> fut = ResultadosBuilder.leerTextos(d.periodosFuturos());
        double[] pron = ResultadosBuilder.leerNumeros(d.pronosticoMensual());
        if (per.isEmpty()) {
            return muted("Sin historial de ventas para graficar.");
        }
        XYChart.Series<String, Number> sr = new XYChart.Series<>();
        sr.setName("Ventas reales");
        XYChart.Series<String, Number> sv = new XYChart.Series<>();
        sv.setName("Predicción en validación (" + r.modelo() + ")");
        for (int i = 0; i < per.size() && i < real.length; i++) {
            String p = Formatters.period(per.get(i));
            sr.getData().add(new XYChart.Data<>(p, real[i]));
            if (i < aj.length && !Double.isNaN(aj[i])) {
                sv.getData().add(new XYChart.Data<>(p, aj[i]));
            }
        }
        chart.getData().add(sr);
        if (!sv.getData().isEmpty()) {
            chart.getData().add(sv);
        }
        if (pron.length > 0) {
            XYChart.Series<String, Number> sf = new XYChart.Series<>();
            sf.setName("Pronóstico");
            for (int i = 0; i < fut.size() && i < pron.length; i++) {
                sf.getData().add(new XYChart.Data<>(Formatters.period(fut.get(i)), pron[i]));
            }
            chart.getData().add(sf);
        }
        javafx.application.Platform.runLater(() -> Charts.tooltips(chart, v -> Formatters.number(v.doubleValue())));
        return chart;
    }

    // ================================================================== comparación

    private Node tabComparar() {
        compararCombo.setPrefWidth(320);
        Button comparar = new Button("Comparar");
        comparar.getStyleClass().add("primary");
        comparar.setOnAction(e -> comparar());
        compFiltro.getItems().setAll("Todos los productos", "Cambiaron de patrón", "Cambiaron de recomendación",
                "Mayor variación del pronóstico", "Mayor error del pronóstico anterior");
        compFiltro.setValue(compFiltro.getItems().get(0));
        compFiltro.valueProperty().addListener((o, a, b) -> filtrarComparacion());
        HBox barra = new HBox(8, new Label("Comparar con"), compararCombo, comparar, new Label("Mostrar"), compFiltro);
        barra.getStyleClass().add("toolbar-row");
        Tables.col(compTable, "Código", ResultsService.CambioProducto::codigo, Kind.TEXT, 85);
        Tables.col(compTable, "Producto", ResultsService.CambioProducto::producto, Kind.TEXT, 200);
        Tables.col(compTable, "Pronóstico 30 d antes", ResultsService.CambioProducto::p30Anterior, Kind.DECIMAL, 135);
        Tables.col(compTable, "Pronóstico 30 d ahora", ResultsService.CambioProducto::p30Nuevo, Kind.DECIMAL, 135);
        Tables.col(compTable, "Variación", ResultsService.CambioProducto::variacion, Kind.PERCENT, 80);
        Tables.col(compTable, "Pronosticado antes", ResultsService.CambioProducto::pronosticadoAnterior, Kind.DECIMAL,
                120);
        Tables.col(compTable, "Venta real", ResultsService.CambioProducto::real, Kind.DECIMAL, 85);
        Tables.col(compTable, "Patrón antes → ahora", c -> nombrePatron(c.patronAnterior()) + " → "
                + nombrePatron(c.patronNuevo()), Kind.TEXT, 190);
        Tables.col(compTable, "Recomendación antes → ahora", c -> (c.decisionAnterior() == null ? "—"
                : c.decisionAnterior().etiqueta()) + " → " + c.decisionNueva().etiqueta(), Kind.TEXT, 230);
        Tables.col(compTable, "Error de predicción antes → ahora", c -> fmt(c.wapeAnterior(), true) + " → " + fmt(c.wapeNuevo(), true),
                Kind.TEXT, 140);
        preparar(compTable, "Elija un análisis anterior y pulse Comparar.");
        return pad(new VBox(10, barra, compResumen, compTable));
    }

    private static String nombrePatron(String p) {
        if (p == null) {
            return "—";
        }
        try {
            return DemandPattern.valueOf(p).etiqueta();
        } catch (IllegalArgumentException e) {
            return p;
        }
    }

    private void renderComparar() {
        Analisis actual = corrida.analisis();
        List<Analisis> otras = corrida.todas().stream().filter(a -> a.id() != actual.id()).toList();
        compararCombo.getItems().setAll(otras);
        compararCombo.setValue(otras.stream().filter(a -> !a.fechaCorte().isAfter(actual.fechaCorte())).findFirst()
                .orElse(otras.isEmpty() ? null : otras.get(0)));
        compResumen.getChildren().setAll(muted(otras.isEmpty() ? "Este es el único análisis del proyecto. Cuando "
                + "importe meses nuevos y vuelva a analizar podrá comparar cómo cambió la demanda y medir la precisión "
                + "real de las predicciones anteriores." : "Compare este análisis con uno anterior para ver cómo "
                + "evolucionó la demanda y cuán precisas fueron las predicciones anteriores."));
        compTable.getItems().clear();
        comparacion = null;
    }

    private void comparar() {
        Analisis otro = compararCombo.getValue();
        if (otro == null) {
            Dialogs.info("Comparar", "No hay otro análisis para comparar.");
            return;
        }
        long a = corrida.analisis().id();
        shell.run("Comparando análisis…", () -> app.resultados().comparar(otro.id(), a), c -> {
            comparacion = c;
            compResumen.getChildren().clear();
            Label t = new Label("Análisis #" + c.anterior().id() + " (corte " + Formatters.date(c.anterior().fechaCorte())
                    + ") frente a #" + c.nuevo().id() + " (corte " + Formatters.date(c.nuevo().fechaCorte()) + ")");
            t.getStyleClass().add("strong");
            compResumen.getChildren().add(t);
            for (String s : c.resumen()) {
                Label l = new Label(s);
                l.getStyleClass().addAll("notice");
                l.setWrapText(true);
                l.setMaxWidth(Double.MAX_VALUE);
                compResumen.getChildren().add(l);
            }
            filtrarComparacion();
        });
    }

    private void filtrarComparacion() {
        if (comparacion == null) {
            return;
        }
        String f = compFiltro.getValue();
        List<ResultsService.CambioProducto> l = new ArrayList<>(comparacion.cambios());
        switch (f) {
            case "Cambiaron de patrón" -> l.removeIf(c -> c.patronAnterior() == null || !c.cambioPatron());
            case "Cambiaron de recomendación" -> l.removeIf(c -> c.decisionAnterior() == null || !c.cambioDecision());
            case "Mayor variación del pronóstico" -> {
                l.removeIf(c -> c.variacion() == null);
                l.sort(Comparator.comparingDouble(c -> -Math.abs(c.variacion())));
            }
            case "Mayor error del pronóstico anterior" -> {
                l.removeIf(c -> c.errorReal() == null);
                l.sort(Comparator.comparingDouble(c -> -c.errorReal()));
            }
            default -> l.sort(Comparator.comparing(ResultsService.CambioProducto::codigo));
        }
        compTable.getItems().setAll(l);
    }

    // ================================================================== acciones

    @FXML
    private void analyze() {
        shell.navigate(View.PROCESAMIENTO, ProcesamientoController.INICIAR);
    }

    @FXML
    private void importData() {
        shell.navigate(View.IMPORTACION);
    }

    private static Node pad(Node contenido) {
        VBox box = new VBox(contenido);
        VBox.setVgrow(contenido, Priority.ALWAYS);
        box.setStyle("-fx-padding: 10 2 2 2;");
        return box;
    }

    /** Fecha de corte usada por los pronósticos de la corrida (para pruebas). */
    LocalDate corte() {
        return corrida == null ? null : corrida.analisis().fechaCorte();
    }
}
