package com.stockwise;

import com.stockwise.controller.MainController;
import com.stockwise.controller.ProyectosController;
import com.stockwise.controller.View;
import com.stockwise.service.AppContext;
import com.stockwise.service.ProjectCatalog;
import com.stockwise.util.AppLogger;
import com.stockwise.util.Dialogs;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Aplicación JavaFX: inicializa la carpeta de datos, muestra el selector de proyectos y abre la ventana principal
 * del proyecto elegido. Cada proyecto tiene su propia base de datos.
 */
public class StockWiseApp extends Application {

    private static final Logger LOG = Logger.getLogger(StockWiseApp.class.getName());
    private MainController main;
    private Stage stage;
    private ProjectCatalog catalog;

    /**
     * Carpeta de datos: propiedad {@code stockwise.data}; si se ejecuta desde la carpeta del proyecto, ./data;
     * en otro caso (aplicación instalada) ~/STOCKWISE/data.
     */
    public static Path dataDirectory() {
        String prop = System.getProperty("stockwise.data");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        if (Files.exists(Path.of("pom.xml"))) {
            return Path.of("data").toAbsolutePath();
        }
        return Path.of(System.getProperty("user.home"), "STOCKWISE", "data");
    }

    @Override
    public void start(Stage primaryStage) {
        Path data = dataDirectory();
        AppLogger.init(data);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> LOG.log(Level.SEVERE, "Excepción no controlada en " + t, e));
        this.stage = primaryStage;
        try {
            catalog = new ProjectCatalog(data);
            stage.getIcons().add(new Image(Objects.requireNonNull(
                    getClass().getResourceAsStream("/images/logo.png"))));
            stage.setMinWidth(1024);
            stage.setMinHeight(640);
            mostrarSelector();
            stage.show();
            LOG.info("STOCKWISE iniciado. Datos en " + data);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "No se pudo iniciar STOCKWISE", e);
            Alert a = new Alert(Alert.AlertType.ERROR, "No se pudo iniciar STOCKWISE:\n" + e.getMessage()
                    + "\n\nRevise el registro en " + data.resolve("logs"));
            a.setHeaderText("Error de inicio");
            a.showAndWait();
            Platform.exit();
        }
    }

    private Scene escena(Parent root) {
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double w = stage.getScene() != null ? stage.getScene().getWidth() : Math.min(1366, screen.getWidth() * 0.95);
        double h = stage.getScene() != null ? stage.getScene().getHeight() : Math.min(840, screen.getHeight() * 0.95);
        return new Scene(root, w, h);
    }

    private void mostrarSelector() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/views/proyectos.fxml"));
            loader.setControllerFactory(t -> new ProyectosController(catalog, p -> abrirProyecto(p.id(), p.nombre())));
            Parent root = loader.load();
            Scene scene = escena(root);
            List<String> css = MainController.stylesheets("claro");
            scene.getStylesheets().setAll(css);
            stage.setScene(scene);
            Dialogs.configure(stage, css);
            stage.setTitle("STOCKWISE - Proyectos");
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo mostrar el selector de proyectos", e);
        }
    }

    private void abrirProyecto(long id, String nombre) {
        try {
            AppContext ctx = catalog.abrir(id);
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/views/main.fxml"));
            main = new MainController(ctx, nombre, this::volverAlSelector);
            loader.setControllerFactory(type -> main);
            Parent root = loader.load();
            stage.setScene(escena(root));
            stage.setTitle("STOCKWISE - " + nombre);
            main.applyTheme(ctx.config().get().tema());
            main.navigate(View.INICIO);
            LOG.info("Proyecto abierto: " + nombre);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "No se pudo abrir el proyecto " + nombre, e);
            Dialogs.error(e);
        }
    }

    private void volverAlSelector() {
        if (main != null) {
            main.shutdown();
            main = null;
        }
        mostrarSelector();
    }

    @Override
    public void stop() {
        if (main != null) {
            main.shutdown();
        }
    }
}
