package com.stockwise.service;

import com.stockwise.analytics.ProductMetrics;
import com.stockwise.prediction.DemandPattern;
import com.stockwise.prediction.ForecastResult;
import com.stockwise.prediction.ForecastResult.MethodResult;
import com.stockwise.prediction.HorizonForecast;
import com.stockwise.repository.DecisionRepository.DetalleAnalisis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Construye el detalle guardado de cada producto en una corrida de análisis y los indicadores agregados. */
public final class ResultadosBuilder {

    /** Precisión agregada del backtesting (ponderada por volumen). */
    public record Agregados(int conPronostico, Double wape, Double sesgo, Double fva) {
    }

    /** Meses de historia hasta los que un producto con ventas recientes se considera nuevo. */
    public static final int MESES_PRODUCTO_NUEVO = 3;

    private ResultadosBuilder() {
    }

    /** Confianza informada para la estimación provisional de productos nuevos (sin backtesting). */
    public static final String PROVISIONAL = "PROVISIONAL";
    /** Días mínimos de ventas observadas para una estimación provisional. */
    public static final int DIAS_MINIMOS_PROVISIONAL = 14;

    public static DetalleAnalisis detalle(ProductMetrics m, LocalDate corte) {
        return detalle(m, corte, null);
    }

    /**
     * @param primeraVenta fecha de la primera venta del producto (para estimar productos nuevos); puede ser nula
     */
    public static DetalleAnalisis detalle(ProductMetrics m, LocalDate corte, LocalDate primeraVenta) {
        ForecastResult fr = m.forecast();
        List<HorizonForecast.Pronostico> h = HorizonForecast.horizontes(fr, corte);
        MethodResult mejor = fr == null ? null : fr.mejor();
        MethodResult ing = fr == null ? null : fr.ingenuo();
        String confianza = fr == null ? null : fr.confianza().name();
        String perfil = perfil(m);
        // Producto nuevo sin historial para validar modelos: estimación provisional por ritmo de venta observado.
        if (h.isEmpty() && primeraVenta != null && m.ultimaVenta() != null && m.unidades90() > 0) {
            long dias = java.time.temporal.ChronoUnit.DAYS.between(primeraVenta, corte) + 1;
            if (dias >= DIAS_MINIMOS_PROVISIONAL && dias <= 90) {
                List<HorizonForecast.Pronostico> prov = new ArrayList<>();
                for (int d : HorizonForecast.HORIZONTES) {
                    prov.add(HorizonForecast.provisional(m.unidades90(), dias, d));
                }
                h = prov;
                confianza = PROVISIONAL;
                perfil = "Producto nuevo: estimación provisional con " + dias + " días de ventas (sin validación)";
            }
        }
        return new DetalleAnalisis(m.unidades30(), m.unidades90(), m.unidades365(),
                fr == null ? null : fr.tendencia().name(), fr == null ? 0 : fr.pendiente(), perfil,
                valor(h, 0), valor(h, 1), valor(h, 2), valor(h, 3), valor(h, 4),
                mejor == null ? null : mejor.rmse(), confianza,
                mejor == null ? null : mejor.mae(), mejor == null ? null : mejor.rmse(),
                mejor == null ? null : mejor.wape(), mejor == null ? null : mejor.mape(),
                mejor == null ? null : mejor.sesgo(), ing == null ? null : ing.mae(),
                mejor == null ? null : mejor.puntosEvaluados(), fr == null ? null : modelos(fr),
                fr == null ? null : String.join(",", fr.periodos()), fr == null ? null : numeros(fr.historia()),
                mejor == null ? null : numeros(mejor.ajustes()), mejor == null ? null : numeros(fr.pronostico()),
                fr == null ? null : String.join(",", fr.periodosFuturos()),
                Double.isInfinite(m.coberturaDias()) ? null : m.coberturaDias(), m.diasSinVenta(), m.estado().name(),
                m.leadTimeDias(), m.producto().categoria(), fr == null ? null : String.join("\n", fr.advertencias()),
                rangos(h), fr == null ? null : metricasHorizonte(fr.horizontes()));
    }

    /** Rangos por horizonte: "inferior|superior" separados por ';' (vacío si no hay rango medible). */
    static String rangos(List<HorizonForecast.Pronostico> h) {
        if (h.isEmpty()) {
            return null;
        }
        return h.stream().map(p -> num(p.inferior()) + "|" + num(p.superior())).collect(Collectors.joining(";"));
    }

