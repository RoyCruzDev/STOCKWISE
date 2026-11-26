package com.stockwise.service;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.analytics.StockReconstructor;
import com.stockwise.recommendation.DecisionEngine;
import com.stockwise.recommendation.DecisionEstrategica;
import com.stockwise.recommendation.ProductDecision;
import com.stockwise.recommendation.PurchasePlan;
import com.stockwise.recommendation.PurchaseRecommendation;
import com.stockwise.recommendation.ScenarioSimulator;
import com.stockwise.repository.AnalisisRepository;
import com.stockwise.repository.CompraRepository;
import com.stockwise.repository.DecisionRepository;
import com.stockwise.repository.DecisionRepository.DecisionGuardada;
import com.stockwise.repository.VentaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Planificación avanzada: decisiones estratégicas por producto, simulación de escenarios, ROI esperado del plan y
 * evaluación del impacto real de decisiones anteriores.
 */
public class PlanningService {

    /** Días de datos nuevos necesarios para evaluar el impacto de una decisión. */
    public static final int DIAS_MINIMOS_IMPACTO = 30;

    /** Análisis completo: fotografía, plan de compras, decisiones, detalle por producto y precisión agregada. */
    public record Analisis(AnalysisSnapshot snapshot, PurchasePlan plan, List<ProductDecision> decisiones,
                           List<String> notas, Map<Long, DecisionRepository.DetalleAnalisis> detalles,
                           ResultadosBuilder.Agregados agregados) {
    }

    /** ROI del plan de compras e indicadores de rentabilidad del inventario. */
    public record Roi(double inversion, Double margenEsperado, Double roiEsperado, double valorInventario,
                      double utilidad365, Double gmroi, int lineasSinCosto) {
    }

    /** Evaluación del impacto real de una decisión pasada con los datos que llegaron después. */
    public record Impacto(DecisionGuardada decision, long diasEvaluados, double demandaEsperada, double unidadesReales,
                          Double cumplimientoDemanda, double margenReal, double unidadesCompradas,
                          double inversionCompras, Double roiReal, Double variacionStock, String resultado) {
    }

    /** Resultado de la evaluación de impacto. */
    public record EvaluacionImpacto(LocalDate fechaCorteDecisiones, LocalDate fechaCorteActual, List<Impacto> impactos,
                                    String mensaje) {
    }

    private final AnalysisService analysis;
    private final RecommendationService recomendaciones;
    private final PresupuestoService presupuesto;
    private final StockHistoryService stockHistorico;
    private final DecisionRepository decisiones;
    private final VentaRepository ventas;
    private final CompraRepository compras;
    private final AnalisisRepository analisisRepo;

    public PlanningService(AnalysisService analysis, RecommendationService recomendaciones,
                           PresupuestoService presupuesto, StockHistoryService stockHistorico,
                           DecisionRepository decisiones, VentaRepository ventas, CompraRepository compras,
                           AnalisisRepository analisisRepo) {
        this.analisisRepo = analisisRepo;
        this.analysis = analysis;
        this.recomendaciones = recomendaciones;
        this.presupuesto = presupuesto;
        this.stockHistorico = stockHistorico;
        this.decisiones = decisiones;
        this.ventas = ventas;
        this.compras = compras;
    }

    /** Calcula (sin guardar) el plan de compras y la decisión estratégica de cada producto. */
    public Analisis analizar() {
        return analizar(Progreso.NINGUNO);
    }

