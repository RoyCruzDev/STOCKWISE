package com.stockwise.controller;

import com.stockwise.service.PlanningService;
import com.stockwise.service.Progreso;
import com.stockwise.util.Dialogs;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pantalla de procesamiento: ejecuta el análisis en segundo plano mostrando el avance real de cada etapa y permite
 * cancelarlo de forma segura (no se guarda nada si se cancela). Al terminar abre los resultados.
 */
public class ProcesamientoController extends BaseController {

    /** Argumento de navegación para iniciar el análisis al abrir la pantalla. */
    public static final String INICIAR = "iniciar";

    private static final Logger LOG = Logger.getLogger(ProcesamientoController.class.getName());
    /** Etapas visibles con el porcentaje en que comienzan (el avance lo informa el propio análisis). */
    static final Object[][] ETAPAS = {
            {1, "Cargando datos del proyecto"}, {10, "Validando información"},
            {20, "Procesando ventas y productos"}, {35, "Identificando patrones de demanda"},
            {50, "Entrenando y evaluando modelos"}, {70, "Comparando predicciones"},
            {85, "Calculando necesidades de compra"}, {95, "Generando rankings y recomendaciones"},
            {100, "Análisis completado"}};

    @FXML
    private Label titleLabel;
    @FXML
    private Label percentLabel;
    @FXML
    private Label messageLabel;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private VBox stagesBox;
    @FXML
    private Label timeLabel;
    @FXML
    private Button cancelButton;
    @FXML
    private Button startButton;
    @FXML
    private Button backButton;

    private final List<Label> etapas = new ArrayList<>();
    private Task<Long> task;
    private Timeline reloj;
    private long inicio;

    public ProcesamientoController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        titleLabel.setText("Análisis del proyecto" + (shell.proyecto() == null ? "" : " " + shell.proyecto()));
        for (Object[] e : ETAPAS) {
            Label l = new Label("○  " + e[1]);
            l.getStyleClass().add("etapa");
            etapas.add(l);
            stagesBox.getChildren().add(l);
        }
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        if (INICIAR.equals(shell.argumento())) {
            start();
        } else {
            messageLabel.setText("Listo para analizar.");
        }
    }

    private void marcarEtapas(double pct) {
        for (int i = 0; i < ETAPAS.length; i++) {
            int desde = (Integer) ETAPAS[i][0];
            int hasta = i + 1 < ETAPAS.length ? (Integer) ETAPAS[i + 1][0] : 101;
            Label l = etapas.get(i);
            l.getStyleClass().removeAll("hecha", "actual");
            String nombre = (String) ETAPAS[i][1];
            if (pct >= hasta || (pct >= 100 && i == ETAPAS.length - 1)) {
                l.getStyleClass().add("hecha");
                l.setText("✓  " + nombre);
            } else if (pct >= desde) {
                l.getStyleClass().add("actual");
                l.setText("●  " + nombre);
            } else {
                l.setText("○  " + nombre);
            }
        }
    }

    @FXML
    private void start() {
        if (task != null && task.isRunning()) {
            return;
        }
        startButton.setVisible(false);
        startButton.setManaged(false);
        backButton.setVisible(false);
        backButton.setManaged(false);
        cancelButton.setVisible(true);
        cancelButton.setManaged(true);
        cancelButton.setDisable(false);
        inicio = System.currentTimeMillis();
        task = new Task<>() {
            @Override
            protected Long call() {
                long t0 = System.currentTimeMillis();
                PlanningService.Analisis a = app.planificacion().analizar(new Progreso() {
                    @Override
                    public void avance(double porcentaje, String mensaje) {
                        updateProgress(porcentaje, 100);
                        updateMessage(mensaje);
                    }

                    @Override
                    public boolean cancelado() {
                        return isCancelled();
                    }
                });
                if (isCancelled()) {
                    throw new CancellationException();
                }
                updateProgress(97, 100);
                updateMessage("Guardando resultados");
                long id = app.planificacion().guardar(a, System.currentTimeMillis() - t0);
                updateProgress(100, 100);
                updateMessage("Análisis completado");
                return id;
            }
        };
        task.progressProperty().addListener((o, a, b) -> {
            double pct = Math.max(0, b.doubleValue() * 100);
            progressBar.setProgress(pct / 100);
            percentLabel.setText(String.format("%.0f %%", Math.floor(pct)));
            marcarEtapas(pct);
            // Al guardar ya no se puede cancelar sin dejar resultados a medias.
            cancelButton.setDisable(pct >= 95);
        });
        task.messageProperty().addListener((o, a, b) -> messageLabel.setText(b));
        task.setOnSucceeded(e -> {
            detenerReloj();
            long id = task.getValue();
            shell.status("Análisis completado en " + segundos() + " s");
            // Solo navega si el usuario sigue en esta pantalla.
            if (progressBar.getScene() != null) {
                shell.navigate(View.RESULTADOS, id);
            }
        });
        task.setOnCancelled(e -> finalizado("Análisis cancelado. No se guardó ningún resultado; los datos del "
                + "proyecto no cambiaron."));
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            if (ex instanceof CancellationException) {
                finalizado("Análisis cancelado. No se guardó ningún resultado.");
                return;
            }
            LOG.log(Level.SEVERE, "Error en el análisis", ex);
            finalizado("El análisis no pudo completarse.");
            Dialogs.error(ex);
        });
        reloj = new Timeline(new KeyFrame(Duration.seconds(1), e -> timeLabel.setText("Tiempo: " + segundos() + " s")));
        reloj.setCycleCount(Timeline.INDEFINITE);
        reloj.play();
        Thread t = new Thread(task, "stockwise-analisis");
        t.setDaemon(true);
        t.start();
    }

    private long segundos() {
        return (System.currentTimeMillis() - inicio) / 1000;
    }

    private void detenerReloj() {
        if (reloj != null) {
            reloj.stop();
        }
    }

    private void finalizado(String mensaje) {
        detenerReloj();
        messageLabel.setText(mensaje);
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        startButton.setText("Analizar de nuevo");
        startButton.setVisible(true);
        startButton.setManaged(true);
        backButton.setVisible(true);
        backButton.setManaged(true);
    }

    @FXML
    private void cancel() {
        if (task != null && task.isRunning()) {
            cancelButton.setDisable(true);
            messageLabel.setText("Cancelando…");
            task.cancel(false);
        }
    }

    @FXML
    private void back() {
        shell.navigate(View.INICIO);
    }
}
