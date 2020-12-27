package my.bookshop.handlers;

import static cds.gen.catalogservice.CatalogService_.BOOKS;

import java.awt.print.Book;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cds.gen.catalogservice.Books_;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.request.UserInfo;
import com.sap.xs.audit.api.exception.AuditLogNotAvailableException;
import com.sap.xs.audit.api.exception.AuditLogWriteException;
import com.sap.xs.audit.api.v2.AuditLogMessageFactory;
import com.sap.xs.audit.api.v2.AuditedDataSubject;
import com.sap.xs.audit.api.v2.AuditedObject;
import com.sap.xs.audit.api.v2.DataAccessAuditMessage;
import com.sap.xs.audit.api.v2.DataModificationAuditMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.services.cds.CdsService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.catalogservice.Books;
import cds.gen.catalogservice.CatalogService_;

/**
 * Custom business logic for the "Catalog Service" (see cat-service.cds)
 * <p>
 * Handles Reading of Books
 * Adds Discount Message to the Book Title if too much stock is available
 */
@Component
@ServiceName(CatalogService_.CDS_NAME)
class CatalogServiceHandler implements EventHandler {
    private final AuditLogMessageFactory auditLogMessageFactory;
    private final PersistenceService db;
    private final UserInfo user;

    public CatalogServiceHandler(AuditLogMessageFactory auditLogMessageFactory, PersistenceService db, UserInfo user) {
        this.auditLogMessageFactory = auditLogMessageFactory;
        this.db = db;
        this.user = user;
    }

    @On(event = CdsService.EVENT_READ)
    public void logMethod(List<Books> books) {
        final DataAccessAuditMessage message = auditLogMessageFactory.createDataAccessAuditMessage();
        final AuditedDataSubject auditedDataSubject = auditLogMessageFactory.createAuditedDataSubject();
        final AuditedObject auditedObject = auditLogMessageFactory.createAuditedObject();
        final String booksIds = books.stream().map(Books::getId).collect(Collectors.joining(", "));
        auditedObject.addIdentifier("Books which has been read",booksIds);
        final String roles = user.getRoles().stream().collect(Collectors.joining(", "));
        auditedDataSubject.setRole(roles);
        auditedDataSubject.setType("Test");
        message.setDataSubject(auditedDataSubject);
        message.addAttachment("test_attr","test_attr");
        message.setTenant(user.getTenant());
        message.setObject(auditedObject);
        try {
            message.log();
        } catch (AuditLogNotAvailableException e) {
            e.printStackTrace();
        } catch (AuditLogWriteException e) {
            e.printStackTrace();
        }
    }

    @After(event = CdsService.EVENT_READ)
    public void discountBooks(Stream<Books> books) {
        books.filter(b -> b.getTitle() != null).forEach(
                b -> {
                    loadStockIfNotSet(b);
                    discountBooksWithMoreThan111Stock(b);
                }
        );
    }

    private void discountBooksWithMoreThan111Stock(Books b) {
        if (b.getStock() > 111) {
            b.setTitle(String.format("%s -- 11%% discount", b.getTitle()));
        }
    }

    private void loadStockIfNotSet(Books b) {
        if (b.getStock() == null) {
            b.setStock(db.run(Select.from(BOOKS).byId(b.getId()).columns(Books_::stock)).single(Books.class).getStock());
        }
    }
}
