package com.stockwise.controller;

import com.stockwise.recommendation.PriceSimulator;
import com.stockwise.recommendation.PriceSimulator.Escenario;
import com.stockwise.service.AccionesService.Accion;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Formatters;
import com.stockwise.util.Forms;
import com.stockwise.util.Tables;
import com.stockwise.util.Tables.Kind;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Simulador de precio de un producto: compara el precio actual con alternativas, la ganancia por unidad y cuántas
 * unidades habría que vender para ganar lo mismo. No cambia el precio: solo informa.
 */
final class PrecioDialog {

    private PrecioDialog() {
    }

    static void mostrar(Accion a, double margenMinimo) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("STOCKWISE");
        d.setHeaderText("Simular precio · " + a.producto());
        d.getDialogPane().getButtonTypes().add(new ButtonType("Cerrar", ButtonBar.ButtonData.CANCEL_CLOSE));
        String moneda = Formatters.currencySymbol();
        if (a.precioVenta() <= 0) {
            d.getDialogPane().setContent(new Label("El producto no tiene precio de venta registrado."));
            Dialogs.style(d);
            d.showAndWait();
            return;
        }
        double base = Math.max(0, a.demanda30());
        Label datos = new Label(String.format("Precio actual %s · costo %s · podrías vender unas %s u en 30 días · margen "
                        + "mínimo configurado %.0f %% (precio mínimo %s).", Formatters.money(a.precioVenta()),
                a.costo() > 0 ? Formatters.money(a.costo()) : "no registrado", Formatters.integer(base),
                margenMinimo * 100, a.costo() > 0 ? Formatters.money(Math.ceil(
                        PriceSimulator.precioMinimo(a.costo(), margenMinimo) * 100) / 100) : "—"));
        datos.setWrapText(true);
        TextField precio = new TextField(Forms.format(PriceSimulator.redondear(a.precioVenta() * 0.975)));
        precio.setPrefWidth(110);
        HBox fila = new HBox(10, new Label("Precio a evaluar (" + moneda + "):"), precio);
        fila.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label explicacion = new Label();
        explicacion.setWrapText(true);
        explicacion.getStyleClass().add("explicacion-sencilla");
        explicacion.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        TableView<Escenario> t = new TableView<>();
        Tables.col(t, "Precio", Escenario::precio, Kind.MONEY, 90);
        Tables.col(t, "Cambio", e -> e.variacionPrecio() == 0 ? "Actual"
                : String.format("%+.1f %%", e.variacionPrecio() * 100), Kind.TEXT, 70);
        Tables.col(t, "Ganancia por unidad", Escenario::margenUnitario, Kind.MONEY, 130);
        Tables.col(t, "Margen", Escenario::margenPct, Kind.PERCENT, 70);
        Tables.col(t, "Si vendes lo mismo: ganancia", Escenario::utilidad, Kind.MONEY, 170);
        Tables.col(t, "Para ganar lo mismo hay que vender", e -> e.unidadesNecesarias() == null ? "No alcanza"
                : Formatters.integer(Math.ceil(e.unidadesNecesarias())) + " u", Kind.TEXT, 210);
        Tables.col(t, "¿Permitido?", e -> e.bajoCosto() ? "No: bajo el costo" : e.bajoMargenMinimo()
                ? "No: bajo tu margen mínimo" : "Sí", Kind.TEXT, 170);
        t.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(Escenario e, boolean empty) {
                super.updateItem(e, empty);
                getStyleClass().remove("fila-no-permitida");
                if (!empty && e != null && !e.permitido()) {
                    getStyleClass().add("fila-no-permitida");
                }
            }
        });
        t.setPrefHeight(280);
        t.setPrefWidth(940);
        Runnable actualizar = () -> {
            Double p;
            try {
                p = Forms.decimal(precio, "Precio", false, -1);
            } catch (RuntimeException ex) {
                p = null;
            }
            explicacion.setText(p == null || p <= 0 ? "Escriba un precio válido."
                    : PriceSimulator.explicar(a.precioVenta(), a.costo(), base, margenMinimo, p, moneda));
            t.getItems().setAll(PriceSimulator.escenarios(a.precioVenta(), a.costo(), base, margenMinimo, p));
        };
        precio.textProperty().addListener((o, x, y) -> actualizar.run());
        actualizar.run();
        Label nota = new Label("Simulación: STOCKWISE no sabe cuánto cambiarán las ventas con otro precio (no hay datos "
                + "para estimarlo). Las columnas \"si vendes lo mismo\" suponen las mismas unidades. El precio del "
                + "producto no se modifica.");
        nota.setWrapText(true);
        nota.getStyleClass().add("muted");
        nota.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox box = new VBox(10, datos, fila, explicacion, t, nota);
        box.setPrefWidth(960);
        d.getDialogPane().setContent(box);
        Dialogs.style(d);
        d.showAndWait();
    }
}
