package com.stockwise.prediction;

/**
 * Suavizamiento exponencial con tendencia amortiguada (Holt, Gardner-McKenzie):
 * <pre>
 *   l(t) = α·y(t) + (1−α)·(l(t−1) + φ·b(t−1))
 *   b(t) = β·(l(t) − l(t−1)) + (1−β)·φ·b(t−1)
 *   ŷ(t+h) = l(t) + (φ + φ² + … + φ^h)·b(t)
 * </pre>
 * El amortiguamiento φ &lt; 1 evita extrapolar la tendencia indefinidamente (más prudente que la regresión lineal
 * para planificar compras). α, β y φ se eligen por mínimo error cuadrático de un paso en la propia historia.
 */
public class HoltForecast implements ForecastMethod {

    static final double[] ALFAS = {0.2, 0.4, 0.6, 0.8};
    static final double[] BETAS = {0.05, 0.1, 0.2};
    static final double[] FIS = {0.8, 0.9, 0.98};

    @Override
    public String nombre() {
        return "Holt (tendencia amortiguada)";
    }

    @Override
    public int minimoDatos() {
        return 4;
    }

    @Override
    public int complejidad() {
        return 2;
    }

    /** {nivel, tendencia, sse} tras recorrer la serie. */
    static double[] ajustar(double[] y, double a, double b, double phi) {
        double l = y[0];
        double t = y[1] - y[0];
        double sse = 0;
        for (int i = 1; i < y.length; i++) {
            double pred = l + phi * t;
            sse += (y[i] - pred) * (y[i] - pred);
            double lPrev = l;
            l = a * y[i] + (1 - a) * (l + phi * t);
            t = b * (l - lPrev) + (1 - b) * phi * t;
        }
        return new double[]{l, t, sse};
    }

    /** Parámetros {α, β, φ} de menor error en la historia. */
    public double[] parametros(double[] y) {
        double mejor = Double.MAX_VALUE;
        double[] out = {ALFAS[0], BETAS[0], FIS[0]};
        for (double a : ALFAS) {
            for (double b : BETAS) {
                for (double phi : FIS) {
                    double sse = ajustar(y, a, b, phi)[2];
                    if (sse < mejor - 1e-12) {
                        mejor = sse;
                        out = new double[]{a, b, phi};
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
        double[] lt = ajustar(historia, p[0], p[1], p[2]);
        double[] out = new double[horizonte];
        double acum = 0;
        for (int h = 0; h < horizonte; h++) {
            acum += Math.pow(p[2], h + 1);
            out[h] = Math.max(0, lt[0] + acum * lt[1]);
        }
        return out;
    }
}
