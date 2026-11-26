package com.stockwise.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Reconstrucción del stock histórico diario hacia atrás desde el stock actual (cálculo puro, sin acceso a datos).
 * <pre>
 *   stock_cierre(hoy)   = stock actual del inventario
 *   stock_cierre(d − 1) = stock_cierre(d) − entradas(d) + salidas(d)
 * </pre>
 * Entradas: compras recibidas, entradas, traslados recibidos y ajustes positivos. Salidas: ventas, salidas,
 * traslados enviados y ajustes negativos. Las cargas iniciales de inventario (importación) son mediciones, no
 * flujos, y no se recorren. Un stock reconstruido negativo indica que faltan registros (compras o ajustes)
 * anteriores a esa fecha: se conserva el valor para diagnosticarlo y la reconstrucción se marca como inconsistente.
 */
public final class StockReconstructor {

    /** Stock al cierre de un día y flujos del día. */
    public record DiaStock(LocalDate fecha, double stock, double entradas, double salidas) {
        public boolean sinStock() {
            return stock <= 1e-9;
        }

        public boolean negativo() {
            return stock < -1e-9;
        }
    }

    /** Resumen de disponibilidad en un rango de fechas. */
    public record Resumen(int dias, int diasSinStock, int diasNegativos, LocalDate primerNegativo,
                          LocalDate ultimoNegativo) {
        /** Proporción de días con stock disponible (nula si el rango no tiene días reconstruidos). */
        public Double disponibilidad() {
            return dias == 0 ? null : (dias - diasSinStock) / (double) dias;
        }

        public boolean consistente() {
            return diasNegativos == 0;
        }
    }

    private StockReconstructor() {
    }

    /**
     * @param stockFinal stock al cierre de {@code hasta}
     * @param desde      primer día a reconstruir
     * @param hasta      día del stock conocido (normalmente hoy)
     * @param flujos     fecha → {entradas, salidas} (magnitudes positivas); se ignoran fechas fuera del rango
     * @return días en orden ascendente
     */
    public static List<DiaStock> reconstruir(double stockFinal, LocalDate desde, LocalDate hasta,
                                             NavigableMap<LocalDate, double[]> flujos) {
        List<DiaStock> out = new ArrayList<>();
        if (desde == null || hasta == null || desde.isAfter(hasta)) {
            return out;
        }
        double stock = stockFinal;
        for (LocalDate d = hasta; !d.isBefore(desde); d = d.minusDays(1)) {
            double[] f = flujos.getOrDefault(d, new double[2]);
            out.add(new DiaStock(d, redondear(stock), f[0], f[1]));
            stock = stock - f[0] + f[1];
        }
        Collections.reverse(out);
        return out;
    }

    private static double redondear(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** Primer día con algún flujo (o {@code hasta} si no hay flujos). */
    public static LocalDate inicio(Map<LocalDate, double[]> flujos, LocalDate hasta) {
        return flujos.keySet().stream().filter(d -> !d.isAfter(hasta)).min(LocalDate::compareTo).orElse(hasta);
    }

    public static Resumen resumir(List<DiaStock> dias, LocalDate desde, LocalDate hasta) {
        int n = 0;
        int sin = 0;
        int neg = 0;
        LocalDate primer = null;
        LocalDate ultimo = null;
        for (DiaStock d : dias) {
            if ((desde != null && d.fecha().isBefore(desde)) || (hasta != null && d.fecha().isAfter(hasta))) {
                continue;
            }
            n++;
            if (d.sinStock()) {
                sin++;
            }
            if (d.negativo()) {
                neg++;
                if (primer == null) {
                    primer = d.fecha();
                }
                ultimo = d.fecha();
            }
        }
        return new Resumen(n, sin, neg, primer, ultimo);
    }
}
