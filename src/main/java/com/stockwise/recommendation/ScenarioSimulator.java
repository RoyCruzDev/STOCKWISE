package com.stockwise.recommendation;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.EstadoInventario;
import com.stockwise.analytics.MetricsCalculator;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.service.AppSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulación de escenarios "what-if": recalcula el plan de compras y las decisiones con variaciones de la demanda,
 * el presupuesto, los tiempos de entrega y el nivel de servicio, sin modificar ningún dato guardado.
 */
public final class ScenarioSimulator {

    /**
     * @param variacionDemanda   cambio relativo de la demanda (0.2 = +20 %, −0.1 = −10 %); mínimo −0.95
     * @param presupuesto        presupuesto disponible del escenario (nulo = el actual)
     * @param variacionLeadTime  días que se suman (o restan) al tiempo de reposición de cada producto
     * @param nivelServicio      nivel de servicio del escenario (nulo = el configurado)
     */
    public record Escenario(double variacionDemanda, Double presupuesto, int variacionLeadTime, Double nivelServicio) {
        public Escenario {
            if (variacionDemanda < -0.95 || variacionDemanda > 5) {
                throw new IllegalArgumentException("La variación de la demanda debe estar entre −95 % y +500 %.");
            }
            if (presupuesto != null && presupuesto < 0) {
                throw new IllegalArgumentException("El presupuesto no puede ser negativo.");
            }
            if (nivelServicio != null && (nivelServicio < 0.5 || nivelServicio > 0.999)) {
                throw new IllegalArgumentException("El nivel de servicio debe estar entre 0.50 y 0.999.");
            }
        }

        public static Escenario base() {
            return new Escenario(0, null, 0, null);
        }
    }

    /** Indicadores agregados de un plan. */
    public record Indicadores(double presupuesto, double inversionNecesaria, double inversionAprobada, double saldo,
                              int productosAComprar, int productosSinPresupuesto, int productosEnRiesgo,
                              double unidadesAComprar, Double roiEsperado, Map<DecisionEstrategica, Long> decisiones) {
    }

    /** Diferencia por producto entre el escenario base y el simulado. */
    public record CambioProducto(String codigo, String producto, int cantidadBase, int cantidadEscenario,
                                 double inversionBase, double inversionEscenario, DecisionEstrategica decisionBase,
                                 DecisionEstrategica decisionEscenario) {
    }

    public record Resultado(Escenario escenario, Indicadores base, Indicadores simulado, List<CambioProducto> cambios,
                            List<String> notas) {
    }

    private ScenarioSimulator() {
    }

    public static Resultado simular(AnalysisSnapshot s, double presupuestoActual, Escenario e) {
        AppSettings cfg = s.settings();
        PurchasePlan planBase = plan(s.productos(), s, presupuestoActual, cfg.nivelServicio());
        List<ProductDecision> decBase = new DecisionEngine().evaluar(s, planBase, null);

        List<ProductMetrics> mod = s.productos().stream().map(m -> modificar(m, e, cfg)).toList();
        AnalysisSnapshot sm = new AnalysisSnapshot(s.fechaCorte(), s.ultimoMesCompleto(), cfg, mod, s.sucursales(),
                s.inventario(), s.unidades90PorProductoSucursal(), s.notas(), s.suministro());
        double presu = e.presupuesto() == null ? presupuestoActual : e.presupuesto();
        double ns = e.nivelServicio() == null ? cfg.nivelServicio() : e.nivelServicio();
        PurchasePlan planSim = plan(mod, sm, presu, ns);
        List<ProductDecision> decSim = new DecisionEngine().evaluar(sm, planSim, null);

        Map<Long, PurchaseRecommendation> lb = porProducto(planBase);
        Map<Long, PurchaseRecommendation> ls = porProducto(planSim);
        Map<Long, DecisionEstrategica> db = new HashMap<>();
        decBase.forEach(d -> db.put(d.productoId(), d.decision()));
        Map<Long, DecisionEstrategica> ds = new HashMap<>();
        decSim.forEach(d -> ds.put(d.productoId(), d.decision()));
        List<CambioProducto> cambios = new ArrayList<>();
        for (ProductMetrics m : s.productos()) {
            PurchaseRecommendation a = lb.get(m.productoId());
            PurchaseRecommendation b = ls.get(m.productoId());
            if (a.cantidadSugerida() != b.cantidadSugerida() || a.cantidadAprobada() != b.cantidadAprobada()
                    || db.get(m.productoId()) != ds.get(m.productoId())) {
                cambios.add(new CambioProducto(m.producto().codigo(), m.producto().nombre(), a.cantidadAprobada(),
                        b.cantidadAprobada(), a.costoTotal(), b.costoTotal(), db.get(m.productoId()),
                        ds.get(m.productoId())));
            }
        }
        cambios.sort(Comparator.comparingDouble((CambioProducto c) -> -Math.abs(c.inversionEscenario()
                - c.inversionBase())));
        List<String> notas = new ArrayList<>(planSim.notas());
        notas.add(0, "Escenario simulado: no se modificó ningún dato. Las cantidades se recalculan con el mismo método "
                + "del plan de compras (stock de seguridad, punto de reorden y nivel objetivo).");
        return new Resultado(e, indicadores(planBase, decBase), indicadores(planSim, decSim), cambios, notas);
    }

