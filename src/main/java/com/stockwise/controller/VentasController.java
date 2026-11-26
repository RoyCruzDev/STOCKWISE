package com.stockwise.controller;

import com.stockwise.model.Producto;
import com.stockwise.model.Sucursal;
import com.stockwise.model.Venta;
import com.stockwise.service.ValidationException;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Formatters;
import com.stockwise.util.Forms;
import com.stockwise.util.Icons;
import com.stockwise.util.Tables;
import com.stockwise.util.Tables.Kind;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class VentasController extends BaseController {

    private static final int LIMITE = 5000;

    private record Init(LocalDate corte, List<Producto> productos, List<Sucursal> sucursales) {
    }

    @FXML
    private DatePicker fromDate;
    @FXML
    private DatePicker toDate;
    @FXML
    private ComboBox<Sucursal> branchFilter;
    @FXML
    private ComboBox<Producto> productFilter;
    @FXML
    private Button deleteButton;
    @FXML
    private FlowPane kpiPane;
    @FXML
    private TableView<Venta> salesTable;
    @FXML
    private Label limitLabel;

    private List<Producto> productos = List.of();
    private List<Sucursal> sucursales = List.of();

    public VentasController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        Tables.col(salesTable, "Fecha", Venta::fecha, Kind.DATE, 95);
        Tables.col(salesTable, "Código", Venta::productoCodigo, Kind.TEXT, 90);
        Tables.col(salesTable, "Producto", Venta::productoNombre, Kind.TEXT, 240);
        Tables.col(salesTable, "Sucursal", Venta::sucursalCodigo, Kind.TEXT, 90);
        Tables.col(salesTable, "Cantidad", Venta::cantidad, Kind.DECIMAL, 80);
        Tables.col(salesTable, "Precio unitario", Venta::precioUnitario, Kind.MONEY, 110);
        Tables.col(salesTable, "Total", Venta::total, Kind.MONEY, 110);
        Tables.col(salesTable, "Referencia", Venta::referencia, Kind.TEXT, 130);
        Tables.placeholder(salesTable, "No hay ventas en el rango seleccionado.");
        deleteButton.disableProperty().bind(salesTable.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.setGraphic(Icons.DELETE.create(14));

        branchFilter.setConverter(Forms.converter(s -> s == null ? "Todas las sucursales" : s.toString()));
        productFilter.setConverter(Forms.converter(p -> p == null ? "Todos los productos" : p.toString()));
        shell.run("Cargando…", () -> new Init(app.analysis().fechaCorte(), app.productos().listar(),
                app.sucursales().listar()), r -> {
            productos = r.productos();
            sucursales = r.sucursales();
            fromDate.setValue(r.corte().minusMonths(3).withDayOfMonth(1));
            toDate.setValue(r.corte());
            List<Sucursal> opS = new ArrayList<>();
            opS.add(null);
            opS.addAll(sucursales);
            branchFilter.getItems().setAll(opS);
            List<Producto> opP = new ArrayList<>();
            opP.add(null);
            opP.addAll(productos);
            productFilter.getItems().setAll(opP);
            branchFilter.getSelectionModel().selectFirst();
            productFilter.getSelectionModel().selectFirst();
            search();
        });
    }

    @FXML
    private void search() {
        LocalDate d = fromDate.getValue();
        LocalDate h = toDate.getValue();
        if (d != null && h != null && d.isAfter(h)) {
            Dialogs.warning("Rango inválido", "La fecha inicial debe ser anterior a la final.");
            return;
        }
        Long suc = branchFilter.getValue() == null ? null : branchFilter.getValue().id();
        Long prod = productFilter.getValue() == null ? null : productFilter.getValue().id();
        shell.run("Buscando ventas…", () -> app.ventas().buscar(d, h, prod, suc, LIMITE), ventas -> {
            salesTable.getItems().setAll(ventas);
            double unidades = ventas.stream().mapToDouble(Venta::cantidad).sum();
            double total = ventas.stream().mapToDouble(Venta::total).sum();
            kpiPane.getChildren().setAll(
                    DashboardController.kpi("Registros", Formatters.integer(ventas.size()), "Ventas en el filtro",
                            null, Icons.FILE, "blue"),
                    DashboardController.kpi("Unidades vendidas", Formatters.integer(unidades), "", null, Icons.BOX,
                            "blue"),
                    DashboardController.kpi("Importe total", Formatters.moneyShort(total),
                            "Ticket promedio " + Formatters.money(ventas.isEmpty() ? 0 : total / ventas.size()), null,
                            Icons.MONEY, "green"));
            limitLabel.setText(ventas.size() >= LIMITE ? "Se muestran las " + LIMITE
                    + " ventas más recientes del filtro. Acote el rango para ver el resto." : "");
        });
    }

    @FXML
    private void register() {
        if (productos.isEmpty()) {
            Dialogs.info("Sin productos", "Registre o importe productos antes de registrar ventas.");
            return;
        }
        DatePicker fecha = new DatePicker(LocalDate.now());
        ComboBox<Producto> producto = Forms.combo(productos.stream().filter(Producto::activo).toList(),
                Producto::toString);
        ComboBox<Sucursal> sucursal = Forms.combo(sucursales, Sucursal::toString);
        sucursal.getSelectionModel().selectFirst();
        TextField cantidad = Forms.decimalField("Unidades");
        TextField precio = Forms.decimalField("Precio unitario");
        TextField referencia = new TextField();
        referencia.setPromptText("N° de boleta o factura (opcional)");
        CheckBox descontar = new CheckBox("Descontar del inventario de la sucursal");
        descontar.setSelected(true);
        producto.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                precio.setText(Forms.format(b.precioVenta()));
            }
        });
        GridPane g = Forms.grid();
        Forms.row(g, "Fecha *", fecha);
        Forms.row(g, "Producto *", producto);
        Forms.row(g, "Sucursal *", sucursal);
        Forms.row(g, "Cantidad *", cantidad);
        Forms.row(g, "Precio unitario *", precio);
        Forms.row(g, "Referencia", referencia);
        Forms.row(g, "", descontar);
        boolean ok = Dialogs.form("Registrar venta", g, "Registrar", () -> {
            if (producto.getValue() == null || sucursal.getValue() == null) {
                throw new ValidationException("Seleccione el producto y la sucursal.");
            }
            app.ventas().registrar(fecha.getValue(), producto.getValue().id(), sucursal.getValue().id(),
                    Forms.decimal(cantidad, "Cantidad", true, 0), Forms.decimal(precio, "Precio", true, 0),
                    referencia.getText().isBlank() ? null : referencia.getText().trim(), descontar.isSelected());
        });
        if (ok) {
            shell.status("Venta registrada");
            search();
        }
    }

    @FXML
    private void delete() {
        Venta v = salesTable.getSelectionModel().getSelectedItem();
        if (v == null) {
            return;
        }
        if (Dialogs.confirm("¿Eliminar la venta seleccionada?", Formatters.date(v.fecha()) + " · " + v.productoNombre()
                + " · " + Formatters.number(v.cantidad()) + " u · " + Formatters.money(v.total())
                + "\n\nSe eliminará del historial (afecta análisis y predicciones). El inventario no se modifica; "
                + "si corresponde, regístrelo con un ajuste de stock.")) {
            shell.run("Eliminando…", () -> {
                app.ventas().eliminar(v.id());
                return true;
            }, r -> {
                shell.status("Venta eliminada");
                search();
            });
        }
    }
}
