package com.stockwise.controller;

import com.stockwise.analytics.ProductMetrics;
import com.stockwise.analytics.StockReconstructor;
import com.stockwise.analytics.StockReconstructor.DiaStock;
import com.stockwise.exporter.ReportTable;
import com.stockwise.exporter.ReportTable.ColumnType;
import com.stockwise.model.Sucursal;
import com.stockwise.prediction.DemandClassifier;
import com.stockwise.prediction.DemandPattern;
import com.stockwise.prediction.ForecastResult;
import com.stockwise.recommendation.DecisionEstrategica;
import com.stockwise.recommendation.ProductDecision;
import com.stockwise.recommendation.ScenarioSimulator;
import com.stockwise.repository.DecisionRepository;
import com.stockwise.repository.DecisionRepository.DecisionGuardada;
import com.stockwise.service.BacktestService;
import com.stockwise.service.PlanningService;
import com.stockwise.service.ScorecardService;
import com.stockwise.service.StockHistoryService;
import com.stockwise.service.ValidationException;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Formatters;
import com.stockwise.util.Forms;
import com.stockwise.util.Icons;
import com.stockwise.util.Tables;
import com.stockwise.util.Tables.Kind;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Módulo de planificación avanzada: decisiones estratégicas por producto, patrones de demanda ADI/CV², stock
 * histórico reconstruido, simulación what-if, scorecard/ROI/impacto y backtesting walk-forward.
 */
public class PlanificacionController extends BaseController {

    @FXML
    private Label subtitleLabel;
    @FXML
    private VBox notesBox;
    @FXML
    private TabPane tabs;

    private PlanningService.Analisis analisis;

    // Patrones
    private final TableView<ProductMetrics> patronTable = tabla();
    private final FlowPane patronResumen = new FlowPane(10, 10);
    // Stock histórico
    private final ComboBox<ProductMetrics> productoStock = new ComboBox<>();
    private final ComboBox<Sucursal> sucursalStock = new ComboBox<>();
    private final LineChart<Number, Number> stockChart = new LineChart<>(ejeFechas(), new NumberAxis());
    private final TableView<DiaStock> stockTable = tabla();
    private final Label stockResumen = muted("Seleccione un producto para ver su stock diario reconstruido.");
    // What-if
    private final TextField demandaField = Forms.decimalField("0 = sin cambio");
    private final TextField presupuestoField = Forms.decimalField("Vacío = presupuesto actual");
    private final TextField leadTimeField = Forms.integerField("0 = sin cambio");
    private final TextField servicioField = Forms.decimalField("Vacío = el configurado");
    private final TableView<String[]> comparacionTable = tabla();
    private final TableView<ScenarioSimulator.CambioProducto> cambiosTable = tabla();
    private final VBox simNotas = new VBox(6);
    // Scorecard
    private final Label scoreLabel = new Label("—");
    private final Label gradeLabel = muted("Pulse \"Calcular scorecard\".");
    private final TableView<ScorecardService.Componente> componentesTable = tabla();
    private final FlowPane roiPane = new FlowPane(14, 14);
    private final TableView<PlanningService.Impacto> impactoTable = tabla();
    private final Label impactoMensaje = muted("");
    // Backtesting
    private final ComboBox<Integer> mesesPrueba = new ComboBox<>();
    private final ComboBox<Integer> horizonte = new ComboBox<>();
    private final FlowPane btKpis = new FlowPane(14, 14);
    private final TableView<BacktestService.Grupo> porPatronTable = tabla();
    private final TableView<BacktestService.Grupo> porModeloTable = tabla();
    private final TableView<BacktestService.ResultadoProducto> btProductos = tabla();
    private final VBox btNotas = new VBox(6);
    private BacktestService.Resultado backtest;

    public PlanificacionController(Shell shell) {
        super(shell);
    }

    private static <T> TableView<T> tabla() {
        TableView<T> t = new TableView<>();
        VBox.setVgrow(t, Priority.ALWAYS);
        return t;
    }

    private static Label muted(String texto) {
        Label l = new Label(texto);
        l.getStyleClass().add("muted");
        l.setWrapText(true);
        return l;
    }

