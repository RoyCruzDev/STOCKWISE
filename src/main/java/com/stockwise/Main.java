package com.stockwise;

import javafx.application.Application;

/**
 * Punto de entrada. Se separa de la clase {@link javafx.application.Application} para que el JAR con
 * JavaFX en el classpath pueda ejecutarse directamente (java -jar) y desde el instalador.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Application.launch(StockWiseApp.class, args);
    }
}
