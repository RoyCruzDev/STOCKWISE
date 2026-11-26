package com.stockwise.prediction;

import java.util.Arrays;

/**
 * Suavizamiento exponencial simple: L(t) = alfa * y(t) + (1 - alfa) * L(t-1).
 * El nivel inicial es el primer valor observado. El pronóstico es el último nivel.
 */
public class ExponentialSmoothing implements ForecastMethod {
    private final double alpha;

    public ExponentialSmoothing(double alpha) {
        if (alpha <= 0 || alpha >= 1) {
            throw new IllegalArgumentException("Alfa debe estar entre 0 y 1");
        }
        this.alpha = alpha;
    }

    @Override
    public String nombre() {
        return "Suavizamiento exponencial (α=" + alpha + ")";
    }

    @Override
    public int minimoDatos() {
        return 2;
    }

    public double nivel(double[] historia) {
        double nivel = historia[0];
        for (int i = 1; i < historia.length; i++) {
            nivel = alpha * historia[i] + (1 - alpha) * nivel;
        }
        return nivel;
    }

    @Override
    public double[] pronosticar(double[] historia, int horizonte) {
        validar(historia, horizonte);
        double[] out = new double[horizonte];
        Arrays.fill(out, Math.max(0, nivel(historia)));
        return out;
    }
}
