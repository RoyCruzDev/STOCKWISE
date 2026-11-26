package com.stockwise.analytics;

import com.stockwise.model.Producto;
import com.stockwise.prediction.ForecastEngine;
import com.stockwise.prediction.ForecastResult;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Cálculo puro (sin acceso a datos) de los indicadores de un producto. */
public final class MetricsCalculator {

    /** Parámetros de inventario necesarios para clasificar. */
    public record Params(int stockMinimoDefecto, int leadTimeDefecto, double mesesSobreinventario,
                         int diasSinMovimiento) {
    }

    /** Ventas agregadas de un producto. */
    public record SalesData(Map<String, Double> unidadesPorMes, double unidades30, double unidades90,
                            double unidades365, double importe365, LocalDate ultimaVenta) {
    }

    private MetricsCalculator() {
    }

    /**
     * Construye la serie mensual consecutiva de un producto desde su primer mes con ventas hasta {@code fin}
     * (inclusive), rellenando con cero los meses sin ventas.
     */
    public static List<String> periodos(Map<String, Double> unidadesPorMes, YearMonth fin) {
        List<String> out = new ArrayList<>();
        YearMonth inicio = unidadesPorMes.keySet().stream().filter(k -> unidadesPorMes.get(k) > 0)
                .map(YearMonth::parse).min(YearMonth::compareTo).orElse(null);
        if (inicio == null || fin == null || inicio.isAfter(fin)) {
            return out;
        }
        for (YearMonth ym = inicio; !ym.isAfter(fin); ym = ym.plusMonths(1)) {
            out.add(ym.toString());
        }
        return out;
    }

    public static double[] serie(Map<String, Double> unidadesPorMes, List<String> periodos) {
        double[] s = new double[periodos.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = unidadesPorMes.getOrDefault(periodos.get(i), 0.0);
        }
        return s;
    }

    public static ProductMetrics calcular(Producto p, int stock, SalesData ventas, ForecastEngine engine,
                                          YearMonth ultimoMesCompleto, LocalDate fechaCorte, Params params) {
        return calcular(p, stock, ventas, engine, ultimoMesCompleto, fechaCorte, params, null);
    }

    /**
     * @param leadTimeProveedor tiempo de reposición del proveedor (configurado u observado) que se usa cuando el
     *                          producto no tiene uno propio; nulo = tiempo por defecto
     */
    public static ProductMetrics calcular(Producto p, int stock, SalesData ventas, ForecastEngine engine,
                                          YearMonth ultimoMesCompleto, LocalDate fechaCorte, Params params,
                                          Integer leadTimeProveedor) {
        List<String> periodos = periodos(ventas.unidadesPorMes(), ultimoMesCompleto);
        double[] serie = serie(ventas.unidadesPorMes(), periodos);
        ForecastResult fr = engine.pronosticar(periodos, serie);

        double demanda;
        boolean confiable;
        if (fr.suficiente()) {
            demanda = fr.pronostico()[0];
            confiable = true;
        } else if (serie.length > 0) {
            demanda = Statistics.mean(serie);
            confiable = false;
        } else {
            // Ventas solo en el mes en curso (incompleto): se usa el promedio diario observado de 90 días.
            demanda = ventas.unidades90() > 0 ? ventas.unidades90() / 3.0 : 0;
            confiable = false;
        }
        double desviacion = Statistics.stdDev(Statistics.tail(serie, 12));
        int stockMin = p.stockMinimo() > 0 ? p.stockMinimo() : params.stockMinimoDefecto();
        int leadTime = p.leadTimeDias() != null && p.leadTimeDias() > 0 ? p.leadTimeDias()
                : leadTimeProveedor != null && leadTimeProveedor > 0 ? leadTimeProveedor : params.leadTimeDefecto();
        double cobertura = demanda > 0 ? stock / (demanda / ProductMetrics.DIAS_POR_MES) : Double.POSITIVE_INFINITY;
        Double rotacion = stock > 0 ? ventas.unidades365() / stock : null;
        Long diasSinVenta = ventas.ultimaVenta() == null ? null
                : ChronoUnit.DAYS.between(ventas.ultimaVenta(), fechaCorte);

        EstadoInventario estado = clasificar(stock, stockMin, p.stockMaximo(), leadTime, demanda, cobertura,
                ventas.ultimaVenta() != null, diasSinVenta, params);
        return new ProductMetrics(p, stock, stockMin, p.stockMaximo(), leadTime, fr, ventas.unidades30(),
                ventas.unidades90(), ventas.unidades365(), ventas.importe365(), ventas.unidades365() * p.costo(),
                demanda, confiable, desviacion, ventas.ultimaVenta(), diasSinVenta, cobertura, rotacion, estado);
    }

    /** Reglas de clasificación del estado del inventario. */
    public static EstadoInventario clasificar(int stock, int stockMin, int stockMax, int leadTimeDias, double demandaMensual,
                                              double coberturaDias, boolean tuvoVentas, Long diasSinVenta,
                                              Params params) {
        if (stock <= 0) {
            return demandaMensual > 0 || tuvoVentas ? EstadoInventario.AGOTADO : EstadoInventario.SIN_MOVIMIENTO;
        }
        if (!tuvoVentas || (diasSinVenta != null && diasSinVenta >= params.diasSinMovimiento())) {
            return EstadoInventario.SIN_MOVIMIENTO;
        }
        if (stock <= stockMin || coberturaDias < leadTimeDias) {
            return EstadoInventario.RIESGO_AGOTAMIENTO;
        }
        if ((stockMax > 0 && stock > stockMax)
                || coberturaDias / ProductMetrics.DIAS_POR_MES > params.mesesSobreinventario()) {
            return EstadoInventario.SOBREINVENTARIO;
        }
        return EstadoInventario.NORMAL;
    }
}
