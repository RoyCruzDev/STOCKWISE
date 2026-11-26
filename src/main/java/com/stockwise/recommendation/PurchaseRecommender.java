package com.stockwise.recommendation;

import com.stockwise.analytics.EstadoInventario;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.analytics.Statistics;
import com.stockwise.analytics.SupplyInfo;
import com.stockwise.model.Prioridad;
import com.stockwise.recommendation.PurchaseRecommendation.Decision;
import com.stockwise.util.Formatters;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Calcula qué y cuánto comprar con un modelo de revisión periódica con punto de reorden:
 * <pre>
 *   SS        = z(nivel de servicio) · √(LT·σd² + d²·σLT²)      (LT y σLT en meses; σLT = 0 sin historial)
 *   ROP       = d · LT + SS                                     (y al menos el stock mínimo)
 *   Objetivo  = d · (LT + R) + SS                               (R = ciclo de compra del proveedor u horizonte;
 *                                                                acotado por el stock máximo)
 *   Posición  = stock físico + unidades en tránsito (órdenes pendientes)
 *   Si posición ≤ ROP  ->  comprar (Objetivo − posición)
 * </pre>
 * Luego asigna el presupuesto por prioridad; <b>nunca</b> aprueba compras que superen el presupuesto.
 */
public class PurchaseRecommender {

    public record Params(int horizonteMeses, double nivelServicio) {
    }

    private final Params params;

    public PurchaseRecommender(Params params) {
        this.params = params;
    }

    public PurchasePlan recomendar(List<ProductMetrics> productos, double presupuestoDisponible) {
        return recomendar(productos, presupuestoDisponible, SupplyInfo::vacio);
    }

    public PurchasePlan recomendar(List<ProductMetrics> productos, double presupuestoDisponible,
                                   Map<Long, SupplyInfo> suministro) {
        return recomendar(productos, presupuestoDisponible,
                id -> suministro.getOrDefault(id, SupplyInfo.vacio(id)));
    }

    public PurchasePlan recomendar(List<ProductMetrics> productos, double presupuestoDisponible,
                                   Function<Long, SupplyInfo> suministro) {
        double z = Statistics.zScore(params.nivelServicio());
        List<Necesidad> necesidades = new ArrayList<>();
        for (ProductMetrics m : productos) {
            necesidades.add(evaluar(m, suministro.apply(m.productoId()), z));
        }

        // Asignación de presupuesto: prioridad, luego contribución (demanda x margen unitario).
        necesidades.sort(Comparator.comparingInt((Necesidad n) -> -n.prioridad.peso())
                .thenComparingDouble(n -> -(n.m.demandaMensual() * Math.max(0, n.m.producto().margenUnitario()))));

        double restante = Math.max(0, presupuestoDisponible);
        double totalAprobado = 0;
        double totalSugerido = 0;
        List<PurchaseRecommendation> lineas = new ArrayList<>();
        List<String> notas = new ArrayList<>();
        for (Necesidad n : necesidades) {
            double costo = n.m.producto().costo();
            int aprobada = 0;
            Decision decision;
            String motivo = n.motivo;
            if (n.cantidad <= 0) {
                decision = Decision.NO_REQUIERE;
            } else {
                totalSugerido += n.cantidad * costo;
                if (costo <= 0) {
                    aprobada = n.cantidad;
                    decision = Decision.COMPRAR;
                    motivo += " (producto sin costo registrado: verifique el costo).";
                } else if (n.cantidad * costo <= restante + 1e-9) {
                    aprobada = n.cantidad;
                    decision = Decision.COMPRAR;
                } else {
                    aprobada = (int) Math.floor(restante / costo);
                    if (aprobada > 0) {
                        decision = Decision.COMPRA_PARCIAL;
                        motivo += " Presupuesto insuficiente: se aprueban " + aprobada + " de " + n.cantidad + " unidades.";
                    } else {
                        decision = Decision.SIN_PRESUPUESTO;
                        motivo += " No queda presupuesto para esta compra.";
                    }
                }
                double gasto = aprobada * costo;
                restante -= gasto;
                totalAprobado += gasto;
            }
            lineas.add(new PurchaseRecommendation(n.m, n.demandaPeriodo, n.ss, n.rop, Math.max(0, n.cantidad), aprobada,
                    costo, aprobada * costo, n.prioridad, decision, n.riesgo, motivo, n.enTransito, n.objetivo,
                    roiEsperado(n, aprobada)));
        }
        if (presupuestoDisponible <= 0) {
            notas.add("No hay presupuesto disponible: registre un presupuesto para el periodo o configure el "
                    + "presupuesto general. Ninguna compra fue aprobada.");
        }
        if (totalSugerido > presupuestoDisponible) {
            notas.add("Las necesidades de compra (" + Formatters.money(totalSugerido) + ") superan el presupuesto ("
                    + Formatters.money(presupuestoDisponible) + "). Se priorizaron los productos con mayor riesgo.");
        }
        long sinPronostico = productos.stream().filter(m -> !m.demandaConfiable() && m.demandaMensual() > 0).count();
        if (sinPronostico > 0) {
            notas.add(sinPronostico + " producto(s) tienen historial insuficiente: su demanda se estimó con el "
                    + "promedio histórico disponible y debe tomarse como referencial.");
        }
        return new PurchasePlan(lineas, presupuestoDisponible, totalAprobado, totalSugerido, notas);
    }

