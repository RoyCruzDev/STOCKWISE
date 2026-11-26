package com.stockwise.prediction;

import java.util.Arrays;

/**
 * Modelos de referencia (benchmarks). Compiten en el mismo periodo de validación que el resto: si un modelo no
 * mejora al ingenuo, no aporta valor y la regla de parsimonia favorece la referencia.
 * <ul>
 *   <li>Ingenuo: el próximo mes repite el último observado.</li>
 *   <li>Ingenuo estacional: cada mes repite el mismo mes del año anterior.</li>
 * </ul>
 */
public class NaiveForecast implements ForecastMethod {

    private final boolean estacional;

    public NaiveForecast(boolean estacional) {
        this.estacional = estacional;
    }

    public static NaiveForecast simple() {
        return new NaiveForecast(false);
    }

    public static NaiveForecast estacional() {
        return new NaiveForecast(true);
    }

    @Override
    public String nombre() {
        return estacional ? "Ingenuo estacional (referencia)" : "Ingenuo (referencia)";
    }

    @Override
    public int minimoDatos() {
        return estacional ? 12 : 1;
    }

    @Override
    public int complejidad() {
        return estacional ? 1 : 0;
    }

    @Override
    public boolean referencia() {
        return true;
    }

    @Override
    public double[] pronosticar(double[] historia, int horizonte) {
        validar(historia, horizonte);
        double[] out = new double[horizonte];
        int n = historia.length;
        if (!estacional) {
            Arrays.fill(out, Math.max(0, historia[n - 1]));
            return out;
        }
        for (int h = 0; h < horizonte; h++) {
            // mismo mes del año anterior (o de dos años antes para horizontes > 12)
            int idx = n - 12 + (h % 12);
            out[h] = Math.max(0, historia[idx]);
        }
        return out;
    }
}
