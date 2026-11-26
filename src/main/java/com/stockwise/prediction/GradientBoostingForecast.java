package com.stockwise.prediction;

import java.util.Arrays;
import java.util.TreeSet;

/**
 * Base de los modelos de gradient boosting sobre árboles de decisión (GBDT) para series mensuales.
 * <p>
 * Implementación 100 % Java (sin bibliotecas nativas ni servicios externos). La serie se escala por su media y se
 * transforma en un problema de regresión supervisada con variables de rezago:
 * <pre>
 *   rezago 1, 2 y 3 · media móvil de 3 y 6 meses · rezago estacional 12 (o media de 6 si no existe) · índice temporal
 *   categoría: posición estacional (t mod 12)
 * </pre>
 * El pronóstico de varios meses es recursivo (cada predicción alimenta los rezagos del mes siguiente). Los árboles
 * no extrapolan: fuera del rango observado el pronóstico se mantiene en el último nivel aprendido.
 */
public abstract class GradientBoostingForecast implements ForecastMethod {

    /** Meses mínimos de historial: 6 de rezagos + al menos 12 filas de entrenamiento. */
    public static final int MINIMO_DATOS = 18;
    static final int INICIO = 6;

    /** Modelo entrenado. */
    protected interface Modelo {
        double predecir(double[] x, int categoria);
    }

    protected abstract Modelo entrenar(double[][] x, double[] y, int[] categorias);

    @Override
    public int complejidad() {
        return 4;
    }

    @Override
    public int minimoDatos() {
        return MINIMO_DATOS;
    }

    static double[] variables(double[] h, int t) {
        double l1 = h[t - 1];
        double l2 = h[t - 2];
        double l3 = h[t - 3];
        double m6 = 0;
        for (int i = t - 6; i < t; i++) {
            m6 += h[i];
        }
        m6 /= 6;
        double l12 = t >= 12 ? h[t - 12] : m6;
        return new double[]{l1, l2, l3, (l1 + l2 + l3) / 3, m6, l12, t};
    }

    @Override
    public double[] pronosticar(double[] historia, int horizonte) {
        validar(historia, horizonte);
        double[] out = new double[horizonte];
        double escala = Arrays.stream(historia).average().orElse(0);
        if (escala <= 0) {
            return out;
        }
        int n = historia.length;
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            y[i] = historia[i] / escala;
        }
        int filas = n - INICIO;
        double[][] x = new double[filas][];
        double[] obj = new double[filas];
        int[] cat = new int[filas];
        for (int t = INICIO; t < n; t++) {
            x[t - INICIO] = variables(y, t);
            obj[t - INICIO] = y[t];
            cat[t - INICIO] = t % 12;
        }
        Modelo m = entrenar(x, obj, cat);
        double[] ext = Arrays.copyOf(y, n + horizonte);
        for (int k = 0; k < horizonte; k++) {
            int t = n + k;
            double p = Math.max(0, m.predecir(variables(ext, t), t % 12));
            ext[t] = p;
            out[k] = p * escala;
        }
        return out;
    }

    // ------------------------------------------------------------------ histogramas (binning)

    /** Bordes de los bins de cada variable: valores únicos, o cuantiles si superan {@code maxBins}. */
    static double[][] bordes(double[][] x, int maxBins) {
        int f = x[0].length;
        double[][] out = new double[f][];
        for (int j = 0; j < f; j++) {
            TreeSet<Double> unicos = new TreeSet<>();
            for (double[] fila : x) {
                unicos.add(fila[j]);
            }
            double[] u = unicos.stream().mapToDouble(Double::doubleValue).toArray();
            if (u.length <= maxBins) {
                out[j] = u;
            } else {
                double[] q = new double[maxBins];
                for (int b = 0; b < maxBins; b++) {
                    q[b] = u[(int) Math.round((b + 1) * (u.length - 1) / (double) maxBins)];
                }
                out[j] = Arrays.stream(q).distinct().toArray();
            }
        }
        return out;
    }

    /** Índice de bin: primer borde mayor o igual que el valor (el último bin recoge los mayores). */
    static int bin(double[] bordes, double v) {
        int i = Arrays.binarySearch(bordes, v);
        if (i < 0) {
            i = -i - 1;
        }
        return Math.min(i, bordes.length - 1);
    }

    static int[][] binear(double[][] x, double[][] bordes) {
        int[][] out = new int[x.length][bordes.length];
        for (int i = 0; i < x.length; i++) {
            for (int j = 0; j < bordes.length; j++) {
                out[i][j] = bin(bordes[j], x[i][j]);
            }
        }
        return out;
    }

    static double ganancia(double g, double h, double lambda) {
        return g * g / (h + lambda);
    }
}
