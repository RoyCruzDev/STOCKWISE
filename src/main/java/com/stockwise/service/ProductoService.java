package com.stockwise.service;

import com.stockwise.database.Database;
import com.stockwise.model.Categoria;
import com.stockwise.model.Producto;
import com.stockwise.model.Proveedor;
import com.stockwise.repository.CategoriaRepository;
import com.stockwise.repository.ProductoRepository;
import com.stockwise.repository.ProveedorRepository;

import java.util.List;
import java.util.Optional;

public class ProductoService {

    private final Database db;
    private final ProductoRepository productos;
    private final CategoriaRepository categorias;
    private final ProveedorRepository proveedores;

    public ProductoService(Database db, ProductoRepository productos, CategoriaRepository categorias,
                           ProveedorRepository proveedores) {
        this.db = db;
        this.productos = productos;
        this.categorias = categorias;
        this.proveedores = proveedores;
    }

    public List<Producto> listar() {
        return productos.findAll();
    }

    public List<Producto> listarActivos() {
        return productos.findActivos();
    }

    public List<Categoria> categorias() {
        return categorias.findAll();
    }

    public List<Proveedor> proveedores() {
        return proveedores.findAll();
    }

    public Optional<Producto> buscar(long id) {
        return productos.findById(id);
    }

    /**
     * Crea o actualiza un producto. La categoría y el proveedor se indican por nombre y se crean si no existen.
     *
     * @return id del producto guardado
     */
    public long guardar(Producto p, String categoria, String proveedor) {
        validar(p);
        return db.inTransaction(() -> {
            Optional<Producto> mismoCodigo = productos.findByCodigo(p.codigo());
            if (mismoCodigo.isPresent() && mismoCodigo.get().id() != p.id()) {
                throw new ValidationException("Ya existe un producto con el código " + p.codigo().trim() + ".");
            }
            Long catId = categorias.getOrCreate(categoria);
            Long provId = proveedores.getOrCreate(proveedor);
            Producto completo = new Producto(p.id(), p.codigo(), p.nombre(), catId, categoria, provId, proveedor,
                    p.precioVenta(), p.costo(), p.stockMinimo(), p.stockMaximo(), p.leadTimeDias(), p.activo());
            if (p.id() <= 0) {
                return productos.insert(completo);
            }
            productos.update(completo);
            return p.id();
        });
    }

    public void eliminar(long id) {
        productos.delete(id);
    }

    public static void validar(Producto p) {
        if (p.codigo() == null || p.codigo().isBlank()) {
            throw new ValidationException("El código del producto es obligatorio.");
        }
        if (p.codigo().trim().length() > 50) {
            throw new ValidationException("El código no debe superar 50 caracteres.");
        }
        if (p.nombre() == null || p.nombre().isBlank()) {
            throw new ValidationException("El nombre del producto es obligatorio.");
        }
        if (p.precioVenta() < 0 || p.costo() < 0) {
            throw new ValidationException("El precio y el costo no pueden ser negativos.");
        }
        if (p.stockMinimo() < 0 || p.stockMaximo() < 0) {
            throw new ValidationException("Los niveles de stock no pueden ser negativos.");
        }
        if (p.stockMaximo() > 0 && p.stockMaximo() < p.stockMinimo()) {
            throw new ValidationException("El stock máximo debe ser mayor o igual que el stock mínimo.");
        }
        if (p.leadTimeDias() != null && (p.leadTimeDias() < 0 || p.leadTimeDias() > 365)) {
            throw new ValidationException("El tiempo de reposición debe estar entre 0 y 365 días.");
        }
    }
}
