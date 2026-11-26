package com.stockwise.recommendation;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.EstadoInventario;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.analytics.StockReconstructor;
import com.stockwise.analytics.SupplyInfo;
import com.stockwise.model.Prioridad;
import com.stockwise.prediction.ForecastResult;
import com.stockwise.service.AppSettings;
import com.stockwise.util.Formatters;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Motor de reglas que asigna <b>una</b> decisión estratégica a cada producto. Las reglas se evalúan en orden y
 * gana la primera que se cumple:
 * <ol>
 *   <li><b>Datos insuficientes</b>: el producto nunca registró ventas (no hay base para decidir).</li>
 *   <li><b>Descontinuar</b>: sin ventas durante 2 × días sin movimiento, o margen negativo con demanda en descenso.</li>
 *   <li><b>Liquidar</b>: con stock y sin ventas durante los días sin movimiento, o cobertura &gt; 2 × umbral de
 *       sobreinventario.</li>
 *   <li><b>Negociar</b>: se vende pero el margen está por debajo del mínimo configurado.</li>
 *   <li><b>Recomprar</b>: posición de inventario (stock + tránsito) ≤ punto de reorden.</li>
 *   <li><b>Reducir</b>: sobreinventario, o tendencia decreciente con cobertura mayor que reposición + ciclo.</li>
 *   <li><b>Publicitar</b>: margen sano con rotación anual &lt; 4 o tendencia decreciente, y stock por encima de lo
 *       que se venderá en reposición + ciclo.</li>
 *   <li><b>Mantener</b>: en cualquier otro caso.</li>
 * </ol>
 * Los impactos se calculan solo con datos registrados (costos, precios, demanda estimada); si faltan se dejan nulos.
 */
public class DecisionEngine {

    public static final double ROTACION_BAJA = 4.0;
    public static final double DISPONIBILIDAD_MINIMA = 0.9;

    public List<ProductDecision> evaluar(AnalysisSnapshot s, PurchasePlan plan,
                                         Map<Long, StockReconstructor.Resumen> disponibilidad) {
        Map<Long, PurchaseRecommendation> lineas = new java.util.HashMap<>();
        if (plan != null) {
            plan.lineas().forEach(l -> lineas.put(l.metrics().productoId(), l));
        }
        List<ProductDecision> out = new ArrayList<>();
        for (ProductMetrics m : s.productos()) {
            StockReconstructor.Resumen r = disponibilidad == null ? null : disponibilidad.get(m.productoId());
            out.add(evaluar(m, s.suministro(m.productoId()), lineas.get(m.productoId()), s.settings(),
                    r == null ? null : r.disponibilidad()));
        }
        out.sort(Comparator.comparingInt((ProductDecision d) -> -d.prioridad().peso())
                .thenComparingDouble(d -> -(d.impactoEstimado() == null ? 0 : d.impactoEstimado())));
        return out;
    }

