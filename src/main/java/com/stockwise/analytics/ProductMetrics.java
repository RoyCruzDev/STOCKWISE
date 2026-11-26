package com.stockwise.analytics;

import com.stockwise.model.Producto;
import com.stockwise.prediction.ForecastResult;

import java.time.LocalDate;

/**
 * Indicadores calculados de un producto a la fecha de corte de los datos.
 *
 * @param stock             stock total en todas las sucursales
 * @param stockMinimo       stock mínimo efectivo (propio o por defecto)
 * @param leadTimeDias      tiempo de reposición efectivo
 * @param demandaMensual    demanda mensual estimada (pronóstico del mejor método o promedio histórico)
 * @param demandaConfiable  true si la demanda proviene de un pronóstico con datos suficientes
 * @param desviacionMensual desviación estándar de las ventas mensuales (últimos 12 meses)
 * @param coberturaDias     días que alcanza el stock al ritmo de demanda (infinito si no hay demanda)
 * @param rotacionAnual     unidades vendidas en 12 meses / stock actual (nulo si no hay stock)
 */
public record ProductMetrics(Producto producto, int stock, int stockMinimo, int stockMaximo, int leadTimeDias,
                             ForecastResult forecast, double unidades30, double unidades90, double unidades365,
                             double importe365, double costoVendido365, double demandaMensual,
                             boolean demandaConfiable, double desviacionMensual, LocalDate ultimaVenta,
                             Long diasSinVenta, double coberturaDias, Double rotacionAnual, EstadoInventario estado) {

    public static final double DIAS_POR_MES = 30.4;

    public long productoId() {
        return producto.id();
    }

    public double valorInventario() {
        return stock * producto.costo();
    }

    public double demandaDiaria() {
        return demandaMensual / DIAS_POR_MES;
    }

    public double coberturaMeses() {
        return coberturaDias / DIAS_POR_MES;
    }

    public double utilidad365() {
        return importe365 - costoVendido365;
    }

    public double margen() {
        return producto.margenPorcentual();
    }
}
