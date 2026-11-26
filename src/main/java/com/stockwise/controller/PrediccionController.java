package com.stockwise.controller;

import com.stockwise.model.Producto;
import com.stockwise.prediction.ForecastResult;
import com.stockwise.prediction.ForecastResult.MethodResult;
import com.stockwise.repository.PrediccionRepository;
import com.stockwise.util.Charts;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Formatters;
import com.stockwise.util.Forms;
import com.stockwise.util.Icons;
import com.stockwise.util.Tables;
import com.stockwise.util.Tables.Kind;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PrediccionController extends BaseController {

    /** Elemento de la lista: un producto o la serie total de ventas (producto nulo). */
    public record Item(Producto producto) {
        @Override
        public String toString() {
            return producto == null ? "★ Ventas totales de la empresa (importe)" : producto.toString();
        }
    }

    private record Resultado(ForecastResult forecast, List<PrediccionRepository.PrediccionGuardada> guardadas) {
    }

    private record PeriodoValor(String periodo, double valor) {
    }

    @FXML
    private TextField searchField;
    @FXML
    private ListView<Item> itemList;
    @FXML
    private Label titleLabel;
    @FXML
    private Label subtitleLabel;
    @FXML
    private ComboBox<Integer> horizonCombo;
    @FXML
    private Button saveButton;
    @FXML
    private FlowPane kpiPane;
    @FXML
    private VBox warningsBox;
    @FXML
    private FlowPane methodChecks;
    @FXML
    private LineChart<String, Number> chart;
    @FXML
    private TableView<MethodResult> methodTable;
    @FXML
    private TableView<PeriodoValor> forecastTable;
    @FXML
    private Label savedLabel;

    private List<Item> items = List.of();
    private ForecastResult actual;
    private Item itemActual;
    private final Set<String> metodosVisibles = new HashSet<>();

    public PrediccionController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        horizonCombo.getItems().setAll(1, 3, 6);
        horizonCombo.setConverter(Forms.converter(h -> h == null ? "" : h + (h == 1 ? " mes" : " meses")));
        horizonCombo.setValue(3);
        horizonCombo.valueProperty().addListener((o, a, b) -> render());
        saveButton.setGraphic(Icons.SAVE.create(14));
        saveButton.setDisable(true);

        Tables.col(methodTable, "Método", MethodResult::metodo, Kind.TEXT, 210);
        Tables.col(methodTable, "MAE", MethodResult::mae, Kind.DECIMAL, 75);
        Tables.col(methodTable, "MAPE", MethodResult::mape, Kind.PERCENT, 75);
        Tables.col(methodTable, "Próx. 1 mes", m -> suma(m, 1), Kind.DECIMAL, 90);
        Tables.col(methodTable, "Próx. 3 meses", m -> suma(m, 3), Kind.DECIMAL, 95);
        Tables.col(methodTable, "Próx. 6 meses", m -> suma(m, 6), Kind.DECIMAL, 95);
        Tables.badge(methodTable, "Selección", m -> actual != null && actual.mejor() == m ? "Seleccionado" : "",
                java.util.Map.of("Seleccionado", "success"), 110);
        Tables.placeholder(methodTable, "Sin métodos aplicables con el historial disponible.");
        Tables.col(forecastTable, "Periodo", p -> Formatters.period(p.periodo()), Kind.TEXT, 120);
        Tables.col(forecastTable, "Pronóstico", PeriodoValor::valor, Kind.DECIMAL, 120);
        Tables.placeholder(forecastTable, "Sin pronóstico: datos insuficientes.");

        itemList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                cargar(b);
            }
        });
        searchField.textProperty().addListener((o, a, b) -> filtrar());
        shell.run("Cargando productos…", () -> app.productos().listarActivos(), ps -> {
            List<Item> l = new ArrayList<>();
            l.add(new Item(null));
            ps.forEach(p -> l.add(new Item(p)));
            items = l;
            filtrar();
            itemList.getSelectionModel().selectFirst();
        });
    }

    private static double suma(MethodResult m, int meses) {
        return Arrays.stream(m.pronostico()).limit(meses).sum();
    }

    private void filtrar() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        itemList.getItems().setAll(items.stream().filter(i -> q.isEmpty() || i.toString().toLowerCase().contains(q))
                .toList());
    }

    private void cargar(Item item) {
        itemActual = item;
        shell.run("Calculando predicciones…", () -> item.producto() == null
                ? new Resultado(app.analysis().pronosticoGlobal(null, true), List.of())
                : new Resultado(app.analysis().pronosticoProducto(item.producto().id()),
                app.predicciones().findByProducto(item.producto().id())), r -> {
            actual = r.forecast();
            metodosVisibles.clear();
            if (actual.mejor() != null) {
                metodosVisibles.add(actual.mejor().metodo());
            }
            savedLabel.setText(r.guardadas().isEmpty() ? "" : "Última predicción guardada: "
                    + r.guardadas().get(0).fechaGeneracion() + " (" + r.guardadas().get(0).metodo() + ")");
            render();
        });
    }

    private void render() {
        if (actual == null || itemActual == null) {
            return;
        }
        ForecastResult fr = actual;
        boolean importe = itemActual.producto() == null;
        String unidad = importe ? "" : " u";
        int h = horizonCombo.getValue() == null ? 3 : horizonCombo.getValue();
        titleLabel.setText(importe ? "Ventas totales de la empresa" : itemActual.producto().nombre());
        subtitleLabel.setText((importe ? "Serie mensual de importe de ventas" : "Serie mensual de unidades vendidas · "
                + itemActual.producto().codigo()) + " · " + fr.historia().length + " meses de historial");
        saveButton.setDisable(importe || !fr.suficiente());

        kpiPane.getChildren().clear();
        if (fr.suficiente()) {
            String fmt1 = fmt(fr.demandaProximos(1), importe) + unidad;
            kpiPane.getChildren().addAll(
                    DashboardController.kpi("Próximo mes", fmt1, Formatters.period(fr.periodosFuturos().get(0)), null,
                            Icons.TREND, "blue"),
                    DashboardController.kpi("Próximos 3 meses", fmt(fr.demandaProximos(3), importe) + unidad, "", null,
                            Icons.TREND, "blue"),
                    DashboardController.kpi("Próximos 6 meses", fmt(fr.demandaProximos(6), importe) + unidad, "", null,
                            Icons.TREND, "blue"),
                    DashboardController.kpi("Tendencia", fr.tendencia().toString(),
                            (fr.pendiente() >= 0 ? "+" : "") + fmt(fr.pendiente(), importe) + unidad + " por mes",
                            fr.tendencia() == ForecastResult.Tendencia.ESTABLE ? null
                                    : fr.tendencia() == ForecastResult.Tendencia.CRECIENTE, Icons.REPORT, "green"),
                    DashboardController.kpi("Confianza", fr.confianza().toString(), "Método: " + fr.mejor().metodo(),
                            null, Icons.CHECK, fr.confianza() == ForecastResult.Confianza.BAJA ? "orange" : "green"));
        }
        if (fr.clasificacion() != null) {
            kpiPane.getChildren().add(DashboardController.kpi("Patrón de demanda", fr.patron().etiqueta(),
                    Double.isNaN(fr.clasificacion().adi()) ? fr.clasificacion().resumen()
                            : String.format("ADI %.2f · CV² %.2f", fr.clasificacion().adi(), fr.clasificacion().cv2()),
                    null, Icons.ANALYTICS, "orange"));
        }
        warningsBox.getChildren().clear();
        for (String a : fr.advertencias()) {
            Label l = new Label(a);
            l.getStyleClass().addAll("notice", fr.suficiente() ? "warning" : "danger");
            l.setMaxWidth(Double.MAX_VALUE);
            warningsBox.getChildren().add(l);
        }

        methodChecks.getChildren().clear();
        for (MethodResult m : fr.metodos()) {
            CheckBox cb = new CheckBox(m.metodo());
            cb.setSelected(metodosVisibles.contains(m.metodo()));
            cb.selectedProperty().addListener((o, a, b) -> {
                if (b) {
                    metodosVisibles.add(m.metodo());
                } else {
                    metodosVisibles.remove(m.metodo());
                }
                renderChart(h, importe);
            });
            methodChecks.getChildren().add(cb);
        }
        renderChart(h, importe);
        methodTable.getItems().setAll(fr.metodos());
        List<PeriodoValor> pv = new ArrayList<>();
        if (fr.suficiente()) {
            for (int i = 0; i < h; i++) {
                pv.add(new PeriodoValor(fr.periodosFuturos().get(i), fr.mejor().pronostico()[i]));
            }
            pv.add(new PeriodoValor("Total " + h + (h == 1 ? " mes" : " meses"), fr.demandaProximos(h)));
        }
        forecastTable.getItems().setAll(pv);
    }

    private static String fmt(double v, boolean importe) {
        return importe ? Formatters.moneyShort(v) : Formatters.number(Math.round(v * 10) / 10.0);
    }

    private void renderChart(int h, boolean importe) {
        ForecastResult fr = actual;
        chart.getData().clear();
        int desde = Math.max(0, fr.historia().length - 36);
        XYChart.Series<String, Number> real = new XYChart.Series<>();
        real.setName("Ventas reales");
        for (int i = desde; i < fr.historia().length; i++) {
            real.getData().add(new XYChart.Data<>(Formatters.period(fr.periodos().get(i)), fr.historia()[i]));
        }
        chart.getData().add(real);
        for (MethodResult m : fr.metodos()) {
            if (!metodosVisibles.contains(m.metodo())) {
                continue;
            }
            XYChart.Series<String, Number> s = new XYChart.Series<>();
            s.setName(m.metodo() + (m == fr.mejor() ? " ✓" : ""));
            for (int i = desde; i < m.ajustes().length; i++) {
                if (!Double.isNaN(m.ajustes()[i])) {
                    s.getData().add(new XYChart.Data<>(Formatters.period(fr.periodos().get(i)), m.ajustes()[i]));
                }
            }
            for (int i = 0; i < h; i++) {
                s.getData().add(new XYChart.Data<>(Formatters.period(fr.periodosFuturos().get(i)), m.pronostico()[i]));
            }
            chart.getData().add(s);
        }
        Platform.runLater(() -> Charts.tooltips(chart, n -> importe ? Formatters.money(n.doubleValue())
                : Formatters.number(n.doubleValue()) + " u"));
    }

    @FXML
    private void save() {
        if (actual == null || !actual.suficiente() || itemActual == null || itemActual.producto() == null) {
            return;
        }
        ForecastResult fr = actual;
        long id = itemActual.producto().id();
        shell.run("Guardando predicción…", () -> {
            app.predicciones().replace(id, fr.mejor().metodo(), fr.periodosFuturos(), fr.mejor().pronostico(),
                    fr.mejor().mae(), fr.mejor().mape(), fr.confianza().toString());
            return app.predicciones().findByProducto(id);
        }, g -> {
            savedLabel.setText("Última predicción guardada: " + g.get(0).fechaGeneracion() + " (" + g.get(0).metodo()
                    + ")");
            Dialogs.info("Predicción guardada", "Se guardaron " + g.size() + " meses de pronóstico para "
                    + itemActual.producto().nombre() + " en la base de datos local (método " + fr.mejor().metodo()
                    + ", confianza " + fr.confianza().toString().toLowerCase() + ").");
        });
    }
}
