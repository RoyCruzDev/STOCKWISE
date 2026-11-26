package com.stockwise.recommendation;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.model.Recomendacion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Motor de reglas del asesor estratégico. Ejecuta todas las reglas registradas y ordena el resultado. */
public class StrategicAdvisor {

    private static final Logger LOG = Logger.getLogger(StrategicAdvisor.class.getName());
    private final List<BusinessRule> reglas;

    public StrategicAdvisor() {
        this(List.of(new StockoutPreventionRule(), new TransferRule(), new OverstockRule(), new LowRotationRule(),
                new PricingRule(), new SupplierNegotiationRule(), new ProfitabilityRule()));
    }

    public StrategicAdvisor(List<BusinessRule> reglas) {
        this.reglas = List.copyOf(reglas);
    }

    public List<BusinessRule> reglas() {
        return reglas;
    }

    public List<Recomendacion> generar(AnalysisSnapshot snapshot) {
        List<Recomendacion> out = new ArrayList<>();
        for (BusinessRule r : reglas) {
            try {
                out.addAll(r.evaluar(snapshot));
            } catch (RuntimeException e) {
                // Una regla defectuosa no debe impedir el resto del análisis.
                LOG.log(Level.SEVERE, "Error en la regla " + r.nombre(), e);
            }
        }
        out.sort(Comparator.comparingInt((Recomendacion r) -> -r.prioridad().peso())
                .thenComparingDouble(r -> -(r.beneficio() == null ? 0 : r.beneficio())));
        return out;
    }
}