    /**
     * ROI esperado de la compra aprobada: margen bruto de las unidades que se espera vender durante el tiempo de
     * reposición + ciclo, dividido entre la inversión. Nulo si no hay inversión o el producto no tiene costo.
     */
    static Double roiEsperado(Necesidad n, int aprobada) {
        double costo = n.m.producto().costo();
        if (aprobada <= 0 || costo <= 0) {
            return null;
        }
        double vendibles = Math.min(aprobada, n.demandaPeriodo);
        return vendibles * n.m.producto().margenUnitario() / (aprobada * costo);
    }

    static final class Necesidad {
        ProductMetrics m;
        int cantidad;
        int enTransito;
        double demandaPeriodo;
        double ss;
        double rop;
        double objetivo;
        Prioridad prioridad = Prioridad.BAJA;
        boolean riesgo;
        String motivo;
    }

    /** Stock de seguridad con variabilidad de la demanda y del tiempo de entrega. */
    public static double stockSeguridad(double z, double demandaMensual, double sigmaMensual, double ltMeses,
                                        double sigmaLtMeses) {
        return z * Math.sqrt(ltMeses * sigmaMensual * sigmaMensual
                + demandaMensual * demandaMensual * sigmaLtMeses * sigmaLtMeses);
    }

    private Necesidad evaluar(ProductMetrics m, SupplyInfo sup, double z) {
        Necesidad n = new Necesidad();
        n.m = m;
        n.enTransito = Math.max(0, sup.enTransito());
        double d = m.demandaMensual();
        double ltMeses = m.leadTimeDias() / ProductMetrics.DIAS_POR_MES;
        double ciclo = sup.cicloMeses(params.horizonteMeses());
        n.ss = stockSeguridad(z, d, m.desviacionMensual(), ltMeses,
                sup.desviacionLeadTime() / ProductMetrics.DIAS_POR_MES);
        n.rop = Math.max(d * ltMeses + n.ss, m.stockMinimo());
        double objetivo = Math.max(d * (ltMeses + ciclo) + n.ss, m.stockMinimo());
        if (m.stockMaximo() > 0) {
            objetivo = Math.min(objetivo, m.stockMaximo());
        }
        n.objetivo = objetivo;
        n.demandaPeriodo = d * (ltMeses + ciclo);
        n.riesgo = m.estado() == EstadoInventario.AGOTADO || m.estado() == EstadoInventario.RIESGO_AGOTAMIENTO;
        int posicion = m.stock() + n.enTransito;
        String transito = n.enTransito > 0 ? " (" + m.stock() + " en stock + " + n.enTransito + " en tránsito)" : "";

        if (m.estado() == EstadoInventario.SIN_MOVIMIENTO && m.stock() > 0) {
            n.motivo = "Producto sin movimiento: no comprar hasta reactivar sus ventas.";
            return n;
        }
        if (d <= 0) {
            if (posicion < m.stockMinimo()) {
                n.cantidad = m.stockMinimo() - posicion;
                n.prioridad = Prioridad.BAJA;
                n.motivo = "Sin historial de ventas: se repone solo hasta el stock mínimo (" + m.stockMinimo() + ")"
                        + transito + ".";
            } else {
                n.motivo = "Sin demanda registrada y posición de inventario por encima del mínimo" + transito + ".";
            }
            return n;
        }
        if (posicion <= n.rop) {
            n.cantidad = (int) Math.ceil(objetivo - posicion);
            if (n.cantidad <= 0) {
                n.motivo = "Posición en punto de reorden, pero el stock máximo impide comprar más.";
                n.cantidad = 0;
                return n;
            }
            double cobertura = m.coberturaDias();
            double coberturaPosicion = posicion / (d / ProductMetrics.DIAS_POR_MES);
            if (m.stock() == 0 && n.enTransito == 0 || coberturaPosicion < m.leadTimeDias() / 2.0) {
                n.prioridad = Prioridad.CRITICA;
            } else if (coberturaPosicion < m.leadTimeDias()) {
                n.prioridad = Prioridad.ALTA;
            } else {
                n.prioridad = Prioridad.MEDIA;
            }
            n.motivo = String.format("Posición %d%s ≤ punto de reorden %.0f. Cobertura %s días vs. reposición %d días. "
                            + "Demanda estimada %.1f u/mes; nivel objetivo %.0f u (ciclo %.1f mes(es))%s.", posicion,
                    transito, n.rop, Double.isInfinite(cobertura) ? "∞" : String.format("%.0f", cobertura),
                    m.leadTimeDias(), d, objetivo, ciclo,
                    m.demandaConfiable() ? "" : " (historial insuficiente, estimación referencial)");
        } else {
            n.motivo = String.format("Posición suficiente: %d u%s cubren %.0f días (punto de reorden %.0f).",
                    posicion, transito, posicion / (d / ProductMetrics.DIAS_POR_MES), n.rop);
        }
        return n;
    }
}
