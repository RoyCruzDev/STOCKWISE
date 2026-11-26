package com.stockwise.prediction;

import java.util.Arrays;

/**
 * Croston-TSB (Teunter, Syntetos y Babai, 2011) para demanda intermitente.
 * <pre>
 *   si y(t) > 0:  p = p + β(1 − p);   z = z + α(y(t) − z)
 *   si y(t) = 0:  p = p + β(0 − p)
 *   pronóstico  = p · z
 * </pre>
 * A diferencia de Croston clásico, la probabilidad de demanda {@code p} se actualiza en cada periodo, por lo que
 * el pronóstico decae cuando un producto deja de venderse (útil para detectar obsolescencia).
 * Los parámetros α y β se eligen por mínimo error cuadrático en la propia historia (búsqueda en rejilla).
 */
public class CrostonTsbForecast implements ForecastMethod {

    static final double[] ALFAS = {0.1, 0.2, 0.3, 0.5};
    static final double[] BETAS = {0.05, 0.1, 0.2, 0.3};

    private final Double alfaFijo;
    private final Double betaFijo;

    /** Parámetros optimizados automáticamente. */
    public CrostonTsbForecast() {
        this.alfaFijo = null;
        this.betaFijo = null;
    }

    public CrostonTsbForecast(double alfa, double beta) {
        if (alfa <= 0 || alfa > 1 || beta <= 0 || beta > 1) {
            throw new IllegalArgumentException("α y β deben estar en (0, 1]");
        }
        this.alfaFijo = alfa;
        this.betaFijo = beta;
    }

    @Override
    public String nombre() {
        return "Croston-TSB";
    }

    @Override
    public int complejidad() {
        return 2;
    }

    @Override
    public int minimoDatos() {
        return 4;
    }

    /** Resultado del suavizado: pronóstico final y error cuadrático de un paso adelante. */
    record Ajuste(double pronostico, double sse) {
    }

    static Ajuste suavizar(double[] y, double alfa, double beta) {
        int primero = -1;
        for (int i = 0; i < y.length; i++) {
            if (y[i] > 0) {
                primero = i;
                break;
            }
        }
        if (primero < 0) {
            return new Ajuste(0, 0);
        }
        double z = y[primero];
        double p = Arrays.stream(y).filter(v -> v > 0).count() / (double) y.length;
        double sse = 0;
        for (int t = 0; t < y.length; t++) {
            double pred = p * z;
            if (t > primero) {
                sse += (y[t] - pred) * (y[t] - pred);
            }
            if (y[t] > 0) {
                p += beta * (1 - p);
                z += alfa * (y[t] - z);
            } else {
                p += beta * (0 - p);
            }
        }
        return new Ajuste(p * z, sse);
    }

    /** Devuelve {α, β} elegidos para la serie. */
    public double[] parametros(double[] historia) {
        if (alfaFijo != null) {
            return new double[]{alfaFijo, betaFijo};
        }
        double mejor = Double.MAX_VALUE;
        double[] out = {ALFAS[0], BETAS[0]};
        for (double a : ALFAS) {
            for (double b : BETAS) {
                double sse = suavizar(historia, a, b).sse();
                if (sse < mejor - 1e-12) {
                    mejor = sse;
                    out = new double[]{a, b};
                }
            }
        }
        return out;
    }

    @Override
    public double[] pronosticar(double[] historia, int horizonte) {
        validar(historia, horizonte);
        double[] ab = parametros(historia);
        double[] out = new double[horizonte];
        Arrays.fill(out, Math.max(0, suavizar(historia, ab[0], ab[1]).pronostico()));
        return out;
    }
}
