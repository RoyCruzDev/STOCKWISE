package com.stockwise.controller;

import com.stockwise.model.Prioridad;
import com.stockwise.model.Recomendacion;
import com.stockwise.model.TipoRecomendacion;
import com.stockwise.repository.RecomendacionRepository;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Formatters;
import com.stockwise.util.Forms;
import com.stockwise.util.Icons;
import com.stockwise.util.Tables;
import com.stockwise.util.Tables.Kind;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AsesorController extends BaseController {

    private static final Pattern CANTIDAD = Pattern.compile("Trasladar (\\d+)");

    @FXML
    private FlowPane kpiPane;
    @FXML
    private ComboBox<TipoRecomendacion> typeFilter;
    @FXML
    private ComboBox<Prioridad> priorityFilter;
    @FXML
    private ComboBox<String> stateFilter;
    @FXML
    private Label countLabel;
    @FXML
    private TableView<Recomendacion> recTable;
    @FXML
    private Label detailTitle;
    @FXML
    private Button applyButton;
    @FXML
    private Button discardButton;
    @FXML
    private GridPane detailGrid;

    private List<Recomendacion> todas = List.of();

    public AsesorController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        List<TipoRecomendacion> tipos = new ArrayList<>();
        tipos.add(null);
        tipos.addAll(List.of(TipoRecomendacion.values()));
        typeFilter.getItems().setAll(tipos);
        typeFilter.setConverter(Forms.converter(t -> t == null ? "Todos los tipos" : t.etiqueta()));
        typeFilter.getSelectionModel().selectFirst();
        List<Prioridad> prioridades = new ArrayList<>();
        prioridades.add(null);
        prioridades.addAll(List.of(Prioridad.values()));
        priorityFilter.getItems().setAll(prioridades);
        priorityFilter.setConverter(Forms.converter(p -> p == null ? "Todas las prioridades" : p.etiqueta()));
        priorityFilter.getSelectionModel().selectFirst();
        stateFilter.getItems().setAll("Pendientes", "Aplicadas", "Descartadas", "Todas");
        stateFilter.setValue("Pendientes");
        typeFilter.valueProperty().addListener((o, a, b) -> filtrar());
        priorityFilter.valueProperty().addListener((o, a, b) -> filtrar());
        stateFilter.valueProperty().addListener((o, a, b) -> filtrar());
        applyButton.setGraphic(Icons.CHECK.create(14));
        discardButton.setGraphic(Icons.CLOSE.create(14));

        Tables.badge(recTable, "Prioridad", r -> r.prioridad().etiqueta(), Tables.ESTADOS, 85);
        Tables.col(recTable, "Tipo", r -> r.tipo().etiqueta(), Kind.TEXT, 175);
        Tables.col(recTable, "Producto", Recomendacion::producto, Kind.TEXT, 200);
        Tables.col(recTable, "Sucursal", AsesorController::sucursales, Kind.TEXT, 110);
        Tables.col(recTable, "Problema detectado", Recomendacion::problema, Kind.TEXT, 330);
        Tables.col(recTable, "Acción recomendada", Recomendacion::accion, Kind.TEXT, 330);
        Tables.col(recTable, "Beneficio estimado", Recomendacion::beneficio, Kind.MONEY, 125);
        Tables.badge(recTable, "Estado", Recomendacion::estado, Tables.ESTADOS, 100);
        Tables.placeholder(recTable, "Sin recomendaciones. Pulse 'Analizar y generar recomendaciones'.");
        recTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> detalle(b));
        detalle(null);
        shell.run("Cargando recomendaciones…", () -> app.recomendaciones().recomendaciones(), this::mostrar);
    }

    private static String sucursales(Recomendacion r) {
        if (r.sucursal() == null) {
            return "Todas";
        }
        return r.sucursalDestino() == null ? r.sucursal() : r.sucursal() + " → " + r.sucursalDestino();
    }

    @FXML
    private void generate() {
        shell.run("Analizando el negocio…", () -> app.recomendaciones().generarRecomendaciones(), recs -> {
            mostrar(recs);
            stateFilter.setValue("Pendientes");
            shell.status(recs.stream().filter(r -> RecomendacionRepository.PENDIENTE.equals(r.estado())).count()
                    + " recomendaciones generadas");
        });
    }

    private void mostrar(List<Recomendacion> recs) {
        todas = recs;
        List<Recomendacion> pend = recs.stream().filter(r -> RecomendacionRepository.PENDIENTE.equals(r.estado()))
                .toList();
        double beneficio = pend.stream().mapToDouble(r -> r.beneficio() == null ? 0 : r.beneficio()).sum();
        kpiPane.getChildren().setAll(
                DashboardController.kpi("Recomendaciones pendientes", String.valueOf(pend.size()),
                        recs.isEmpty() ? "Aún no se ejecutó el análisis" : "Generadas el " + recs.get(0).fechaGeneracion(),
                        null, Icons.ADVISOR, "blue"),
                DashboardController.kpi("Críticas / altas", pend.stream().filter(r -> r.prioridad() == Prioridad.CRITICA)
                                .count() + " / " + pend.stream().filter(r -> r.prioridad() == Prioridad.ALTA).count(),
                        "Atender primero", null, Icons.WARNING, "red"),
                DashboardController.kpi("Beneficio potencial", Formatters.moneyShort(beneficio),
                        "Suma de beneficios estimables", null, Icons.MONEY, "green"),
                DashboardController.kpi("Traslados sugeridos", String.valueOf(pend.stream()
                                .filter(r -> r.tipo() == TipoRecomendacion.TRASLADO).count()),
                        "Aplicables directamente", null, Icons.SWAP, "orange"));
        filtrar();
    }

    private void filtrar() {
        TipoRecomendacion t = typeFilter.getValue();
        Prioridad p = priorityFilter.getValue();
        String e = stateFilter.getValue();
        List<Recomendacion> l = todas.stream()
                .filter(r -> t == null || r.tipo() == t)
                .filter(r -> p == null || r.prioridad() == p)
                .filter(r -> switch (e == null ? "Todas" : e) {
                    case "Pendientes" -> RecomendacionRepository.PENDIENTE.equals(r.estado());
                    case "Aplicadas" -> RecomendacionRepository.APLICADA.equals(r.estado());
                    case "Descartadas" -> RecomendacionRepository.DESCARTADA.equals(r.estado());
                    default -> true;
                }).toList();
        recTable.getItems().setAll(l);
        countLabel.setText(l.size() + " recomendación(es)");
    }

    private void detalle(Recomendacion r) {
        detailGrid.getChildren().clear();
        boolean pendiente = r != null && RecomendacionRepository.PENDIENTE.equals(r.estado());
        applyButton.setDisable(!pendiente);
        discardButton.setDisable(!pendiente);
        if (r == null) {
            detailTitle.setText("Seleccione una recomendación");
            return;
        }
        applyButton.setText(r.tipo() == TipoRecomendacion.TRASLADO ? "Ejecutar traslado" : "Marcar como aplicada");
        detailTitle.setText(r.tipo().etiqueta() + (r.producto() == null ? "" : " · " + r.producto()));
        fila("Problema detectado", r.problema());
        fila("Acción recomendada", r.accion());
        fila("Justificación", r.justificacion());
        fila("Beneficio esperado", r.beneficio() == null ? "No cuantificable con los datos disponibles"
                : Formatters.money(r.beneficio()));
        fila("Sucursal", sucursales(r));
        fila("Generada", r.fechaGeneracion() + " · prioridad " + r.prioridad().etiqueta().toLowerCase());
    }

    private void fila(String titulo, String texto) {
        int row = detailGrid.getRowCount();
        Label t = new Label(titulo);
        t.getStyleClass().add("form-label");
        t.setMinWidth(150);
        Label v = new Label(texto);
        v.setWrapText(true);
        detailGrid.add(t, 0, row);
        detailGrid.add(v, 1, row);
    }

    @FXML
    private void apply() {
        Recomendacion r = recTable.getSelectionModel().getSelectedItem();
        if (r == null) {
            return;
        }
        if (r.tipo() == TipoRecomendacion.TRASLADO) {
            TextField cantidad = Forms.integerField("Unidades");
            Matcher m = CANTIDAD.matcher(r.accion());
            if (m.find()) {
                cantidad.setText(m.group(1));
            }
            GridPane g = Forms.grid();
            Forms.row(g, "Producto", new Label(r.producto()));
            Forms.row(g, "Origen → destino", new Label(sucursales(r)));
            Forms.row(g, "Cantidad a trasladar *", cantidad);
            boolean ok = Dialogs.form("Ejecutar traslado recomendado", g, "Trasladar", () ->
                    app.recomendaciones().aplicar(r, Forms.integer(cantidad, "Cantidad", true, 0)));
            if (!ok) {
                return;
            }
        } else {
            if (!Dialogs.confirm("¿Marcar como aplicada?", "Indica que la acción \"" + r.accion()
                    + "\" fue ejecutada por la gerencia.")) {
                return;
            }
            app.recomendaciones().aplicar(r, null);
        }
        shell.status("Recomendación aplicada");
        recargar();
    }

    @FXML
    private void discard() {
        Recomendacion r = recTable.getSelectionModel().getSelectedItem();
        if (r != null && Dialogs.confirm("¿Descartar la recomendación?", r.accion())) {
            app.recomendaciones().descartar(r);
            recargar();
        }
    }

    private void recargar() {
        shell.run("Actualizando…", () -> app.recomendaciones().recomendaciones(), this::mostrar);
    }
}