    public static Double[][] leerRangos(String texto) {
        if (texto == null || texto.isBlank()) {
            return new Double[0][];
        }
        String[] partes = texto.split(";", -1);
        Double[][] out = new Double[partes.length][];
        for (int i = 0; i < partes.length; i++) {
            String[] f = partes[i].split("\\|", -1);
            out[i] = new Double[]{parse(f[0]), f.length > 1 ? parse(f[1]) : null};
        }
        return out;
    }

    /** Precisión por horizonte: "meses|mae|rmse|wape|sesgo|puntos" separados por ';'. */
    static String metricasHorizonte(ForecastResult.Horizontes hz) {
        if (hz == null) {
            return null;
        }
        List<String> l = new ArrayList<>();
        for (int i = 0; i < hz.metricas().length; i++) {
            double[] x = hz.metricas()[i];
            l.add((i + 1) + "|" + num(x[0]) + "|" + num(x[1]) + "|" + num(x[2]) + "|" + num(x[3]) + "|" + (int) x[4]);
        }
        return String.join(";", l);
    }

    /** Precisión del pronóstico a un horizonte (meses), leída del texto guardado. */
    public record MetricaHorizonte(int meses, Double mae, Double rmse, Double wape, Double sesgo, int puntos) {
    }

    public static List<MetricaHorizonte> leerMetricasHorizonte(String texto) {
        List<MetricaHorizonte> out = new ArrayList<>();
        if (texto == null || texto.isBlank()) {
            return out;
        }
        for (String s : texto.split(";")) {
            String[] f = s.split("\\|", -1);
            if (f.length >= 6) {
                out.add(new MetricaHorizonte(Integer.parseInt(f[0]), parse(f[1]), parse(f[2]), parse(f[3]),
                        parse(f[4]), Integer.parseInt(f[5])));
            }
        }
        return out;
    }

    /**
     * Pronósticos por horizonte de un resultado guardado: usa los valores y rangos almacenados (v4); para resultados
     * anteriores reconstruye el rango con la aproximación normal del RMSE mensual. Vacío si no hubo pronóstico.
     */
    public static List<HorizonForecast.Pronostico> pronosticosGuardados(DetalleAnalisis d, LocalDate corte) {
        List<HorizonForecast.Pronostico> out = new ArrayList<>();
        if (d == null) {
            return out;
        }
        Double[] valores = {d.p7(), d.p15(), d.p30(), d.p60(), d.p90()};
        if (d.rangos() != null) {
            Double[][] r = leerRangos(d.rangos());
            for (int i = 0; i < HorizonForecast.HORIZONTES.length && valores[i] != null; i++) {
                Double inf = i < r.length ? r[i][0] : null;
                Double sup = i < r.length ? r[i][1] : null;
                out.add(new HorizonForecast.Pronostico(HorizonForecast.HORIZONTES[i], valores[i], inf, sup));
            }
            return out;
        }
        if (d.pronosticoMensual() != null) {
            List<String> fut = leerTextos(d.periodosFuturos());
            double[] men = leerNumeros(d.pronosticoMensual());
            for (int h : HorizonForecast.HORIZONTES) {
                out.add(HorizonForecast.pronostico(fut, men, corte, h, d.sigmaMensual()));
            }
        }
        return out;
    }

    private static Double valor(List<HorizonForecast.Pronostico> h, int i) {
        return h.size() > i ? h.get(i).valor() : null;
    }

    /** Descripción breve del comportamiento de la demanda. */
    public static String perfil(ProductMetrics m) {
        ForecastResult fr = m.forecast();
        if (m.ultimaVenta() == null) {
            return "Sin ventas";
        }
        int meses = fr == null ? 0 : fr.historia().length;
        if (meses <= MESES_PRODUCTO_NUEVO && m.diasSinVenta() != null && m.diasSinVenta() < 90) {
            return "Producto nuevo";
        }
        if (fr == null || !fr.suficiente()) {
            return "Historial insuficiente";
        }
        List<String> partes = new ArrayList<>();
        partes.add(fr.patron() == DemandPattern.SIN_CLASIFICAR ? "Regular" : fr.patron().etiqueta());
        if (fr.estacional()) {
            partes.add("estacional");
        }
        if (fr.tendencia() == ForecastResult.Tendencia.CRECIENTE) {
            partes.add("en crecimiento");
        } else if (fr.tendencia() == ForecastResult.Tendencia.DECRECIENTE) {
            partes.add("en descenso");
        }
        if (fr.atipicos() > 0) {
            partes.add(fr.atipicos() + " mes(es) atípico(s)");
        }
        return String.join(", ", partes);
    }

