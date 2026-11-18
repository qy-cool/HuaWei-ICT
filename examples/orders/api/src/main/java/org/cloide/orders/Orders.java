package org.cloide.orders;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Orders {
    public record Order(UUID id, String item, String status, int version) {}
    private static final String COLUMNS = "SELECT id,item,status,version FROM orders";
    private static final DataClassRowMapper<Order> ROW = new DataClassRowMapper<>(Order.class);
    private final JdbcTemplate db;

    public Orders(JdbcTemplate db) { this.db = db; }

    public List<Order> latest() {
        return db.query(COLUMNS + " ORDER BY created_at DESC, id DESC LIMIT 100", ROW);
    }

    public Order find(UUID id) {
        return one(id, "");
    }

    public int version(UUID id) {
        return db.query("SELECT version FROM orders WHERE id=?", (rs, row) -> rs.getInt(1), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Order one(UUID id, String lock) {
        return db.query(COLUMNS + " WHERE id=?" + lock, ROW, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public Order create(String item) {
        var order = new Order(UUID.randomUUID(), item.strip(), "pending", 1);
        db.update("INSERT INTO orders(id,item,status,version) VALUES (?,?,?,?)",
                order.id(), order.item(), order.status(), order.version());
        event(order);
        return order;
    }

    @Transactional
    public Order transition(UUID id, String next) {
        var old = one(id, " FOR UPDATE");
        if (old.status().equals(next)) return old;
        if (!old.status().equals("pending") || !List.of("shipped","cancelled").contains(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单当前状态不允许此操作");
        }
        var updated = new Order(id, old.item(), next, old.version() + 1);
        db.update("UPDATE orders SET status=?,version=? WHERE id=?", next, updated.version(), id);
        event(updated);
        return updated;
    }

    private void event(Order order) {
        db.update("INSERT INTO order_outbox(event_id,order_id,order_version,status) VALUES (?,?,?,?)",
                UUID.randomUUID(), order.id(), order.version(), order.status());
    }
}
