package com.stockwise.controller;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.EstadoInventario;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.InventarioItem;
import com.stockwise.model.Movimiento;
import com.stockwise.model.Producto;
import com.stockwise.model.Sucursal;
import com.stockwise.model.TipoMovimiento;
import com.stockwise.service.ValidationException;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Formatters;
import com.stockwise.util.Forms;
import com.stockwise.util.Icons;
import com.stockwise.util.Tables;
import com.stockwise.util.Tables.Kind;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;

import java.util.List;

public class InventarioController extends BaseController {

    private static final String TODOS = "Todos los estados";

    private record Datos(AnalysisSnapshot snapshot, List<Movimiento> movimientos, List<Sucursal> sucursales,
                         List<Producto> productos) {
    }

    @FXML
    private FlowPane kpiPane;
    @FXML
    private ComboBox<Sucursal> branchFilter;
    @FXML
    private TextField searchField;
    @FXML
    private TableView<InventarioItem> stockTable;
    @FXML
    private ComboBox<String> stateFilter;
    @FXML
    private TableView<ProductMetrics> alertTable;
    @FXML
    private TableView<Movimiento> movementTable;

    private Datos datos;
    private final Sucursal todas = new Sucursal(-1, "*", "Todas las sucursales", "", "", true);

    public InventarioController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        branchFilter.setConverter(Forms.converter(s -> s == null ? "" : s.id() < 0 ? s.nombre() : s.toString()));
        Tables.col(stockTable, "Código", InventarioItem::productoCodigo, Kind.TEXT, 90);
        Tables.col(stockTable, "Producto", InventarioItem::productoNombre, Kind.TEXT, 220);
        Tables.col(stockTable, "Sucursal", InventarioItem::sucursalCodigo, Kind.TEXT, 90);
        Tables.col(stockTable, "Stock", InventarioItem::stock, Kind.INT, 70);
        Tables.col(stockTable, "Mín. (producto)", InventarioItem::stockMinimo, Kind.INT, 100);
        Tables.col(stockTable, "Máx. (producto)", InventarioItem::stockMaximo, Kind.INT, 100);
        Tables.col(stockTable, "Costo", InventarioItem::costo, Kind.MONEY, 90);
        Tables.col(stockTable, "Valor (costo)", InventarioItem::valorCosto, Kind.MONEY, 110);
        Tables.col(stockTable, "Valor (venta)", InventarioItem::valorVenta, Kind.MONEY, 110);
        Tables.col(stockTable, "Actualizado", InventarioItem::fechaActualizacion, Kind.TEXT, 140);
        Tables.placeholder(stockTable, "Sin inventario. Importe existencias o registre entradas.");

        Tables.col(alertTable, "Código", m -> m.producto().codigo(), Kind.TEXT, 90);
        Tables.col(alertTable, "Producto", m -> m.producto().nombre(), Kind.TEXT, 210);
        Tables.badge(alertTable, "Estado", m -> m.estado().etiqueta(), Tables.ESTADOS, 135);
        Tables.col(alertTable, "Stock total", ProductMetrics::stock, Kind.INT, 80);
        Tables.col(alertTable, "Mínimo", ProductMetrics::stockMinimo, Kind.INT, 70);
        Tables.col(alertTable, "Máximo", ProductMetrics::stockMaximo, Kind.INT, 70);
        Tables.col(alertTable, "Demanda/mes", ProductMetrics::demandaMensual, Kind.DECIMAL, 95);
        Tables.col(alertTable, "Días de stock", ProductMetrics::coberturaDias, Kind.DECIMAL, 110);
        Tables.col(alertTable, "Reposición (días)", ProductMetrics::leadTimeDias, Kind.INT, 110);
        Tables.col(alertTable, "Qué tan rápido se vende (veces al año)", ProductMetrics::rotacionAnual, Kind.DECIMAL, 100);
        Tables.col(alertTable, "Valor", ProductMetrics::valorInventario, Kind.MONEY, 110);
        stateFilter.getItems().add(TODOS);
        for (EstadoInventario e : EstadoInventario.values()) {
            stateFilter.getItems().add(e.etiqueta());
        }
        stateFilter.setValue(TODOS);

        Tables.col(movementTable, "Fecha", Movimiento::fecha, Kind.TEXT, 140);
        Tables.col(movementTable, "Código", Movimiento::productoCodigo, Kind.TEXT, 90);
        Tables.col(movementTable, "Producto", Movimiento::productoNombre, Kind.TEXT, 200);
        Tables.col(movementTable, "Sucursal", Movimiento::sucursalCodigo, Kind.TEXT, 80);
        Tables.col(movementTable, "Tipo", m -> m.tipo().etiqueta(), Kind.TEXT, 130);
        Tables.col(movementTable, "Cantidad", Movimiento::cantidad, Kind.INT, 80);
        Tables.col(movementTable, "Stock resultante", Movimiento::stockResultante, Kind.INT, 110);
        Tables.col(movementTable, "Nota", Movimiento::nota, Kind.TEXT, 300);
        Tables.placeholder(movementTable, "Sin movimientos registrados");