    /** Modelos comparados: "nombre|mae|rmse|wape|sesgo|complejidad|referencia|seleccionado" separados por ';'. */
    static String modelos(ForecastResult fr) {
        return fr.metodos().stream().map(r -> String.join("|", r.metodo().replace("|", "/").replace(";", ","),
                        num(r.mae()), num(r.rmse()), num(r.wape()), num(r.sesgo()), String.valueOf(r.complejidad()),
                        r.referencia() ? "1" : "0", r == fr.mejor() ? "1" : "0"))
                .collect(Collectors.joining(";"));
    }

    /** Modelo comparado reconstruido desde el texto guardado. */
    public record ModeloComparado(String nombre, Double mae, Double rmse, Double wape, Double sesgo, int complejidad,
                                  boolean referencia, boolean seleccionado) {
    }

    public static List<ModeloComparado> leerModelos(String texto) {
        List<ModeloComparado> out = new ArrayList<>();
        if (texto == null || texto.isBlank()) {
            return out;
        }
        for (String m : texto.split(";")) {
            String[] f = m.split("\\|", -1);
            if (f.length < 8) {
                continue;
            }
            out.add(new ModeloComparado(f[0], parse(f[1]), parse(f[2]), parse(f[3]), parse(f[4]),
                    Integer.parseInt(f[5]), "1".equals(f[6]), "1".equals(f[7])));
        }
        return out;
    }

    static String num(Double v) {
        return v == null || v.isNaN() || v.isInfinite() ? "" : String.format(Locale.ROOT, "%.6g", v);
    }

    static String numeros(double[] v) {
        return Arrays.stream(v).mapToObj(x -> Double.isNaN(x) ? "" : String.format(Locale.ROOT, "%.4f", x))
                .collect(Collectors.joining(","));
    }

    public static Double parse(String s) {
        return s == null || s.isBlank() ? null : Double.parseDouble(s);
    }

    /** Lee una lista de números guardada (vacío = NaN). */
    public static double[] leerNumeros(String s) {
        if (s == null || s.isBlank()) {
            return new double[0];
        }
        return Arrays.stream(s.split(",", -1)).mapToDouble(x -> x.isBlank() ? Double.NaN : Double.parseDouble(x))
                .toArray();
    }

    public static List<String> leerTextos(String s) {
        return s == null || s.isBlank() ? List.of() : List.of(s.split(","));
    }

    /**
     * Precisión agregada del backtesting de todos los productos con pronóstico, ponderada por volumen:
     * WAPE = Σ errores absolutos / Σ ventas reales; FVA = 1 − Σ errores del modelo / Σ errores del ingenuo.
     */
    public static Agregados agregados(List<ProductMetrics> productos) {
        int con = 0;
        double abs = 0;
        double real = 0;
        double err = 0;
        double absIng = 0;
        double absConIng = 0;
        for (ProductMetrics m : productos) {
            ForecastResult fr = m.forecast();
            if (fr == null || !fr.suficiente()) {
                continue;
            }
            con++;
            MethodResult b = fr.mejor();
            if (b.mae() == null || b.puntosEvaluados() == 0) {
                continue;
            }
            double absP = b.mae() * b.puntosEvaluados();
            if (b.wape() != null && b.wape() > 0) {
                double realP = absP / b.wape();
                abs += absP;
                real += realP;
                err += b.sesgo() == null ? 0 : b.sesgo() * realP;
            }
            MethodResult ing = fr.ingenuo();
            if (ing != null && ing.mae() != null) {
                absIng += ing.mae() * ing.puntosEvaluados();
                absConIng += absP;
            }
        }
        return new Agregados(con, real > 0 ? abs / real : null, real > 0 ? err / real : null,
                absIng > 0 ? 1 - absConIng / absIng : null);
    }
}
