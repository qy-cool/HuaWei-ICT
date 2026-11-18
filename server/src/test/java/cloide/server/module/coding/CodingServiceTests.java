package cloide.server.module.coding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloide.server.common.BusinessException;
import cloide.server.module.project.service.ProjectAccess;
import cloide.server.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CodingServiceTests {
    final ObjectMapper json=new ObjectMapper();
    final ProjectAccess access=mock(ProjectAccess.class);
    final UUID worker=UUID.randomUUID();
    UUID id;
    JdbcTemplate jdbc;
    CodingService service;
    TransactionTemplate tx;
    final CodingController.Create request=new CodingController.Create(UUID.randomUUID(),4,"orders","a".repeat(40),"edit",null,false);

    @BeforeEach void before() {
        var db=new JdbcDataSource();
        db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V43__project_coding_tasks.sql")).execute(db);
        jdbc=new JdbcTemplate(db);
        tx=new TransactionTemplate(new DataSourceTransactionManager(db));
        service=new CodingService(jdbc,access,json,Clock.systemUTC());
        when(access.principal()).thenReturn(new AuthenticatedUser(1L,"dev","user","approved",null,null));
        when(access.canRun(1,2,4)).thenReturn(true);
        id=UUID.fromString(commit(()->service.create(2,request)).taskId());
    }
    @AfterEach void after() { if(jdbc!=null) jdbc.execute("SHUTDOWN"); }
    <T>T commit(Supplier<T> call) {return tx.execute(s->call.get());}
    Map<String,Object> poll(UUID task) {return commit(()->service.poll(4,new CodingNodeController.Poll(worker,2,"orders",task)));}
    int report(int seq,Map<String,Object> event) {
        return commit(()->service.record(4,new CodingNodeController.Receipt(worker,id,seq,json.valueToTree(event))));
    }

    @Test void onceOnlyDispatchAndApprovalBoundToContent() {
        assertEquals(id.toString(),commit(()->service.create(2,request)).taskId());
        assertEquals("start",poll(null).get("action"));
        assertEquals("start",poll(null).get("action"));
        assertEquals(1,report(1,Map.of("type","started")));
        assertEquals(1,report(1,Map.of("type","started")));
        assertThrows(BusinessException.class,()->report(1,Map.of("type","text","text","x")));
        UUID approval=UUID.randomUUID();
        String hash="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        var proposed=Map.<String,Object>of("type","approval","path","a.java","beforeHash","missing","afterHash",hash,
                "approvalId",approval.toString(),"content","");
        assertEquals(2,report(2,proposed));
        assertThrows(BusinessException.class,()->commit(()->service.decide(2,id,new CodingController.Decision(UUID.randomUUID(),true))));
        commit(()->service.decide(2,id,new CodingController.Decision(approval,true)));
        assertEquals(Map.of("action","decision","approvalId",approval.toString(),"allow",true),poll(id));
        assertThrows(BusinessException.class,()->report(3,Map.of("type","change","path","different","beforeHash","missing","afterHash",hash,"content","")));
        assertEquals(3,report(3,Map.of("type","change","path","a.java","beforeHash","missing","afterHash",hash,"content","")));
        report(4,Map.of("type","patch","text","patch"));
        report(5,Map.of("type","finished","status","ready_for_review"));
        assertEquals("ready_for_review",service.get(2,id).status());
        assertEquals(2,service.events(2,id,3).size());
        assertThrows(BusinessException.class,()->service.get(22,id));
    }
    @Test void revokedAndUncertainTasksAreNotRestarted() {
        poll(null); report(1,Map.of("type","started"));
        assertThrows(BusinessException.class,()->commit(()->service.poll(4,new CodingNodeController.Poll(UUID.randomUUID(),2,"orders",id))));
        when(access.canRun(1,2,4)).thenReturn(false);
        assertEquals(true,poll(id).get("revoked"));
        assertThrows(BusinessException.class,()->report(2,Map.of("type","text","text","private")));
        report(2,Map.of("type","finished","status","cancelled"));
        assertEquals("cancelled",service.get(2,id).status());
        assertEquals("idle",poll(null).get("action"));
    }
    @Test void lostWorkerEntersUncertainAndNewWorkerCannotRepeatTheTask() {
        poll(null);
        jdbc.update("UPDATE project_coding_tasks SET last_seen='2000-01-01' WHERE task_id=?",id.toString());
        service.expireLeases();
        assertEquals("uncertain",service.get(2,id).status());
        assertEquals("idle",poll(null).get("action"));
    }
}
