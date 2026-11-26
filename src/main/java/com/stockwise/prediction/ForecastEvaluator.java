package com.stockwise.prediction;

import java.util.Arrays;

/**
 * Evalúa un método mediante backtesting "walk-forward": para cada periodo t a partir de {@code inicio} se entrena
 * con los datos [0, t) y se pronostica t. Sobre esos errores (fuera de muestra) se calculan MAE, RMSE, WAPE, MAPE y
 * sesgo.
 */
public final class ForecastEvaluator {

    /**
     * Resultado de la evaluación.
     *
     * @param mape  nulo si no hay valores reales mayores que cero (ignora meses sin ventas)
     * @param wape  Σ|error| / Σ real; nulo si la venta real del periodo es cero
     * @param sesgo Σ(pronóstico − real) / Σ real (positivo = sobreestima); nulo si la venta real es cero
     */
    public record Evaluation(double[] ajustes, Double mae, Double mape, int puntos, Double rmse, Double wape,
                             Double sesgo, double[][] multipaso) {

        public Evaluation(double[] ajustes, Double mae, Double mape, int puntos, Double rmse, Double wape,
                          Double sesgo) {
            this(ajustes, mae, mape, puntos, rmse, wape, sesgo, null);
        }

        public Evaluation(double[] ajustes, Double mae, Double mape, int puntos) {
            this(ajustes, mae, mape, puntos, null, null, null, null);
        }
    }

    /** Horizontes (meses) evaluados en cada origen del backtesting. */
    public static final int HORIZONTES = 3;

    private ForecastEvaluator() {
    }

    public static Evaluation evaluar(ForecastMethod metodo, double[] serie, int inicio) {
        return evaluar(metodo, serie, serie, inicio);
    }

    /**
     * @param entrenamiento serie con la que se entrena (p. ej. con ventas atípicas moderadas)
     * @param real          ventas reales contra las que se mide el error (mismo tamaño)
     */
    public static Evaluation evaluar(ForecastMethod metodo, double[] entrenamiento, double[] real, int inicio) {
        if (entrenamiento.length != real.length) {
            throw new IllegalArgumentException("Series de longitud distinta");
        }
        double[] ajustes = new double[real.length];
        Arrays.fill(ajustes, Double.NaN);
        // multipaso[t][h-1]: pronóstico a h meses hecho con los datos [0, t) (NaN si el mes t+h-1 no existe).
        double[][] multipaso = new double[real.length][HORIZONTES];
        for (double[] fila : multipaso) {
            Arrays.fill(fila, Double.NaN);
        }
        int desde = Math.max(inicio, metodo.minimoDatos());
        double sumAbs = 0;
        double sumCuad = 0;
        double sumErr = 0;
        double sumReal = 0;
        double sumPct = 0;
        int n = 0;
        int nPct = 0;
        for (int t = desde; t < real.length; t++) {
            // Un solo entrenamiento por origen produce los pronósticos a 1, 2 y 3 meses.
            double[] f = metodo.pronosticar(Arrays.copyOfRange(entrenamiento, 0, t),
                    Math.min(HORIZONTES, real.length - t));
            System.arraycopy(f, 0, multipaso[t], 0, f.length);
            double pred = f[0];
            ajustes[t] = pred;
            double e = pred - real[t];
            sumAbs += Math.abs(e);
            sumCuad += e * e;
            sumErr += e;
            sumReal += real[t];
            n++;
            if (real[t] > 0) {
                sumPct += Math.abs(e) / real[t];
                nPct++;
            }
        }
        if (n == 0) {
            return new Evaluation(ajustes, null, null, 0, null, null, null, multipaso);
        }
        return new Evaluation(ajustes, sumAbs / n, nPct == 0 ? null : sumPct / nPct, n, Math.sqrt(sumCuad / n),
                sumReal > 0 ? sumAbs / sumReal : null, sumReal > 0 ? sumErr / sumReal : null, multipaso);
    }

    /** Métricas de un conjunto de pronósticos a h meses: {mae, rmse, wape, sesgo, puntos} (NaN si no hay puntos). */
    public static double[] metricasHorizonte(double[][] multipaso, double[] real, int h) {
        double abs = 0;
        double cuad = 0;
        double err = 0;
        double sumReal = 0;
        int n = 0;
        if (multipaso != null) {
            for (int t = 0; t + h - 1 < real.length && t < multipaso.length; t++) {
                double f = multipaso[t][h - 1];
                if (Double.isNaN(f)) {
                    continue;
                }
                double e = f - real[t + h - 1];
                abs += Math.abs(e);
                cuad += e * e;
                err += e;
                sumReal += real[t + h - 1];
                n++;
            }
        }
        if (n == 0) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0};
        }
        return new double[]{abs / n, Math.sqrt(cuad / n), sumReal > 0 ? abs / sumReal : Double.NaN,
                sumReal > 0 ? err / sumReal : Double.NaN, n};
    }

    /**
     * Errores (real − pronóstico) de la demanda acumulada de los próximos k meses, en cada origen con k meses
     * reales disponibles. Base de los rangos de predicción empíricos.
     */
    public static double[] erroresAcumulados(double[][] multipaso, double[] real, int k) {
        java.util.List<Double> out = new java.util.ArrayList<>();
        if (multipaso != null) {
            for (int t = 0; t + k - 1 < real.length && t < multipaso.length; t++) {
                double sf = 0;
                double sr = 0;
                boolean ok = true;
                for (int h = 1; h <= k; h++) {
                    double f = multipaso[t][h - 1];
                    if (Double.isNaN(f)) {
                        ok = false;
                        break;
                    }
                    sf += f;
                    sr += real[t + h - 1];
                }
                if (ok) {
                    out.add(sr - sf);
                }
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** MAE entre dos series de igual longitud. */
    public static double mae(double[] reales, double[] pronosticos) {
        if (reales.length != pronosticos.length || reales.length == 0) {
            throw new IllegalArgumentException("Series de longitud distinta o vacías");
        }
        double s = 0;
        for (int i = 0; i < reales.length; i++) {
            s += Math.abs(reales[i] - pronosticos[i]);
        }
        return s / reales.length;
    }

    /** MAPE entre dos series (ignora reales iguales a cero); nulo si todos los reales son cero. */
    public static Double mape(double[] reales, double[] pronosticos) {
        if (reales.length != pronosticos.length) {
            throw new IllegalArgumentException("Series de longitud distinta");
        }
        double s = 0;
        int n = 0;
        for (int i = 0; i < reales.length; i++) {
            if (reales[i] > 0) {
                s += Math.abs(reales[i] - pronosticos[i]) / reales[i];
                n++;
            }
        }
        return n == 0 ? null : s / n;
    }
}
