package com.stockwise;

import com.stockwise.analytics.EstadoInventario;
import com.stockwise.analytics.MetricsCalculator;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.Producto;
import com.stockwise.prediction.ForecastEngine;
import com.stockwise.service.AppSettings;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

/** Utilidades para construir datos de prueba. */
public final class TestData {

    public static final ForecastEngine ENGINE = new ForecastEngine(new ForecastEngine.Params(3, 3, 0.3, 6));
    public static final MetricsCalculator.Params PARAMS = new MetricsCalculator.Params(5, 15, 4, 90);
    public static final YearMonth FIN = YearMonth.of(2025, 6);
    public static final LocalDate CORTE = FIN.atEndOfMonth();

    private TestData() {
    }

    public static Producto producto(long id, String codigo, double precio, double costo, int min, int max,
                                    Integer leadTime) {
        return new Producto(id, codigo, "Producto " + codigo, null, null, null, null, precio, costo, min, max,
                leadTime, true);
    }

    /** Métricas de un producto con demanda mensual constante durante {@code meses} meses. */
    public static ProductMetrics metrics(Producto p, int stock, double demandaMensual, int meses) {
        Map<String, Double> mensual = new HashMap<>();
        for (int i = 0; i < meses; i++) {
            mensual.put(FIN.minusMonths(i).toString(), demandaMensual);
        }
        LocalDate ultima = meses > 0 ? CORTE.minusDays(2) : null;
        MetricsCalculator.SalesData data = new MetricsCalculator.SalesData(mensual, demandaMensual,
                demandaMensual * 3, demandaMensual * Math.min(12, meses), demandaMensual * Math.min(12, meses) * p.precioVenta(),
                ultima);
        return MetricsCalculator.calcular(p, stock, data, ENGINE, FIN, CORTE, PARAMS);
    }

    public static AppSettings settings() {
        return AppSettings.defaults();
    }

    public static boolean estado(ProductMetrics m, EstadoInventario e) {
        return m.estado() == e;
    }
}
