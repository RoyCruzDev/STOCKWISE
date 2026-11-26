package com.stockwise.controller;

import com.stockwise.service.AppContext;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Servicios que la ventana principal ofrece a los controladores de cada módulo. */
public interface Shell {

    AppContext app();

    /**
     * Ejecuta un trabajo en segundo plano mostrando un indicador de progreso; al terminar invoca
     * {@code alTerminar} en el hilo de la interfaz. Los errores se muestran automáticamente.
     */
    <T> void run(String mensaje, Callable<T> trabajo, Consumer<T> alTerminar);

    void navigate(View view);

    /** Navega pasando un dato a la nueva vista (p. ej. el id del análisis a mostrar). */
    default void navigate(View view, Object argumento) {
        navigate(view);
    }

    /** Dato pasado por {@link #navigate(View, Object)} a la vista actual (se consume al leerlo). */
    default Object argumento() {
        return null;
    }

    /** Nombre del proyecto abierto (nulo si se abrió una base suelta). */
    default String proyecto() {
        return null;
    }

    /** Mensaje breve en la barra de estado. */
    void status(String mensaje);

    void applyTheme(String tema);

    /** Ventana principal (propietaria de selectores de archivo). */
    javafx.stage.Window window();

    /** Notifica que la configuración cambió (nombre de empresa, moneda...). */
    void refreshHeader();
}