    /**
     * Análisis completo informando el avance real de cada etapa. Si el usuario cancela se lanza
     * {@link java.util.concurrent.CancellationException} y no se guarda nada.
     */
    public Analisis analizar(Progreso p) {
        p.avance(1, "Cargando datos del proyecto");
        p.verificar();
        long registros = ventas.count();
        p.avance(10, "Validando información");
        if (registros == 0) {
            throw new ValidationException("El proyecto no tiene ventas registradas. Importe un archivo de ventas antes "
                    + "de analizar.");
        }
        p.verificar();
        p.avance(20, "Procesando ventas y productos");
        AnalysisSnapshot s = analysis.snapshot((hecho, total) -> {
            p.verificar();
            if (total > 0) {
                p.avance(35 + 35.0 * hecho / total, (hecho * 2 < total ? "Identificando patrones de demanda"
                        : "Entrenando y evaluando modelos") + ": " + hecho + " de " + total + " productos");
            }
        });
        p.avance(70, "Comparando predicciones con los modelos de referencia");
        ResultadosBuilder.Agregados agregados = ResultadosBuilder.agregados(s.productos());
        p.verificar();
        p.avance(85, "Calculando necesidades de compra");
        PurchasePlan plan = recomendaciones.planDeCompras(s, presupuesto.estadoActual().disponible());
        boolean medible = stockHistorico.disponibilidadMedible();
        Map<Long, StockReconstructor.Resumen> disp = medible
                ? stockHistorico.resumenPorProducto(s.fechaCorte().minusDays(89), s.fechaCorte()) : Map.of();
        List<ProductDecision> lista = new DecisionEngine().evaluar(s, plan, disp);
        p.verificar();
        p.avance(95, "Generando rankings y recomendaciones");
        Map<Long, DecisionRepository.DetalleAnalisis> detalles = new HashMap<>();
        Map<Long, LocalDate> primeras = ventas.primeraVentaPorProducto();
        for (ProductMetrics m : s.productos()) {
            detalles.put(m.productoId(), ResultadosBuilder.detalle(m, s.fechaCorte(), primeras.get(m.productoId())));
        }
        List<String> notas = new ArrayList<>(s.notas());
        if (!medible) {
            notas.add(StockHistoryService.SIN_ENTRADAS);
        }
        long pendientes = lista.stream().filter(d -> d.decision() == DecisionEstrategica.DATOS_INSUFICIENTES).count();
        if (pendientes > 0) {
            notas.add(pendientes + " producto(s) quedan pendientes por falta de datos de ventas: no se genera una "
                    + "recomendación para ellos.");
        }
        p.verificar();
        return new Analisis(s, plan, lista, notas, detalles, agregados);
    }

    /** Calcula y guarda una corrida de análisis completa (sin informe de avance). */
    public Analisis generarYGuardar() {
        long t0 = System.currentTimeMillis();
        Analisis a = analizar();
        guardar(a, System.currentTimeMillis() - t0);
        return a;
    }

    /**
     * Guarda la corrida: cabecera en {@code analisis} y un resultado por producto. Las corridas anteriores se
     * conservan para consultarlas y compararlas.
     *
     * @return id del análisis guardado
     */
    public long guardar(Analisis a, long duracionMs) {
        LocalDate corte = a.snapshot().fechaCorte();
        String ahora = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        ResultadosBuilder.Agregados ag = a.agregados();
        long id = analisisRepo.insertar(new AnalisisRepository.Analisis(0, ahora, corte, AnalisisRepository.COMPLETADO,
                duracionMs, a.decisiones().size(), ag.conPronostico(), ventas.fechaMinima().orElse(null),
                ventas.fechaMaxima().orElse(null), ventas.count(), a.plan().presupuestoDisponible(),
                a.plan().totalSugerido(), a.plan().totalAprobado(), ag.wape(), ag.sesgo(), ag.fva(),
                String.join("\n", a.notas())));
        decisiones.guardarCorrida(id, ahora, corte, a.decisiones(), a.detalles());
        // Sugerencias del asesor (traslados entre sucursales, etc.) con el mismo análisis, para Acciones recomendadas.
        recomendaciones.generarRecomendaciones(a.snapshot());
        return id;
    }

    /** Decisiones guardadas para la fecha de corte actual (vacío si aún no se generaron). */
    public List<DecisionGuardada> decisionesGuardadas() {
        return decisiones.porFechaCorte(analysis.fechaCorte());
    }

    public void marcarDecision(long id, boolean aplicada) {
        DecisionGuardada d = decisiones.findById(id)
                .orElseThrow(() -> new ValidationException("La decisión no existe."));
        if (!DecisionRepository.PENDIENTE.equals(d.estado())) {
            throw new ValidationException("La decisión ya fue procesada (" + d.estado() + ").");
        }
        decisiones.updateEstado(id, aplicada ? DecisionRepository.APLICADA : DecisionRepository.DESCARTADA);
    }

    public ScenarioSimulator.Resultado simular(ScenarioSimulator.Escenario e) {
        return ScenarioSimulator.simular(analysis.snapshot(), presupuesto.estadoActual().disponible(), e);
    }

