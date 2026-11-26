package com.stockwise.controller;

import com.stockwise.service.AppContext;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Icons;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Ventana principal: menú lateral, navegación entre módulos y ejecución de tareas en segundo plano. */
public class MainController implements Shell {

    private static final Logger LOG = Logger.getLogger(MainController.class.getName());

    private final AppContext app;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "stockwise-worker");
        t.setDaemon(true);
        return t;
    });
    private final Map<View, ToggleButton> navButtons = new EnumMap<>(View.class);
    private final ToggleGroup navGroup = new ToggleGroup();
    private final VBox overlay = new VBox();
    private final Label overlayLabel = new Label();
    private int tareasActivas;
    private View actual;

    @FXML
    private VBox navBox;
    @FXML
    private Label titleLabel;
    @FXML
    private Label companyLabel;
    @FXML
    private Button themeButton;
    @FXML
    private StackPane contentPane;
    @FXML
    private Label statusLabel;
    @FXML
    private Label dbLabel;

    private final String nombreProyecto;
    private final Runnable cambiarProyecto;
    private Object argumento;
    @FXML
    private Label projectLabel;
    @FXML
    private Button switchButton;

    public MainController(AppContext app) {
        this(app, null, null);
    }

    /**
     * @param nombreProyecto  proyecto abierto (se muestra en la barra superior)
     * @param cambiarProyecto acción para volver al selector de proyectos (nula = no se muestra el botón)
     */
    public MainController(AppContext app, String nombreProyecto, Runnable cambiarProyecto) {
        this.app = app;
        this.nombreProyecto = nombreProyecto;
        this.cambiarProyecto = cambiarProyecto;
    }

    @Override
    public String proyecto() {
        return nombreProyecto;
    }

    @Override
    public Object argumento() {
        Object a = argumento;
        argumento = null;
        return a;
    }

    @Override
    public void navigate(View view, Object arg) {
        argumento = arg;
        navigate(view);
        argumento = null;
    }

    @FXML
    private void switchProject() {
        if (cambiarProyecto != null) {
            cambiarProyecto.run();
        }
    }

    @FXML
    private void initialize() {
        for (View v : View.menu()) {
            if (v.seccion() != null) {
                Label sec = new Label(v.seccion());
                sec.getStyleClass().add("nav-section");
                navBox.getChildren().add(sec);
            }
            ToggleButton b = new ToggleButton(v.titulo(), v.icono().create(18));
            b.getStyleClass().setAll("nav-button");
            b.setToggleGroup(navGroup);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> {
                if (actual != null && actual.modulo() == v) {
                    b.setSelected(true);
                }
                navigate(v);
            });
            navButtons.put(v, b);
            navBox.getChildren().add(b);
        }
        ProgressIndicator pi = new ProgressIndicator();
        pi.setPrefSize(46, 46);
        VBox box = new VBox(pi, overlayLabel);
        box.getStyleClass().add("overlay-box");
        overlay.getChildren().add(box);
        overlay.getStyleClass().add("overlay");
        overlay.setAlignment(javafx.geometry.Pos.CENTER);
        overlay.setVisible(false);
        themeButton.setGraphic(Icons.MOON.create(16));
        dbLabel.setText("Base de datos: " + app.database().getFile());
        projectLabel.setText(nombreProyecto == null ? "" : "Proyecto: " + nombreProyecto);
        projectLabel.setVisible(nombreProyecto != null);
        projectLabel.setManaged(nombreProyecto != null);
        switchButton.setVisible(cambiarProyecto != null);
        switchButton.setManaged(cambiarProyecto != null);
        refreshHeader();
    }

    @Override
    public AppContext app() {
        return app;
    }

    @Override
    public void navigate(View solicitada) {
        View view = solicitada.destino();
        try {
            FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(getClass().getResource(view.recurso()),
                    "Vista no encontrada: " + view.recurso()));
            loader.setControllerFactory(type -> {
                try {
                    return type.getConstructor(Shell.class).newInstance(this);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("No se pudo crear el controlador " + type.getName(), e);
                }
            });
            Parent root = loader.load();
            Node barra = barraModulo(view);
            if (barra != null) {
                VBox.setVgrow(root, Priority.ALWAYS);
                VBox caja = new VBox(barra, root);
                contentPane.getChildren().setAll(caja, overlay);
            } else {
                contentPane.getChildren().setAll(root, overlay);
            }
            actual = view;
            titleLabel.setText(view.padre() == null ? view.titulo() : view.padre().titulo() + "  ›  " + view.titulo());
            ToggleButton nb = navButtons.get(view.modulo());
            if (nb != null) {
                nb.setSelected(true);
            } else if (navGroup.getSelectedToggle() != null) {
                navGroup.getSelectedToggle().setSelected(false);
            }
            status("Módulo: " + view.titulo());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Error al abrir la vista " + view, e);
            Dialogs.error(e);
        }
    }

    /**
     * Barra del módulo: pasos (p. ej. Importar → Analizar → Resultados → Decisiones) y el desplegable "Más
     * herramientas". Nula si el módulo no tiene pantallas hijas.
     */
    private Node barraModulo(View view) {
        View modulo = view.modulo();
        List<View> pasos = new java.util.ArrayList<>();
        if (modulo.destino() == modulo) {
            pasos.add(modulo);
        }
        pasos.addAll(modulo.hijos(View.Acceso.PASO));
        List<View> herramientas = modulo.hijos(View.Acceso.HERRAMIENTA);
        if (pasos.size() + herramientas.size() <= 1) {
            return null;
        }
        HBox barra = new HBox(6);
        barra.getStyleClass().add("module-bar");
        barra.setAlignment(Pos.CENTER_LEFT);
        boolean recorrido = false;
        for (int i = 0; i < pasos.size(); i++) {
            View p = pasos.get(i);
            if (i > 0 && recorrido) {
                Label flecha = new Label("→");
                flecha.getStyleClass().add("module-arrow");
                barra.getChildren().add(flecha);
            }
            Button b = new Button(p.tituloCorto());
            b.getStyleClass().add("module-step");
            if (p == view) {
                b.getStyleClass().add("module-step-actual");
            }
            b.setOnAction(e -> navigate(p));
            barra.getChildren().add(b);
        }
        if (!herramientas.isEmpty()) {
            Region espacio = new Region();
            HBox.setHgrow(espacio, Priority.ALWAYS);
            MenuButton mas = new MenuButton("Más herramientas");
            mas.getStyleClass().add("module-more");
            for (View h : herramientas) {
                MenuItem mi = new MenuItem(h.titulo(), h.icono().create(14));
                mi.setOnAction(e -> navigate(h));
                mas.getItems().add(mi);
            }
            if (herramientas.contains(view)) {
                mas.setText("Más herramientas: " + view.titulo());
                mas.getStyleClass().add("module-step-actual");
            }
            barra.getChildren().addAll(espacio, mas);
        }
        return barra;
    }

    @Override
    public <T> void run(String mensaje, Callable<T> trabajo, Consumer<T> alTerminar) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return trabajo.call();
            }
        };
        mostrarOverlay(mensaje);
        task.setOnSucceeded(e -> {
            ocultarOverlay();
            try {
                alTerminar.accept(task.getValue());
            } catch (RuntimeException ex) {
                Dialogs.error(ex);
            }
        });
        task.setOnFailed(e -> {
            ocultarOverlay();
            Dialogs.error(task.getException());
        });
        executor.submit(task);
    }

    private void mostrarOverlay(String mensaje) {
        tareasActivas++;
        overlayLabel.setText(mensaje);
        if (!contentPane.getChildren().contains(overlay)) {
            contentPane.getChildren().add(overlay);
        }
        overlay.setVisible(true);
        overlay.toFront();
    }

    private void ocultarOverlay() {
        tareasActivas = Math.max(0, tareasActivas - 1);
        if (tareasActivas == 0) {
            overlay.setVisible(false);
        }
    }

    @Override
    public void status(String mensaje) {
        Runnable r = () -> statusLabel.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + "  ·  "
                + mensaje);
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    @FXML
    private void toggleTheme() {
        String nuevo = "oscuro".equals(app.config().get().tema()) ? "claro" : "oscuro";
        app.config().setTema(nuevo);
        applyTheme(nuevo);
    }

    @Override
    public void applyTheme(String tema) {
        Scene scene = contentPane.getScene();
        if (scene == null) {
            return;
        }
        List<String> css = stylesheets(tema);
        scene.getStylesheets().setAll(css);
        Dialogs.configure(scene.getWindow(), css);
        themeButton.setText("oscuro".equals(tema) ? "Modo claro" : "Modo oscuro");
    }

    public static List<String> stylesheets(String tema) {
        String base = Objects.requireNonNull(MainController.class.getResource("/css/base.css")).toExternalForm();
        if ("oscuro".equals(tema)) {
            return List.of(base, Objects.requireNonNull(MainController.class.getResource("/css/dark.css"))
                    .toExternalForm());
        }
        return List.of(base);
    }

    @Override
    public void refreshHeader() {
        var s = app.config().get();
        companyLabel.setText(s.empresa() + "  ·  " + s.moneda());
    }

    @Override
    public Window window() {
        return contentPane.getScene() == null ? null : contentPane.getScene().getWindow();
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
