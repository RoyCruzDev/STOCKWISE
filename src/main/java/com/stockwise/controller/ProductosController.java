package com.stockwise.controller;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.Categoria;
import com.stockwise.model.InventarioItem;
import com.stockwise.model.Producto;
import com.stockwise.model.Proveedor;
import com.stockwise.model.Venta;
import com.stockwise.repository.VentaRepository;
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
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;

import java.util.List;
import java.util.Map;

public class ProductosController extends BaseController {

    private static final String TODAS = "Todas las categorías";

    /** Fila de la tabla: producto + métricas (nulas si el producto está inactivo). */
    public record Fila(Producto p, ProductMetrics m) {
    }

    private record Detalle(List<VentaRepository.MonthlyTotal> mensual, List<Venta> ventas,
                           List<InventarioItem> inventario) {
    }

    @FXML
    private TextField searchField;
    @FXML
    private ComboBox<String> categoryFilter;
    @FXML
    private Label countLabel;
    @FXML
    private Button newButton;
    @FXML
    private Button editButton;
    @FXML
    private Button demandButton;
    @FXML
    private Button deleteButton;
    @FXML
    private Button refreshButton;
    @FXML
    private TableView<Fila> productTable;
    @FXML
    private Label detailTitle;
    @FXML
    private Label detailSubtitle;
    @FXML
    private FlowPane detailKpis;
    @FXML
    private LineChart<String, Number> historyChart;
    @FXML
    private TableView<Venta> salesTable;
    @FXML
    private TableView<InventarioItem> stockTable;

    private List<Fila> todas = List.of();

    public ProductosController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        newButton.setGraphic(Icons.ADD.create(14));
        editButton.setGraphic(Icons.EDIT.create(14));
        deleteButton.setGraphic(Icons.DELETE.create(14));
        refreshButton.setGraphic(Icons.REFRESH.create(14));