    /** ROI esperado del plan y GMROI del inventario actual. */
    public static Roi roi(PurchasePlan plan, List<ProductMetrics> productos) {
        double inversion = 0;
        double margen = 0;
        double invConRoi = 0;
        int sinCosto = 0;
        for (PurchaseRecommendation l : plan.lineas()) {
            if (l.cantidadAprobada() <= 0) {
                continue;
            }
            inversion += l.costoTotal();
            if (l.roiEsperado() != null) {
                margen += l.roiEsperado() * l.costoTotal();
                invConRoi += l.costoTotal();
            } else {
                sinCosto++;
            }
        }
        double valor = productos.stream().mapToDouble(ProductMetrics::valorInventario).sum();
        double utilidad = productos.stream().mapToDouble(ProductMetrics::utilidad365).sum();
        return new Roi(inversion, invConRoi > 0 ? margen : null, invConRoi > 0 ? margen / invConRoi : null, valor,
                utilidad, valor > 0 ? utilidad / valor : null, sinCosto);
    }

    /**
     * Compara las decisiones de la corrida más reciente con al menos {@value #DIAS_MINIMOS_IMPACTO} días de datos
     * posteriores contra lo que realmente ocurrió (ventas, margen y compras desde su fecha de corte).
     */
    public EvaluacionImpacto evaluarImpacto() {
        LocalDate corteActual = analysis.fechaCorte();
        Optional<LocalDate> corteDec = decisiones.ultimaFechaCorteHasta(corteActual.minusDays(DIAS_MINIMOS_IMPACTO));
        if (corteDec.isEmpty()) {
            return new EvaluacionImpacto(null, corteActual, List.of(), "Pendiente: no hay decisiones guardadas con al "
                    + "menos " + DIAS_MINIMOS_IMPACTO + " días de datos de ventas posteriores. Genere y guarde "
                    + "decisiones y vuelva a evaluar cuando importe ventas nuevas.");
        }
        LocalDate desde = corteDec.get();
        long dias = ChronoUnit.DAYS.between(desde, corteActual);
        double meses = dias / ProductMetrics.DIAS_POR_MES;
        Map<Long, double[]> reales = ventas.totalesPorProductoEntre(desde, corteActual);
        Map<Long, ProductMetrics> actuales = analysis.snapshot().porId();
        List<Impacto> out = new ArrayList<>();
        for (DecisionGuardada d : decisiones.porFechaCorte(desde)) {
            if (DecisionRepository.DESCARTADA.equals(d.estado())) {
                continue;
            }
            double[] r = reales.getOrDefault(d.productoId(), new double[2]);
            double esperada = d.demandaMensual() * meses;
            Double cumplimiento = esperada > 0 ? r[0] / esperada : null;
            double margenReal = r[1] - r[0] * d.costoUnitario();
            double[] comp = compras.compradoEntre(d.productoId(), desde, corteActual);
            Double roiReal = comp[1] > 0 ? Math.min(r[0], comp[0]) * (d.precioVenta() - d.costoUnitario()) / comp[1]
                    : null;
            ProductMetrics act = actuales.get(d.productoId());
            Double varStock = act == null ? null : act.stock() - d.stock();
            out.add(new Impacto(d, dias, esperada, r[0], cumplimiento, margenReal, comp[0], comp[1], roiReal, varStock,
                    resultado(d, cumplimiento, varStock, comp[0])));
        }
        return new EvaluacionImpacto(desde, corteActual, out, out.isEmpty() ? "No hay decisiones evaluables." : null);
    }

    static String resultado(DecisionGuardada d, Double cumplimiento, Double varStock, double comprado) {
        return switch (d.decision()) {
            case RECOMPRAR, MANTENER, NEGOCIAR, PUBLICITAR -> cumplimiento == null ? "No evaluable (sin demanda estimada)"
                    : cumplimiento >= 0.8 && cumplimiento <= 1.25 ? "Demanda en línea con lo estimado"
                    : cumplimiento > 1.25 ? "Demanda superior a la estimada" : "Demanda inferior a la estimada";
            case LIQUIDAR, REDUCIR, DESCONTINUAR -> varStock == null ? "No evaluable"
                    : varStock < 0 && comprado == 0 ? "Stock reducido sin nuevas compras"
                    : comprado > 0 ? "Se volvió a comprar pese a la decisión" : "Sin reducción de stock";
            case DATOS_INSUFICIENTES -> "No evaluable (datos insuficientes)";
        };
    }
}
