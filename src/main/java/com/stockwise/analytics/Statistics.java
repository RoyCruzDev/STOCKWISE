package com.stockwise.analytics;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.regression.SimpleRegression;

import java.util.Arrays;

/** Funciones estadísticas básicas sobre series numéricas (apoyadas en Apache Commons Math). */
public final class Statistics {

    private static final NormalDistribution NORMAL = new NormalDistribution();

    private Statistics() {
    }

    public static double mean(double[] v) {
        return v.length == 0 ? 0 : Arrays.stream(v).average().orElse(0);
    }

    public static double sum(double[] v) {
        return Arrays.stream(v).sum();
    }

    /** Desviación estándar muestral (0 si hay menos de 2 datos). */
    public static double stdDev(double[] v) {
        if (v.length < 2) {
            return 0;
        }
        DescriptiveStatistics ds = new DescriptiveStatistics(v);
        return ds.getStandardDeviation();
    }

    /** Coeficiente de variación (desviación / media); 0 si la media es 0. */
    public static double coefficientOfVariation(double[] v) {
        double m = mean(v);
        return m == 0 ? 0 : stdDev(v) / m;
    }

    /** Últimos {@code n} valores de la serie (o toda la serie si es más corta). */
    public static double[] tail(double[] v, int n) {
        int from = Math.max(0, v.length - n);
        return Arrays.copyOfRange(v, from, v.length);
    }

    /** Proporción de valores iguales a cero. */
    public static double zeroRatio(double[] v) {
        if (v.length == 0) {
            return 0;
        }
        return Arrays.stream(v).filter(x -> x == 0).count() / (double) v.length;
    }

    /** Valor z de la normal estándar para un nivel de servicio (p. ej. 0.95 -> 1.645). */
    public static double zScore(double serviceLevel) {
        double p = Math.min(0.999, Math.max(0.5, serviceLevel));
        return NORMAL.inverseCumulativeProbability(p);
    }

    /** Regresión lineal de la serie contra el índice temporal 0..n-1. */
    public static SimpleRegression regression(double[] v) {
        SimpleRegression r = new SimpleRegression();
        for (int i = 0; i < v.length; i++) {
            r.addData(i, v[i]);
        }
        return r;
    }
}
