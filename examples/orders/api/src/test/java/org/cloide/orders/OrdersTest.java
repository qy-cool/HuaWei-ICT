package org.cloide.orders;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

class OrdersTest {
    private Orders orders;
    private JdbcTemplate db;
    @BeforeEach void start() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__orders_and_events.sql")).execute(ds);
        db = new JdbcTemplate(ds);
        orders = new Orders(db);
    }

    @Test void repeatedCancellationUsesOneVersionAndOneEvent() {
        var order = orders.create("book");
        var cancelled = orders.transition(order.id(), "cancelled");
        assertEquals(cancelled, orders.transition(order.id(), "cancelled"));
        assertEquals(2, orders.version(order.id()));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM order_outbox", Integer.class));
        assertThrows(ResponseStatusException.class, () -> orders.transition(order.id(), "shipped"));
    }

    @Test void shippedOrderCannotBeCancelled() {
        var order = orders.create("desk");
        orders.transition(order.id(), "shipped");
        assertThrows(ResponseStatusException.class, () -> orders.transition(order.id(), "cancelled"));
        assertEquals("shipped", orders.find(order.id()).status());
    }
}
