package com.stockwise.service;

import com.stockwise.database.Database;
import com.stockwise.database.SchemaInitializer;
import com.stockwise.importer.ImportService;
import com.stockwise.repository.CategoriaRepository;
import com.stockwise.repository.AnalisisRepository;
import com.stockwise.repository.CompraRepository;
import com.stockwise.repository.DecisionRepository;
import com.stockwise.repository.ImportacionRepository;
import com.stockwise.repository.StockHistoricoRepository;
import com.stockwise.repository.ConfigRepository;
import com.stockwise.repository.InventarioRepository;
import com.stockwise.repository.MovimientoRepository;
import com.stockwise.repository.PrediccionRepository;
import com.stockwise.repository.PresupuestoRepository;
import com.stockwise.repository.ProductoRepository;
import com.stockwise.repository.ProveedorRepository;
import com.stockwise.repository.RecomendacionRepository;
import com.stockwise.repository.SucursalRepository;
import com.stockwise.repository.VentaRepository;

import java.nio.file.Path;

/**
 * Contenedor de dependencias de la aplicación (inyección manual por constructor).
 * Crea la base de datos y su esquema en el primer inicio.
 */
public final class AppContext {

    private final Database database;
    private final ConfigService config;
    private final ProductoService productos;
    private final InventarioService inventario;
    private final VentaService ventas;
    private final SucursalService sucursales;
    private final PresupuestoService presupuestos;
    private final CompraService compras;
    private final AnalysisService analysis;
    private final RecommendationService recomendaciones;
    private final ReportService reportes;
    private final ImportService importacion;
    private final BackupService backup;
    private final PrediccionRepository predicciones;
    private final VentaRepository ventaRepository;
    private final DashboardService dashboard;
    private final StockHistoryService stockHistorico;
    private final PlanningService planificacion;
    private final BacktestService backtest;
    private final ScorecardService scorecard;
    private final ResultsService resultados;
    private final AccionesService acciones;
    private final ReportesAnalisisService reportesAnalisis;
    private final ImportHistoryService historialImportaciones;

    public AppContext(Path dbFile) {
        database = new Database(dbFile);
        SchemaInitializer.initialize(database);

        ConfigRepository configRepo = new ConfigRepository(database);
        ProductoRepository productoRepo = new ProductoRepository(database);
        CategoriaRepository categoriaRepo = new CategoriaRepository(database);
        ProveedorRepository proveedorRepo = new ProveedorRepository(database);
        SucursalRepository sucursalRepo = new SucursalRepository(database);
        InventarioRepository inventarioRepo = new InventarioRepository(database);
        MovimientoRepository movimientoRepo = new MovimientoRepository(database);
        ventaRepository = new VentaRepository(database);
        CompraRepository compraRepo = new CompraRepository(database);
        PresupuestoRepository presupuestoRepo = new PresupuestoRepository(database);
        RecomendacionRepository recomendacionRepo = new RecomendacionRepository(database);
        predicciones = new PrediccionRepository(database);

        config = new ConfigService(configRepo);
        config.get();
        productos = new ProductoService(database, productoRepo, categoriaRepo, proveedorRepo);
        inventario = new InventarioService(database, inventarioRepo, movimientoRepo);
        ventas = new VentaService(database, ventaRepository, inventario);
        sucursales = new SucursalService(sucursalRepo, ventaRepository, inventarioRepo);
        presupuestos = new PresupuestoService(presupuestoRepo, compraRepo, config);
        compras = new CompraService(database, compraRepo, productoRepo, inventario, presupuestos, proveedorRepo);
        analysis = new AnalysisService(productoRepo, ventaRepository, inventarioRepo, sucursalRepo, config, compraRepo,
                proveedorRepo);
        recomendaciones = new RecommendationService(analysis, presupuestos, recomendacionRepo, inventario);
        reportes = new ReportService(ventaRepository, analysis, recomendaciones, presupuestos, sucursales, config);
        importacion = new ImportService(database, productoRepo, sucursalRepo, categoriaRepo, proveedorRepo,
                ventaRepository, presupuestoRepo, inventario, compraRepo, configRepo);
        backup = new BackupService(database);
        dashboard = new DashboardService(analysis, ventaRepository, inventarioRepo);
        stockHistorico = new StockHistoryService(database, new StockHistoricoRepository(database));
        planificacion = new PlanningService(analysis, recomendaciones, presupuestos, stockHistorico,
                new DecisionRepository(database), ventaRepository, compraRepo, new AnalisisRepository(database));
        backtest = new BacktestService(analysis, productoRepo, ventaRepository);
        scorecard = new ScorecardService(planificacion, backtest, stockHistorico);
        resultados = new ResultsService(new AnalisisRepository(database), new DecisionRepository(database),
                ventaRepository, config, database);
        acciones = new AccionesService(resultados, new DecisionRepository(database), recomendacionRepo, config);
        reportesAnalisis = new ReportesAnalisisService(resultados, acciones);
        historialImportaciones = new ImportHistoryService(database, new ImportacionRepository(database));
    }

    public ReportesAnalisisService reportesAnalisis() {
        return reportesAnalisis;
    }

    public AccionesService acciones() {
        return acciones;
    }

    public ResultsService resultados() {
        return resultados;
    }

    public ImportHistoryService historialImportaciones() {
        return historialImportaciones;
    }

    public StockHistoryService stockHistorico() {
        return stockHistorico;
    }

    public PlanningService planificacion() {
        return planificacion;
    }

    public BacktestService backtest() {
        return backtest;
    }

    public ScorecardService scorecard() {
        return scorecard;
    }

    public DashboardService dashboard() {
        return dashboard;
    }

    /**
     * Verdadero si el proyecto tiene inventario registrado. Sin él, todo lo que depende del stock (qué comprar y
     * cuánto, agotados, excesos, valor del inventario) se muestra como pendiente en lugar de suponer stock cero.
     */
    public boolean hayInventario() {
        return new com.stockwise.repository.InventarioRepository(database).hayRegistros();
    }

    public Database database() {
        return database;
    }

    public ConfigService config() {
        return config;
    }

    public ProductoService productos() {
        return productos;
    }

    public InventarioService inventario() {
        return inventario;
    }

    public VentaService ventas() {
        return ventas;
    }

    public VentaRepository ventaRepository() {
        return ventaRepository;
    }

    public SucursalService sucursales() {
        return sucursales;
    }

    public PresupuestoService presupuestos() {
        return presupuestos;
    }

    public CompraService compras() {
        return compras;
    }

    public AnalysisService analysis() {
        return analysis;
    }

    public RecommendationService recomendaciones() {
        return recomendaciones;
    }

    public ReportService reportes() {
        return reportes;
    }

    public ImportService importacion() {
        return importacion;
    }

    public BackupService backup() {
        return backup;
    }

    public PrediccionRepository predicciones() {
        return predicciones;
    }
}
