package com.stockwise.controller;

import com.stockwise.model.Compra;
import com.stockwise.model.Presupuesto;
import com.stockwise.model.Proveedor;
import com.stockwise.repository.CompraRepository;
import com.stockwise.model.Sucursal;
import com.stockwise.recommendation.PurchasePlan;
import com.stockwise.recommendation.PurchaseRecommendation;
import com.stockwise.service.CompraService;
import com.stockwise.service.PresupuestoService;
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
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

public class ComprasController extends BaseController {

    private static final String[] FILTROS = {"Productos a comprar", "Con riesgo de agotarse",
            "Sin presupuesto suficiente", "No requieren compra", "Todos"};

    private record Datos(PurchasePlan plan, PresupuestoService.EstadoPresupuesto presupuesto, List<Compra> compras,
                         List<Sucursal> sucursales) {
    }

    @FXML
    private Label budgetOrigin;
    @FXML
    private FlowPane kpiPane;
    @FXML
    private VBox notesBox;
    @FXML
    private ComboBox<String> filterCombo;
    @FXML
    private ComboBox<Sucursal> branchCombo;
    @FXML
    private Button orderButton;
    @FXML
    private TableView<PurchaseRecommendation> planTable;
    @FXML
    private TableView<Compra> purchasesTable;
    @FXML
    private CheckBox pendingCheck;
    @FXML
    private Label transitLabel;
    @FXML
    private Button receiveButton;
    @FXML
    private Button cancelButton;

    private Datos datos;

    public ComprasController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        filterCombo.getItems().setAll(FILTROS);
        filterCombo.setValue(FILTROS[0]);
        filterCombo.valueProperty().addListener((o, a, b) -> filtrar());
        branchCombo.setConverter(Forms.converter(s -> s == null ? "" : s.toString()));
        orderButton.setGraphic(Icons.CHECK.create(14));

        Tables.badge(planTable, "Prioridad", r -> r.prioridad().etiqueta(), Tables.ESTADOS, 85);
        Tables.col(planTable, "Código", r -> r.metrics().producto().codigo(), Kind.TEXT, 85);
        Tables.col(planTable, "Producto", r -> r.metrics().producto().nombre(), Kind.TEXT, 190);
        Tables.col(planTable, "Stock", r -> r.metrics().stock(), Kind.INT, 60);
        Tables.col(planTable, "Demanda/mes", r -> r.metrics().demandaMensual(), Kind.DECIMAL, 90);
        Tables.col(planTable, "Días de stock", r -> r.metrics().coberturaDias(), Kind.DECIMAL, 105);
        Tables.col(planTable, "En tránsito", PurchaseRecommendation::enTransito, Kind.INT, 80);
        Tables.col(planTable, "Stock seg.", PurchaseRecommendation::stockSeguridad, Kind.DECIMAL, 80);
        Tables.col(planTable, "Punto reorden", PurchaseRecommendation::puntoReorden, Kind.DECIMAL, 95);
        Tables.col(planTable, "Nivel objetivo", PurchaseRecommendation::nivelObjetivo, Kind.DECIMAL, 95);
        Tables.col(planTable, "Sugerido", PurchaseRecommendation::cantidadSugerida, Kind.INT, 75);
        Tables.col(planTable, "Aprobado", PurchaseRecommendation::cantidadAprobada, Kind.INT, 75);
        Tables.col(planTable, "Costo unit.", PurchaseRecommendation::costoUnitario, Kind.MONEY, 90);
        Tables.col(planTable, "Inversión", PurchaseRecommendation::costoTotal, Kind.MONEY, 100);
        Tables.col(planTable, "ROI esperado", PurchaseRecommendation::roiEsperado, Kind.PERCENT, 90);
        Tables.badge(planTable, "Decisión", r -> r.decision().toString(), Tables.ESTADOS, 175);
        Tables.col(planTable, "Justificación", PurchaseRecommendation::motivo, Kind.TEXT, 520);
        planTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        Tables.placeholder(planTable, "No hay productos en esta categoría.");