    private static PurchasePlan plan(List<ProductMetrics> productos, AnalysisSnapshot s, double presupuesto,
                                     double nivelServicio) {
        return new PurchaseRecommender(new PurchaseRecommender.Params(s.settings().horizonteMeses(), nivelServicio))
                .recomendar(productos, presupuesto, s::suministro);
    }

    private static Map<Long, PurchaseRecommendation> porProducto(PurchasePlan p) {
        Map<Long, PurchaseRecommendation> out = new HashMap<>();
        p.lineas().forEach(l -> out.put(l.metrics().productoId(), l));
        return out;
    }

    /** Aplica el escenario a las métricas de un producto y recalcula cobertura y estado. */
    static ProductMetrics modificar(ProductMetrics m, Escenario e, AppSettings cfg) {
        double f = 1 + e.variacionDemanda();
        double demanda = m.demandaMensual() * f;
        int lt = Math.max(1, m.leadTimeDias() + e.variacionLeadTime());
        double cobertura = demanda > 0 ? m.stock() / (demanda / ProductMetrics.DIAS_POR_MES) : Double.POSITIVE_INFINITY;
        MetricsCalculator.Params p = new MetricsCalculator.Params(cfg.stockMinimoDefecto(), cfg.leadTimeDefectoDias(),
                cfg.mesesSobreinventario(), cfg.diasSinMovimiento());
        EstadoInventario estado = MetricsCalculator.clasificar(m.stock(), m.stockMinimo(), m.stockMaximo(), lt, demanda,
                cobertura, m.ultimaVenta() != null, m.diasSinVenta(), p);
        return new ProductMetrics(m.producto(), m.stock(), m.stockMinimo(), m.stockMaximo(), lt, m.forecast(),
                m.unidades30(), m.unidades90(), m.unidades365(), m.importe365(), m.costoVendido365(), demanda,
                m.demandaConfiable(), m.desviacionMensual() * f, m.ultimaVenta(), m.diasSinVenta(), cobertura,
                m.rotacionAnual(), estado);
    }

    static Indicadores indicadores(PurchasePlan p, List<ProductDecision> decisiones) {
        int comprar = 0;
        int sinPresu = 0;
        int riesgo = 0;
        double unidades = 0;
        double margen = 0;
        double inversionConCosto = 0;
        for (PurchaseRecommendation l : p.lineas()) {
            if (l.cantidadAprobada() > 0) {
                comprar++;
                unidades += l.cantidadAprobada();
            }
            if (l.decision() == PurchaseRecommendation.Decision.SIN_PRESUPUESTO
                    || l.decision() == PurchaseRecommendation.Decision.COMPRA_PARCIAL) {
                sinPresu++;
            }
            // En riesgo: necesita compra y no queda cubierta totalmente por el presupuesto.
            if (l.cantidadSugerida() > 0 && l.cantidadAprobada() < l.cantidadSugerida() && l.riesgoAgotamiento()) {
                riesgo++;
            }
            if (l.roiEsperado() != null) {
                margen += l.roiEsperado() * l.costoTotal();
                inversionConCosto += l.costoTotal();
            }
        }
        Map<DecisionEstrategica, Long> conteo = new java.util.EnumMap<>(DecisionEstrategica.class);
        decisiones.forEach(d -> conteo.merge(d.decision(), 1L, Long::sum));
        return new Indicadores(p.presupuestoDisponible(), p.totalSugerido(), p.totalAprobado(), p.saldo(), comprar,
                sinPresu, riesgo, unidades, inversionConCosto > 0 ? margen / inversionConCosto : null, conteo);
    }
}
