package com.stockwise.prediction;

/**
 * Holt-Winters aditivo con estacionalidad de 12 meses:
 * <pre>
 *   l(t) = α·(y(t) − s(t−12)) + (1−α)·(l(t−1) + b(t−1))
 *   b(t) = β·(l(t) − l(t−1)) + (1−β)·b(t−1)
 *   s(t) = γ·(y(t) − l(t)) + (1−γ)·s(t−12)
 *   ŷ(t+h) = l(t) + h·b(t) + s(t+h−12)
 * </pre>
 * Inicialización con las dos primeras temporadas: nivel = media del primer año, tendencia = diferencia media
 * entre años / 12, índices = desvío de cada mes del primer año respecto de su media. Requiere 24 meses.
 * Parámetros por mínimo error cuadrático de un paso (búsqueda en rejilla).
 */
public class HoltWintersForecast implements ForecastMethod {

    public static final int PERIODO = 12;
    static final double[] ALFAS = {0.1, 0.3, 0.5};
    static final double[] BETAS = {0.0, 0.05, 0.15};
    static final double[] GAMMAS = {0.1, 0.3, 0.5};

    @Override
    public String nombre() {
        return "Holt-Winters (estacional)";
    }

    @Override
    public int minimoDatos() {
        return 2 * PERIODO;
    }

    @Override
    public int complejidad() {
        return 3;
    }

    /** Estado final: [nivel, tendencia, sse, s(n−12) … s(n−1)]. */
    static double[] ajustar(double[] y, double a, double b, double g) {
        int m = PERIODO;
        double m1 = 0;
        double m2 = 0;
        for (int i = 0; i < m; i++) {
            m1 += y[i];
            m2 += y[i + m];
        }
        m1 /= m;
        m2 /= m;
        double t = (m2 - m1) / m;
        double[] s = new double[y.length + m];
        double centro = (m - 1) / 2.0;
        for (int i = 0; i < m; i++) {
            // índice estacional = desvío respecto de la tendencia del primer año
            s[i] = y[i] - (m1 + t * (i - centro));
        }
        double sse = 0;
        // El primer año inicializa: el nivel corresponde al último mes del primer año.
        double l = m1 + t * centro;
        for (int i = m; i < y.length; i++) {
            double pred = l + t + s[i - m];
            sse += (y[i] - pred) * (y[i] - pred);
            double lPrev = l;
            l = a * (y[i] - s[i - m]) + (1 - a) * (l + t);
            t = b * (l - lPrev) + (1 - b) * t;
            s[i] = g * (y[i] - l) + (1 - g) * s[i - m];
        }
        double[] out = new double[3 + m];
        out[0] = l;
        out[1] = t;
        out[2] = sse;
        System.arraycopy(s, y.length - m, out, 3, m);
        return out;
    }

    public double[] parametros(double[] y) {
        double mejor = Double.MAX_VALUE;
        double[] out = {ALFAS[0], BETAS[0], GAMMAS[0]};
        for (double a : ALFAS) {
            for (double b : BETAS) {
                for (double g : GAMMAS) {
                    double sse = ajustar(y, a, b, g)[2];
                    if (sse < mejor - 1e-12) {
                        mejor = sse;
                        out = new double[]{a, b, g};
                    }
                }
            }
        }
        return out;
    }

    @Override
    public double[] pronosticar(double[] historia, int horizonte) {
        validar(historia, horizonte);
        double[] p = parametros(historia);
        double[] e = ajustar(historia, p[0], p[1], p[2]);
        double[] out = new double[horizonte];
        for (int h = 1; h <= horizonte; h++) {
            double estacional = e[3 + ((h - 1) % PERIODO)];
            out[h - 1] = Math.max(0, e[0] + h * e[1] + estacional);
        }
        return out;
    }
}
