package com.stockwise.controller;

import com.stockwise.model.InventarioItem;
import com.stockwise.model.Sucursal;
import com.stockwise.recommendation.TransferRule;
import com.stockwise.service.SucursalService.RendimientoSucursal;
import com.stockwise.util.Charts;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Formatters;
import com.stockwise.util.Forms;
import com.stockwise.util.Tables;
import com.stockwise.util.Tables.Kind;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import java.time.LocalDate;

public class SucursalesController extends BaseController {

    @FXML
    private Button editButton;
    @FXML
    private Button deleteButton;
    @FXML
    private TableView<Sucursal> branchTable;
    @FXML
    private Label inventoryTitle;
    @FXML
    private Label inventorySummary;
    @FXML
    private TableView<InventarioItem> inventoryTable;
    @FXML
    private DatePicker fromDate;
    @FXML
    private DatePicker toDate;
    @FXML
    private BarChart<String, Number> compareChart;
    @FXML
    private TableView<RendimientoSucursal> compareTable;
    @FXML
    private Button transferButton;
    @FXML
    private TableView<TransferRule.Traslado> transferTable;

    public SucursalesController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        Tables.col(branchTable, "Código", Sucursal::codigo, Kind.TEXT, 90);
        Tables.col(branchTable, "Nombre", Sucursal::nombre, Kind.TEXT, 170);
        Tables.col(branchTable, "Ciudad", Sucursal::ciudad, Kind.TEXT, 110);
        Tables.col(branchTable, "Dirección", Sucursal::direccion, Kind.TEXT, 170);
        Tables.badge(branchTable, "Estado", s -> s.activa() ? "Activa" : "Inactiva",
                java.util.Map.of("Activa", "success", "Inactiva", "neutral"), 80);
        editButton.disableProperty().bind(branchTable.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.disableProperty().bind(branchTable.getSelectionModel().selectedItemProperty().isNull());
        branchTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> inventario(b));

        Tables.col(inventoryTable, "Código", InventarioItem::productoCodigo, Kind.TEXT, 90);
        Tables.col(inventoryTable, "Producto", InventarioItem::productoNombre, Kind.TEXT, 200);
        Tables.col(inventoryTable, "Stock", InventarioItem::stock, Kind.INT, 70);
        Tables.col(inventoryTable, "Valor (costo)", InventarioItem::valorCosto, Kind.MONEY, 110);
        Tables.placeholder(inventoryTable, "Seleccione una sucursal");

        Tables.col(compareTable, "Sucursal", r -> r.sucursal().toString(), Kind.TEXT, 200);
        Tables.col(compareTable, "Unidades", RendimientoSucursal::unidades, Kind.INT, 90);
        Tables.col(compareTable, "Ventas", RendimientoSucursal::ventas, Kind.MONEY, 120);
        Tables.col(compareTable, "Utilidad bruta", RendimientoSucursal::utilidad, Kind.MONEY, 120);
        Tables.col(compareTable, "Margen", RendimientoSucursal::margen, Kind.PERCENT, 80);
        Tables.col(compareTable, "Participación", RendimientoSucursal::participacion, Kind.PERCENT, 100);
        Tables.col(compareTable, "Inventario (costo)", RendimientoSucursal::valorInventario, Kind.MONEY, 130);
        Tables.col(compareTable, "Unid. en stock", RendimientoSucursal::unidadesInventario, Kind.INT, 100);
        Tables.col(compareTable, "Qué tan rápido se vende (veces al año)", RendimientoSucursal::rotacion, Kind.DECIMAL, 105);

        Tables.col(transferTable, "Producto", t -> t.metrics().producto().toString(), Kind.TEXT, 230);
        Tables.col(transferTable, "Origen", t -> t.origen().codigo(), Kind.TEXT, 80);
        Tables.col(transferTable, "Stock origen", TransferRule.Traslado::stockOrigen, Kind.INT, 90);
        Tables.col(transferTable, "Demanda origen/mes", TransferRule.Traslado::demandaOrigen, Kind.DECIMAL, 130);
        Tables.col(transferTable, "Destino", t -> t.destino().codigo(), Kind.TEXT, 80);
        Tables.col(transferTable, "Stock destino", TransferRule.Traslado::stockDestino, Kind.INT, 95);
        Tables.col(transferTable, "Demanda destino/mes", TransferRule.Traslado::demandaDestino, Kind.DECIMAL, 135);
        Tables.col(transferTable, "Cantidad sugerida", TransferRule.Traslado::cantidad, Kind.INT, 120);
        Tables.col(transferTable, "Compra evitada", t -> t.cantidad() * t.metrics().producto().costo(), Kind.MONEY, 120);
        Tables.placeholder(transferTable, "No se detectaron desequilibrios de inventario entre sucursales.");
        transferButton.disableProperty().bind(transferTable.getSelectionModel().selectedItemProperty().isNull());

