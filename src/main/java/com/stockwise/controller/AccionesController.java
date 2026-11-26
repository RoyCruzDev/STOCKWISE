package com.stockwise.controller;

import com.stockwise.service.AccionesService;
import com.stockwise.service.AccionesService.Accion;
import com.stockwise.service.AccionesService.Estado;
import com.stockwise.service.AccionesService.TipoAccion;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Forms;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Acciones recomendadas: una tarjeta por producto con qué pasa, qué considerar hacer, por qué, el error de predicción,
 * el riesgo y el resultado esperado. El dueño acepta, rechaza o pospone cada una; nada se ejecuta automáticamente.
 */
public class AccionesController extends BaseController {

    /** Filtro de estado. */
    private enum FiltroEstado {
        PENDIENTES("Pendientes"), ACEPTADAS("Aceptadas"), POSPUESTAS("Pospuestas"), RECHAZADAS("Rechazadas"),
        TODAS("Todas");

        final String etiqueta;

        FiltroEstado(String e) {
            etiqueta = e;
        }

        boolean incluye(Estado e) {
            return switch (this) {
                case PENDIENTES -> e == Estado.PENDIENTE;
                case ACEPTADAS -> e == Estado.ACEPTADA;
                case POSPUESTAS -> e == Estado.POSPUESTA;
                case RECHAZADAS -> e == Estado.RECHAZADA;
                case TODAS -> true;
            };
        }
    }

    @FXML
    private VBox contentBox;
    @FXML
    private VBox emptyBox;
    @FXML
    private TextField searchField;
    @FXML
    private ComboBox<FiltroEstado> estadoCombo;
    @FXML
    private FlowPane tiposPane;
    @FXML
    private ListView<Accion> accionesList;

    private final ToggleGroup tipos = new ToggleGroup();
    private List<Accion> todas = new ArrayList<>();