        branchFilter.valueProperty().addListener((o, a, b) -> filtrar());
        searchField.textProperty().addListener((o, a, b) -> filtrar());
        stateFilter.valueProperty().addListener((o, a, b) -> filtrar());
        refresh();
    }

    @FXML
    private void refresh() {
        shell.run("Cargando inventario…", () -> new Datos(app.analysis().snapshot(),
                app.inventario().movimientos(null, null, 1000), app.sucursales().listar(),
                app.productos().listarActivos()), d -> {
            datos = d;
            Sucursal sel = branchFilter.getValue();
            branchFilter.getItems().setAll(todas);
            branchFilter.getItems().addAll(d.sucursales());
            branchFilter.setValue(sel == null ? todas : d.sucursales().stream().filter(s -> s.id() == sel.id())
                    .findFirst().orElse(todas));
            renderKpis();
            filtrar();
        });
    }

    private void renderKpis() {
        AnalysisSnapshot s = datos.snapshot();
        if (!app.hayInventario()) {
            // Sin existencias registradas el stock es desconocido: no se muestran ceros ni "agotados" inventados.
            Label aviso = new Label("Aún no hay inventario registrado. Importe sus existencias (Importar Excel / CSV) o "
                    + "registre entradas para ver el stock, lo que falta y lo que sobra.");
            aviso.getStyleClass().addAll("notice", "warning");
            aviso.setWrapText(true);
            aviso.setMaxWidth(Double.MAX_VALUE);
            kpiPane.getChildren().setAll(aviso);
            return;
        }
        kpiPane.getChildren().setAll(
                DashboardController.kpi("Valor del inventario", Formatters.moneyShort(
                                s.productos().stream().mapToDouble(ProductMetrics::valorInventario).sum()),
                        "A costo de adquisición", null, Icons.MONEY, "blue"),
                DashboardController.kpi("Unidades en stock", Formatters.integer(
                                s.productos().stream().mapToLong(ProductMetrics::stock).sum()),
                        s.productos().size() + " productos activos", null, Icons.BOX, "blue"),
                DashboardController.kpi("Agotados / en riesgo", s.conEstado(EstadoInventario.AGOTADO).size() + " / "
                                + s.conEstado(EstadoInventario.RIESGO_AGOTAMIENTO).size(),
                        "Requieren reposición", null, Icons.WARNING, "red"),
                DashboardController.kpi("Exceso de stock", String.valueOf(s.conEstado(EstadoInventario.SOBREINVENTARIO)
                        .size()), "Stock para más de " + Formatters.number(s.settings().mesesSobreinventario())
                        + " meses o sobre el máximo", null, Icons.INVENTORY, "orange"),
                DashboardController.kpi("Sin movimiento", String.valueOf(s.conEstado(EstadoInventario.SIN_MOVIMIENTO)
                        .size()), "Sin ventas en " + s.settings().diasSinMovimiento() + " días", null, Icons.PRODUCTS,
                        "orange"));
    }

    private void filtrar() {
        if (datos == null) {
            return;
        }
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        Sucursal suc = branchFilter.getValue();
        stockTable.getItems().setAll(datos.snapshot().inventario().stream()
                .filter(i -> suc == null || suc.id() < 0 || i.sucursalId() == suc.id())
                .filter(i -> q.isEmpty() || i.productoCodigo().toLowerCase().contains(q)
                        || i.productoNombre().toLowerCase().contains(q)).toList());
        String estado = stateFilter.getValue();
        alertTable.getItems().setAll(datos.snapshot().productos().stream()
                .filter(m -> estado == null || TODOS.equals(estado) || m.estado().etiqueta().equals(estado))
                .filter(m -> q.isEmpty() || m.producto().codigo().toLowerCase().contains(q)
                        || m.producto().nombre().toLowerCase().contains(q)).toList());
        movementTable.getItems().setAll(datos.movimientos().stream()
                .filter(m -> suc == null || suc.id() < 0 || m.sucursalId() == suc.id())
                .filter(m -> q.isEmpty() || m.productoCodigo().toLowerCase().contains(q)
                        || m.productoNombre().toLowerCase().contains(q)).toList());
    }

    private enum Operacion { AJUSTE, ENTRADA, SALIDA }

    @FXML
    private void ajustar() {
        movimiento(Operacion.AJUSTE);
    }

    @FXML
    private void entrada() {
        movimiento(Operacion.ENTRADA);
    }

    @FXML
    private void salida() {
        movimiento(Operacion.SALIDA);
    }

    private ComboBox<Producto> comboProductos() {
        ComboBox<Producto> cb = Forms.combo(datos.productos(), Producto::toString);
        InventarioItem sel = stockTable.getSelectionModel().getSelectedItem();
        if (sel != null) {
            datos.productos().stream().filter(p -> p.id() == sel.productoId()).findFirst().ifPresent(cb::setValue);
        }
        return cb;
    }

    private ComboBox<Sucursal> comboSucursales() {
        ComboBox<Sucursal> cb = Forms.combo(datos.sucursales(), Sucursal::toString);
        InventarioItem sel = stockTable.getSelectionModel().getSelectedItem();
        Sucursal filtro = branchFilter.getValue();
        long id = sel != null ? sel.sucursalId() : filtro != null && filtro.id() > 0 ? filtro.id() : -1;
        datos.sucursales().stream().filter(s -> s.id() == id).findFirst().ifPresentOrElse(cb::setValue,
                () -> cb.getSelectionModel().selectFirst());
        return cb;
    }

    private void movimiento(Operacion op) {
        if (datos == null) {
            return;
        }
        ComboBox<Producto> producto = comboProductos();
        ComboBox<Sucursal> sucursal = comboSucursales();
        TextField cantidad = Forms.integerField(op == Operacion.AJUSTE ? "Stock contado" : "Unidades");
        TextField nota = new TextField();
        Label actual = new Label();
        actual.getStyleClass().add("muted");
        Runnable mostrarActual = () -> {
            if (producto.getValue() != null && sucursal.getValue() != null) {
                actual.setText("Stock actual: " + app.inventario().stock(producto.getValue().id(),
                        sucursal.getValue().id()) + " u");
            }
        };
        producto.valueProperty().addListener((o, a, b) -> mostrarActual.run());
        sucursal.valueProperty().addListener((o, a, b) -> mostrarActual.run());
        mostrarActual.run();
        GridPane g = Forms.grid();
        Forms.row(g, "Producto *", producto);
        Forms.row(g, "Sucursal *", sucursal);
        Forms.row(g, "", actual);
        Forms.row(g, op == Operacion.AJUSTE ? "Nuevo stock *" : "Cantidad *", cantidad);
        Forms.row(g, "Nota / motivo", nota);
        String titulo = switch (op) {
            case AJUSTE -> "Ajuste de stock (conteo físico)";
            case ENTRADA -> "Entrada de mercadería";
            case SALIDA -> "Salida de mercadería (merma, consumo, devolución)";
        };
        boolean ok = Dialogs.form(titulo, g, "Registrar", () -> {
            if (producto.getValue() == null || sucursal.getValue() == null) {
                throw new ValidationException("Seleccione el producto y la sucursal.");
            }
            int n = Forms.integer(cantidad, "Cantidad", true, 0);
            long pid = producto.getValue().id();
            long sid = sucursal.getValue().id();
            switch (op) {
                case AJUSTE -> app.inventario().fijarStock(pid, sid, n, TipoMovimiento.AJUSTE, nota.getText());
                case ENTRADA -> {
                    if (n <= 0) {
                        throw new ValidationException("La cantidad debe ser mayor que cero.");
                    }
                    app.inventario().mover(pid, sid, n, TipoMovimiento.ENTRADA, nota.getText());
                }
                case SALIDA -> {
                    if (n <= 0) {
                        throw new ValidationException("La cantidad debe ser mayor que cero.");
                    }
                    app.inventario().mover(pid, sid, -n, TipoMovimiento.SALIDA, nota.getText());
                }
            }
        });
        if (ok) {
            shell.status("Movimiento registrado");
            refresh();
        }
    }

    @FXML
    private void trasladar() {
        if (datos == null) {
            return;
        }
        if (datos.sucursales().size() < 2) {
            Dialogs.info("Traslados", "Registre al menos dos sucursales para realizar traslados.");
            return;
        }
        ComboBox<Producto> producto = comboProductos();
        ComboBox<Sucursal> origen = comboSucursales();
        ComboBox<Sucursal> destino = Forms.combo(datos.sucursales(), Sucursal::toString);
        TextField cantidad = Forms.integerField("Unidades");
        TextField nota = new TextField();
        GridPane g = Forms.grid();
        Forms.row(g, "Producto *", producto);
        Forms.row(g, "Sucursal de origen *", origen);
        Forms.row(g, "Sucursal de destino *", destino);
        Forms.row(g, "Cantidad *", cantidad);
        Forms.row(g, "Nota", nota);
        boolean ok = Dialogs.form("Traslado entre sucursales", g, "Trasladar", () -> {
            if (producto.getValue() == null || origen.getValue() == null || destino.getValue() == null) {
                throw new ValidationException("Seleccione el producto y las sucursales.");
            }
            app.inventario().trasladar(producto.getValue().id(), origen.getValue().id(), destino.getValue().id(),
                    Forms.integer(cantidad, "Cantidad", true, 0), nota.getText());
        });
        if (ok) {
            shell.status("Traslado registrado");
            refresh();
        }
    }
}
