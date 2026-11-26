package com.stockwise.prediction;

/** Método de predicción de demanda sobre una serie mensual de unidades. */
public interface ForecastMethod {

    /** Nombre legible del método (incluye sus parámetros). */
    String nombre();

    /** Cantidad mínima de observaciones que necesita el método. */
    int minimoDatos();

    /**
     * Complejidad relativa (0 = referencia ingenua … 4 = gradient boosting). Se usa como desempate por
     * parsimonia: entre modelos con error equivalente se prefiere el más simple.
     */
    default int complejidad() {
        return 1;
    }

    /** Verdadero para los modelos de referencia (benchmarks) contra los que se mide el valor agregado. */
    default boolean referencia() {
        return false;
    }

    /**
     * Pronostica {@code horizonte} periodos futuros.
     *
     * @param historia serie histórica (longitud >= {@link #minimoDatos()})
     * @return valores pronosticados, nunca negativos
     */
    double[] pronosticar(double[] historia, int horizonte);

    default void validar(double[] historia, int horizonte) {
        if (historia == null || historia.length < minimoDatos()) {
            throw new IllegalArgumentException(nombre() + " requiere al menos " + minimoDatos() + " datos");
        }
        if (horizonte < 1) {
            throw new IllegalArgumentException("El horizonte debe ser >= 1");
        }
    }
}
