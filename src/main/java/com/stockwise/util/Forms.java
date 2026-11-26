package com.stockwise.util;

import com.stockwise.service.ValidationException;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.util.StringConverter;

import java.util.Collection;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Ayudas para formularios: campos numéricos filtrados, lectura validada y rejillas etiqueta/campo. */
public final class Forms {

    private static final Pattern DECIMAL = Pattern.compile("-?\\d*([.,]\\d*)?");
    private static final Pattern ENTERO = Pattern.compile("-?\\d*");

    private Forms() {
    }

    public static TextField decimalField(String prompt) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        tf.setTextFormatter(new TextFormatter<>(c -> DECIMAL.matcher(c.getControlNewText()).matches() ? c : null));
        return tf;
    }

    public static TextField integerField(String prompt) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        tf.setTextFormatter(new TextFormatter<>(c -> ENTERO.matcher(c.getControlNewText()).matches() ? c : null));
        return tf;
    }

    public static void makeDecimal(TextField tf) {
        tf.setTextFormatter(new TextFormatter<>(c -> DECIMAL.matcher(c.getControlNewText()).matches() ? c : null));
    }

    public static void makeInteger(TextField tf) {
        tf.setTextFormatter(new TextFormatter<>(c -> ENTERO.matcher(c.getControlNewText()).matches() ? c : null));
    }

    public static double decimal(TextField tf, String campo, boolean obligatorio, double porDefecto) {
        String t = tf.getText() == null ? "" : tf.getText().trim().replace(',', '.');
        if (t.isEmpty() || t.equals("-") || t.equals(".")) {
            if (obligatorio) {
                throw new ValidationException("El campo '" + campo + "' es obligatorio.");
            }
            return porDefecto;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            throw new ValidationException("El campo '" + campo + "' debe ser numérico.");
        }
    }

    public static Integer integer(TextField tf, String campo, boolean obligatorio, Integer porDefecto) {
        String t = tf.getText() == null ? "" : tf.getText().trim();
        if (t.isEmpty() || t.equals("-")) {
            if (obligatorio) {
                throw new ValidationException("El campo '" + campo + "' es obligatorio.");
            }
            return porDefecto;
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw new ValidationException("El campo '" + campo + "' debe ser un número entero.");
        }
    }

    public static String format(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** Rejilla de dos columnas (etiqueta, campo). */
    public static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setMinWidth(150);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        c2.setMinWidth(260);
        g.getColumnConstraints().addAll(c1, c2);
        return g;
    }

    public static void row(GridPane g, String etiqueta, Node campo) {
        int r = g.getRowCount();
        Label l = new Label(etiqueta);
        l.getStyleClass().add("form-label");
        g.add(l, 0, r);
        g.add(campo, 1, r);
        if (campo instanceof javafx.scene.layout.Region reg) {
            reg.setMaxWidth(Double.MAX_VALUE);
        }
    }

    /** ComboBox con texto personalizado para objetos de dominio. */
    public static <T> ComboBox<T> combo(Collection<T> items, Function<T, String> texto) {
        ComboBox<T> cb = new ComboBox<>();
        cb.getItems().setAll(items);
        cb.setConverter(converter(t -> t == null ? "" : texto.apply(t)));
        cb.setMaxWidth(Double.MAX_VALUE);
        return cb;
    }

    /** Conversor de solo lectura (la función recibe también null, p. ej. para la opción "Todos"). */
    public static <T> StringConverter<T> converter(Function<T, String> texto) {
        return new StringConverter<>() {
            @Override
            public String toString(T t) {
                return texto.apply(t);
            }

            @Override
            public T fromString(String s) {
                return null;
            }
        };
    }
}