        Tables.col(productTable, "Código", f -> f.p().codigo(), Kind.TEXT, 90);
        Tables.col(productTable, "Producto", f -> f.p().nombre(), Kind.TEXT, 200);
        Tables.col(productTable, "Categoría", f -> f.p().categoria(), Kind.TEXT, 120);
        Tables.col(productTable, "Proveedor", f -> f.p().proveedor(), Kind.TEXT, 110);
        Tables.col(productTable, "Precio", f -> f.p().precioVenta(), Kind.MONEY, 95);
        Tables.col(productTable, "Costo", f -> f.p().costo(), Kind.MONEY, 95);
        Tables.col(productTable, "Margen", f -> f.p().margenPorcentual(), Kind.PERCENT, 70);
        Tables.col(productTable, "Stock", f -> f.m() == null ? null : f.m().stock(), Kind.INT, 65);
        Tables.col(productTable, "Mín.", f -> f.p().stockMinimo(), Kind.INT, 55);
        Tables.col(productTable, "Máx.", f -> f.p().stockMaximo(), Kind.INT, 55);
        Tables.badge(productTable, "Estado", f -> f.m() == null ? "Inactivo" : f.m().estado().etiqueta(),
                Tables.ESTADOS, 135);
        Tables.placeholder(productTable, "No hay productos. Regístrelos aquí o impórtelos desde Excel.");
        productTable.setRowFactory(tv -> {
            TableRow<Fila> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    editar();
                }
            });
            return row;
        });
        productTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> mostrarDetalle(b));
        editButton.disableProperty().bind(productTable.getSelectionModel().selectedItemProperty().isNull());
        demandButton.disableProperty().bind(productTable.getSelectionModel().selectedItemProperty().isNull());
        demandButton.setGraphic(Icons.TREND.create(14));
        deleteButton.disableProperty().bind(productTable.getSelectionModel().selectedItemProperty().isNull());

        Tables.col(salesTable, "Fecha", Venta::fecha, Kind.DATE, 90);
        Tables.col(salesTable, "Sucursal", Venta::sucursalCodigo, Kind.TEXT, 80);
        Tables.col(salesTable, "Cantidad", Venta::cantidad, Kind.DECIMAL, 70);
        Tables.col(salesTable, "Precio", Venta::precioUnitario, Kind.MONEY, 90);
        Tables.col(salesTable, "Total", Venta::total, Kind.MONEY, 100);
        Tables.placeholder(salesTable, "Sin ventas registradas");

        Tables.col(stockTable, "Sucursal", i -> i.sucursalCodigo() + " - " + i.sucursalNombre(), Kind.TEXT, 200);
        Tables.col(stockTable, "Stock", InventarioItem::stock, Kind.INT, 80);
        Tables.col(stockTable, "Valor (costo)", InventarioItem::valorCosto, Kind.MONEY, 120);
        Tables.col(stockTable, "Actualizado", InventarioItem::fechaActualizacion, Kind.TEXT, 150);
        Tables.placeholder(stockTable, "Sin inventario registrado");

        searchField.textProperty().addListener((o, a, b) -> filtrar());
        categoryFilter.valueProperty().addListener((o, a, b) -> filtrar());
        refresh();
    }

    @FXML
    private void refresh() {
        shell.run("Cargando productos…", () -> {
            AnalysisSnapshot s = app.analysis().snapshot();
            Map<Long, ProductMetrics> m = s.porId();
            return app.productos().listar().stream().map(p -> new Fila(p, m.get(p.id()))).toList();
        }, filas -> {
            todas = filas;
            String sel = categoryFilter.getValue();
            categoryFilter.getItems().setAll(TODAS);
            categoryFilter.getItems().addAll(app.productos().categorias().stream().map(Categoria::nombre).toList());
            categoryFilter.setValue(sel != null && categoryFilter.getItems().contains(sel) ? sel : TODAS);
            filtrar();
        });
    }

    private void filtrar() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        String cat = categoryFilter.getValue();
        List<Fila> filtradas = todas.stream()
                .filter(f -> q.isEmpty() || f.p().codigo().toLowerCase().contains(q)
                        || f.p().nombre().toLowerCase().contains(q))
                .filter(f -> cat == null || TODAS.equals(cat) || cat.equals(f.p().categoria()))
                .toList();
        productTable.getItems().setAll(filtradas);
        countLabel.setText(filtradas.size() + " de " + todas.size() + " productos");
    }

    private void mostrarDetalle(Fila f) {
        detailKpis.getChildren().clear();
        historyChart.getData().clear();
        salesTable.getItems().clear();
        stockTable.getItems().clear();
        if (f == null) {
            detailTitle.setText("Seleccione un producto");
            detailSubtitle.setText("");
            historyChart.setVisible(false);
            historyChart.setManaged(false);
            return;
        }
        Producto p = f.p();
        detailTitle.setText(p.codigo() + " · " + p.nombre());
        detailSubtitle.setText((p.categoria() == null ? "Sin categoría" : p.categoria()) + " · Proveedor: "
                + (p.proveedor() == null ? "—" : p.proveedor()) + " · Reposición: "
                + (p.leadTimeDias() == null ? "por defecto" : p.leadTimeDias() + " días"));
        detailKpis.getChildren().add(mini("Precio / costo", Formatters.money(p.precioVenta()) + " / "
                + Formatters.money(p.costo())));
        detailKpis.getChildren().add(mini("Margen", Formatters.percent(p.margenPorcentual())));
        ProductMetrics m = f.m();
        boolean inventario = app.hayInventario();
        if (m != null && !inventario) {
            detailKpis.getChildren().add(mini("Podrías vender", Formatters.number(m.demandaMensual()) + " u/mes"
                    + (m.demandaConfiable() ? "" : " *")));
            detailKpis.getChildren().add(mini("Stock", "Pendiente: importe su inventario"));
        } else if (m != null) {
            detailKpis.getChildren().add(mini("Stock total", Formatters.integer(m.stock()) + " u"));
            detailKpis.getChildren().add(mini("Demanda estimada", Formatters.number(m.demandaMensual()) + " u/mes"
                    + (m.demandaConfiable() ? "" : " *")));
            detailKpis.getChildren().add(mini("Días de stock", Double.isInfinite(m.coberturaDias()) ? "Sin demanda"
                    : Formatters.integer(m.coberturaDias()) + " días"));
            detailKpis.getChildren().add(mini("Qué tan rápido se vende (veces al año)", m.rotacionAnual() == null ? "—"
                    : Formatters.number(m.rotacionAnual()) + " veces"));
        }
        long id = p.id();
        shell.run("Cargando historial…", () -> new Detalle(app.ventaRepository().mensual(id, null),
                app.ventas().buscar(null, null, id, null, 200), app.inventario().porProducto(id)), d -> {
            XYChart.Series<String, Number> s = new XYChart.Series<>();
            s.setName("Unidades vendidas");
            List<VentaRepository.MonthlyTotal> ult = d.mensual().subList(Math.max(0, d.mensual().size() - 24),
                    d.mensual().size());
            for (VentaRepository.MonthlyTotal mt : ult) {
                s.getData().add(new XYChart.Data<>(Formatters.period(mt.periodo()), mt.unidades()));
            }
            historyChart.getData().add(s);
            historyChart.setVisible(!ult.isEmpty());
            historyChart.setManaged(!ult.isEmpty());
            Platform.runLater(() -> Charts.tooltips(historyChart, n -> Formatters.number(n.doubleValue()) + " u"));
            salesTable.getItems().setAll(d.ventas());
            stockTable.getItems().setAll(d.inventario());
        });
    }

    private static javafx.scene.layout.VBox mini(String titulo, String valor) {
        Label t = new Label(titulo);
        t.getStyleClass().add("kpi-title");
        Label v = new Label(valor);
        v.getStyleClass().add("strong");
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(2, t, v);
        box.getStyleClass().add("kpi-card");
        box.setMinWidth(150);
        box.setPrefWidth(160);
        return box;
    }

    @FXML
    private void nuevo() {
        formulario(null);
    }

    /** Abre la predicción del producto en Demanda › Resultados (último análisis). */
    @FXML
    private void verDemanda() {
        Fila f = productTable.getSelectionModel().getSelectedItem();
        if (f != null) {
            shell.navigate(View.RESULTADOS, new ResultadosController.VerProducto(f.p().id()));
        }
    }

    @FXML
    private void editar() {
        Fila f = productTable.getSelectionModel().getSelectedItem();
        if (f != null) {
            formulario(f.p());
        }
    }

    private void formulario(Producto p) {
        TextField codigo = new TextField(p == null ? "" : p.codigo());
        TextField nombre = new TextField(p == null ? "" : p.nombre());
        ComboBox<String> categoria = new ComboBox<>();
        categoria.setEditable(true);
        categoria.getItems().setAll(app.productos().categorias().stream().map(Categoria::nombre).toList());
        categoria.setValue(p == null ? "" : p.categoria());
        ComboBox<String> proveedor = new ComboBox<>();
        proveedor.setEditable(true);
        proveedor.getItems().setAll(app.productos().proveedores().stream().map(Proveedor::nombre).toList());
        proveedor.setValue(p == null ? "" : p.proveedor());
        TextField precio = Forms.decimalField("0.00");
        TextField costo = Forms.decimalField("0.00");
        TextField min = Forms.integerField("0");
        TextField max = Forms.integerField("0 = sin máximo");
        TextField lead = Forms.integerField("Vacío = valor por defecto");
        CheckBox activo = new CheckBox("Producto activo (se incluye en análisis y compras)");
        if (p != null) {
            precio.setText(Forms.format(p.precioVenta()));
            costo.setText(Forms.format(p.costo()));
            min.setText(String.valueOf(p.stockMinimo()));
            max.setText(String.valueOf(p.stockMaximo()));
            lead.setText(p.leadTimeDias() == null ? "" : String.valueOf(p.leadTimeDias()));
        }
        activo.setSelected(p == null || p.activo());

        GridPane g = Forms.grid();
        Forms.row(g, "Código *", codigo);
        Forms.row(g, "Nombre *", nombre);
        Forms.row(g, "Categoría", categoria);
        Forms.row(g, "Proveedor", proveedor);
        Forms.row(g, "Precio de venta *", precio);
        Forms.row(g, "Costo *", costo);
        Forms.row(g, "Stock mínimo", min);
        Forms.row(g, "Stock máximo", max);
        Forms.row(g, "Reposición (días)", lead);
        Forms.row(g, "", activo);

        boolean ok = Dialogs.form(p == null ? "Nuevo producto" : "Editar producto " + p.codigo(), g, "Guardar", () -> {
            Producto nuevo = new Producto(p == null ? 0 : p.id(), codigo.getText(), nombre.getText(), null, null,
                    null, null, Forms.decimal(precio, "Precio de venta", true, 0),
                    Forms.decimal(costo, "Costo", true, 0), Forms.integer(min, "Stock mínimo", false, 0),
                    Forms.integer(max, "Stock máximo", false, 0), Forms.integer(lead, "Reposición", false, null),
                    activo.isSelected());
            app.productos().guardar(nuevo, categoria.getEditor().getText(), proveedor.getEditor().getText());
        });
        if (ok) {
            shell.status("Producto guardado: " + codigo.getText());
            refresh();
        }
    }

    @FXML
    private void eliminar() {
        Fila f = productTable.getSelectionModel().getSelectedItem();
        if (f == null) {
            return;
        }
        if (Dialogs.confirm("¿Eliminar el producto " + f.p().codigo() + "?",
                "Se eliminarán también su inventario, ventas, compras, movimientos y predicciones asociadas. "
                        + "Esta acción no se puede deshacer.\n\nSi solo desea dejar de analizarlo, edítelo y "
                        + "desmarque 'Producto activo'.")) {
            shell.run("Eliminando…", () -> {
                app.productos().eliminar(f.p().id());
                return true;
            }, r -> {
                shell.status("Producto eliminado: " + f.p().codigo());
                refresh();
            });
        }
    }
}
