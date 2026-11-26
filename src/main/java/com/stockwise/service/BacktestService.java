package com.stockwise.service;

import com.stockwise.analytics.MetricsCalculator;
import com.stockwise.model.Producto;
import com.stockwise.prediction.DemandClassifier;
import com.stockwise.prediction.DemandPattern;
import com.stockwise.prediction.ForecastEngine;
import com.stockwise.prediction.ForecastResult;
import com.stockwise.repository.ProductoRepository;
import com.stockwise.repository.VentaRepository;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Backtesting walk-forward del proceso completo de pronóstico: para cada mes de prueba se entrena el motor (incluida
 * la clasificación ADI/CV² y la selección del mejor modelo) solo con los meses anteriores al origen y se compara el
 * pronóstico a {@code h} meses con la venta real. Se compara contra un pronóstico ingenuo (último mes observado)
 * para medir el valor agregado del pronóstico (FVA).
 */
public class BacktestService {

    /** Error de un producto en el periodo de prueba. */
    public record ResultadoProducto(long productoId, String codigo, String nombre, DemandPattern patron, int puntos,
                                    double real, double mae, double wape, double sesgo, double maeIngenuo,
                                    Double fva, String metodoMasUsado) {
    }

    /** Error agregado de un grupo (patrón o modelo). */
    public record Grupo(String nombre, int productos, int puntos, Double wape, Double sesgo, Double fva) {
    }

    /**
     * @param wape  error absoluto ponderado = Σ|pronóstico − real| / Σ real (nulo si no hubo ventas reales)
     * @param sesgo Σ(pronóstico − real) / Σ real (positivo = sobreestima)
     * @param fva   1 − MAE modelo / MAE ingenuo (positivo = el modelo mejora al ingenuo)
     */
    public record Resultado(int mesesPrueba, int horizonte, List<ResultadoProducto> productos,
                            List<String> sinDatos, Double wape, Double sesgo, Double fva,
                            List<Grupo> porPatron, List<Grupo> porModelo, List<String> notas,
                            Double coberturaIntervalo) {

        /** Compatibilidad: sin medición de cobertura del rango. */
        public Resultado(int mesesPrueba, int horizonte, List<ResultadoProducto> productos, List<String> sinDatos,
                         Double wape, Double sesgo, Double fva, List<Grupo> porPatron, List<Grupo> porModelo,
                         List<String> notas) {
            this(mesesPrueba, horizonte, productos, sinDatos, wape, sesgo, fva, porPatron, porModelo, notas, null);
        }

        public boolean disponible() {
            return !productos.isEmpty();
        }

        /** Precisión = 1 − WAPE, acotada a [0, 1]. */
        public Double precision() {
            return wape == null ? null : Math.max(0, Math.min(1, 1 - wape));
        }
    }

    private final AnalysisService analysis;
    private final ProductoRepository productos;
    private final VentaRepository ventas;

    public BacktestService(AnalysisService analysis, ProductoRepository productos, VentaRepository ventas) {
        this.analysis = analysis;
        this.productos = productos;
        this.ventas = ventas;
    }

    public Resultado ejecutar(int mesesPrueba, int horizonte) {
        if (mesesPrueba < 1 || mesesPrueba > 24) {
            throw new ValidationException("Los meses de prueba deben estar entre 1 y 24.");
        }
        if (horizonte < 1 || horizonte > ForecastEngine.HORIZONTE_MAXIMO) {
            throw new ValidationException("El horizonte debe estar entre 1 y " + ForecastEngine.HORIZONTE_MAXIMO + ".");
        }
        YearMonth fin = AnalysisService.ultimoMesCompleto(analysis.fechaCorte());
        Map<Long, Map<String, Double>> mensual = new HashMap<>();
        for (VentaRepository.ProductMonth pm : ventas.mensualPorProducto()) {
            mensual.computeIfAbsent(pm.productoId(), k -> new HashMap<>()).put(pm.periodo(), pm.unidades());
        }
        List<Serie> series = new ArrayList<>();
        for (Producto p : productos.findActivos()) {
            Map<String, Double> m = mensual.getOrDefault(p.id(), Map.of());
            List<String> per = MetricsCalculator.periodos(m, fin);
            series.add(new Serie(p, per, MetricsCalculator.serie(m, per)));
        }
        return ejecutar(series, analysis.engine(), mesesPrueba, horizonte);
    }

    /** Serie mensual de un producto. */
    public record Serie(Producto producto, List<String> periodos, double[] valores) {
    }

