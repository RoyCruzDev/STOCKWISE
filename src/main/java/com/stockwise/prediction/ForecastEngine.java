package com.stockwise.prediction;

import com.stockwise.analytics.Statistics;
import com.stockwise.prediction.ForecastResult.Confianza;
import com.stockwise.prediction.ForecastResult.MethodResult;
import com.stockwise.prediction.ForecastResult.Tendencia;
import org.apache.commons.math3.stat.regression.SimpleRegression;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Motor de predicción. Para cada serie:
 * <ol>
 *   <li>Diagnostica la demanda: patrón ADI/CV², tendencia, estacionalidad (autocorrelación a 12 meses) y ventas
 *       atípicas (desvío robusto respecto de la tendencia, MAD).</li>
 *   <li>Arma los candidatos: 4 métodos clásicos, el modelo de referencia ingenuo y, según el diagnóstico, Holt
 *       amortiguado (tendencia), Holt-Winters e ingenuo estacional (estacionalidad), Croston-TSB (intermitente) y
 *       GBDT tipo LightGBM / CatBoost (con historial suficiente).</li>
 *   <li>Evalúa todos con backtesting walk-forward <b>en el mismo periodo</b>: se entrena con la serie (atípicos
 *       moderados) anterior a cada mes y se mide el error contra las ventas reales.</li>
 *   <li>Selecciona por MAE (o RMSE en demanda intermitente, donde el MAE premia pronosticar cero) con
 *       <b>parsimonia</b>: entre modelos con error a menos del {@value #TOLERANCIA_PARSIMONIA_PCT}% del mejor se elige
 *       el más simple. La complejidad nunca es un mérito por sí misma.</li>
 * </ol>
 * Regla clave: nunca se genera un pronóstico con menos de {@link #MINIMO_ABSOLUTO} meses de historial.
 */
public class ForecastEngine {

    public static final int MINIMO_ABSOLUTO = 3;
    public static final int HORIZONTE_MAXIMO = 6;
    /** Puntos de validación mínimos para que un modelo de gradient boosting compita. */
    public static final int PUNTOS_MINIMOS_GBDT = 6;
    public static final int TOLERANCIA_PARSIMONIA_PCT = 2;
    /** Umbral robusto de atípicos: residuo &gt; k · 1,4826 · MAD. */
    public static final double K_ATIPICOS = 3.5;

    /** Parámetros del motor. */
    public record Params(int ventanaPm, int ventanaPmp, double alpha, int mesesConfiables) {
    }

    private final Params params;

    public ForecastEngine(Params params) {
        this.params = params;
    }

    /** Métodos clásicos que siempre compiten. */
    public List<ForecastMethod> metodos() {
        return List.of(new MovingAverage(params.ventanaPm()), new WeightedMovingAverage(params.ventanaPmp()),
                new ExponentialSmoothing(params.alpha()), new LinearRegressionForecast());
    }

    /**
     * @param periodos etiquetas YYYY-MM consecutivas de la serie
     * @param serie    unidades por periodo (mismo tamaño que periodos)
     */
    public ForecastResult pronosticar(List<String> periodos, double[] serie) {
        if (periodos.size() != serie.length) {
            throw new IllegalArgumentException("Periodos y serie deben tener el mismo tamaño");
        }
        List<String> advertencias = new ArrayList<>();
        List<String> futuros = periodosFuturos(periodos, HORIZONTE_MAXIMO);
        int n = serie.length;
        DemandClassifier.Clasificacion clasificacion = DemandClassifier.clasificar(serie);

        if (n == 0 || Statistics.sum(serie) == 0) {
            advertencias.add("No hay ventas registradas para este producto: no es posible estimar la demanda.");
            return new ForecastResult(periodos, serie, List.of(), null, futuros, Tendencia.INDETERMINADA, 0,
                    Confianza.INSUFICIENTE, advertencias, clasificacion);
        }
        if (n < MINIMO_ABSOLUTO) {
            advertencias.add("Solo hay " + n + " mes(es) de historial. Se requieren al menos " + MINIMO_ABSOLUTO
                    + " meses para generar una predicción; no se muestra un pronóstico para no inventar datos.");
            return new ForecastResult(periodos, serie, List.of(), null, futuros, Tendencia.INDETERMINADA, 0,
                    Confianza.INSUFICIENTE, advertencias, clasificacion);
        }

        // ---------------------------------------------------------- diagnóstico
        SimpleRegression reg = Statistics.regression(serie);
        double pendiente = reg.getSlope();
        double media = Statistics.mean(serie);
        Tendencia tendencia = Tendencia.ESTABLE;
        double significancia = Double.isNaN(reg.getSignificance()) ? 1 : reg.getSignificance();
        if (media > 0 && Math.abs(pendiente / media) >= 0.02 && significancia < 0.10) {
            tendencia = pendiente > 0 ? Tendencia.CRECIENTE : Tendencia.DECRECIENTE;
        }
        double estacionalidad = autocorrelacionEstacional(serie);
        boolean estacional = !Double.isNaN(estacionalidad) && estacionalidad >= ForecastResult.UMBRAL_ESTACIONAL;
        boolean intermitente = clasificacion.patron() == DemandPattern.INTERMITENTE
                || clasificacion.patron() == DemandPattern.IRREGULAR;
        double[] entrenamiento = intermitente ? serie.clone() : moderarAtipicos(serie);
        int atipicos = 0;
        for (int i = 0; i < n; i++) {
            if (entrenamiento[i] < serie[i]) {
                atipicos++;
            }
        }
        if (atipicos > 0) {
            advertencias.add(atipicos + " mes(es) con ventas atípicas (muy por encima de lo habitual): se moderaron para "
                    + "entrenar los modelos; el error siempre se mide contra las ventas reales.");
        }

        // ---------------------------------------------------------- candidatos
        List<ForecastMethod> aplicables = new ArrayList<>();
        for (ForecastMethod m : metodos()) {
            if (n >= m.minimoDatos()) {
                aplicables.add(m);
            } else {
                advertencias.add(m.nombre() + " no se aplicó: requiere " + m.minimoDatos() + " meses de datos.");
            }
        }
        aplicables.add(NaiveForecast.simple());
        if (tendencia == Tendencia.CRECIENTE || tendencia == Tendencia.DECRECIENTE) {
            agregar(aplicables, new HoltForecast(), n, 2, advertencias, "tendencia");
        }
        if (estacional) {
            agregar(aplicables, new HoltWintersForecast(), n, 3, advertencias, "estacionalidad");
            if (aplicables.stream().anyMatch(m -> m instanceof HoltWintersForecast)) {
                aplicables.add(NaiveForecast.estacional());
            }
        }
        for (ForecastMethod m : especializados(clasificacion.patron())) {
            int puntos = m instanceof GradientBoostingForecast ? PUNTOS_MINIMOS_GBDT : 2;
            agregar(aplicables, m, n, puntos, advertencias,
                    "demanda " + clasificacion.patron().etiqueta().toLowerCase());
        }

        // ---------------------------------------------------------- backtesting en el mismo periodo
        int inicio = aplicables.stream().mapToInt(ForecastMethod::minimoDatos).max().orElse(MINIMO_ABSOLUTO);
        List<MethodResult> resultados = new ArrayList<>();
        for (ForecastMethod m : aplicables) {
            ForecastEvaluator.Evaluation ev = ForecastEvaluator.evaluar(m, entrenamiento, serie, inicio);
            resultados.add(new MethodResult(m.nombre(), m.pronosticar(entrenamiento, HORIZONTE_MAXIMO), ev.ajustes(),
                    ev.mae(), ev.mape(), ev.puntos(), ev.rmse(), ev.wape(), ev.sesgo(), m.complejidad(),
                    m.referencia(), ev.multipaso()));
        }

        String criterio = intermitente ? "RMSE" : "MAE";
        MethodResult combinacion = combinar(resultados, serie, inicio, intermitente);
        if (combinacion != null) {
            resultados.add(combinacion);
        }
        MethodResult mejor;
        int puntos = resultados.isEmpty() ? 0 : resultados.get(0).puntosEvaluados();
        if (puntos >= 2) {
            mejor = seleccionar(resultados, intermitente ? MethodResult::rmse : MethodResult::mae);
        } else {
            // Sin puntos suficientes para medir el error se usa el promedio ponderado (más robusto con poca historia).
            mejor = resultados.stream().filter(r -> r.metodo().startsWith("Promedio móvil ponderado"))
                    .findFirst().orElse(resultados.get(0));
            advertencias.add("El historial no permite medir el error de predicción (se necesitan al menos "
                    + (inicio + 2) + " meses). Se usó el promedio móvil ponderado por defecto.");
        }
        MethodResult ingenuo = resultados.stream().filter(r -> r.referencia() && r.complejidad() == 0).findFirst()
                .orElse(null);
        if (puntos >= 2 && ingenuo != null && mejor != ingenuo && mejor.mae() != null && ingenuo.mae() != null
                && mejor.mae() > ingenuo.mae()) {
            advertencias.add("Ningún modelo superó claramente al pronóstico ingenuo en el periodo de validación.");
        }

        // ---------------------------------------------------------- calidad de datos y confianza
        double ceros = Statistics.zeroRatio(serie);
        if (ceros >= 0.5) {
            advertencias.add(String.format("Demanda intermitente: %.0f%% de los meses sin ventas. "
                    + "Las predicciones pueden ser poco precisas.", ceros * 100));
        }
        double cv = Statistics.coefficientOfVariation(serie);
        if (cv > 1.0) {
            advertencias.add("Alta variabilidad en las ventas (coeficiente de variación " + String.format("%.2f", cv)
                    + "). Considere la predicción como referencial.");
        }
        if (n < params.mesesConfiables()) {
            advertencias.add("Historial corto (" + n + " meses). Se recomiendan al menos " + params.mesesConfiables()
                    + " meses para una predicción confiable.");
        }
        ForecastResult.Horizontes horizontes = horizontes(resultados, mejor, serie, intermitente);
        Confianza confianza;
        // WAPE cuando existe (considera los meses sin ventas); si no, MAPE. La confianza exige haber demostrado la
        // precisión con suficientes meses de validación: con pocos puntos el error medido no es concluyente.
        Double error = mejor.wape() != null ? mejor.wape() : mejor.mape();
        int validados = mejor.puntosEvaluados();
        if (validados < PUNTOS_CONFIANZA_MEDIA) {
            confianza = Confianza.BAJA;
            advertencias.add("Precisión no demostrada: solo " + validados + " mes(es) de validación. Tome el "
                    + "pronóstico como referencial.");
        } else if (n >= params.mesesConfiables() && error != null && error <= 0.25 && ceros < 0.5
                && validados >= PUNTOS_CONFIANZA_ALTA) {
            confianza = Confianza.ALTA;
        } else if (n >= params.mesesConfiables() && (error == null || error <= 0.5)) {
            confianza = Confianza.MEDIA;
        } else if (n < params.mesesConfiables() && error != null && error <= 0.25) {
            confianza = Confianza.MEDIA;
        } else {
            confianza = Confianza.BAJA;
        }
        return new ForecastResult(periodos, serie, resultados, mejor, futuros, tendencia, pendiente, confianza,
                advertencias, clasificacion, estacionalidad, atipicos, criterio, horizontes);
    }

    /** Meses de validación mínimos para una confianza alta / media (por debajo: precisión no demostrada). */
    public static final int PUNTOS_CONFIANZA_ALTA = 4;
    public static final int PUNTOS_CONFIANZA_MEDIA = 2;

    /**
     * Precisión fuera de muestra del modelo elegido a 1, 2 y 3 meses y errores de la demanda acumulada (base de los
     * rangos), a partir de sus pronósticos multipaso del backtesting.
     * <p>
     * Se evaluó también elegir un modelo distinto por horizonte: en los datos reales empeoró levemente el WAPE a 2 y
     * 3 meses (ruido de selección con pocos meses de validación), por lo que se usa el mismo modelo en todos los
     * horizontes y solo se informa su precisión en cada uno.
     */
    static ForecastResult.Horizontes horizontes(List<MethodResult> resultados, MethodResult mejor, double[] real,
                                                boolean porRmse) {
        int n = real.length;
        // La combinación solo tiene pronósticos a 1 paso en el backtesting.
        double[][] comp = new double[n][ForecastEvaluator.HORIZONTES];
        for (int t = 0; t < n; t++) {
            for (int h = 1; h <= ForecastEvaluator.HORIZONTES; h++) {
                comp[t][h - 1] = mejor.multipaso() != null ? mejor.multipaso()[t][h - 1]
                        : (h == 1 ? mejor.ajustes()[t] : Double.NaN);
            }
        }
        double[][] metricas = new double[ForecastEvaluator.HORIZONTES][];
        double[][] errores = new double[ForecastEvaluator.HORIZONTES][];
        List<String> modelos = new ArrayList<>();
        for (int h = 1; h <= ForecastEvaluator.HORIZONTES; h++) {
            metricas[h - 1] = ForecastEvaluator.metricasHorizonte(comp, real, h);
            errores[h - 1] = ForecastEvaluator.erroresAcumulados(comp, real, h);
            modelos.add(mejor.metodo());
        }
        return new ForecastResult.Horizontes(mejor.pronostico().clone(), modelos, metricas, errores);
    }

    public static final String COMBINACION = "Combinación de los 3 mejores";
    static final int MODELOS_COMBINADOS = 3;

    /**
     * Combinación (promedio) de los 3 mejores modelos, evaluada sin mirar el futuro: en cada mes de validación los
     * 3 mejores se eligen con los errores de los meses de validación <b>anteriores</b> (en los dos primeros meses se
     * promedian todos). Así su error es tan fuera de muestra como el de los demás y compite en igualdad. El
     * pronóstico final promedia los 3 mejores de toda la ventana. Nulo si hay menos de 3 modelos o 4 meses de
     * validación.
     */
    static MethodResult combinar(List<MethodResult> resultados, double[] real, int inicio, boolean porRmse) {
        List<MethodResult> base = resultados.stream().filter(r -> !r.referencia() && r.mae() != null).toList();
        int n = real.length;
        if (base.size() < MODELOS_COMBINADOS || n - inicio < 4) {
            return null;
        }
        double[] ajustes = new double[n];
        Arrays.fill(ajustes, Double.NaN);
        double sumAbs = 0;
        double sumCuad = 0;
        double sumErr = 0;
        double sumReal = 0;
        double sumPct = 0;
        int puntos = 0;
        int nPct = 0;
        for (int t = inicio; t < n; t++) {
            List<MethodResult> elegidos = base;
            if (t - inicio >= 2) {
                final int hasta = t;
                elegidos = base.stream().sorted(Comparator.comparingDouble(r -> errorHasta(r, real, inicio, hasta,
                        porRmse))).limit(MODELOS_COMBINADOS).toList();
            }
            final int tt = t;
            double pred = elegidos.stream().mapToDouble(r -> r.ajustes()[tt]).average().orElse(Double.NaN);
            if (Double.isNaN(pred)) {
                return null;
            }
            ajustes[t] = pred;
            double e = pred - real[t];
            sumAbs += Math.abs(e);
            sumCuad += e * e;
            sumErr += e;
            sumReal += real[t];
            puntos++;
            if (real[t] > 0) {
                sumPct += Math.abs(e) / real[t];
                nPct++;
            }
        }
        List<MethodResult> top = base.stream().sorted(Comparator.comparingDouble(r -> errorHasta(r, real, inicio, n,
                porRmse))).limit(MODELOS_COMBINADOS).toList();
        double[] pron = new double[HORIZONTE_MAXIMO];
        for (int h = 0; h < HORIZONTE_MAXIMO; h++) {
            final int hh = h;
            pron[h] = top.stream().mapToDouble(r -> r.pronostico()[hh]).average().orElse(0);
        }
        return new MethodResult(COMBINACION, pron, ajustes, sumAbs / puntos, nPct == 0 ? null : sumPct / nPct, puntos,
                Math.sqrt(sumCuad / puntos), sumReal > 0 ? sumAbs / sumReal : null,
                sumReal > 0 ? sumErr / sumReal : null, 2, false);
    }

    /** Error (MAE o RMSE) de un modelo en los meses de validación [inicio, hasta). */
    private static double errorHasta(MethodResult r, double[] real, int inicio, int hasta, boolean porRmse) {
        double s = 0;
        int k = 0;
        for (int t = inicio; t < hasta; t++) {
            double a = r.ajustes()[t];
            if (!Double.isNaN(a)) {
                double e = a - real[t];
                s += porRmse ? e * e : Math.abs(e);
                k++;
            }
        }
        return k == 0 ? Double.MAX_VALUE : (porRmse ? Math.sqrt(s / k) : s / k);
    }

    private static void agregar(List<ForecastMethod> lista, ForecastMethod m, int n, int puntosMinimos,
                                List<String> advertencias, String motivo) {
        if (n >= m.minimoDatos() + puntosMinimos) {
            lista.add(m);
        } else {
            advertencias.add(m.nombre() + " (modelo para " + motivo + ") no se aplicó: requiere "
                    + (m.minimoDatos() + puntosMinimos) + " meses de datos.");
        }
    }

    /**
     * Selección por menor error con parsimonia: entre los modelos con error ≤ mejor · (1 + tolerancia) se elige el
     * de menor complejidad (y, a igual complejidad, el de menor error).
     */
    static MethodResult seleccionar(List<MethodResult> resultados, Function<MethodResult, Double> metrica) {
        List<MethodResult> validos = resultados.stream().filter(r -> metrica.apply(r) != null).toList();
        if (validos.isEmpty()) {
            return resultados.get(0);
        }
        double mejor = validos.stream().mapToDouble(metrica::apply).min().orElse(0);
        double limite = mejor * (1 + TOLERANCIA_PARSIMONIA_PCT / 100.0) + 1e-9;
        return validos.stream().filter(r -> metrica.apply(r) <= limite)
                .min(Comparator.comparingInt(MethodResult::complejidad).thenComparingDouble(metrica::apply)
                        .thenComparingDouble(r -> r.mape() == null ? Double.MAX_VALUE : r.mape()))
                .orElse(validos.get(0));
    }

    /**
     * Autocorrelación a 12 meses de la serie sin tendencia lineal. NaN si hay menos de 24 meses o la serie es
     * constante.
     */
    public static double autocorrelacionEstacional(double[] serie) {
        int n = serie.length;
        if (n < 2 * HoltWintersForecast.PERIODO) {
            return Double.NaN;
        }
        SimpleRegression r = Statistics.regression(serie);
        double[] res = new double[n];
        double m = 0;
        for (int i = 0; i < n; i++) {
            res[i] = serie[i] - r.predict(i);
            m += res[i];
        }
        m /= n;
        double den = 0;
        for (double v : res) {
            den += (v - m) * (v - m);
        }
        if (den <= 1e-12) {
            return Double.NaN;
        }
        double num = 0;
        for (int i = 12; i < n; i++) {
            num += (res[i] - m) * (res[i - 12] - m);
        }
        return num / den;
    }

    /**
     * Modera ventas atípicas (picos por pedidos excepcionales): calcula el residuo de cada mes respecto de la tendencia
     * lineal y recorta los que superan {@value #K_ATIPICOS} desviaciones robustas (1,4826 · MAD) al valor límite.
     * Solo afecta a los picos hacia arriba; requiere al menos 6 meses.
     */
    public static double[] moderarAtipicos(double[] serie) {
        int n = serie.length;
        double[] out = serie.clone();
        if (n < 6) {
            return out;
        }
        SimpleRegression r = Statistics.regression(serie);
        double[] res = new double[n];
        for (int i = 0; i < n; i++) {
            res[i] = serie[i] - r.predict(i);
        }
        double med = mediana(res);
        double[] dev = Arrays.stream(res).map(v -> Math.abs(v - med)).toArray();
        double mad = mediana(dev) * 1.4826;
        if (mad <= 1e-9) {
            return out;
        }
        for (int i = 0; i < n; i++) {
            double limite = r.predict(i) + med + K_ATIPICOS * mad;
            if (serie[i] > limite) {
                out[i] = Math.max(0, limite);
            }
        }
        return out;
    }

    private static double mediana(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    /**
     * Modelos especializados por patrón de demanda:
     * suave → LightGBM; errática → CatBoost; intermitente → Croston-TSB; irregular → Croston-TSB y CatBoost.
     */
    public static List<ForecastMethod> especializados(DemandPattern patron) {
        return switch (patron) {
            case SUAVE -> List.of(new LightGbmForecast());
            case ERRATICA -> List.of(new CatBoostForecast());
            case INTERMITENTE -> List.of(new CrostonTsbForecast());
            case IRREGULAR -> List.of(new CrostonTsbForecast(), new CatBoostForecast());
            case SIN_CLASIFICAR -> List.of();
        };
    }

    public static List<String> periodosFuturos(List<String> periodos, int horizonte) {
        List<String> out = new ArrayList<>();
        if (periodos.isEmpty()) {
            return out;
        }
        YearMonth ultimo = YearMonth.parse(periodos.get(periodos.size() - 1));
        for (int i = 1; i <= horizonte; i++) {
            out.add(ultimo.plusMonths(i).toString());
        }
        return out;
    }
}