    public ProductDecision evaluar(ProductMetrics m, SupplyInfo sup, PurchaseRecommendation linea, AppSettings cfg,
                                   Double disponibilidad) {
        double costo = m.producto().costo();
        double margenU = m.producto().margenUnitario();
        double d = m.demandaMensual();
        double ltMeses = m.leadTimeDias() / ProductMetrics.DIAS_POR_MES;
        double ciclo = sup.cicloMeses(cfg.horizonteMeses());
        int posicion = m.stock() + sup.enTransito();
        double ss = linea == null ? 0 : linea.stockSeguridad();
        double rop = linea == null ? 0 : linea.puntoReorden();
        double objetivo = linea == null ? 0 : linea.nivelObjetivo();
        int sugerida = linea == null ? 0 : linea.cantidadSugerida();
        ForecastResult fr = m.forecast();
        boolean decreciente = fr != null && fr.tendencia() == ForecastResult.Tendencia.DECRECIENTE;
        String modelo = fr != null && fr.mejor() != null ? fr.mejor().metodo() : "Sin pronóstico";
        String notaDatos = (m.demandaConfiable() ? "" : " Demanda estimada con historial insuficiente (referencial).")
                + (disponibilidad != null && disponibilidad < DISPONIBILIDAD_MINIMA
                ? String.format(" Hubo stock solo el %.0f%% de los días en los últimos 90 días: la demanda real "
                + "puede estar subestimada.", disponibilidad * 100) : "");

        Decidida x;
        if (m.ultimaVenta() == null) {
            x = new Decidida(DecisionEstrategica.DATOS_INSUFICIENTES, Prioridad.BAJA, null,
                    "El producto no registra ventas" + (m.stock() > 0 ? " y tiene " + m.stock() + " u en stock" : "")
                            + ": no hay base para estimar su demanda.",
                    "Pendiente: registre o importe su historial de ventas (o confirme si es un producto nuevo) antes "
                            + "de decidir.");
        } else if (m.diasSinVenta() != null && m.diasSinVenta() >= 2L * cfg.diasSinMovimiento()
                || (margenU < 0 && decreciente)) {
            boolean sinVentas = m.diasSinVenta() != null && m.diasSinVenta() >= 2L * cfg.diasSinMovimiento();
            x = new Decidida(DecisionEstrategica.DESCONTINUAR, m.stock() > 0 ? Prioridad.ALTA : Prioridad.MEDIA,
                    m.stock() > 0 && costo > 0 ? m.stock() * costo : null,
                    sinVentas ? "Sin ventas desde hace " + m.diasSinVenta() + " días (umbral " + 2 * cfg.diasSinMovimiento()
                            + " días)." : String.format("Margen negativo (%s por unidad) y demanda decreciente.",
                            Formatters.money(margenU)),
                    (m.stock() > 0 ? "Liquidar las " + m.stock() + " u remanentes, " : "") + "no volver a comprar y "
                            + "retirar del catálogo.");
        } else if (m.stock() > 0 && (m.estado() == EstadoInventario.SIN_MOVIMIENTO
                || m.coberturaMeses() > 2 * cfg.mesesSobreinventario())) {
            double sano = Math.ceil(d * cfg.mesesSobreinventario());
            double exceso = Math.max(0, m.stock() - sano);
            x = new Decidida(DecisionEstrategica.LIQUIDAR, Prioridad.ALTA, costo > 0 ? exceso * costo : null,
                    m.estado() == EstadoInventario.SIN_MOVIMIENTO
                            ? "Sin ventas desde hace " + m.diasSinVenta() + " días con " + m.stock() + " u en stock."
                            : String.format("Cobertura de %.1f meses: más del doble del umbral de sobreinventario "
                            + "(%.1f meses).", m.coberturaMeses(), cfg.mesesSobreinventario()),
                    "Liquidar unas " + (long) exceso + " u con descuento (manteniendo " + (long) sano
                            + " u) y suspender las compras.");
        } else if (d > 0 && m.producto().precioVenta() > 0 && m.margen() < cfg.margenMinimo()) {
            double brecha = (cfg.margenMinimo() - m.margen()) * m.producto().precioVenta();
            x = new Decidida(DecisionEstrategica.NEGOCIAR, sugerida > 0 ? Prioridad.ALTA : Prioridad.MEDIA,
                    brecha * d * 12,
                    String.format("Margen de %s inferior al mínimo de %s.", Formatters.percent(m.margen()),
                            Formatters.percent(cfg.margenMinimo())),
                    String.format("Negociar una reducción de costo de %s por unidad con %s%s.",
                            Formatters.money(brecha), sup.proveedor() == null ? "el proveedor" : sup.proveedor(),
                            sugerida > 0 ? " antes de emitir el próximo pedido (" + sugerida + " u requeridas)" : ""));
        } else if (sugerida > 0) {
            double protegido = Math.min(sugerida, d * (ltMeses + ciclo)) * Math.max(0, margenU);
            x = new Decidida(DecisionEstrategica.RECOMPRAR, linea.prioridad(), protegido > 0 ? protegido : null,
                    String.format("Posición de inventario %d u (stock %d + tránsito %d) ≤ punto de reorden %.0f.",
                            posicion, m.stock(), sup.enTransito(), rop),
                    String.format("Pedir %d u para llegar al nivel objetivo de %.0f u%s.", sugerida, objetivo,
                            sup.proximoPedido() != null ? " (próximo ciclo del proveedor: "
                                    + Formatters.date(sup.proximoPedido()) + ")" : ""));
        } else if (m.estado() == EstadoInventario.SOBREINVENTARIO
                || (decreciente && d > 0 && m.coberturaMeses() > ltMeses + ciclo)) {
            double necesario = Math.ceil(d * (ltMeses + ciclo) + ss);
            double exceso = Math.max(0, posicion - necesario);
            x = new Decidida(DecisionEstrategica.REDUCIR, Prioridad.MEDIA, costo > 0 && exceso > 0 ? exceso * costo : null,
                    m.estado() == EstadoInventario.SOBREINVENTARIO
                            ? String.format("Sobreinventario: cobertura de %.1f meses (umbral %.1f).", m.coberturaMeses(),
                            cfg.mesesSobreinventario())
                            : String.format("Demanda decreciente (%.1f u/mes) con cobertura de %.1f meses.",
                            fr.pendiente(), m.coberturaMeses()),
                    "Reducir o suspender las próximas compras hasta bajar a " + (long) necesario + " u.");
        } else if (d > 0 && margenU > 0 && m.margen() >= cfg.margenMinimo()
                && (decreciente || (m.rotacionAnual() != null && m.rotacionAnual() < ROTACION_BAJA))
                && posicion > d * (ltMeses + ciclo)) {
            double excedente = posicion - d * (ltMeses + ciclo);
            x = new Decidida(DecisionEstrategica.PUBLICITAR, Prioridad.MEDIA, excedente * margenU,
                    String.format("Margen de %s con %s.", Formatters.percent(m.margen()), decreciente
                            ? "demanda en descenso" : String.format("rotación anual baja (%.1f)", m.rotacionAnual())),
                    "Promocionar o dar visibilidad al producto para vender las ~" + (long) excedente
                            + " u que exceden lo necesario para el ciclo de compra.");
        } else {
            x = new Decidida(DecisionEstrategica.MANTENER, Prioridad.BAJA, null,
                    String.format("Posición de %d u sobre el punto de reorden (%.0f) y sin señales de exceso.",
                            posicion, rop),
                    "Mantener la política actual y revisar en el próximo ciclo.");
        }
        return new ProductDecision(m, sup, x.decision, x.prioridad, fr == null ? null : fr.patron(), modelo, ss, rop,
                objetivo, x.decision == DecisionEstrategica.RECOMPRAR || x.decision == DecisionEstrategica.NEGOCIAR
                ? sugerida : 0, x.impacto, x.motivo + notaDatos, x.accion, disponibilidad,
                x.decision == DecisionEstrategica.RECOMPRAR && linea != null ? linea.roiEsperado() : null);
    }

    private record Decidida(DecisionEstrategica decision, Prioridad prioridad, Double impacto, String motivo,
                            String accion) {
    }
}
