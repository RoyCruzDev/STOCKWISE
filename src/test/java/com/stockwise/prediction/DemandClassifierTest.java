package com.stockwise.prediction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemandClassifierTest {

    @Test
    void demandaRegularEsSuave() {
        DemandClassifier.Clasificacion c = DemandClassifier.clasificar(new double[]{40, 42, 38, 41, 39, 40, 43, 37});
        assertEquals(DemandPattern.SUAVE, c.patron());
        assertEquals(1.0, c.adi(), 1e-9);
        assertTrue(c.cv2() < DemandPattern.CV2_CORTE);
    }

    @Test
    void demandaFrecuentePeroMuyVariableEsErratica() {
        DemandClassifier.Clasificacion c = DemandClassifier.clasificar(new double[]{5, 80, 3, 120, 10, 2, 90, 4});
        assertEquals(DemandPattern.ERRATICA, c.patron());
        assertTrue(c.cv2() >= DemandPattern.CV2_CORTE);
    }

    @Test
    void muchosCerosConTamanosEstablesEsIntermitente() {
        DemandClassifier.Clasificacion c = DemandClassifier.clasificar(new double[]{0, 10, 0, 0, 12, 0, 0, 0, 9, 0});
        assertEquals(DemandPattern.INTERMITENTE, c.patron());
        assertEquals(10 / 3.0, c.adi(), 1e-9);
        assertEquals(3, c.periodosConDemanda());
    }

    @Test
    void muchosCerosConTamanosVariablesEsIrregular() {
        DemandClassifier.Clasificacion c = DemandClassifier.clasificar(new double[]{0, 1, 0, 0, 60, 0, 0, 0, 3, 0, 90, 0});
        assertEquals(DemandPattern.IRREGULAR, c.patron());
    }

    @Test
    void sinHistorialSuficienteNoSeClasifica() {
        assertEquals(DemandPattern.SIN_CLASIFICAR, DemandClassifier.clasificar(new double[]{10, 12, 9}).patron());
        assertEquals(DemandPattern.SIN_CLASIFICAR, DemandClassifier.clasificar(new double[]{0, 0, 0, 0, 0, 7}).patron());
        assertEquals(DemandPattern.SIN_CLASIFICAR, DemandClassifier.clasificar(new double[0]).patron());
        assertTrue(Double.isNaN(DemandClassifier.clasificar(null).adi()));
    }

    @Test
    void elUmbralDeAdiSeRespeta() {
        // 4 de 5 periodos con demanda: ADI = 1.25 < 1.32 -> no intermitente
        DemandClassifier.Clasificacion c = DemandClassifier.clasificar(new double[]{10, 10, 0, 10, 10, 10, 10, 0, 10, 10});
        assertEquals(1.25, c.adi(), 1e-9);
        assertEquals(DemandPattern.SUAVE, c.patron());
    }
}
