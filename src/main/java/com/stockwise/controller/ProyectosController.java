package com.stockwise.controller;

import com.stockwise.service.ProjectCatalog;
import com.stockwise.service.ProjectCatalog.Proyecto;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Forms;
import com.stockwise.util.Icons;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Pantalla de inicio: crear, seleccionar, renombrar y eliminar proyectos. */
public class ProyectosController {

    private final ProjectCatalog catalog;
    private final Consumer<Proyecto> abrir;
    private Proyecto seleccionado;

    @FXML
    private VBox listBox;
    @FXML
    private Label infoLabel;
    @FXML
    private Button openButton;
    @FXML
    private Button renameButton;
    @FXML
    private Button deleteButton;
    @FXML
    private Button newButton;

    public ProyectosController(ProjectCatalog catalog, Consumer<Proyecto> abrir) {
        this.catalog = catalog;
        this.abrir = abrir;
    }

    @FXML
    private void initialize() {
        newButton.setGraphic(Icons.ADD.create(14));
        cargar(null);
    }

    private void cargar(Long seleccionar) {
        listBox.getChildren().clear();
        seleccionado = null;
        var lista = catalog.listar();
        if (lista.isEmpty()) {
            VBox vacio = new VBox(10, label("Aún no tiene proyectos", "empty-title"),
                    label("Cree su primer proyecto (por ejemplo \"Ropa\" o \"Alimentos\"), importe sus archivos de "
                            + "Excel y pulse Analizar.", "muted"));
            vacio.getStyleClass().add("empty-state");
            listBox.getChildren().add(vacio);
        }
        for (Proyecto p : lista) {
            Path base = catalog.rutaBase(p);
            String tam = "";
            try {
                tam = Files.isRegularFile(base) ? String.format(" · %.1f MB", Files.size(base) / 1048576.0) : "";
            } catch (java.io.IOException ignored) {
                // tamaño no disponible
            }
            Label nombre = label(p.nombre(), "project-name");
            Label detalle = label("Creado " + p.creado() + (p.ultimoUso() == null ? "" : " · último uso "
                    + p.ultimoUso()) + tam, "muted");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            HBox fila = new HBox(12, new VBox(2, nombre, detalle), sp);
            fila.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            fila.getStyleClass().add("project-card");
            fila.setOnMouseClicked(e -> {
                seleccionar(p, fila);
                if (e.getClickCount() == 2) {
                    open();
                }
            });
            listBox.getChildren().add(fila);
            if ((seleccionar != null && seleccionar == p.id()) || (seleccionar == null && seleccionado == null)) {
                seleccionar(p, fila);
            }
        }
        actualizarBotones();
    }

    private static Label label(String t, String estilo) {
        Label l = new Label(t);
        l.getStyleClass().add(estilo);
        l.setWrapText(true);
        return l;
    }

    private void seleccionar(Proyecto p, HBox fila) {
        listBox.getChildren().forEach(n -> n.getStyleClass().remove("seleccionado"));
        fila.getStyleClass().add("seleccionado");
        seleccionado = p;
        infoLabel.setText("Datos en " + catalog.rutaBase(p));
        actualizarBotones();
    }

    private void actualizarBotones() {
        boolean sin = seleccionado == null;
        openButton.setDisable(sin);
        renameButton.setDisable(sin);
        deleteButton.setDisable(sin);
        if (sin) {
            infoLabel.setText("");
        }
    }

    @FXML
    private void open() {
        if (seleccionado != null) {
            abrir.accept(seleccionado);
        }
    }

    @FXML
    private void create() {
        TextField nombre = new TextField();
        nombre.setPromptText("Ej.: Ropa, Alimentos, Electrónica");
        GridPane g = Forms.grid();
        Forms.row(g, "Nombre del proyecto *", nombre);
        long[] creado = {-1};
        if (Dialogs.form("Nuevo proyecto", new VBox(10, g, label("Se creará una base de datos propia para este proyecto. "
                + "Después podrá importar sus ventas, compras e inventario.", "muted")), "Crear", () ->
                creado[0] = catalog.crear(nombre.getText()).id())) {
            cargar(creado[0]);
            catalog.buscar(creado[0]).ifPresent(abrir);
        }
    }

    @FXML
    private void rename() {
        Proyecto p = seleccionado;
        if (p == null) {
            return;
        }
        TextField nombre = new TextField(p.nombre());
        GridPane g = Forms.grid();
        Forms.row(g, "Nuevo nombre *", nombre);
        if (Dialogs.form("Renombrar proyecto", g, "Guardar", () -> catalog.renombrar(p.id(), nombre.getText()))) {
            cargar(p.id());
        }
    }

    @FXML
    private void delete() {
        Proyecto p = seleccionado;
        if (p == null) {
            return;
        }
        TextField conf = new TextField();
        conf.setPromptText(p.nombre());
        Label aviso = label("Se eliminarán TODOS los datos del proyecto \"" + p.nombre() + "\": productos, ventas, "
                + "compras, inventario, importaciones y análisis. Antes se guardará una copia de seguridad en la "
                + "carpeta backups/proyectos-eliminados. Los demás proyectos no se modifican.", "notice");
        aviso.getStyleClass().add("danger");
        GridPane g = Forms.grid();
        Forms.row(g, "Escriba el nombre para confirmar *", conf);
        Path[] copia = new Path[1];
        if (Dialogs.form("Eliminar proyecto", new VBox(12, aviso, g), "Eliminar definitivamente", () ->
                copia[0] = catalog.eliminar(p.id(), conf.getText()))) {
            Dialogs.info("Proyecto eliminado", "Se eliminó el proyecto \"" + p.nombre() + "\"."
                    + (copia[0] == null ? "" : "\nCopia de seguridad: " + copia[0]));
            cargar(null);
        }
    }
}
