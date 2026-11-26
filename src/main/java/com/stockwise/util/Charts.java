package com.stockwise.util;

import javafx.scene.Node;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

import java.util.function.Function;

/** Interactividad de gráficos: tooltips con valores formateados al pasar el mouse. */
public final class Charts {

    private Charts() {
    }

    /** Instala tooltips en los puntos/barras de todas las series (llamar después de mostrar el gráfico). */
    public static <X, Y> void tooltips(XYChart<X, Y> chart, Function<Number, String> formato) {
        for (XYChart.Series<X, Y> s : chart.getData()) {
            for (XYChart.Data<X, Y> d : s.getData()) {
                Node n = d.getNode();
                if (n == null) {
                    continue;
                }
                Number v = d.getYValue() instanceof Number num ? num : (Number) d.getXValue();
                Object categoria = d.getYValue() instanceof Number ? d.getXValue() : d.getYValue();
                Tooltip t = new Tooltip(s.getName() + "\n" + categoria + ": " + formato.apply(v));
                t.setShowDelay(Duration.millis(80));
                Tooltip.install(n, t);
                n.setOnMouseEntered(e -> n.setScaleX(1.25));
                n.setOnMouseExited(e -> n.setScaleX(1));
            }
        }
    }

    public static void tooltips(PieChart chart, Function<Number, String> formato) {
        double total = chart.getData().stream().mapToDouble(PieChart.Data::getPieValue).sum();
        for (PieChart.Data d : chart.getData()) {
            Node n = d.getNode();
            if (n == null) {
                continue;
            }
            String pct = total > 0 ? Formatters.percent(d.getPieValue() / total) : "";
            Tooltip t = new Tooltip(d.getName() + "\n" + formato.apply(d.getPieValue()) + " (" + pct + ")");
            t.setShowDelay(Duration.millis(80));
            Tooltip.install(n, t);
            n.setOnMouseEntered(e -> n.setOpacity(0.8));
            n.setOnMouseExited(e -> n.setOpacity(1));
        }
    }
}