    /** Backtest puro sobre series ya construidas. */
    public static Resultado ejecutar(List<Serie> series, ForecastEngine engine, int mesesPrueba, int horizonte) {
        List<ResultadoProducto> res = new ArrayList<>();
        int[] cobertura = new int[2]; // dentro del rango, evaluados
        List<String> sinDatos = new ArrayList<>();
        for (Serie s : series) {
            int n = s.valores().length;
            double sumAbs = 0;
            double sumErr = 0;
            double sumReal = 0;
            double sumAbsIng = 0;
            int puntos = 0;
            Map<String, Integer> usos = new HashMap<>();
            for (int j = Math.max(0, n - mesesPrueba); j < n; j++) {
                int t = j - horizonte + 1; // origen: se entrena con [0, t)
                if (t < ForecastEngine.MINIMO_ABSOLUTO) {
                    continue;
                }
                ForecastResult r = engine.pronosticar(s.periodos().subList(0, t), Arrays.copyOfRange(s.valores(), 0, t));
                if (!r.suficiente()) {
                    continue;
                }
                double pred = r.pronostico()[horizonte - 1];
                double real = s.valores()[j];
                double ingenuo = s.valores()[t - 1];
                sumAbs += Math.abs(pred - real);
                sumErr += pred - real;
                sumReal += real;
                sumAbsIng += Math.abs(ingenuo - real);
                // Cobertura fuera de muestra del rango del 80 % del mes siguiente (calculado solo con datos previos).
                if (horizonte == 1) {
                    double[] rg = com.stockwise.prediction.HorizonForecast.rango(r.horizontes(),
                            com.stockwise.prediction.HorizonForecast.DIAS_MES);
                    if (rg != null) {
                        cobertura[1]++;
                        if (real >= Math.max(0, pred + rg[0]) - 1e-9 && real <= Math.max(0, pred + rg[1]) + 1e-9) {
                            cobertura[0]++;
                        }
                    }
                }
                puntos++;
                usos.merge(r.mejor().metodo(), 1, Integer::sum);
            }
            if (puntos == 0) {
                sinDatos.add(s.producto().toString());
                continue;
            }
            double mae = sumAbs / puntos;
            double maeIng = sumAbsIng / puntos;
            String metodo = usos.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                    .orElse("—");
            res.add(new ResultadoProducto(s.producto().id(), s.producto().codigo(), s.producto().nombre(),
                    DemandClassifier.clasificar(s.valores()).patron(), puntos, sumReal, mae,
                    sumReal > 0 ? sumAbs / sumReal : Double.NaN, sumReal > 0 ? sumErr / sumReal : Double.NaN,
                    maeIng, maeIng > 0 ? 1 - mae / maeIng : null, metodo));
        }
        List<String> notas = new ArrayList<>();
        notas.add("Walk-forward: en cada mes de prueba el motor se entrena solo con los meses anteriores al origen "
                + "(sin usar información futura) y pronostica a " + horizonte + " mes(es).");
        if (!sinDatos.isEmpty()) {
            notas.add(sinDatos.size() + " producto(s) sin historial suficiente para el backtest (se requieren al menos "
                    + (ForecastEngine.MINIMO_ABSOLUTO + horizonte) + " meses con ventas): resultado no disponible.");
        }
        Grupo total = grupo("Total", res);
        return new Resultado(mesesPrueba, horizonte, res, sinDatos, total.wape(), total.sesgo(), total.fva(),
                agrupar(res, r -> r.patron().etiqueta()), agrupar(res, ResultadoProducto::metodoMasUsado), notas,
                cobertura[1] == 0 ? null : cobertura[0] / (double) cobertura[1]);
    }

    private static List<Grupo> agrupar(List<ResultadoProducto> res, Function<ResultadoProducto, String> clave) {
        Map<String, List<ResultadoProducto>> g = res.stream()
                .collect(Collectors.groupingBy(clave, LinkedHashMap::new, Collectors.toList()));
        return g.entrySet().stream().map(e -> grupo(e.getKey(), e.getValue())).toList();
    }

    private static Grupo grupo(String nombre, List<ResultadoProducto> res) {
        double abs = 0;
        double err = 0;
        double real = 0;
        double absIng = 0;
        int puntos = 0;
        for (ResultadoProducto r : res) {
            abs += r.mae() * r.puntos();
            absIng += r.maeIngenuo() * r.puntos();
            if (!Double.isNaN(r.sesgo())) {
                err += r.sesgo() * r.real();
            }
            real += r.real();
            puntos += r.puntos();
        }
        return new Grupo(nombre, res.size(), puntos, real > 0 ? abs / real : null, real > 0 ? err / real : null,
                absIng > 0 ? 1 - abs / absIng : null);
    }
}
