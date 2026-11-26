package com.stockwise.controller;

import com.stockwise.analytics.EstadoInventario;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.Recomendacion;
import com.stockwise.prediction.ForecastResult;
import com.stockwise.repository.VentaRepository;
import com.stockwise.service.DashboardService.DashboardData;
import com.stockwise.util.Charts;
import com.stockwise.util.Formatters;
import com.stockwise.util.Icons;
import com.stockwise.util.Tables;
import com.stockwise.util.Tables.Kind;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

public class DashboardController extends BaseController {

    @FXML
    private Label subtitleLabel;
    @FXML
    private VBox emptyState;
    @FXML
    private VBox contentBox;
    @FXML
    private FlowPane kpiPane;
    @FXML
    private Label forecastNote;
    @FXML
    private LineChart<String, Number> salesChart;
    @FXML
    private PieChart categoryChart;
    @FXML
    private BarChart<Number, String> topChart;
    @FXML
    private VBox alertsBox;
    @FXML
    private TableView<ProductMetrics> riskTable;
    @FXML
    private TableView<ProductMetrics> overstockTable;
    @FXML
    private TableView<ProductMetrics> lowRotationTable;
    /** Sin inventario registrado se ocultan las cifras que dependen del stock (serían inventadas). */
    private volatile boolean hayInventario = true;

    public DashboardController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        Tables.col(riskTable, "Producto", m -> m.producto().nombre(), Kind.TEXT, 170);
        Tables.col(riskTable, "Stock", ProductMetrics::stock, Kind.INT, 60);
        Tables.col(riskTable, "Días de stock", ProductMetrics::coberturaDias, Kind.DECIMAL, 100);
        Tables.badge(riskTable, "Estado", m -> m.estado().etiqueta(), Tables.ESTADOS, 120);

        Tables.col(overstockTable, "Producto", m -> m.producto().nombre(), Kind.TEXT, 170);
        Tables.col(overstockTable, "Stock", ProductMetrics::stock, Kind.INT, 60);
        Tables.col(overstockTable, "Meses de stock", ProductMetrics::coberturaMeses, Kind.DECIMAL, 95);
        Tables.col(overstockTable, "Valor", ProductMetrics::valorInventario, Kind.MONEY, 100);

