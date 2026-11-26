package com.stockwise.util;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;

/** Construcción de columnas de tabla con formato y alineación coherentes. */
public final class Tables {

    public enum Kind {
        TEXT, INT, DECIMAL, MONEY, PERCENT, DATE
    }

    private Tables() {
    }

    /**
     * Agrega una columna. La celda conserva el valor original (para ordenar correctamente) y solo cambia
     * su representación.
     */
    public static <T> TableColumn<T, Object> col(TableView<T> table, String titulo, Function<T, Object> getter,
                                                 Kind kind, double ancho) {
        TableColumn<T, Object> c = new TableColumn<>(titulo);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(getter.apply(cd.getValue())));
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : format(item, kind));
                setGraphic(null);
            }
        });
        if (kind != Kind.TEXT && kind != Kind.DATE) {
            c.getStyleClass().add("cell-number");
        }
        if (ancho > 0) {
            c.setPrefWidth(ancho);
        }
        table.getColumns().add(c);
        return c;
    }

    public static <T> TableColumn<T, Object> col(TableView<T> table, String titulo, Function<T, Object> getter,
                                                 Kind kind) {
        return col(table, titulo, getter, kind, -1);
    }

    /** Columna con "insignia" de color según el texto (mapa texto -> clase CSS: danger, warning, success...). */
    public static <T> TableColumn<T, Object> badge(TableView<T> table, String titulo, Function<T, Object> getter,
                                                   Map<String, String> estilos, double ancho) {
        TableColumn<T, Object> c = new TableColumn<>(titulo);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(getter.apply(cd.getValue())));
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                String t = item.toString();
                // Los estados guardados en mayúsculas (p. ej. COMPLETADA) se muestran como texto normal.
                String visible = t.length() > 1 && t.equals(t.toUpperCase())
                        ? t.charAt(0) + t.substring(1).toLowerCase() : t;
                Label l = new Label(visible);
                l.getStyleClass().addAll("badge", "badge-" + estilos.getOrDefault(item.toString(), "neutral"));
                setGraphic(l);
            }
        });
        if (ancho > 0) {
            c.setPrefWidth(ancho);
        }
        table.getColumns().add(c);
        return c;
    }

    public static String format(Object item, Kind kind) {
        if (item instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isInfinite(d) || Double.isNaN(d)) {
                return "—";
            }
            return switch (kind) {
                case INT -> Formatters.integer(d);
                case MONEY -> Formatters.money(d);
                case PERCENT -> Formatters.percent(d);
                case DECIMAL -> Formatters.number(d);
                default -> Formatters.number(d);
            };
        }
        if (item instanceof LocalDate d) {
            return Formatters.date(d);
        }
        return item.toString();
    }

    public static void placeholder(TableView<?> table, String texto) {
        Label l = new Label(texto);
        l.getStyleClass().add("muted");
        l.setWrapText(true);
        table.setPlaceholder(l);
    }

    /** Estilos de insignia para los estados de inventario y prioridades. */
    public static final Map<String, String> ESTADOS = Map.ofEntries(
            Map.entry("Agotado", "danger"), Map.entry("Riesgo de agotarse", "warning"), Map.entry("Normal", "success"),
            Map.entry("Exceso de stock", "info"), Map.entry("Sin movimiento", "neutral"),
            Map.entry("Crítica", "danger"), Map.entry("Alta", "warning"), Map.entry("Media", "info"),
            Map.entry("Baja", "neutral"), Map.entry("Nuevo", "success"), Map.entry("Actualiza", "info"),
            Map.entry("Duplicado (se omite)", "warning"), Map.entry("Error", "danger"),
            Map.entry("Comprar", "success"), Map.entry("Compra parcial", "warning"),
            Map.entry("Sin presupuesto", "danger"), Map.entry("No requiere compra", "neutral"),
            Map.entry("PENDIENTE", "warning"), Map.entry("APLICADA", "success"), Map.entry("DESCARTADA", "neutral"),
            Map.entry("RECIBIDA", "success"), Map.entry("CANCELADA", "neutral"), Map.entry("Omitida", "neutral"),
            Map.entry("Recomprar", "info"), Map.entry("Mantener", "success"), Map.entry("Liquidar", "danger"),
            Map.entry("Reducir", "warning"), Map.entry("Publicitar", "info"), Map.entry("Negociar", "warning"),
            Map.entry("Descontinuar", "danger"), Map.entry("Pendiente: datos insuficientes", "neutral"));
}
