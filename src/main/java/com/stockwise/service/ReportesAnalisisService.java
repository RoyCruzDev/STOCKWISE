package com.stockwise.service;

import com.stockwise.exporter.ReportTable;
import com.stockwise.exporter.ReportTable.ColumnType;
import com.stockwise.prediction.HorizonForecast;
import com.stockwise.repository.AnalisisRepository.Analisis;
import com.stockwise.repository.DecisionRepository.DecisionGuardada;
import com.stockwise.repository.DecisionRepository.DetalleAnalisis;
import com.stockwise.service.AccionesService.Accion;
import com.stockwise.service.ResultsService.Pregunta;
import com.stockwise.service.ResultsService.Ranking;
import com.stockwise.util.Formatters;

import java.util.ArrayList;
import java.util.List;

/**
 * Reportes del último análisis guardado (acciones recomendadas, más vendidos, necesidades de compra, pronósticos e
 * informe general). Usan las cifras guardadas de la corrida, sin recalcular ni redondear distinto que en pantalla.
 */
public class ReportesAnalisisService {

    /** Reportes disponibles del análisis. */
    public enum Tipo {
        ACCIONES("Acciones recomendadas"),
        MAS_VENDIDOS("Productos más vendidos"),
        COMPRAS("Necesidades de compra"),
        PRONOSTICOS("Pronósticos por producto"),
        INFORME("Informe general del análisis");

        private final String etiqueta;

        Tipo(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        public String etiqueta() {
            return etiqueta;
        }

        @Override
        public String toString() {
            return etiqueta;
        }
    }

    private final ResultsService resultados;
    private final AccionesService acciones;

    public ReportesAnalisisService(ResultsService resultados, AccionesService acciones) {
        this.resultados = resultados;
        this.acciones = acciones;
    }

    /** Tablas del reporte (el informe general tiene varias); vacío si aún no hay análisis. */
    public List<ReportTable> generar(Tipo tipo) {
        Analisis a = resultados.ultimo().orElseThrow(() -> new ValidationException(
                "Todavía no hay un análisis: importe sus ventas y pulse Analizar."));
        List<DecisionGuardada> r = resultados.resultados(a.id());
        String sub = "Análisis del " + a.fecha() + " · ventas hasta el " + Formatters.date(a.fechaCorte());
        return switch (tipo) {
            case ACCIONES -> List.of(acciones(acciones.acciones(), sub, false));
            case MAS_VENDIDOS -> List.of(masVendidos(r, sub, 0));
            case COMPRAS -> List.of(compras(r, sub));
            case PRONOSTICOS -> List.of(pronosticos(r, sub, 0));
            case INFORME -> {
                List<ReportTable> l = new ArrayList<>();
                l.add(resumen(a, r, sub));
                l.add(acciones(acciones.acciones(), sub, true));
                l.add(compras(r, sub));
                l.add(masVendidos(r, sub, 20));
                l.add(pronosticos(r, sub, 50));
                yield l;
            }
        };
    }

    private static Object n(Double v) {
        return v == null ? "" : v;
    }

    static ReportTable resumen(Analisis a, List<DecisionGuardada> r, String sub) {
        List<List<Object>> filas = new ArrayList<>();
        for (Pregunta q : Pregunta.values()) {
            List<DecisionGuardada> l = ResultsService.pregunta(r, q);
            filas.add(List.of(q.toString(), l.size(), ResultsService.resumen(q, l)));
        }
        List<String> res = new ArrayList<>();
        res.add("Productos analizados: " + a.productos() + " · con predicción: " + a.conPronostico());
        res.add("Ventas procesadas: " + Formatters.integer(a.registrosVentas()) + (a.ventasDesde() == null ? ""
                : " (del " + Formatters.date(a.ventasDesde()) + " al " + Formatters.date(a.ventasHasta()) + ")"));
        res.add(a.wape() == null ? "Error de predicción medio: no medible"
                : String.format("Error de predicción medio: %.0f %% (comprobado con meses pasados)", a.wape() * 100));
        if (a.compraSugerida() != null) {
            res.add("Compra sugerida: " + Formatters.money(a.compraSugerida()));
        }
        res.add("Las recomendaciones son ayudas: el dueño toma la decisión final.");
        return new ReportTable("Resumen del análisis", sub, List.of("Pregunta", "Productos", "Resumen"),
                List.of(ColumnType.TEXTO, ColumnType.ENTERO, ColumnType.TEXTO), filas, res);
    }

    static ReportTable acciones(List<Accion> acciones, String sub, boolean soloAtencion) {
        List<List<Object>> filas = acciones.stream()
                .filter(x -> !soloAtencion || x.tipo() != AccionesService.TipoAccion.MANTENER)
                .<List<Object>>map(x -> List.of(x.producto(), x.tipo().etiqueta(), x.situacion(), x.recomendacion(),
                        x.motivo(), x.errorPrediccion(), x.errorAlto() ? "Sí" : "No", x.riesgo(), x.resultadoEsperado(),
                        x.estado().toString())).toList();
        return new ReportTable("Acciones recomendadas", sub, List.of("Producto", "Acción", "Qué pasa", "Qué considerar",
                "Por qué", "Error de predicción", "Predicción poco confiable", "Riesgo", "Qué podrías lograr", "Estado"),
                List.of(ColumnType.TEXTO, ColumnType.TEXTO, ColumnType.TEXTO, ColumnType.TEXTO, ColumnType.TEXTO,
                        ColumnType.TEXTO, ColumnType.TEXTO, ColumnType.TEXTO, ColumnType.TEXTO, ColumnType.TEXTO),
                filas, List.of("Ayudas para decidir: STOCKWISE no cambia precios, inventario ni compras."));
    }