    public AccionesController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        estadoCombo.getItems().setAll(FiltroEstado.values());
        estadoCombo.setConverter(Forms.converter(f -> f == null ? "" : f.etiqueta));
        estadoCombo.setValue(FiltroEstado.PENDIENTES);
        estadoCombo.valueProperty().addListener((o, a, b) -> renderTipos());
        searchField.textProperty().addListener((o, a, b) -> filtrar());
        accionesList.setCellFactory(lv -> new Tarjeta());
        accionesList.setPlaceholder(new Label("No hay acciones con este filtro."));
        cargar();
    }

    private final Label avisoInventario = new Label("Aún no hay inventario registrado: las compras (qué comprar y "
            + "cuánto), los productos por agotarse y los excesos aparecerán cuando importe sus existencias.");

    private void cargar() {
        shell.run("Preparando acciones recomendadas…", () -> {
            boolean inv = app.acciones().inventarioRegistrado();
            return java.util.Map.entry(inv, app.acciones().acciones());
        }, res -> {
            List<Accion> l = res.getValue();
            avisoInventario.getStyleClass().setAll("notice", "warning");
            avisoInventario.setWrapText(true);
            avisoInventario.setMaxWidth(Double.MAX_VALUE);
            contentBox.getChildren().remove(avisoInventario);
            if (!res.getKey()) {
                contentBox.getChildren().add(1, avisoInventario);
            }
            todas = new ArrayList<>(l);
            boolean vacio = l.isEmpty();
            contentBox.setVisible(!vacio);
            contentBox.setManaged(!vacio);
            emptyBox.setVisible(vacio);
            emptyBox.setManaged(vacio);
            renderTipos();
        });
    }

    private List<Accion> porEstado() {
        FiltroEstado f = estadoCombo.getValue() == null ? FiltroEstado.PENDIENTES : estadoCombo.getValue();
        return todas.stream().filter(a -> f.incluye(a.estado())).toList();
    }

    /** Filtros por tipo con la cantidad de acciones de cada uno (solo los que tienen alguna). */
    private void renderTipos() {
        Object sel = tipos.getSelectedToggle() == null ? null : tipos.getSelectedToggle().getUserData();
        List<Accion> base = porEstado();
        Map<TipoAccion, Integer> n = new EnumMap<>(TipoAccion.class);
        base.forEach(a -> n.merge(a.tipo(), 1, Integer::sum));
        tiposPane.getChildren().clear();
        long sinMantener = base.stream().filter(a -> a.tipo() != TipoAccion.MANTENER).count();
        tiposPane.getChildren().add(chip("Requieren atención (" + sinMantener + ")", "ATENCION", sel == null
                || "ATENCION".equals(sel)));
        for (TipoAccion t : TipoAccion.values()) {
            if (n.containsKey(t)) {
                ToggleButton b = chip(t.etiqueta() + " (" + n.get(t) + ")", t, t == sel);
                b.getStyleClass().add("chip-" + t.color());
                tiposPane.getChildren().add(b);
            }
        }
        if (tipos.getSelectedToggle() == null && !tiposPane.getChildren().isEmpty()) {
            ((ToggleButton) tiposPane.getChildren().get(0)).setSelected(true);
        }
        filtrar();
    }

    private ToggleButton chip(String texto, Object dato, boolean seleccionado) {
        ToggleButton b = new ToggleButton(texto);
        b.getStyleClass().setAll("chip");
        b.setUserData(dato);
        b.setToggleGroup(tipos);
        b.setSelected(seleccionado);
        b.setOnAction(e -> {
            if (!b.isSelected()) {
                b.setSelected(true);
            }
            filtrar();
        });
        return b;
    }

    private void filtrar() {
        Object sel = tipos.getSelectedToggle() == null ? "ATENCION" : tipos.getSelectedToggle().getUserData();
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        accionesList.getItems().setAll(porEstado().stream()
                .filter(a -> "ATENCION".equals(sel) ? a.tipo() != TipoAccion.MANTENER : a.tipo() == sel)
                .filter(a -> q.isEmpty() || a.producto().toLowerCase(Locale.ROOT).contains(q)
                        || a.codigo().toLowerCase(Locale.ROOT).contains(q)).toList());
    }

    private void marcar(Accion a, Estado e) {
        shell.run("Guardando su decisión…", () -> {
            app.acciones().marcar(a, e);
            return e;
        }, x -> {
            int i = todas.indexOf(a);
            if (i >= 0) {
                todas.set(i, conEstado(a, e));
            }
            shell.status(a.producto() + ": " + e.toString().toLowerCase(Locale.ROOT)
                    + (e == Estado.POSPUESTA ? " (volverá a aparecer en el próximo análisis)" : "")
                    + ". No se modificó inventario, precios ni compras.");
            renderTipos();
        });
    }

    private static Accion conEstado(Accion a, Estado e) {
        return new Accion(a.origen(), a.id(), a.productoId(), a.codigo(), a.producto(), a.tipo(), a.prioridad(),
                a.situacion(), a.recomendacion(), a.motivo(), a.errorPrediccion(), a.errorAlto(), a.riesgo(),
                a.resultadoEsperado(), e, a.precioVenta(), a.costo(), a.demanda30(), a.cantidad(), a.detalleTecnico());
    }

    @FXML
    private void importData() {
        shell.navigate(View.IMPORTACION);
    }

    @FXML
    private void analyze() {
        shell.navigate(View.PROCESAMIENTO, ProcesamientoController.INICIAR);
    }

    /** Iniciales del producto como imagen (los productos no tienen fotografía registrada). */
    static String iniciales(String nombre) {
        String[] p = nombre == null ? new String[0] : nombre.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String s : p) {
            if (!s.isEmpty() && Character.isLetterOrDigit(s.charAt(0))) {
                sb.append(Character.toUpperCase(s.charAt(0)));
            }
            if (sb.length() == 2) {
                break;
            }
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    private static Label texto(String t, String estilo) {
        Label l = new Label(t);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        if (estilo != null) {
            l.getStyleClass().add(estilo);
        }
        return l;
    }

    /** Tarjeta de una acción. */
    private final class Tarjeta extends ListCell<Accion> {
        @Override
        protected void updateItem(Accion a, boolean empty) {
            super.updateItem(a, empty);
            if (empty || a == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label nombre = texto(a.producto(), "accion-producto");
            Label codigo = texto(a.codigo().isEmpty() ? "" : "Código " + a.codigo(), "muted");
            VBox cab = new VBox(1, nombre, codigo);
            HBox.setHgrow(cab, Priority.ALWAYS);
            Label tipo = new Label(a.tipo().etiqueta());
            tipo.getStyleClass().addAll("accion-tipo", "accion-tipo-" + a.tipo().color());
            HBox top = new HBox(12, cab, tipo);
            top.setAlignment(Pos.CENTER_LEFT);
            if (a.estado() != Estado.PENDIENTE) {
                Label est = new Label(a.estado().toString());
                est.getStyleClass().addAll("badge", a.estado() == Estado.ACEPTADA ? "badge-success"
                        : a.estado() == Estado.RECHAZADA ? "badge-neutral" : "badge-warning");
                top.getChildren().add(est);
            }

            GridPane g = new GridPane();
            g.setHgap(14);
            g.setVgap(6);
            String[][] filas = {{"Qué pasa", a.situacion()}, {"Qué considerar", a.recomendacion()},
                    {"Por qué", a.motivo()}, {"Riesgo", a.riesgo()}, {"Qué podrías lograr", a.resultadoEsperado()}};
            for (int i = 0; i < filas.length; i++) {
                Label k = new Label(filas[i][0]);
                k.getStyleClass().add("accion-campo");
                k.setMinWidth(130);
                Label v = texto(filas[i][1], i == 1 ? "accion-recomendacion" : null);
                GridPane.setHgrow(v, Priority.ALWAYS);
                g.addRow(i, k, v);
            }
            Label error = texto((a.errorAlto() ? "⚠ " : "✓ ") + a.errorPrediccion()
                    + (a.errorAlto() ? " La predicción es poco confiable: tómala solo como referencia y confirma antes "
                    + "de actuar." : ""), null);
            error.getStyleClass().addAll("notice", a.errorAlto() ? "warning" : "success");
            error.setMaxWidth(Double.MAX_VALUE);

            HBox botones = new HBox(8);
            botones.setAlignment(Pos.CENTER_LEFT);
            Button aceptar = new Button("Aceptar");
            aceptar.getStyleClass().add("primary");
            aceptar.setOnAction(e -> marcar(a, Estado.ACEPTADA));
            Button posponer = new Button("Posponer");
            posponer.setOnAction(e -> marcar(a, Estado.POSPUESTA));
            Button rechazar = new Button("Rechazar");
            rechazar.setOnAction(e -> marcar(a, Estado.RECHAZADA));
            if (a.estado() != Estado.PENDIENTE) {
                Button reabrir = new Button("Volver a pendiente");
                reabrir.setOnAction(e -> marcar(a, Estado.PENDIENTE));
                botones.getChildren().add(reabrir);
            } else {
                botones.getChildren().addAll(aceptar, posponer, rechazar);
            }
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            botones.getChildren().add(sp);
            if (a.tipo().usaPrecio() || a.tipo() == TipoAccion.NEGOCIAR) {
                Button sim = new Button("Simular precio");
                sim.setOnAction(e -> PrecioDialog.mostrar(a, app.acciones().margenMinimo()));
                botones.getChildren().add(sim);
            }
            if (a.origen() == AccionesService.Origen.DECISION) {
                Hyperlink det = new Hyperlink("Ver predicción");
                det.setOnAction(e -> shell.navigate(View.RESULTADOS,
                        new ResultadosController.VerProducto(a.productoId())));
                botones.getChildren().add(det);
            }
            if (!a.detalleTecnico().isBlank()) {
                Hyperlink tec = new Hyperlink("Detalles técnicos");
                tec.setOnAction(e -> Dialogs.info(a.producto(), a.detalleTecnico()));
                botones.getChildren().add(tec);
            }

            VBox card = new VBox(10, top, g, error, botones);
            card.getStyleClass().addAll("accion-card", "accion-" + a.tipo().color());
            card.maxWidthProperty().bind(getListView().widthProperty().subtract(36));
            card.prefWidthProperty().bind(card.maxWidthProperty());
            setGraphic(card);
            setText(null);
        }
    }
}