        cargarSucursales();
        shell.run("Preparando comparación…", () -> app.analysis().fechaCorte(), corte -> {
            fromDate.setValue(corte.minusMonths(12).plusDays(1));
            toDate.setValue(corte);
            comparar();
            calcularTraslados();
        });
    }

    private void cargarSucursales() {
        shell.run("Cargando sucursales…", () -> app.sucursales().listar(), l -> {
            branchTable.getItems().setAll(l);
            if (!l.isEmpty()) {
                branchTable.getSelectionModel().selectFirst();
            }
        });
    }

    private void inventario(Sucursal s) {
        inventoryTable.getItems().clear();
        if (s == null) {
            inventoryTitle.setText("Inventario de la sucursal");
            inventorySummary.setText("");
            return;
        }
        inventoryTitle.setText("Inventario de " + s.nombre());
        shell.run("Cargando inventario…", () -> app.inventario().listar(s.id()), items -> {
            inventoryTable.getItems().setAll(items);
            inventorySummary.setText(items.size() + " productos · " + Formatters.integer(items.stream()
                    .mapToInt(InventarioItem::stock).sum()) + " unidades · " + Formatters.money(items.stream()
                    .mapToDouble(InventarioItem::valorCosto).sum()));
        });
    }

    @FXML
    private void nueva() {
        formulario(null);
    }

    @FXML
    private void editar() {
        Sucursal s = branchTable.getSelectionModel().getSelectedItem();
        if (s != null) {
            formulario(s);
        }
    }

    private void formulario(Sucursal s) {
        TextField codigo = new TextField(s == null ? "" : s.codigo());
        TextField nombre = new TextField(s == null ? "" : s.nombre());
        TextField ciudad = new TextField(s == null ? "" : s.ciudad());
        TextField direccion = new TextField(s == null ? "" : s.direccion());
        CheckBox activa = new CheckBox("Sucursal activa");
        activa.setSelected(s == null || s.activa());
        GridPane g = Forms.grid();
        Forms.row(g, "Código *", codigo);
        Forms.row(g, "Nombre *", nombre);
        Forms.row(g, "Ciudad", ciudad);
        Forms.row(g, "Dirección", direccion);
        Forms.row(g, "", activa);
        if (Dialogs.form(s == null ? "Nueva sucursal" : "Editar sucursal " + s.codigo(), g, "Guardar", () ->
                app.sucursales().guardar(new Sucursal(s == null ? 0 : s.id(), codigo.getText(), nombre.getText(),
                        ciudad.getText(), direccion.getText(), activa.isSelected())))) {
            shell.status("Sucursal guardada");
            cargarSucursales();
        }
    }

    @FXML
    private void eliminar() {
        Sucursal s = branchTable.getSelectionModel().getSelectedItem();
        if (s != null && Dialogs.confirm("¿Eliminar la sucursal " + s.codigo() + "?",
                "Se eliminarán su inventario, ventas, compras y movimientos. Esta acción no se puede deshacer.\n\n"
                        + "Si la sucursal cerró pero quiere conservar su historial, edítela y desmárquela como activa.")) {
            shell.run("Eliminando…", () -> {
                app.sucursales().eliminar(s.id());
                return true;
            }, r -> cargarSucursales());
        }
    }

    @FXML
    private void comparar() {
        LocalDate d = fromDate.getValue();
        LocalDate h = toDate.getValue();
        if (d == null || h == null || d.isAfter(h)) {
            Dialogs.warning("Rango inválido", "Seleccione un rango de fechas válido.");
            return;
        }
        shell.run("Comparando sucursales…", () -> app.sucursales().comparar(d, h), lista -> {
            compareTable.getItems().setAll(lista);
            compareChart.getData().clear();
            XYChart.Series<String, Number> ventas = new XYChart.Series<>();
            ventas.setName("Ventas");
            XYChart.Series<String, Number> utilidad = new XYChart.Series<>();
            utilidad.setName("Utilidad bruta");
            for (RendimientoSucursal r : lista) {
                ventas.getData().add(new XYChart.Data<>(r.sucursal().codigo(), r.ventas()));
                utilidad.getData().add(new XYChart.Data<>(r.sucursal().codigo(), r.utilidad()));
            }
            compareChart.getData().add(ventas);
            compareChart.getData().add(utilidad);
            Platform.runLater(() -> Charts.tooltips(compareChart, n -> Formatters.money(n.doubleValue())));
        });
    }

    @FXML
    private void calcularTraslados() {
        shell.run("Analizando inventario entre sucursales…",
                () -> new TransferRule().calcular(app.analysis().snapshot()),
                l -> transferTable.getItems().setAll(l));
    }

    @FXML
    private void ejecutarTraslado() {
        TransferRule.Traslado t = transferTable.getSelectionModel().getSelectedItem();
        if (t == null) {
            return;
        }
        TextField cantidad = Forms.integerField("Unidades");
        cantidad.setText(String.valueOf(t.cantidad()));
        GridPane g = Forms.grid();
        Forms.row(g, "Producto", new Label(t.metrics().producto().toString()));
        Forms.row(g, "Origen → destino", new Label(t.origen().codigo() + " → " + t.destino().codigo()));
        Forms.row(g, "Cantidad *", cantidad);
        if (Dialogs.form("Ejecutar traslado", g, "Trasladar", () -> app.inventario().trasladar(
                t.metrics().productoId(), t.origen().id(), t.destino().id(),
                Forms.integer(cantidad, "Cantidad", true, 0), "Traslado sugerido por análisis de sucursales"))) {
            shell.status("Traslado ejecutado");
            calcularTraslados();
            inventario(branchTable.getSelectionModel().getSelectedItem());
        }
    }
}
