package com.stockwise.service;

import java.util.concurrent.CancellationException;

/**
 * Avance real de un proceso largo (porcentaje 0-100 y mensaje) con cancelación cooperativa: el proceso consulta
 * {@link #cancelado()} entre tareas y se detiene sin guardar resultados parciales.
 */
public interface Progreso {

    Progreso NINGUNO = new Progreso() {
        @Override
        public void avance(double porcentaje, String mensaje) {
        }

        @Override
        public boolean cancelado() {
            return false;
        }
    };

    void avance(double porcentaje, String mensaje);

    boolean cancelado();

    /** Lanza {@link CancellationException} si el usuario canceló. */
    default void verificar() {
        if (cancelado()) {
            throw new CancellationException("Análisis cancelado por el usuario");
        }
    }
}