        Tables.col(lowRotationTable, "Producto", m -> m.producto().nombre(), Kind.TEXT, 170);
        Tables.col(lowRotationTable, "Stock", ProductMetrics::stock, Kind.INT, 60);
        Tables.col(lowRotationTable, "Días sin venta", ProductMetrics::diasSinVenta, Kind.INT, 95);
        Tables.col(lowRotationTable, "Valor", ProductMetrics::valorInventario, Kind.MONEY, 100);
        for (TableView<?> t : List.of(riskTable, overstockTable, lowRotationTable)) {
            t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            Tables.placeholder(t, "Sin productos en esta situación");
        }
        refresh();
    }

    @FXML
    private void refresh() {
        shell.run("Calculando indicadores…", () -> {
            hayInventario = app.hayInventario();
            return app.dashboard().calcular();
        }, this::render);
    }

    @FXML
    private void openImport() {
        shell.navigate(View.IMPORTACION);
    }

    @FXML
    private void openAdvisor() {
        shell.navigate(View.ASESOR);
    }

    private void render(DashboardData d) {
        boolean vacio = d.totalVentasRegistradas() == 0 && d.snapshot().productos().isEmpty();
        emptyState.setVisible(vacio);
        emptyState.setManaged(vacio);
        contentBox.setVisible(!vacio);
        contentBox.setManaged(!vacio);
        subtitleLabel.setText(String.join("  ", d.snapshot().notas()));
        if (vacio) {
            return;
        }
        renderKpis(d);
        renderSalesChart(d);
        renderCategories(d);
        renderTop(d);
        renderAlerts(hayInventario ? d.alertas() : d.alertas().stream().filter(a -> !dependeDelStock(a.tipo())).toList());
        riskTable.getItems().setAll(d.riesgo());
        overstockTable.getItems().setAll(d.sobreinventario());
        lowRotationTable.getItems().setAll(d.bajaRotacion());
        // Solo se muestran los cuadros con información real: sin inventario o sin productos, se ocultan.
        boolean r = mostrar(riskTable.getParent(), hayInventario && !d.riesgo().isEmpty());
        boolean o = mostrar(overstockTable.getParent(), hayInventario && !d.sobreinventario().isEmpty());
        boolean b = mostrar(lowRotationTable.getParent(), hayInventario && !d.bajaRotacion().isEmpty());
        mostrar(riskTable.getParent().getParent(), r || o || b);
        mostrar(salesChart.getParent(), !d.mensual().isEmpty());
        mostrar(categoryChart.getParent(), d.categorias().size() >= 2);
        mostrar(topChart.getParent(), !d.top().isEmpty());
        shell.status("Indicadores actualizados");
    }

    /** Muestra u oculta un cuadro (sin dejar su espacio); devuelve si queda visible. */
    private static boolean mostrar(javafx.scene.Node n, boolean visible) {
        n.setVisible(visible);
        n.setManaged(visible);
        return visible;
    }

    private static boolean dependeDelStock(com.stockwise.model.TipoRecomendacion t) {
        return switch (t) {
            case TRASLADO, LIQUIDAR, REDUCIR_COMPRAS, PREVENIR_SOBREINVENTARIO, PREVENIR_DESABASTECIMIENTO -> true;
            default -> false;
        };
    }

    private void renderKpis(DashboardData d) {
        kpiPane.getChildren().clear();
        double margen = d.ventas12m() > 0 ? d.utilidad12m() / d.ventas12m() : 0;
        kpiPane.getChildren().add(kpi("Ventas (12 meses)", Formatters.moneyShort(d.ventas12m()),
                "Utilidad bruta " + Formatters.moneyShort(d.utilidad12m()) + " · margen " + Formatters.percent(margen),
                null, Icons.MONEY, "green"));

        String variacion = null;
        Boolean sube = null;
        if (d.ventasMesAnterior() > 0) {
            double v = (d.ventasUltimoMes() - d.ventasMesAnterior()) / d.ventasMesAnterior();
            variacion = (v >= 0 ? "▲ " : "▼ ") + Formatters.percent(Math.abs(v)) + " vs. mes anterior";
            sube = v >= 0;
        }
        kpiPane.getChildren().add(kpi("Ventas " + Formatters.period(d.ultimoMes()),
                Formatters.moneyShort(d.ventasUltimoMes()), variacion == null ? "Sin mes anterior para comparar"
                        : variacion, sube, Icons.SALES, "blue"));

        ForecastResult fr = d.pronosticoImporte();
        kpiPane.getChildren().add(fr.suficiente()
                ? kpi("Predicción " + Formatters.period(fr.periodosFuturos().get(0)),
                Formatters.moneyShort(fr.demandaProximos(1)),
                "Próximos 3 meses: " + Formatters.moneyShort(fr.demandaProximos(3)) + " · confianza "
                        + fr.confianza().toString().toLowerCase(), null, Icons.TREND, "blue")
                : kpi("Predicción de ventas", "—", "Historial insuficiente para predecir", null, Icons.TREND, "blue"));

        if (!hayInventario) {
            kpiPane.getChildren().add(kpi("Inventario", "Pendiente", "Importe sus existencias para ver qué falta, qué "
                    + "sobra y su valor", null, Icons.BOX, "blue"));
            return;
        }
        kpiPane.getChildren().add(kpi("Valor del inventario", Formatters.moneyShort(d.valorInventario()),
                Formatters.integer(d.unidadesInventario()) + " unidades disponibles", null, Icons.BOX, "blue"));
        long agotados = d.riesgo().stream().filter(m -> m.estado() == EstadoInventario.AGOTADO).count();
        kpiPane.getChildren().add(kpi("Riesgo de agotarse", String.valueOf(d.riesgo().size()),
                agotados + " producto(s) ya agotados", null, Icons.WARNING, "red"));
        double inmovilizado = d.sobreinventario().stream().mapToDouble(ProductMetrics::valorInventario).sum();
        kpiPane.getChildren().add(kpi("Exceso de stock", String.valueOf(d.sobreinventario().size()),
                "Capital inmovilizado " + Formatters.moneyShort(inmovilizado), null, Icons.INVENTORY, "orange"));
        double sinMov = d.bajaRotacion().stream().mapToDouble(ProductMetrics::valorInventario).sum();
        kpiPane.getChildren().add(kpi("Se venden lento", String.valueOf(d.bajaRotacion().size()),
                "Stock sin movimiento " + Formatters.moneyShort(sinMov), null, Icons.PRODUCTS, "orange"));
    }

    static VBox kpi(String titulo, String valor, String detalle, Boolean tendencia, Icons icono, String color) {
        // El icono y el color se conservan en la firma por compatibilidad; el diseño sobrio no los muestra.
        Label head = new Label(titulo);
        head.getStyleClass().add("kpi-title");
        Label v = new Label(valor);
        v.getStyleClass().add("kpi-value");
        Label det = new Label(detalle);
        det.setWrapText(true);
        det.getStyleClass().add(tendencia == null ? "kpi-detail" : tendencia ? "trend-up" : "trend-down");
        VBox box = new VBox(head, v, det);
        box.getStyleClass().add("kpi-card");
        return box;
    }

    private void renderSalesChart(DashboardData d) {
        salesChart.getData().clear();
        XYChart.Series<String, Number> real = new XYChart.Series<>();
        real.setName("Ventas reales");
        for (VentaRepository.MonthlyTotal m : d.mensual()) {
            real.getData().add(new XYChart.Data<>(Formatters.period(m.periodo()), m.importe()));
        }
        salesChart.getData().add(real);
        ForecastResult fr = d.pronosticoImporte();
        if (fr.suficiente() && !real.getData().isEmpty()) {
            XYChart.Series<String, Number> pred = new XYChart.Series<>();
            pred.setName("Predicción (" + fr.mejor().metodo() + ")");
            String ultimoReal = fr.periodos().get(fr.periodos().size() - 1);
            pred.getData().add(new XYChart.Data<>(Formatters.period(ultimoReal),
                    fr.historia()[fr.historia().length - 1]));
            for (int i = 0; i < 3; i++) {
                pred.getData().add(new XYChart.Data<>(Formatters.period(fr.periodosFuturos().get(i)),
                        fr.mejor().pronostico()[i]));
            }
            salesChart.getData().add(pred);
            forecastNote.setText("Tendencia " + fr.tendencia().toString().toLowerCase() + " · confianza "
                    + fr.confianza().toString().toLowerCase() + (fr.advertencias().isEmpty() ? ""
                    : " · " + fr.advertencias().get(0)));
        } else {
            forecastNote.setText(fr.advertencias().isEmpty() ? "" : fr.advertencias().get(0));
        }
        Platform.runLater(() -> Charts.tooltips(salesChart, n -> Formatters.money(n.doubleValue())));
    }

    private void renderCategories(DashboardData d) {
        categoryChart.getData().clear();
        for (VentaRepository.Aggregate a : d.categorias()) {
            categoryChart.getData().add(new PieChart.Data(a.nombre(), a.importe()));
        }
        Platform.runLater(() -> Charts.tooltips(categoryChart, n -> Formatters.money(n.doubleValue())));
    }

    private void renderTop(DashboardData d) {
        topChart.getData().clear();
        XYChart.Series<Number, String> s = new XYChart.Series<>();
        s.setName("Ventas");
        List<VentaRepository.Aggregate> top = d.top();
        for (int i = top.size() - 1; i >= 0; i--) {
            VentaRepository.Aggregate a = top.get(i);
            String nombre = a.nombre().length() > 24 ? a.nombre().substring(0, 23) + "…" : a.nombre();
            s.getData().add(new XYChart.Data<>(a.importe(), nombre));
        }
        topChart.getData().add(s);
        Platform.runLater(() -> Charts.tooltips(topChart, n -> Formatters.money(n.doubleValue())));
    }

    private void renderAlerts(List<Recomendacion> alertas) {
        alertsBox.getChildren().clear();
        if (alertas.isEmpty()) {
            Label l = new Label("No se detectaron alertas con los datos actuales.");
            l.getStyleClass().addAll("notice", "success");
            alertsBox.getChildren().add(l);
            return;
        }
        for (Recomendacion r : alertas) {
            Label badge = new Label(r.prioridad().etiqueta());
            badge.getStyleClass().addAll("badge", "badge-" + Tables.ESTADOS.getOrDefault(r.prioridad().etiqueta(),
                    "neutral"));
            badge.setMinWidth(Region.USE_PREF_SIZE);
            Label titulo = new Label(r.tipo().etiqueta() + (r.producto() == null ? "" : " · " + r.producto()));
            titulo.getStyleClass().add("strong");
            Label accion = new Label(r.accion());
            accion.setWrapText(true);
            accion.getStyleClass().add("muted");
            VBox texto = new VBox(2, titulo, accion);
            HBox.setHgrow(texto, Priority.ALWAYS);
            HBox fila = new HBox(10, badge, texto);
            alertsBox.getChildren().add(fila);
        }
    }
}