    static ReportTable masVendidos(List<DecisionGuardada> r, String sub, int limite) {
        List<DecisionGuardada> l = ResultsService.ranking(r, Ranking.MAS_VENDIDOS, 0, limite);
        List<List<Object>> filas = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            DecisionGuardada x = l.get(i);
            DetalleAnalisis d = x.detalle();
            filas.add(List.of(i + 1, x.codigo(), x.producto(), d == null ? 0 : d.unidades365(),
                    d == null ? 0 : d.unidades90(), n(d == null ? null : d.p30()), x.stock(),
                    d == null || d.tendencia() == null ? "" : d.tendencia().toLowerCase()));
        }
        return new ReportTable("Productos más vendidos", sub, List.of("#", "Código", "Producto", "Vendidas 12 meses",
                "Vendidas 90 días", "Podrías vender 30 días", "Stock", "Tendencia"),
                List.of(ColumnType.ENTERO, ColumnType.TEXTO, ColumnType.TEXTO, ColumnType.DECIMAL, ColumnType.DECIMAL,
                        ColumnType.DECIMAL, ColumnType.DECIMAL, ColumnType.TEXTO), filas, List.of());
    }

    static ReportTable compras(List<DecisionGuardada> r, String sub) {
        List<DecisionGuardada> l = ResultsService.pregunta(r, Pregunta.COMPRAR);
        List<List<Object>> filas = l.stream().<List<Object>>map(x -> List.of(x.codigo(), x.producto(),
                x.cantidadSugerida(), x.costoUnitario(), x.cantidadSugerida() * x.costoUnitario(), x.stock(),
                x.enTransito(), n(x.detalle() == null ? null : x.detalle().p30()),
                n(x.detalle() == null ? null : x.detalle().coberturaDias()), x.prioridad() == null ? "" : x.prioridad()))
                .toList();
        double total = l.stream().mapToDouble(x -> x.cantidadSugerida() * x.costoUnitario()).sum();
        return new ReportTable("Necesidades de compra", sub, List.of("Código", "Producto", "Comprar (u)",
                "Costo unitario", "Inversión", "Stock", "En camino", "Podrías vender 30 días", "Días de stock",
                "Prioridad"),
                List.of(ColumnType.TEXTO, ColumnType.TEXTO, ColumnType.ENTERO, ColumnType.MONEDA, ColumnType.MONEDA,
                        ColumnType.DECIMAL, ColumnType.DECIMAL, ColumnType.DECIMAL, ColumnType.DECIMAL, ColumnType.TEXTO),
                filas, List.of(l.size() + " producto(s) · inversión total " + Formatters.money(total)));
    }

    static ReportTable pronosticos(List<DecisionGuardada> r, String sub, int limite) {
        List<DecisionGuardada> l = ResultsService.ranking(r, Ranking.MAYOR_DEMANDA, 0, limite);
        List<List<Object>> filas = new ArrayList<>();
        for (DecisionGuardada x : l) {
            DetalleAnalisis d = x.detalle();
            List<HorizonForecast.Pronostico> h = ResultadosBuilder.pronosticosGuardados(d, x.fechaCorte());
            Object inf30 = h.size() > 2 && h.get(2).inferior() != null ? h.get(2).inferior() : "";
            Object sup30 = h.size() > 2 && h.get(2).superior() != null ? h.get(2).superior() : "";
            filas.add(List.of(x.codigo(), x.producto(), n(d.p7()), n(d.p15()), n(d.p30()), inf30, sup30, n(d.p60()),
                    n(d.p90()), n(d.wape()), d.puntosValidacion() == null ? 0 : d.puntosValidacion(),
                    d.confianza() == null ? "" : d.confianza().toLowerCase(), x.modelo() == null ? "" : x.modelo()));
        }
        return new ReportTable("Pronósticos por producto", sub, List.of("Código", "Producto", "7 días", "15 días",
                "30 días", "30 días mínimo (80 %)", "30 días máximo (80 %)", "60 días", "90 días", "Error de predicción",
                "Meses validados", "Confianza", "Modelo"),
                List.of(ColumnType.TEXTO, ColumnType.TEXTO, ColumnType.DECIMAL, ColumnType.DECIMAL, ColumnType.DECIMAL,
                        ColumnType.DECIMAL, ColumnType.DECIMAL, ColumnType.DECIMAL, ColumnType.DECIMAL,
                        ColumnType.PORCENTAJE, ColumnType.ENTERO, ColumnType.TEXTO, ColumnType.TEXTO), filas,
                List.of("Unidades que podrías vender desde el día siguiente al corte. El rango indica dónde cae la venta "
                        + "real 8 de cada 10 veces."));
    }
}