        Tables.col(purchasesTable, "Fecha", Compra::fecha, Kind.DATE, 95);
        Tables.col(purchasesTable, "Código", Compra::productoCodigo, Kind.TEXT, 90);
        Tables.col(purchasesTable, "Producto", Compra::productoNombre, Kind.TEXT, 240);
        Tables.col(purchasesTable, "Sucursal", Compra::sucursalCodigo, Kind.TEXT, 90);
        Tables.col(purchasesTable, "Cantidad", Compra::cantidad, Kind.INT, 80);
        Tables.col(purchasesTable, "Costo unit.", Compra::costoUnitario, Kind.MONEY, 100);
        Tables.col(purchasesTable, "Total", Compra::total, Kind.MONEY, 110);
        Tables.badge(purchasesTable, "Estado", Compra::estado, Tables.ESTADOS, 100);
        Tables.col(purchasesTable, "Llegada esperada", Compra::fechaEsperada, Kind.DATE, 110);
        Tables.col(purchasesTable, "Recepción", Compra::fechaRecepcion, Kind.DATE, 95);
        Tables.col(purchasesTable, "Documento", Compra::documento, Kind.TEXT, 100);
        Tables.col(purchasesTable, "Origen", c -> Compra.ORIGEN_IMPORTACION.equals(c.origen()) ? "Importada (ERP)"
                : "STOCKWISE", Kind.TEXT, 110);
        Tables.placeholder(purchasesTable, "Aún no se registraron compras.");
        receiveButton.disableProperty().bind(purchasesTable.getSelectionModel().selectedItemProperty().isNull());
        cancelButton.disableProperty().bind(purchasesTable.getSelectionModel().selectedItemProperty().isNull());
        refresh();
    }

    @FXML
    private void refresh() {
        shell.run("Calculando plan de compras…", () -> {
            PresupuestoService.EstadoPresupuesto ep = app.presupuestos().estadoActual();
            PurchasePlan plan = app.recomendaciones().planDeCompras(app.analysis().snapshot(), ep.disponible());
            return new Datos(plan, ep, app.compras().recientes(500), app.sucursales().activas());
        }, d -> {
            datos = d;
            Sucursal sel = branchCombo.getValue();
            branchCombo.getItems().setAll(d.sucursales());
            branchCombo.setValue(sel == null ? d.sucursales().stream().findFirst().orElse(null)
                    : d.sucursales().stream().filter(s -> s.id() == sel.id()).findFirst().orElse(null));
            render();
        });
    }

    private void render() {
        PurchasePlan plan = datos.plan();
        PresupuestoService.EstadoPresupuesto ep = datos.presupuesto();
        budgetOrigin.setText("Periodo " + Formatters.period(ep.periodo()) + " · " + ep.origen()
                + " · Comprar para cubrir: " + app.config().get().horizonteMeses() + " mes(es) + tiempo de reposición");
        kpiPane.getChildren().setAll(
                DashboardController.kpi("Presupuesto disponible", Formatters.money(ep.disponible()),
                        "Asignado " + Formatters.money(ep.asignado()) + " · comprado " + Formatters.money(ep.comprometido()),
                        null, Icons.MONEY, "blue"),
                DashboardController.kpi("Compra recomendada", Formatters.money(plan.totalAprobado()),
                        plan.aComprar().size() + " producto(s) dentro del presupuesto", null, Icons.PURCHASE, "green"),
                DashboardController.kpi("Necesidad total", Formatters.money(plan.totalSugerido()),
                        plan.totalSugerido() > ep.disponible() ? "Supera el presupuesto en "
                                + Formatters.money(plan.totalSugerido() - ep.disponible()) : "Cubierta por el presupuesto",
                        plan.totalSugerido() > ep.disponible() ? Boolean.FALSE : null, Icons.REPORT, "orange"),
                DashboardController.kpi("Saldo tras la compra", Formatters.money(plan.saldo()), "", null, Icons.MONEY,
                        "blue"),
                DashboardController.kpi("Riesgo de agotarse", String.valueOf(plan.conRiesgo().size()),
                        plan.noRequieren().size() + " producto(s) no requieren compra", null, Icons.WARNING, "red"));
        notesBox.getChildren().clear();
        inventario = app.hayInventario();
        if (!inventario) {
            kpiPane.getChildren().setAll(kpiPane.getChildren().get(0));
            Label l = new Label("Aún no hay inventario registrado: sin saber cuánto stock tiene no se puede calcular qué "
                    + "comprar ni cuánto. Importe sus existencias (Importar Excel / CSV) y vuelva a analizar. Las "
                    + "órdenes de compra registradas siguen disponibles en la otra pestaña.");
            l.getStyleClass().addAll("notice", "warning");
            l.setWrapText(true);
            l.setMaxWidth(Double.MAX_VALUE);
            notesBox.getChildren().add(l);
            Tables.placeholder(planTable, "Pendiente: importe su inventario para calcular el plan de compras.");
        }
        for (String n : inventario ? plan.notas() : List.<String>of()) {
            Label l = new Label(n);
            l.getStyleClass().addAll("notice", "warning");
            l.setMaxWidth(Double.MAX_VALUE);
            notesBox.getChildren().add(l);
        }
        filtrar();
        purchasesTable.getItems().setAll(datos.compras());
        long pendientes = datos.compras().stream().filter(Compra::pendiente).count();
        int unidades = datos.compras().stream().filter(Compra::pendiente).mapToInt(Compra::cantidad).sum();
        transitLabel.setText(pendientes == 0 ? "No hay órdenes pendientes de recepción."
                : pendientes + " orden(es) pendiente(s) con " + unidades + " u en tránsito: se descuentan de las "
                + "necesidades de compra hasta su recepción.");
    }

    /** Sin inventario registrado el plan de compras supondría stock cero: no se muestra. */
    private boolean inventario = true;

    private void filtrar() {
        if (datos == null) {
            return;
        }
        if (!inventario) {
            planTable.getItems().clear();
            return;
        }
        String f = filterCombo.getValue();
        planTable.getItems().setAll(datos.plan().lineas().stream().filter(l -> switch (f) {
            case "Productos a comprar" -> l.cantidadAprobada() > 0;
            case "Con riesgo de agotarse" -> l.riesgoAgotamiento();
            case "Sin presupuesto suficiente" -> l.decision() == PurchaseRecommendation.Decision.SIN_PRESUPUESTO
                    || l.decision() == PurchaseRecommendation.Decision.COMPRA_PARCIAL;
            case "No requieren compra" -> l.decision() == PurchaseRecommendation.Decision.NO_REQUIERE;
            default -> true;
        }).toList());
    }

    @FXML
    private void registerOrder() {
        if (datos == null) {
            return;
        }
        Sucursal suc = branchCombo.getValue();
        if (suc == null) {
            Dialogs.warning("Sucursal", "Seleccione la sucursal que recibirá la mercadería.");
            return;
        }
        List<PurchaseRecommendation> seleccion = new ArrayList<>(planTable.getSelectionModel().getSelectedItems());
        if (seleccion.isEmpty()) {
            seleccion = datos.plan().aComprar();
        }
        List<CompraService.LineaCompra> lineas = seleccion.stream().filter(r -> r.cantidadAprobada() > 0)
                .map(r -> new CompraService.LineaCompra(r.metrics().productoId(), suc.id(), r.cantidadAprobada(),
                        r.costoUnitario())).toList();
        if (lineas.isEmpty()) {
            Dialogs.info("Sin compras aprobadas", "No hay productos con cantidad aprobada dentro del presupuesto.");
            return;
        }
        double total = lineas.stream().mapToDouble(CompraService.LineaCompra::total).sum();
        boolean pedido = pendingCheck.isSelected();
        if (!Dialogs.confirm(pedido ? "¿Registrar el pedido en tránsito?" : "¿Registrar la orden de compra?",
                lineas.size() + " producto(s) por " + Formatters.money(total) + " con recepción en " + suc + ".\n\n"
                        + (pedido ? "Quedará como pedido PENDIENTE: no modifica el inventario hasta que se reciba "
                        + "(pestaña Órdenes), pero se descuenta de las necesidades de compra y del presupuesto."
                        : "Se incrementará el inventario de la sucursal y se descontará del presupuesto del periodo.")
                        + " (Seleccione filas en la tabla para registrar solo algunas.)")) {
            return;
        }
        int ltDefecto = app.config().get().leadTimeDefectoDias();
        shell.run("Registrando compra…", () -> {
            if (pedido) {
                app.compras().registrarPedido(lineas, true, null, ltDefecto);
            } else {
                app.compras().registrar(lineas, true);
            }
            return total;
        }, t -> {
            Dialogs.info(pedido ? "Pedido registrado" : "Compra registrada", "Se registró " + (pedido
                    ? "el pedido en tránsito" : "la compra") + " por " + Formatters.money(t) + ".");
            refresh();
        });
    }

    @FXML
    private void receiveOrder() {
        Compra c = purchasesTable.getSelectionModel().getSelectedItem();
        if (c == null) {
            return;
        }
        if (!c.pendiente()) {
            Dialogs.info("Orden no pendiente", "La orden seleccionada está " + c.estado() + ".");
            return;
        }
        TextField cantidad = Forms.integerField("Cantidad recibida");
        cantidad.setText(String.valueOf(c.cantidad()));
        GridPane g = Forms.grid();
        Forms.row(g, "Orden", new Label("#" + c.id() + " · " + c.productoCodigo() + " - " + c.productoNombre()));
        Forms.row(g, "Sucursal", new Label(c.sucursalCodigo()));
        Forms.row(g, "Cantidad pedida", new Label(String.valueOf(c.cantidad())));
        Forms.row(g, "Cantidad recibida *", cantidad);
        Dialogs.form("Recibir orden de compra", new VBox(10, g, new Label("La mercadería ingresará al inventario y "
                + "se registrará la fecha real de recepción (mide el tiempo de entrega del proveedor).")), "Recibir", () -> {
            app.compras().recibir(c.id(), Forms.integer(cantidad, "Cantidad recibida", true, c.cantidad()));
            refresh();
        });
    }

    @FXML
    private void cancelOrder() {
        Compra c = purchasesTable.getSelectionModel().getSelectedItem();
        if (c == null) {
            return;
        }
        if (!c.pendiente()) {
            Dialogs.info("Orden no pendiente", "Solo se pueden cancelar órdenes pendientes.");
            return;
        }
        if (Dialogs.confirm("¿Cancelar la orden #" + c.id() + "?", c.cantidad() + " u de " + c.productoNombre()
                + " dejarán de contarse como stock en tránsito y se liberará " + Formatters.money(c.total())
                + " del presupuesto.")) {
            shell.run("Cancelando orden…", () -> {
                app.compras().cancelar(c.id());
                return c.id();
            }, id -> refresh());
        }
    }

    /** Tiempo de entrega y ciclo de compra por proveedor, con el tiempo de entrega real observado. */
    @FXML
    private void manageSuppliers() {
        record Fila(Proveedor p, CompraRepository.LeadTimeObservado obs, java.time.LocalDate ultimo) {
        }
        TableView<Fila> tabla = new TableView<>();
        Tables.col(tabla, "Proveedor", f -> f.p().nombre(), Kind.TEXT, 170);
        Tables.col(tabla, "Entrega (días)", f -> f.p().leadTimeDias() == null ? "Por defecto"
                : String.valueOf(f.p().leadTimeDias()), Kind.TEXT, 95);
        Tables.col(tabla, "Ciclo (días)", f -> f.p().cicloCompraDias() == null ? "Horizonte general"
                : String.valueOf(f.p().cicloCompraDias()), Kind.TEXT, 115);
        Tables.col(tabla, "Entrega real prom.", f -> f.obs() == null ? null : f.obs().promedioDias(), Kind.DECIMAL, 115);
        Tables.col(tabla, "Desv. (días)", f -> f.obs() == null ? null : f.obs().desviacionDias(), Kind.DECIMAL, 85);
        Tables.col(tabla, "Pedidos medidos", f -> f.obs() == null ? 0 : f.obs().observaciones(), Kind.INT, 105);
        Tables.col(tabla, "Próximo pedido", f -> f.ultimo() == null || f.p().cicloCompraDias() == null ? null
                : f.ultimo().plusDays(f.p().cicloCompraDias()), Kind.DATE, 105);
        Tables.placeholder(tabla, "No hay proveedores. Se crean al importar productos u órdenes de compra.");
        tabla.setPrefSize(820, 280);
        Runnable cargar = () -> {
            var obs = app.compras().leadTimesObservados();
            var ult = app.compras().ultimoPedidoPorProveedor();
            tabla.getItems().setAll(app.compras().proveedores().stream()
                    .map(p -> new Fila(p, obs.get(p.id()), ult.get(p.id()))).toList());
        };
        cargar.run();
        TextField lt = Forms.integerField("Vacío = por defecto");
        TextField ciclo = Forms.integerField("Vacío = horizonte general");
        Label nombre = new Label("Seleccione un proveedor");
        nombre.getStyleClass().add("strong");
        Button guardar = new Button("Guardar cambios", Icons.SAVE.create(14));
        guardar.getStyleClass().add("primary");
        guardar.disableProperty().bind(tabla.getSelectionModel().selectedItemProperty().isNull());
        tabla.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                nombre.setText(b.p().nombre());
                lt.setText(b.p().leadTimeDias() == null ? "" : String.valueOf(b.p().leadTimeDias()));
                ciclo.setText(b.p().cicloCompraDias() == null ? "" : String.valueOf(b.p().cicloCompraDias()));
            }
        });
        guardar.setOnAction(e -> {
            Fila f = tabla.getSelectionModel().getSelectedItem();
            try {
                Proveedor p = f.p();
                app.compras().guardarProveedor(new Proveedor(p.id(), p.nombre(), p.contacto(), p.telefono(), p.email(),
                        Forms.integer(lt, "Tiempo de entrega", false, null),
                        Forms.integer(ciclo, "Ciclo de compra", false, null)));
                cargar.run();
            } catch (RuntimeException ex) {
                Dialogs.error(ex);
            }
        });
        GridPane g = Forms.grid();
        Forms.row(g, "Proveedor", nombre);
        Forms.row(g, "Tiempo de entrega (días)", lt);
        Forms.row(g, "Ciclo de compra (días)", ciclo);
        Label ayuda = new Label("El tiempo de entrega del proveedor se usa para los productos sin tiempo propio (si no "
                + "está configurado y hay al menos 3 pedidos recibidos, se usa el observado). El ciclo de compra es el "
                + "periodo de revisión R del nivel objetivo = demanda × (tiempo de entrega + R) + stock de seguridad; la "
                + "variabilidad observada del tiempo de entrega aumenta el stock de seguridad.");
        ayuda.setWrapText(true);
        ayuda.getStyleClass().add("muted");
        VBox box = new VBox(10, tabla, g, guardar, ayuda);
        box.setPrefWidth(840);
        Dialogs.form("Proveedores y ciclos de compra", box, "Cerrar", () -> { });
        refresh();
    }

    @FXML
    private void manageBudgets() {
        TableView<Presupuesto> tabla = new TableView<>();
        Tables.col(tabla, "Periodo", Presupuesto::periodo, Kind.TEXT, 90);
        Tables.col(tabla, "Sucursal", p -> p.sucursalCodigo() == null ? "General" : p.sucursalCodigo(), Kind.TEXT, 100);
        Tables.col(tabla, "Monto", Presupuesto::monto, Kind.MONEY, 120);
        Tables.col(tabla, "Descripción", Presupuesto::descripcion, Kind.TEXT, 200);
        Tables.placeholder(tabla, "Sin presupuestos registrados");
        tabla.getItems().setAll(app.presupuestos().listar());
        tabla.setPrefSize(560, 260);

        TextField periodo = new TextField(YearMonth.now().toString());
        periodo.setPromptText("AAAA-MM");
        List<Sucursal> opciones = new ArrayList<>();
        opciones.add(null);
        opciones.addAll(app.sucursales().listar());
        ComboBox<Sucursal> sucursal = new ComboBox<>();
        sucursal.getItems().setAll(opciones);
        sucursal.setConverter(Forms.converter(s -> s == null ? "General (toda la empresa)" : s.toString()));
        sucursal.getSelectionModel().selectFirst();
        TextField monto = Forms.decimalField("Monto");
        TextField desc = new TextField();
        Button guardar = new Button("Guardar presupuesto", Icons.SAVE.create(14));
        guardar.getStyleClass().add("primary");
        Button eliminar = new Button("Eliminar seleccionado");
        eliminar.getStyleClass().add("danger");
        eliminar.disableProperty().bind(tabla.getSelectionModel().selectedItemProperty().isNull());
        tabla.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                periodo.setText(b.periodo());
                monto.setText(Forms.format(b.monto()));
                desc.setText(b.descripcion() == null ? "" : b.descripcion());
                sucursal.getSelectionModel().select(opciones.stream()
                        .filter(s -> s != null && b.sucursalId() != null && s.id() == b.sucursalId()).findFirst()
                        .orElse(null));
            }
        });
        guardar.setOnAction(e -> {
            try {
                app.presupuestos().guardar(periodo.getText().trim(),
                        sucursal.getValue() == null ? null : sucursal.getValue().id(),
                        Forms.decimal(monto, "Monto", true, 0), desc.getText());
                tabla.getItems().setAll(app.presupuestos().listar());
            } catch (ValidationException ex) {
                Dialogs.error(ex);
            }
        });
        eliminar.setOnAction(e -> {
            Presupuesto p = tabla.getSelectionModel().getSelectedItem();
            if (p != null && Dialogs.confirm("¿Eliminar presupuesto?", "Periodo " + p.periodo() + " · "
                    + Formatters.money(p.monto()))) {
                app.presupuestos().eliminar(p.id());
                tabla.getItems().setAll(app.presupuestos().listar());
            }
        });
        GridPane g = Forms.grid();
        Forms.row(g, "Periodo (AAAA-MM) *", periodo);
        Forms.row(g, "Ámbito", sucursal);
        Forms.row(g, "Monto *", monto);
        Forms.row(g, "Descripción", desc);
        Label ayuda = new Label("El presupuesto disponible del mes es la suma de los presupuestos del periodo menos las "
                + "compras registradas. Si no hay presupuesto para el mes se usa el presupuesto general de "
                + "Configuración.");
        ayuda.setWrapText(true);
        ayuda.getStyleClass().add("muted");
        VBox box = new VBox(10, tabla, g, new HBox(8, guardar, eliminar), ayuda);
        box.setPrefWidth(600);
        Dialogs.form("Presupuestos de compra", box, "Cerrar", () -> { });
        refresh();
    }
}
