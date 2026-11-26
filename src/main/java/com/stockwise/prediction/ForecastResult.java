package com.stockwise.prediction;

import java.util.List;

/**
 * Resultado completo de la predicción de un producto (o de una serie agregada).
 *
 * @param periodos         etiquetas YYYY-MM de la historia usada
 * @param historia         unidades por periodo (ventas reales netas)
 * @param metodos          resultado de cada método aplicable (incluye los modelos de referencia)
 * @param mejor            método seleccionado (nulo si los datos son insuficientes)
 * @param periodosFuturos  etiquetas YYYY-MM de los periodos pronosticados
 * @param tendencia        dirección de la tendencia detectada
 * @param pendiente        variación estimada en unidades por mes (regresión lineal)
 * @param confianza        nivel de confianza de la predicción
 * @param advertencias     mensajes para el usuario sobre la calidad de los datos
 * @param clasificacion    patrón de demanda ADI/CV² (determina los modelos especializados evaluados)
 * @param estacionalidad   autocorrelación a 12 meses de la serie sin tendencia (NaN si hay menos de 24 meses)
 * @param atipicos         meses con ventas atípicas moderadas para entrenar
 * @param criterio         métrica de selección usada (MAE o RMSE)
 */
public record ForecastResult(List<String> periodos, double[] historia, List<MethodResult> metodos, MethodResult mejor,
                             List<String> periodosFuturos, Tendencia tendencia, double pendiente,
                             Confianza confianza, List<String> advertencias,
                             DemandClassifier.Clasificacion clasificacion, double estacionalidad, int atipicos,
                             String criterio, Horizontes horizontes) {

    /**
     * Resultado por horizonte del backtesting.
     *
     * @param pronostico pronóstico mensual final (mes 1 del mejor modelo a 1 mes, mes 2 del mejor a 2 meses, meses 3+
     *                   del mejor a 3 meses; igual al del modelo elegido si se usa uno solo)
     * @param modelos    modelo usado para cada horizonte (1, 2 y 3 meses)
     * @param metricas   [h-1] = {mae, rmse, wape, sesgo, puntos} del pronóstico final a h meses (fuera de muestra)
     * @param errores    [k-1] = errores (real − pronóstico) de la demanda acumulada de k meses; base de los rangos
     */
    public record Horizontes(double[] pronostico, List<String> modelos, double[][] metricas, double[][] errores) {
    }

    public ForecastResult(List<String> periodos, double[] historia, List<MethodResult> metodos, MethodResult mejor,
                          List<String> periodosFuturos, Tendencia tendencia, double pendiente, Confianza confianza,
                          List<String> advertencias, DemandClassifier.Clasificacion clasificacion,
                          double estacionalidad, int atipicos, String criterio) {
        this(periodos, historia, metodos, mejor, periodosFuturos, tendencia, pendiente, confianza, advertencias,
                clasificacion, estacionalidad, atipicos, criterio, null);
    }

    /** Pronóstico mensual final (por horizonte si está disponible). */
    public double[] pronostico() {
        if (horizontes != null) {
            return horizontes.pronostico();
        }
        return mejor == null ? new double[0] : mejor.pronostico();
    }

    /** Umbral de autocorrelación a 12 meses a partir del cual la serie se considera estacional. */
    public static final double UMBRAL_ESTACIONAL = 0.3;

    public ForecastResult(List<String> periodos, double[] historia, List<MethodResult> metodos, MethodResult mejor,
                          List<String> periodosFuturos, Tendencia tendencia, double pendiente, Confianza confianza,
                          List<String> advertencias, DemandClassifier.Clasificacion clasificacion) {
        this(periodos, historia, metodos, mejor, periodosFuturos, tendencia, pendiente, confianza, advertencias,
                clasificacion, Double.NaN, 0, "MAE");
    }

    /** Compatibilidad: la clasificación ADI/CV² se calcula desde la historia. */
    public ForecastResult(List<String> periodos, double[] historia, List<MethodResult> metodos, MethodResult mejor,
                          List<String> periodosFuturos, Tendencia tendencia, double pendiente, Confianza confianza,
                          List<String> advertencias) {
        this(periodos, historia, metodos, mejor, periodosFuturos, tendencia, pendiente, confianza, advertencias,
                DemandClassifier.clasificar(historia));
    }

    /** Patrón de demanda ADI/CV² de la serie. */
    public DemandPattern patron() {
        return clasificacion == null ? DemandPattern.SIN_CLASIFICAR : clasificacion.patron();
    }

    public boolean estacional() {
        return !Double.isNaN(estacionalidad) && estacionalidad >= UMBRAL_ESTACIONAL;
    }

    /** Resultado del modelo de referencia ingenuo (nulo si no se evaluó). */
    public MethodResult ingenuo() {
        return metodos.stream().filter(m -> m.referencia() && m.complejidad() == 0).findFirst().orElse(null);
    }

    /**
     * Valor agregado del pronóstico (FVA): 1 − MAE del modelo elegido / MAE del ingenuo. Positivo = el modelo
     * mejora a la referencia; nulo si no se puede medir.
     */
    public Double fva() {
        MethodResult ing = ingenuo();
        if (mejor == null || ing == null || mejor.mae() == null || ing.mae() == null || ing.mae() <= 0) {
            return null;
        }
        return 1 - mejor.mae() / ing.mae();
    }

    public enum Tendencia {
        CRECIENTE("Creciente"), DECRECIENTE("Decreciente"), ESTABLE("Estable"), INDETERMINADA("Indeterminada");

        private final String etiqueta;

        Tendencia(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        @Override
        public String toString() {
            return etiqueta;
        }
    }

    public enum Confianza {
        ALTA("Alta"), MEDIA("Media"), BAJA("Baja"), INSUFICIENTE("Datos insuficientes");

        private final String etiqueta;

        Confianza(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        @Override
        public String toString() {
            return etiqueta;
        }
    }

    /**
     * Resultado de un método en el backtesting walk-forward.
     *
     * @param ajustes     pronósticos un paso adelante sobre la historia (NaN donde no hay)
     * @param complejidad complejidad del modelo (desempate por parsimonia)
     * @param referencia  verdadero para los modelos de referencia (ingenuos)
     */
    public record MethodResult(String metodo, double[] pronostico, double[] ajustes, Double mae, Double mape,
                               int puntosEvaluados, Double rmse, Double wape, Double sesgo, int complejidad,
                               boolean referencia, double[][] multipaso) {

        public MethodResult(String metodo, double[] pronostico, double[] ajustes, Double mae, Double mape,
                            int puntosEvaluados, Double rmse, Double wape, Double sesgo, int complejidad,
                            boolean referencia) {
            this(metodo, pronostico, ajustes, mae, mape, puntosEvaluados, rmse, wape, sesgo, complejidad, referencia,
                    null);
        }

        public MethodResult(String metodo, double[] pronostico, double[] ajustes, Double mae, Double mape,
                            int puntosEvaluados) {
            this(metodo, pronostico, ajustes, mae, mape, puntosEvaluados, null, null, null, 1, false);
        }
    }

    public boolean suficiente() {
        return mejor != null;
    }

    /** Demanda pronosticada acumulada para los próximos {@code meses} (0 si no hay pronóstico). */
    public double demandaProximos(int meses) {
        if (mejor == null) {
            return 0;
        }
        double s = 0;
        double[] p = pronostico();
        for (int i = 0; i < Math.min(meses, p.length); i++) {
            s += p[i];
        }
        return s;
    }
}