    private static NumberAxis ejeFechas() {
        NumberAxis x = new NumberAxis();
        x.setForceZeroInRange(false);
        x.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number n) {
                return Formatters.date(LocalDate.ofEpochDay(n.longValue()));
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });
        return x;
    }

    @FXML
    private void initialize() {
        tabs.getTabs().setAll(
                new Tab("Patrones de demanda (ADI/CV²)", tabPatrones()),
                new Tab("Stock histórico diario", tabStock()),
                new Tab("Simulación what-if", tabSimulacion()),
                new Tab("Scorecard, ROI e impacto", tabScorecard()),
                new Tab("Backtesting", tabBacktest()));
        refresh();
    }

    // ================================================================== carga

    @FXML
    private void refresh() {
        shell.run("Analizando productos…", () -> app.planificacion().analizar(), a -> {
            analisis = a;
            render();
        });
    }

    private void render() {
        notesBox.getChildren().clear();
        for (String n : analisis.notas()) {
            Label l = new Label(n);
            l.getStyleClass().addAll("notice", "warning");
            l.setMaxWidth(Double.MAX_VALUE);
            l.setWrapText(true);
            notesBox.getChildren().add(l);
        }
        subtitleLabel.setText("Fecha de corte " + Formatters.date(analisis.snapshot().fechaCorte()) + " · "
                + analisis.decisiones().size() + " productos · herramientas de análisis detallado. Las "
                + "recomendaciones por producto están en Resultados del análisis.");
        patronTable.getItems().setAll(analisis.snapshot().productos());
        renderResumenPatrones();
        ProductMetrics sel = productoStock.getValue();
        productoStock.getItems().setAll(analisis.snapshot().productos());
        if (sel != null) {
            productoStock.getItems().stream().filter(m -> m.productoId() == sel.productoId()).findFirst()
                    .ifPresent(productoStock::setValue);
        }
        List<Sucursal> sucs = new ArrayList<>();
        sucs.add(null);
        sucs.addAll(analisis.snapshot().sucursales());
        Sucursal ss = sucursalStock.getValue();
        sucursalStock.getItems().setAll(sucs);
        sucursalStock.setValue(ss);
    }

    // ================================================================== patrones ADI/CV²

    private Node tabPatrones() {
        Function<ProductMetrics, DemandClassifier.Clasificacion> c = m -> m.forecast() == null ? null
                : m.forecast().clasificacion();
        Tables.col(patronTable, "Código", m -> m.producto().codigo(), Kind.TEXT, 85);
        Tables.col(patronTable, "Producto", m -> m.producto().nombre(), Kind.TEXT, 200);
        Tables.badge(patronTable, "Patrón", m -> m.forecast() == null ? "" : m.forecast().patron().etiqueta(),
                Map.of("Suave", "success", "Errática", "warning", "Intermitente", "info", "Irregular (lumpy)", "danger"),
                130);
        Tables.col(patronTable, "ADI", m -> c.apply(m) == null || Double.isNaN(c.apply(m).adi()) ? null : c.apply(m).adi(),
                Kind.DECIMAL, 70);
        Tables.col(patronTable, "CV²", m -> c.apply(m) == null || Double.isNaN(c.apply(m).cv2()) ? null : c.apply(m).cv2(),
                Kind.DECIMAL, 70);
        Tables.col(patronTable, "Meses", m -> c.apply(m) == null ? null : c.apply(m).periodos(), Kind.INT, 60);
        Tables.col(patronTable, "Con ventas", m -> c.apply(m) == null ? null : c.apply(m).periodosConDemanda(),
                Kind.INT, 75);
        Tables.col(patronTable, "Modelo seleccionado", m -> m.forecast() != null && m.forecast().mejor() != null
                ? m.forecast().mejor().metodo() : "Sin pronóstico (datos insuficientes)", Kind.TEXT, 230);
        Tables.col(patronTable, "MAE", m -> m.forecast() != null && m.forecast().mejor() != null
                ? m.forecast().mejor().mae() : null, Kind.DECIMAL, 70);
        Tables.col(patronTable, "Confianza", m -> m.forecast() == null ? "" : m.forecast().confianza().toString(),
                Kind.TEXT, 120);
        Tables.col(patronTable, "Modelos especializados evaluados", m -> m.forecast() == null ? ""
                : especializados(m.forecast()), Kind.TEXT, 300);
        Tables.placeholder(patronTable, "No hay productos activos.");
        Label ayuda = muted("Clasificación Syntetos-Boylan: ADI = periodos / periodos con venta; CV² = (σ/μ)² de las "
                + "ventas no nulas. Cortes ADI 1,32 y CV² 0,49. Modelos especializados: suave → LightGBM; errática → "
                + "CatBoost; intermitente → Croston-TSB; irregular → Croston-TSB y CatBoost. Compiten con los métodos "
                + "clásicos y se elige el de menor MAE en validación walk-forward. LightGBM y CatBoost son "
                + "implementaciones propias en Java de esos algoritmos (sin bibliotecas nativas).");
        return pad(new VBox(8, patronResumen, patronTable, ayuda));
    }

    private static String especializados(ForecastResult fr) {
        List<String> nombres = new ArrayList<>();
        com.stockwise.prediction.ForecastEngine.especializados(fr.patron()).forEach(m -> {
            boolean evaluado = fr.metodos().stream().anyMatch(r -> r.metodo().equals(m.nombre()));
            nombres.add(m.nombre() + (evaluado ? "" : " (historial insuficiente)"));
        });
        return nombres.isEmpty() ? "—" : String.join(", ", nombres);
    }

    private void renderResumenPatrones() {
        Map<DemandPattern, Long> conteo = new EnumMap<>(DemandPattern.class);
        analisis.snapshot().productos().forEach(m -> conteo.merge(m.forecast() == null ? DemandPattern.SIN_CLASIFICAR
                : m.forecast().patron(), 1L, Long::sum));
        patronResumen.getChildren().clear();
        for (DemandPattern p : DemandPattern.values()) {
            VBox k = DashboardController.kpi(p.etiqueta(), String.valueOf(conteo.getOrDefault(p, 0L)), p.descripcion(),
                    null, Icons.TREND, p == DemandPattern.SIN_CLASIFICAR ? "red" : "blue");
            k.setPrefWidth(230);
            patronResumen.getChildren().add(k);
        }
    }

    // ================================================================== stock histórico

    private Node tabStock() {
        productoStock.setConverter(Forms.converter(m -> m == null ? "" : m.producto().toString()));
        productoStock.setPrefWidth(320);
        sucursalStock.setConverter(Forms.converter(s -> s == null ? "Todas las sucursales" : s.toString()));
        sucursalStock.setPrefWidth(200);
        Button ver = new Button("Ver stock diario");
        ver.getStyleClass().add("primary");
        ver.setOnAction(e -> verStock());
        Button reconstruir = new Button("Reconstruir y guardar todo", Icons.REFRESH.create(14));
        reconstruir.setOnAction(e -> reconstruirTodo());
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox barra = new HBox(8, new Label("Producto"), productoStock, new Label("Sucursal"), sucursalStock, ver, sp,
                reconstruir);
        barra.getStyleClass().add("toolbar-row");
        stockChart.setCreateSymbols(false);
        stockChart.setAnimated(false);
        stockChart.setLegendVisible(false);
        stockChart.setTitle("Stock al cierre del día (reconstruido)");
        stockChart.setMinHeight(260);
        Tables.col(stockTable, "Fecha", DiaStock::fecha, Kind.DATE, 100);
        Tables.col(stockTable, "Stock al cierre", DiaStock::stock, Kind.DECIMAL, 110);
        Tables.col(stockTable, "Entradas", DiaStock::entradas, Kind.DECIMAL, 90);
        Tables.col(stockTable, "Salidas", DiaStock::salidas, Kind.DECIMAL, 90);
        Tables.badge(stockTable, "Situación", d -> d.negativo() ? "Inconsistente" : d.sinStock() ? "Sin stock" : "Con stock",
                Map.of("Inconsistente", "danger", "Sin stock", "warning", "Con stock", "success"), 120);
        Tables.placeholder(stockTable, "Sin datos reconstruidos.");
        SplitPane split = new SplitPane(stockChart, stockTable);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.55);
        VBox.setVgrow(split, Priority.ALWAYS);
        Label ayuda = muted("Se reconstruye hacia atrás desde el inventario actual: stock(d−1) = stock(d) − entradas(d) + "
                + "salidas(d), con ventas, compras recibidas, ajustes y traslados. Un stock negativo indica que faltan "
                + "compras o ajustes registrados antes de esa fecha (no se corrige con datos inventados).");
        return pad(new VBox(8, barra, stockResumen, split, ayuda));
    }

    private void verStock() {
        ProductMetrics m = productoStock.getValue();
        if (m == null) {
            Dialogs.info("Producto", "Seleccione un producto.");
            return;
        }
        Sucursal s = sucursalStock.getValue();
        LocalDate corte = analisis.snapshot().fechaCorte();
        shell.run("Reconstruyendo stock diario…", () -> app.stockHistorico().reconstruir(m.productoId(),
                s == null ? null : s.id()), dias -> {
            stockChart.getData().clear();
            XYChart.Series<Number, Number> serie = new XYChart.Series<>();
            serie.setName("Stock");
            for (DiaStock d : dias) {
                serie.getData().add(new XYChart.Data<>(d.fecha().toEpochDay(), d.stock()));
            }
            stockChart.getData().add(serie);
            List<DiaStock> inv = new ArrayList<>(dias);
            java.util.Collections.reverse(inv);
            stockTable.getItems().setAll(inv);
            if (dias.isEmpty()) {
                stockResumen.setText("No disponible: el producto no tiene stock ni movimientos registrados.");
                return;
            }
            StockReconstructor.Resumen total = StockReconstructor.resumir(dias, null, null);
            StockReconstructor.Resumen r90 = StockReconstructor.resumir(dias, corte.minusDays(89), corte);
            stockResumen.setText(String.format("%d días reconstruidos (%s a %s) · %d sin stock · disponibilidad en los "
                            + "90 días previos al corte: %s%s", total.dias(), Formatters.date(dias.get(0).fecha()),
                    Formatters.date(dias.get(dias.size() - 1).fecha()), total.diasSinStock(),
                    analisis.notas().contains(StockHistoryService.SIN_ENTRADAS)
                            ? "no medible (no hay compras ni entradas registradas)"
                            : r90.disponibilidad() == null ? "no disponible" : Formatters.percent(r90.disponibilidad()),
                    total.consistente() ? "" : " · ATENCIÓN: " + total.diasNegativos() + " día(s) con stock negativo "
                            + "(del " + Formatters.date(total.primerNegativo()) + " al "
                            + Formatters.date(total.ultimoNegativo()) + "): faltan registros de compras o ajustes."));
        });
    }

    private void reconstruirTodo() {
        shell.run("Reconstruyendo el stock histórico de todos los productos…",
                () -> app.stockHistorico().reconstruirYGuardar(), (StockHistoryService.ResultadoReconstruccion r) ->
                        Dialogs.info("Stock histórico reconstruido", r.combinaciones() + " combinación(es) "
                                + "producto-sucursal, " + r.dias() + " días guardados.\n\n" + String.join("\n", r.notas())));
    }

    // ================================================================== what-if

    private Node tabSimulacion() {
        GridPane g = Forms.grid();
        Forms.row(g, "Demanda (± %)", demandaField);
        Forms.row(g, "Presupuesto", presupuestoField);
        Forms.row(g, "Entrega (± días)", leadTimeField);
        Forms.row(g, "Nivel de servicio", servicioField);
        servicioField.setPromptText("0.50 - 0.999 (vacío = el configurado)");
        Button simular = new Button("Simular escenario");
        simular.getStyleClass().add("primary");
        simular.setOnAction(e -> simular());
        Tables.col(comparacionTable, "Indicador", r -> r[0], Kind.TEXT, 260);
        Tables.col(comparacionTable, "Escenario actual", r -> r[1], Kind.TEXT, 160);
        Tables.col(comparacionTable, "Escenario simulado", r -> r[2], Kind.TEXT, 160);
        Tables.col(comparacionTable, "Diferencia", r -> r[3], Kind.TEXT, 160);
        Tables.placeholder(comparacionTable, "Defina el escenario y pulse \"Simular escenario\".");
        Tables.col(cambiosTable, "Código", ScenarioSimulator.CambioProducto::codigo, Kind.TEXT, 85);
        Tables.col(cambiosTable, "Producto", ScenarioSimulator.CambioProducto::producto, Kind.TEXT, 200);
        Tables.col(cambiosTable, "Compra actual", ScenarioSimulator.CambioProducto::cantidadBase, Kind.INT, 95);
        Tables.col(cambiosTable, "Compra simulada", ScenarioSimulator.CambioProducto::cantidadEscenario, Kind.INT, 105);
        Tables.col(cambiosTable, "Inversión actual", ScenarioSimulator.CambioProducto::inversionBase, Kind.MONEY, 110);
        Tables.col(cambiosTable, "Inversión simulada", ScenarioSimulator.CambioProducto::inversionEscenario, Kind.MONEY,
                120);
        Tables.col(cambiosTable, "Decisión actual", c -> c.decisionBase() == null ? "" : c.decisionBase().etiqueta(),
                Kind.TEXT, 140);
        Tables.col(cambiosTable, "Decisión simulada", c -> c.decisionEscenario() == null ? ""
                : c.decisionEscenario().etiqueta(), Kind.TEXT, 140);
        Tables.placeholder(cambiosTable, "Sin cambios por producto.");
        comparacionTable.setMinHeight(300);
        cambiosTable.setMinHeight(180);
        VBox izquierda = new VBox(10, new Label("Escenario"), g, simular, muted("Ejemplos: +20 % de demanda por campaña; "
                + "presupuesto reducido; proveedor con 10 días más de entrega. No se modifica ningún dato."), simNotas);
        izquierda.setPrefWidth(420);
        izquierda.setMinWidth(380);
        VBox derecha = new VBox(8, comparacionTable, new Label("Productos con cambios"), cambiosTable);
        HBox.setHgrow(derecha, Priority.ALWAYS);
        return pad(new HBox(16, izquierda, derecha));
    }

    private void simular() {
        ScenarioSimulator.Escenario e;
        try {
            double dem = Forms.decimal(demandaField, "Variación de la demanda", false, 0) / 100.0;
            String pt = presupuestoField.getText() == null ? "" : presupuestoField.getText().trim();
            Double presu = pt.isEmpty() ? null : Forms.decimal(presupuestoField, "Presupuesto", true, 0);
            int lt = Forms.integer(leadTimeField, "Variación del tiempo de entrega", false, 0);
            String st = servicioField.getText() == null ? "" : servicioField.getText().trim();
            Double ns = st.isEmpty() ? null : Forms.decimal(servicioField, "Nivel de servicio", true, 0.95);
            e = new ScenarioSimulator.Escenario(dem, presu, lt, ns);
        } catch (ValidationException ex) {
            Dialogs.error(ex);
            return;
        } catch (IllegalArgumentException ex) {
            Dialogs.error(new ValidationException(ex.getMessage()));
            return;
        }
        shell.run("Simulando escenario…", () -> app.planificacion().simular(e), r -> {
            ScenarioSimulator.Indicadores a = r.base();
            ScenarioSimulator.Indicadores b = r.simulado();
            List<String[]> filas = new ArrayList<>();
            filas.add(dinero("Presupuesto disponible", a.presupuesto(), b.presupuesto()));
            filas.add(dinero("Inversión necesaria", a.inversionNecesaria(), b.inversionNecesaria()));
            filas.add(dinero("Inversión aprobada", a.inversionAprobada(), b.inversionAprobada()));
            filas.add(dinero("Saldo de presupuesto", a.saldo(), b.saldo()));
            filas.add(entero("Productos a comprar", a.productosAComprar(), b.productosAComprar()));
            filas.add(entero("Unidades a comprar", a.unidadesAComprar(), b.unidadesAComprar()));
            filas.add(entero("Productos sin presupuesto suficiente", a.productosSinPresupuesto(),
                    b.productosSinPresupuesto()));
            filas.add(entero("Productos en riesgo no cubiertos", a.productosEnRiesgo(), b.productosEnRiesgo()));
            filas.add(new String[]{"ROI esperado del plan", pct(a.roiEsperado()), pct(b.roiEsperado()),
                    a.roiEsperado() == null || b.roiEsperado() == null ? "—"
                            : String.format("%+.1f pp", (b.roiEsperado() - a.roiEsperado()) * 100)});
            for (DecisionEstrategica d : DecisionEstrategica.values()) {
                filas.add(entero("Decisión: " + d.etiqueta(), a.decisiones().getOrDefault(d, 0L),
                        b.decisiones().getOrDefault(d, 0L)));
            }
            comparacionTable.getItems().setAll(filas);
            cambiosTable.getItems().setAll(r.cambios());
            simNotas.getChildren().clear();
            r.notas().forEach(n -> simNotas.getChildren().add(muted("• " + n)));
        });
    }

    private static String pct(Double v) {
        return v == null ? "No disponible" : Formatters.percent(v);
    }

    private static String[] dinero(String n, double a, double b) {
        return new String[]{n, Formatters.money(a), Formatters.money(b), (b - a >= 0 ? "+" : "") + Formatters.money(b - a)};
    }

    private static String[] entero(String n, double a, double b) {
        return new String[]{n, Formatters.integer(a), Formatters.integer(b), String.format("%+,.0f", b - a)};
    }

    // ================================================================== scorecard / ROI / impacto

    private Node tabScorecard() {
        Button calcular = new Button("Calcular scorecard");
        calcular.getStyleClass().add("primary");
        calcular.setOnAction(e -> calcularScorecard());
        Button impacto = new Button("Evaluar impacto de decisiones guardadas");
        impacto.setOnAction(e -> evaluarImpacto());
        scoreLabel.getStyleClass().add("big-number");
        VBox puntaje = new VBox(4, new Label("Puntaje de calidad del análisis"), scoreLabel, gradeLabel);
        puntaje.getStyleClass().add("card");
        puntaje.setMinWidth(260);
        Tables.col(componentesTable, "Componente", ScorecardService.Componente::nombre, Kind.TEXT, 240);
        Tables.col(componentesTable, "Puntaje", c -> c.puntaje() == null ? "No disponible"
                : Formatters.number(c.puntaje()), Kind.TEXT, 110);
        Tables.col(componentesTable, "Peso", ScorecardService.Componente::peso, Kind.PERCENT, 70);
        Tables.col(componentesTable, "Detalle", ScorecardService.Componente::detalle, Kind.TEXT, 560);
        componentesTable.setMinHeight(240);
        impactoTable.setMinHeight(220);
        Tables.placeholder(componentesTable, "Pulse \"Calcular scorecard\".");

        Tables.col(impactoTable, "Código", i -> i.decision().codigo(), Kind.TEXT, 85);
        Tables.col(impactoTable, "Producto", i -> i.decision().producto(), Kind.TEXT, 180);
        Tables.badge(impactoTable, "Decisión", i -> i.decision().decision().etiqueta(), Tables.ESTADOS, 140);
        Tables.badge(impactoTable, "Estado", i -> i.decision().estado(), Tables.ESTADOS, 95);
        Tables.col(impactoTable, "Demanda estimada", PlanningService.Impacto::demandaEsperada, Kind.DECIMAL, 110);
        Tables.col(impactoTable, "Venta real", PlanningService.Impacto::unidadesReales, Kind.DECIMAL, 85);
        Tables.col(impactoTable, "Cumplimiento", PlanningService.Impacto::cumplimientoDemanda, Kind.PERCENT, 95);
        Tables.col(impactoTable, "Margen real", PlanningService.Impacto::margenReal, Kind.MONEY, 100);
        Tables.col(impactoTable, "Comprado (u)", PlanningService.Impacto::unidadesCompradas, Kind.INT, 90);
        Tables.col(impactoTable, "ROI real", PlanningService.Impacto::roiReal, Kind.PERCENT, 80);
        Tables.col(impactoTable, "Var. stock", PlanningService.Impacto::variacionStock, Kind.INT, 80);
        Tables.col(impactoTable, "Resultado", PlanningService.Impacto::resultado, Kind.TEXT, 280);
        Tables.placeholder(impactoTable, "Pulse \"Evaluar impacto\" (requiere decisiones guardadas y ventas posteriores).");
        HBox cabecera = new HBox(16, puntaje, roiPane);
        HBox.setHgrow(roiPane, Priority.ALWAYS);
        HBox barra = new HBox(8, calcular, impacto);
        barra.getStyleClass().add("toolbar-row");
        VBox box = new VBox(10, barra, cabecera, componentesTable, new Label("Impacto real de decisiones anteriores"),
                impactoMensaje, impactoTable);
        return pad(box);
    }

    private void calcularScorecard() {
        shell.run("Calculando scorecard (incluye backtest de 3 meses)…", () -> app.scorecard().calcular(), sc -> {
            scoreLabel.setText(sc.puntaje() == null ? "No disponible" : Formatters.number(sc.puntaje()) + " / 100");
            gradeLabel.setText(sc.calificacion() + (sc.notas().isEmpty() ? "" : "\n" + String.join("\n", sc.notas())));
            componentesTable.getItems().setAll(sc.componentes());
            PlanningService.Roi r = sc.roi();
            roiPane.getChildren().setAll(
                    DashboardController.kpi("Inversión del plan", Formatters.money(r.inversion()),
                            r.lineasSinCosto() > 0 ? r.lineasSinCosto() + " línea(s) sin costo registrado" : "", null,
                            Icons.PURCHASE, "blue"),
                    DashboardController.kpi("Margen esperado del plan", r.margenEsperado() == null ? "No disponible"
                            : Formatters.money(r.margenEsperado()), "Unidades que se venderán en reposición + ciclo", null,
                            Icons.MONEY, "green"),
                    DashboardController.kpi("ROI esperado", pct(r.roiEsperado()), "Margen esperado / inversión", null,
                            Icons.TREND, "green"),
                    DashboardController.kpi("GMROI del inventario", r.gmroi() == null ? "No disponible"
                                    : Formatters.number(r.gmroi()), "Utilidad 12 meses / inventario valorizado ("
                                    + Formatters.money(r.valorInventario()) + ")", null, Icons.INVENTORY, "orange"));
        });
    }

    private void evaluarImpacto() {
        shell.run("Evaluando impacto de decisiones…", () -> app.planificacion().evaluarImpacto(), ev -> {
            impactoTable.getItems().setAll(ev.impactos());
            impactoMensaje.setText(ev.mensaje() != null ? ev.mensaje()
                    : "Decisiones con corte " + Formatters.date(ev.fechaCorteDecisiones()) + " comparadas con las ventas "
                    + "hasta " + Formatters.date(ev.fechaCorteActual()) + ".");
        });
    }

    // ================================================================== backtesting

    private Node tabBacktest() {
        mesesPrueba.getItems().setAll(3, 6, 9, 12);
        mesesPrueba.setValue(6);
        horizonte.getItems().setAll(1, 2, 3);
        horizonte.setValue(1);
        Button ejecutar = new Button("Ejecutar backtesting");
        ejecutar.getStyleClass().add("primary");
        ejecutar.setOnAction(e -> ejecutarBacktest());
        Button exportar = new Button("Exportar a Excel", Icons.DOWNLOAD.create(14));
        exportar.setOnAction(e -> exportarBacktest());
        HBox barra = new HBox(8, new Label("Meses de prueba"), mesesPrueba, new Label("Horizonte (meses)"), horizonte,
                ejecutar, exportar);
        barra.getStyleClass().add("toolbar-row");
        for (TableView<BacktestService.Grupo> t : List.of(porPatronTable, porModeloTable)) {
            Tables.col(t, t == porPatronTable ? "Patrón" : "Modelo más usado", BacktestService.Grupo::nombre, Kind.TEXT, 220);
            Tables.col(t, "Productos", BacktestService.Grupo::productos, Kind.INT, 75);
            Tables.col(t, "Puntos", BacktestService.Grupo::puntos, Kind.INT, 65);
            Tables.col(t, "WAPE", BacktestService.Grupo::wape, Kind.PERCENT, 75);
            Tables.col(t, "Sesgo", BacktestService.Grupo::sesgo, Kind.PERCENT, 75);
            Tables.col(t, "FVA vs ingenuo", BacktestService.Grupo::fva, Kind.PERCENT, 100);
            t.setMinHeight(160);
            Tables.placeholder(t, "Ejecute el backtesting.");
        }
        Tables.col(btProductos, "Código", BacktestService.ResultadoProducto::codigo, Kind.TEXT, 85);
        Tables.col(btProductos, "Producto", BacktestService.ResultadoProducto::nombre, Kind.TEXT, 200);
        Tables.col(btProductos, "Patrón", r -> r.patron().etiqueta(), Kind.TEXT, 110);
        Tables.col(btProductos, "Puntos", BacktestService.ResultadoProducto::puntos, Kind.INT, 60);
        Tables.col(btProductos, "Venta real", BacktestService.ResultadoProducto::real, Kind.DECIMAL, 85);
        Tables.col(btProductos, "MAE", BacktestService.ResultadoProducto::mae, Kind.DECIMAL, 70);
        Tables.col(btProductos, "WAPE", BacktestService.ResultadoProducto::wape, Kind.PERCENT, 70);
        Tables.col(btProductos, "Sesgo", BacktestService.ResultadoProducto::sesgo, Kind.PERCENT, 70);
        Tables.col(btProductos, "MAE ingenuo", BacktestService.ResultadoProducto::maeIngenuo, Kind.DECIMAL, 90);
        Tables.col(btProductos, "FVA", BacktestService.ResultadoProducto::fva, Kind.PERCENT, 70);
        Tables.col(btProductos, "Modelo más usado", BacktestService.ResultadoProducto::metodoMasUsado, Kind.TEXT, 220);
        Tables.placeholder(btProductos, "Ejecute el backtesting.");
        HBox grupos = new HBox(12, porPatronTable, porModeloTable);
        HBox.setHgrow(porPatronTable, Priority.ALWAYS);
        HBox.setHgrow(porModeloTable, Priority.ALWAYS);
        return pad(new VBox(8, barra, btKpis, btNotas, grupos, btProductos));
    }

    private void ejecutarBacktest() {
        int meses = mesesPrueba.getValue();
        int h = horizonte.getValue();
        shell.run("Ejecutando backtesting walk-forward…", () -> app.backtest().ejecutar(meses, h), r -> {
            backtest = r;
            btKpis.getChildren().setAll(
                    DashboardController.kpi("WAPE", pct(r.wape()), "Error absoluto ponderado", null, Icons.TREND, "blue"),
                    DashboardController.kpi("Precisión", pct(r.precision()), "1 − WAPE", null, Icons.CHECK, "green"),
                    DashboardController.kpi("Sesgo", pct(r.sesgo()), "Positivo = sobreestima", null, Icons.WARNING,
                            "orange"),
                    DashboardController.kpi("Valor agregado (FVA)", pct(r.fva()), "Mejora del MAE frente al pronóstico "
                            + "ingenuo", null, Icons.ADVISOR, "green"),
                    DashboardController.kpi("Productos evaluados", String.valueOf(r.productos().size()),
                            r.sinDatos().size() + " sin historial suficiente", null, Icons.PRODUCTS, "blue"));
            btNotas.getChildren().clear();
            r.notas().forEach(n -> btNotas.getChildren().add(muted("• " + n)));
            porPatronTable.getItems().setAll(r.porPatron());
            porModeloTable.getItems().setAll(r.porModelo());
            btProductos.getItems().setAll(r.productos());
        });
    }

    private void exportarBacktest() {
        if (backtest == null) {
            Dialogs.info("Backtesting", "Ejecute primero el backtesting.");
            return;
        }
        List<List<Object>> filas = backtest.productos().stream().<List<Object>>map(r -> List.of(r.codigo(), r.nombre(),
                r.patron().etiqueta(), r.puntos(), r.real(), r.mae(), Double.isNaN(r.wape()) ? "" : r.wape(),
                Double.isNaN(r.sesgo()) ? "" : r.sesgo(), r.maeIngenuo(), r.fva() == null ? "" : r.fva(),
                r.metodoMasUsado())).toList();
        exportar("backtesting.xlsx", new ReportTable("Backtesting walk-forward", backtest.mesesPrueba()
                + " meses de prueba · horizonte " + backtest.horizonte() + " mes(es)",
                List.of("Código", "Producto", "Patrón", "Puntos", "Venta real", "MAE", "WAPE", "Sesgo", "MAE ingenuo",
                        "FVA", "Modelo más usado"),
                List.of(ColumnType.TEXTO, ColumnType.TEXTO, ColumnType.TEXTO, ColumnType.ENTERO, ColumnType.DECIMAL,
                        ColumnType.DECIMAL, ColumnType.PORCENTAJE, ColumnType.PORCENTAJE, ColumnType.DECIMAL,
                        ColumnType.PORCENTAJE, ColumnType.TEXTO), filas,
                List.of("WAPE global: " + pct(backtest.wape()), "Sesgo global: " + pct(backtest.sesgo()),
                        "FVA global: " + pct(backtest.fva()))));
    }

    // ================================================================== utilidades

    private void exportar(String nombre, ReportTable tabla) {
        FileChooser fc = new FileChooser();
        fc.setInitialFileName(nombre);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx"));
        File f = fc.showSaveDialog(shell.window());
        if (f == null) {
            return;
        }
        shell.run("Exportando…", () -> {
            app.reportes().exportarExcel(List.of(tabla), f.toPath());
            return f;
        }, r -> Dialogs.info("Archivo generado", "Se guardó en:\n" + r.getAbsolutePath()));
    }

    private static Node pad(Node contenido) {
        VBox box = new VBox(contenido);
        VBox.setVgrow(contenido, Priority.ALWAYS);
        box.setStyle("-fx-padding: 10 2 2 2;");
        if (contenido instanceof Region r) {
            r.setMaxHeight(Double.MAX_VALUE);
        }
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        box.setMinHeight(Region.USE_PREF_SIZE);
        sp.getStyleClass().add("scroll-page");
        return sp;
    }
}
