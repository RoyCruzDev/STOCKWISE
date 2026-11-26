package com.stockwise.util;

import com.stockwise.database.DataAccessException;
import com.stockwise.service.ValidationException;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Cuadros de diálogo con el estilo de la aplicación y manejo uniforme de errores. */
public final class Dialogs {

    private static final Logger LOG = Logger.getLogger(Dialogs.class.getName());
    private static final List<String> STYLESHEETS = new ArrayList<>();
    private static Window owner;

    private Dialogs() {
    }

    public static void configure(Window mainWindow, List<String> stylesheets) {
        owner = mainWindow;
        STYLESHEETS.clear();
        STYLESHEETS.addAll(stylesheets);
    }

    public static void style(Dialog<?> d) {
        if (owner != null && d.getOwner() == null) {
            d.initOwner(owner);
        }
        d.getDialogPane().getStylesheets().setAll(STYLESHEETS);
        d.getDialogPane().setMinWidth(420);
    }

    public static void info(String titulo, String mensaje) {
        show(Alert.AlertType.INFORMATION, titulo, mensaje);
    }

    public static void warning(String titulo, String mensaje) {
        show(Alert.AlertType.WARNING, titulo, mensaje);
    }

    private static void show(Alert.AlertType tipo, String titulo, String mensaje) {
        Alert a = new Alert(tipo);
        a.setTitle("STOCKWISE");
        a.setHeaderText(titulo);
        Label l = new Label(mensaje);
        l.setWrapText(true);
        l.setMaxWidth(520);
        a.getDialogPane().setContent(l);
        style(a);
        a.showAndWait();
    }

    /** Solicita confirmación; devuelve true solo si el usuario acepta. */
    public static boolean confirm(String titulo, String mensaje) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("STOCKWISE");
        a.setHeaderText(titulo);
        Label l = new Label(mensaje);
        l.setWrapText(true);
        l.setMaxWidth(520);
        a.getDialogPane().setContent(l);
        ButtonType si = new ButtonType("Aceptar", ButtonBar.ButtonData.OK_DONE);
        ButtonType no = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        a.getButtonTypes().setAll(si, no);
        style(a);
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == si;
    }

    /**
     * Pregunta con varias opciones (la primera se destaca como recomendada).
     *
     * @return índice de la opción elegida, o −1 si se canceló
     */
    public static int elegir(String titulo, String mensaje, String... opciones) {
        Alert a = new Alert(Alert.AlertType.WARNING);
        a.setTitle("STOCKWISE");
        a.setHeaderText(titulo);
        Label l = new Label(mensaje);
        l.setWrapText(true);
        l.setMaxWidth(620);
        javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(l);
        sp.setFitToWidth(true);
        sp.setPrefViewportHeight(Math.min(380, 22 + 18 * mensaje.split("\n").length + mensaje.length() / 6.0));
        sp.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        a.getDialogPane().setContent(sp);
        a.getDialogPane().setMinWidth(660);
        java.util.List<ButtonType> botones = new java.util.ArrayList<>();
        for (String o : opciones) {
            botones.add(new ButtonType(o, ButtonBar.ButtonData.OTHER));
        }
        ButtonType cancelar = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        a.getButtonTypes().setAll(botones);
        a.getButtonTypes().add(cancelar);
        style(a);
        if (!botones.isEmpty()) {
            a.getDialogPane().lookupButton(botones.get(0)).getStyleClass().add("primary");
        }
        Optional<ButtonType> r = a.showAndWait();
        return r.map(botones::indexOf).orElse(-1);
    }

    /**
     * Muestra un formulario modal. Al pulsar "Guardar" ejecuta {@code alAceptar}; si lanza una excepción se
     * muestra el error y el formulario permanece abierto para corregir los datos.
     *
     * @return true si se aceptó y la acción se completó sin errores
     */
    public static boolean form(String titulo, javafx.scene.Node contenido, String textoAceptar, Runnable alAceptar) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("STOCKWISE");
        d.setHeaderText(titulo);
        ButtonType ok = new ButtonType(textoAceptar, ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ok, new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE));
        d.getDialogPane().setContent(contenido);
        style(d);
        d.getDialogPane().lookupButton(ok).getStyleClass().add("primary");
        d.getDialogPane().lookupButton(ok).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try {
                alAceptar.run();
            } catch (RuntimeException ex) {
                error(ex);
                e.consume();
            }
        });
        Optional<ButtonType> r = d.showAndWait();
        return r.isPresent() && r.get() == ok;
    }

    /** Muestra un error: las validaciones se muestran tal cual; el resto se registra en el log. */
    public static void error(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && !(root instanceof ValidationException)) {
            root = root.getCause();
        }
        if (e instanceof ValidationException || root instanceof ValidationException) {
            warning("Revise los datos", (root instanceof ValidationException ? root : e).getMessage());
            return;
        }
        LOG.log(Level.SEVERE, "Error no controlado", e);
        String detalle = e instanceof DataAccessException ? "Error al acceder a la base de datos."
                : "Ocurrió un error inesperado.";
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("STOCKWISE");
        a.setHeaderText(detalle);
        TextArea ta = new TextArea(String.valueOf(root.getMessage()));
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setPrefRowCount(4);
        a.getDialogPane().setContent(new VBox(8, new Label("El detalle se guardó en data/logs. Mensaje:"), ta));
        style(a);
        a.showAndWait();
    }
}
